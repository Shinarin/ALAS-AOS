#!/usr/bin/env python3
"""AlasAos v3 · ALAS 进程管理 wrapper（rootfs 内，stdlib only）。

薄 HTTP（127.0.0.1:22400）+ 进程组管理，供 App 悬浮窗 start/stop/日志 使用。
端口选择：避开 m0 桥 22300 与 WebUI 22267。

端点
----
GET  /status        → {"runner_alive": bool, "pid": int|null, "config": str|null,
                       "runner_wanted": bool, "runner_respawns": int,
                       "gui_alive": bool, "gui_pid": int|null,
                       "log_file": str|null, "log_size": int,
                       "tool_alive": bool, "tool_name": str|null, "tool_pid": int|null}
POST /start?config=N → 幂等拉起 runner 子进程（已在跑则直接返回现状）；N = config/ 下的
                       实例配置名（默认 alas），runner.py argv[1] 透传；
                       置 wanted——此后 runner 意外退出由监管循环按退避自动重拉；
                       工具在跑先停工具（反向互斥，见下）
POST /stop          → 杀进程组：os.killpg(SIGTERM) → 3s → SIGKILL；复位 wanted 不再重拉
POST /tool/start?name=T&config=N
                    → 拉起 ALAS 工具任务（T ∈ daemon|event_story：半自动点击常驻 /
                       活动剧情一次性自退），runner.py argv[2] 透传；幂等：同名在跑
                       直接返回，不同工具在跑先停旧的；与挂机互斥——runner 在跑先
                       stop_runner（复位 wanted 防重拉），工具结束后**不自动恢复
                       runner**；N 缺省用 runner 现值配置、再缺省 alas
POST /tool/stop     → 杀工具进程组（SIGTERM → 3s → SIGKILL），返回 was_alive/exit_code
GET  /logs?tail=N   → ./log/ 下最新 *.txt 的尾部 N 行（默认 200，上限 2000）
GET  /configs       → {"configs": [str]}：config/*.json 去掉 template* 的实例名列表，
                       'alas' 固定排最前；供 App 侧下拉选择运行配置

设计要点（依据 docs/spike-d-wrapper-surface.md 的 Spike D 结论）
----
- wrapper 本体**完全不 import ALAS**，只 subprocess 拉 runner.py（同目录）。
  runner 子进程 preexec_fn=os.setsid 独立进程组，停止用 os.killpg。
- **不碰 ALAS 的 ProcessManager**（双头管理风险：两边都以为自己在管进程，状态互踩）。
  WebUI（gui.py）由 wrapper 作子进程监管（崩溃自动重拉，退避 5s→60s），
  悬浮窗 start/stop 与 WebUI 启停按钮并存的双头问题留阶段四决策
  （候选：wrapper 同进程 uvicorn 直调 ProcessManager.get_manager()）。
  ALASAOS_WEBUI=0 可关 WebUI（省内存/调试）。
- 停止语义等同 m0 的 ProcessManager.stop（其本身就是 kill()，无 graceful）：
  ALAS 无 SIGTERM handler，SIGTERM 即默认终止；3s 不死补 SIGKILL。
- 工具进程与挂机互斥（共用同一块虚拟屏）：锁序固定 _tool_lock → _runner_lock，
  /tool/start 与 /start 都把「停对方 + 拉自己」放进 _tool_lock 临界区完成，
  两个 HTTP 线程不会互等；工具由 _tool_supervisor 只记录退出码，绝不重拉。
- 防孤儿（m0 教训）：① 父退出前 atexit + SIGTERM handler 清理 runner；
  ② stdin 管道破裂自尽——monitor 线程阻塞读 stdin，父进程（proot 启动器）死亡
  导致管道 EOF 时，杀掉 runner 进程组并退出。stdin 是 tty（手工调试）时不挂监控。
- 单实例锁：./log/wrapper.lock（fcntl.flock LOCK_EX|LOCK_NB，rootfs 是 Linux），
  锁不住说明已有 wrapper 在跑，直接退出码 2。
- 日志面：ALAS 写 ./log/{YYYY-MM-DD}_{config_name}.txt（module/logger.py:171-177），
  runner import 期另建 {date}_runner.txt；/logs 与 /status 按 mtime 取最新 *.txt，
  不猜文件名（append 打开、整天不换名，见 Spike D §3）。
"""
import atexit
import datetime
import fcntl
import json
import os
import re
import signal
import stat
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HOST = '127.0.0.1'
PORT = 22400

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
RUNNER_PATH = os.path.join(BASE_DIR, 'runner.py')
LOG_DIR = os.path.join(BASE_DIR, 'log')
CONFIG_DIR = os.path.join(BASE_DIR, 'config')
LOCK_PATH = os.path.join(LOG_DIR, 'wrapper.lock')
_STOP_GRACE_SEC = 3.0
# 实例名只许安全字符：它会拼进 runner argv 与日志文件名
_CONFIG_RE = re.compile(r'^[A-Za-z0-9_\-]+$')

_runner = None            # subprocess.Popen | None
_runner_lock = threading.Lock()
_runner_started_at = None  # float | None
_runner_config = None      # str | None：本次拉起跑的实例名，/status 汇报用

_gui = None                # subprocess.Popen | None
_gui_lock = threading.Lock()
_gui_started_at = None
_GUI_BACKOFF_INIT = 5.0    # 重拉退避：5s 起步翻倍，60s 封顶；活过 5 分钟复位
_GUI_BACKOFF_MAX = 60.0
_GUI_HEALTHY_UPTIME = 300.0
_closing = threading.Event()

# runner 崩溃自拉起：/start 置 wanted，/stop 复位；监管循环只在 wanted 期间重拉
_RUNNER_BACKOFF_INIT = 5.0
_RUNNER_BACKOFF_MAX = 60.0
_RUNNER_HEALTHY_UPTIME = 300.0
_runner_wanted = threading.Event()
_runner_respawns = 0       # 非预期死亡后的重拉次数，/status 汇报

# 工具任务（ALAS 工具进程，runner.py argv[2] 透传）：与挂机共用同一块虚拟屏，严格互斥。
# 专用 _tool_lock，不与 _runner_lock 混用；锁序固定 _tool_lock → _runner_lock。
_TOOL_TASKS = ('daemon', 'event_story')  # 工具白名单，与 runner.py 侧对齐（两处同改）
_tool = None               # subprocess.Popen | None
_tool_lock = threading.Lock()
_tool_started_at = None    # float | None
_tool_name = None          # str | None：本次拉起的工具名，/status 汇报用
_tool_config = None        # str | None：本次拉起的实例名
_tool_exit_code = None     # int | None：最近一次工具退出码（自然死亡或被杀）


# ---------------------------------------------------------------- runner 进程组管理

def _runner_alive():
    return _runner is not None and _runner.poll() is None


def _spawn_runner(config_name):
    """拉起一次 runner。返回 Popen。调用方须持 _runner_lock。"""
    global _runner, _runner_started_at, _runner_config
    _runner = subprocess.Popen(
        [sys.executable, RUNNER_PATH, config_name],
        cwd=BASE_DIR,
        preexec_fn=os.setsid,  # 独立进程组，停止走 os.killpg
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,  # ALAS 日志走 ./log/ 文件，不走管道
        stderr=subprocess.DEVNULL,
    )
    _runner_started_at = time.time()
    _runner_config = config_name
    return _runner


def start_runner(config_name='alas'):
    """幂等：已在跑直接返回现状（不发新配置）。返回 (alive, pid, started_now)。
    /start 即表态「要它跑」：置 wanted，监管循环接管其后的意外死亡。
    与工具互斥（反向）：工具在跑先停——「停工具 + 拉 runner」同在 _tool_lock
    临界区完成，锁序 _tool_lock → _runner_lock 与 start_tool 一致。"""
    _runner_wanted.set()
    with _tool_lock:
        if _tool_alive():
            _stop_tool_locked()
        with _runner_lock:
            if _runner_alive():
                return True, _runner.pid, False
            proc = _spawn_runner(config_name)
            return True, proc.pid, True


def stop_runner():
    """SIGTERM → 3s → SIGKILL，杀整个进程组。返回 (was_alive, exit_code)。
    /stop 即表态「不要它跑」：复位 wanted，监管循环不再重拉。"""
    global _runner, _runner_config
    _runner_wanted.clear()
    with _runner_lock:
        if not _runner_alive():
            return False, _runner.returncode if _runner else None
        pgid = os.getpgid(_runner.pid)
        try:
            os.killpg(pgid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        deadline = time.time() + _STOP_GRACE_SEC
        while time.time() < deadline and _runner.poll() is None:
            time.sleep(0.05)
        if _runner.poll() is None:
            try:
                os.killpg(pgid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            _runner.wait(timeout=5)
        code = _runner.returncode
        _runner = None
        _runner_config = None
        return True, code


def _runner_supervisor():
    """崩溃重拉循环：runner 非预期死亡（wanted 仍置位）时按退避重拉同配置实例。
    活过 5 分钟视为健康、退避复位；/stop（wanted 复位）或 _closing 后不重拉。
    与 _gui_supervisor 同款：5s 起步翻倍，60s 封顶。"""
    global _runner_respawns
    backoff = _RUNNER_BACKOFF_INIT
    while not _closing.is_set():
        if not _runner_wanted.is_set():
            if _closing.wait(1.0):
                break
            continue
        with _runner_lock:
            proc = _runner
        if proc is None:
            # wanted 已置但 /start 的 spawn 还没落：让出，下拍再看
            if _closing.wait(0.5):
                break
            continue
        proc.wait()
        if _closing.is_set() or not _runner_wanted.is_set():
            continue
        uptime = time.time() - (_runner_started_at or time.time())
        backoff = _RUNNER_BACKOFF_INIT if uptime > _RUNNER_HEALTHY_UPTIME \
            else min(backoff * 2, _RUNNER_BACKOFF_MAX)
        print(f'AlasAos wrapper: runner exited code={proc.returncode} '
              f'uptime={uptime:.0f}s, respawn in {backoff:.0f}s', flush=True)
        if _closing.wait(backoff):
            break
        if not _runner_wanted.is_set():
            continue
        with _runner_lock:
            if _runner_alive():
                continue  # 竞态：已被 /start 拉起
            cfg = _runner_config or 'alas'
            try:
                proc2 = _spawn_runner(cfg)
                _runner_respawns += 1
                print(f'AlasAos wrapper: runner respawned pid={proc2.pid} '
                      f'config={cfg} (#{_runner_respawns})', flush=True)
            except OSError as e:
                print(f'AlasAos wrapper: runner respawn failed: {e}', file=sys.stderr, flush=True)


# ---------------------------------------------------------------- 工具进程管理（ALAS 工具任务）

def _tool_alive():
    return _tool is not None and _tool.poll() is None


def _spawn_tool(name, config_name):
    """拉起一次工具任务：runner.py <config> <name>。返回 Popen。调用方须持 _tool_lock。
    与 _spawn_runner 同款环境/cwd/日志处理（ALAS 日志走 ./log/ 文件，不走管道）。"""
    global _tool, _tool_started_at, _tool_name, _tool_config, _tool_exit_code
    _tool = subprocess.Popen(
        [sys.executable, RUNNER_PATH, config_name, name],
        cwd=BASE_DIR,
        preexec_fn=os.setsid,  # 独立进程组，停止走 os.killpg
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    _tool_started_at = time.time()
    _tool_name = name
    _tool_config = config_name
    _tool_exit_code = None
    return _tool


def _stop_tool_locked():
    """SIGTERM → 3s → SIGKILL 杀工具进程组。调用方须持 _tool_lock。
    返回 (was_alive, exit_code)，形制与 stop_runner 一致。"""
    global _tool, _tool_name, _tool_config, _tool_exit_code
    if not _tool_alive():
        return False, _tool.returncode if _tool else None
    pgid = os.getpgid(_tool.pid)
    try:
        os.killpg(pgid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    deadline = time.time() + _STOP_GRACE_SEC
    while time.time() < deadline and _tool.poll() is None:
        time.sleep(0.05)
    if _tool.poll() is None:
        try:
            os.killpg(pgid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        _tool.wait(timeout=5)
    code = _tool.returncode
    _tool = None
    _tool_name = None
    _tool_config = None
    _tool_exit_code = code
    return True, code


def start_tool(name, config_name=None):
    """幂等：同名工具在跑直接返回现状。返回 (alive, pid, started_now)。
    与挂机互斥：先 stop_runner（复位 wanted，监管循环不再重拉）再拉工具；
    不同工具在跑先停旧的。「停对方 + 拉自己」整个在 _tool_lock 临界区完成。"""
    with _tool_lock:
        if _tool_alive() and _tool_name == name:
            return True, _tool.pid, False
        if config_name is None:
            # 缺省沿用 runner 现值配置；须在 stop_runner 前取（它会清 _runner_config）
            config_name = _runner_config or 'alas'
        if _tool_alive():
            _stop_tool_locked()  # 换工具：先停旧的
        stop_runner()  # 无条件调：兼清 _runner_wanted，防 supervisor 退避期重拉破互斥
        proc = _spawn_tool(name, config_name)
        return True, proc.pid, True


def stop_tool():
    """SIGTERM → 3s → SIGKILL，杀工具进程组。返回 (was_alive, exit_code)。
    工具无 wanted/重拉语义，停后也不自动恢复 runner（决策：工具结束后保持停止）。"""
    with _tool_lock:
        return _stop_tool_locked()


def _tool_supervisor():
    """工具退出记录循环：wait() 阻塞到当前工具死亡，落 exit_code。
    **不重拉工具、不自动恢复 runner**（决策：工具结束后保持停止）；
    被 stop_tool/start_tool 换掉的进程不覆写状态（_tool is proc 校验）。"""
    global _tool_exit_code
    while not _closing.is_set():
        with _tool_lock:
            proc = _tool
        if proc is None:
            if _closing.wait(0.5):
                break
            continue
        proc.wait()
        with _tool_lock:
            if _tool is proc:  # 自然死亡：留 _tool 作墓碑，/status 依 poll() 实时判死
                _tool_exit_code = proc.returncode


# ---------------------------------------------------------------- WebUI（gui.py）监管

def _gui_alive():
    return _gui is not None and _gui.poll() is None


def _start_gui_once():
    """拉起一次 gui.py（端口由 config/deploy.yaml 的 WebuiPort 定，默认 22267）。
    uvicorn 输出追加到 ./log/gui.out（gui.py 自身的 ALAS 日志另走 {date}_gui.txt）。"""
    global _gui, _gui_started_at
    os.makedirs(LOG_DIR, exist_ok=True)
    out = open(os.path.join(LOG_DIR, 'gui.out'), 'ab')
    _gui = subprocess.Popen(
        [sys.executable, os.path.join(BASE_DIR, 'gui.py')],
        cwd=BASE_DIR,
        preexec_fn=os.setsid,  # 独立进程组，清理走 os.killpg
        stdin=subprocess.DEVNULL,
        stdout=out,
        stderr=subprocess.STDOUT,
    )
    _gui_started_at = time.time()


def _stop_gui():
    """SIGTERM → 3s → SIGKILL 杀 gui 进程组。幂等：没在跑直接返回。"""
    global _gui
    with _gui_lock:
        if not _gui_alive():
            return
        pgid = os.getpgid(_gui.pid)
        try:
            os.killpg(pgid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        deadline = time.time() + _STOP_GRACE_SEC
        while time.time() < deadline and _gui.poll() is None:
            time.sleep(0.05)
        if _gui.poll() is None:
            try:
                os.killpg(pgid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            _gui.wait(timeout=5)
        _gui = None


def _gui_supervisor():
    """崩溃重拉循环：wait() 阻塞到 gui 死亡；活过 5 分钟视为健康、退避复位，
    否则退避翻倍（5s→60s 封顶）。_closing 置位（关停）后不重拉直接退出。"""
    backoff = _GUI_BACKOFF_INIT
    while not _closing.is_set():
        with _gui_lock:
            try:
                _start_gui_once()
                proc = _gui
                print(f'AlasAos wrapper: gui.py started pid={proc.pid}', flush=True)
            except OSError as e:
                print(f'AlasAos wrapper: gui spawn failed: {e}', file=sys.stderr, flush=True)
                proc = None
        if proc is None:
            if _closing.wait(backoff):
                break
            backoff = min(backoff * 2, _GUI_BACKOFF_MAX)
            continue
        proc.wait()
        if _closing.is_set():
            break
        uptime = time.time() - (_gui_started_at or time.time())
        backoff = _GUI_BACKOFF_INIT if uptime > _GUI_HEALTHY_UPTIME \
            else min(backoff * 2, _GUI_BACKOFF_MAX)
        print(f'AlasAos wrapper: gui.py exited code={proc.returncode} '
              f'uptime={uptime:.0f}s, respawn in {backoff:.0f}s', flush=True)
        if _closing.wait(backoff):
            break


def _cleanup():
    """父退出前清理：atexit + SIGTERM 都汇到这里。先置 _closing 让监管循环退出，
    再杀 runner、工具与 gui，防止 supervisor 在我们杀完又重拉。"""
    _closing.set()
    if _runner_alive():
        stop_runner()
    if _tool_alive():
        stop_tool()
    _stop_gui()


def _on_signal(signum, frame):
    _cleanup()
    # 按信号语义退出：128+signum
    sys.exit(128 + signum)


def _stdin_watchdog():
    """stdin 管道 EOF = 父进程已死 → 杀进程组自我了断（m0 孤儿教训）。"""
    try:
        sys.stdin.buffer.read()
    except (OSError, ValueError):
        pass
    _cleanup()
    os._exit(0)


def _arm_stdin_watchdog():
    # 只有 stdin 是管道（FIFO）时才挂监控：父进程死亡 → 管道 EOF → 自尽。
    # tty（手工调试）没有"父进程管道"语义；/dev/null（如 Java Redirect.DISCARD）
    # 读即 EOF，挂上会立即自尽——两者都不挂。
    try:
        if sys.stdin is not None and not sys.stdin.isatty() \
                and stat.S_ISFIFO(os.fstat(sys.stdin.fileno()).st_mode):
            threading.Thread(target=_stdin_watchdog, daemon=True).start()
    except (OSError, ValueError):
        pass


def _acquire_instance_lock():
    """单实例锁：返回锁文件对象（引用防 GC 关 fd），已有实例则 sys.exit(2)。"""
    os.makedirs(LOG_DIR, exist_ok=True)
    fd = open(LOCK_PATH, 'a', encoding='utf-8')
    try:
        fcntl.flock(fd.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        print(f'AlasAos wrapper: another instance holds {LOCK_PATH}', file=sys.stderr)
        sys.exit(2)
    fd.write(str(os.getpid()))
    fd.flush()
    return fd


# ---------------------------------------------------------------- 配置实例发现

def _list_configs():
    """config/ 下的实例配置名：*.json 去掉 template*（template.json/.maa/.fpy 等），
    去扩展名排序，'alas' 固定排最前。与 WebUI 配置下拉的来源同一层。"""
    try:
        names = []
        for p in os.listdir(CONFIG_DIR):
            if not p.endswith('.json'):
                continue
            stem = p[:-len('.json')]
            if stem.startswith('template'):
                continue
            names.append(stem)
    except FileNotFoundError:
        return []
    names.sort()
    if 'alas' in names:
        names.remove('alas')
        names.insert(0, 'alas')
    return names


# ---------------------------------------------------------------- 日志读取

def _latest_log_file():
    """./log/ 下 mtime 最新的 *.txt；没有则 None。"""
    try:
        candidates = [os.path.join(LOG_DIR, p) for p in os.listdir(LOG_DIR) if p.endswith('.txt')]
    except FileNotFoundError:
        return None
    if not candidates:
        return None
    return max(candidates, key=os.path.getmtime)


def _tail_lines(path, n):
    try:
        with open(path, 'rb') as f:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            f.seek(max(0, size - 256 * 1024))  # 尾部窗口，足够覆盖 2000 行日志
            data = f.read().decode('utf-8', errors='replace')
    except OSError:
        return []
    return data.splitlines()[-n:]


def _log_size(path):
    """日志文件字节数：append-only 下与行数同单调、轮转同重置，/status 的滚底 key 用它"""
    try:
        return os.path.getsize(path)
    except (OSError, TypeError):
        return 0


# ---------------------------------------------------------------- HTTP 面

class _Handler(BaseHTTPRequestHandler):
    server_version = 'AlasAosWrapper/3.0'

    def log_message(self, fmt, *args):  # 静音访问日志
        pass

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _text(self, text, code=200):
        body = text.encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == '/status':
            log_file = _latest_log_file()
            self._json({
                'runner_alive': _runner_alive(),
                'pid': _runner.pid if _runner_alive() else None,
                'config': _runner_config if _runner_alive() else None,
                'runner_wanted': _runner_wanted.is_set(),
                'runner_respawns': _runner_respawns,
                'started_at': datetime.datetime.fromtimestamp(_runner_started_at).isoformat()
                if _runner_alive() and _runner_started_at else None,
                'gui_alive': _gui_alive(),
                'gui_pid': _gui.pid if _gui_alive() else None,
                'gui_started_at': datetime.datetime.fromtimestamp(_gui_started_at).isoformat()
                if _gui_alive() and _gui_started_at else None,
                'log_file': log_file,
                'log_size': _log_size(log_file),
                'tool_alive': _tool_alive(),
                'tool_name': _tool_name if _tool_alive() else None,
                'tool_pid': _tool.pid if _tool_alive() else None,
            })
        elif url.path == '/configs':
            self._json({'configs': _list_configs()})
        elif url.path == '/logs':
            qs = parse_qs(url.query)
            try:
                n = min(int(qs.get('tail', ['200'])[0]), 2000)
            except ValueError:
                n = 200
            log_file = _latest_log_file()
            if not log_file:
                self._text('')
            else:
                self._text('\n'.join(_tail_lines(log_file, n)))
        else:
            self._json({'error': 'not found'}, code=404)

    def do_POST(self):
        url = urlparse(self.path)
        if url.path == '/start':
            qs = parse_qs(url.query)
            config_name = qs.get('config', ['alas'])[0] or 'alas'
            if not _CONFIG_RE.match(config_name):
                self._json({'error': 'invalid config name'}, code=400)
                return
            alive, pid, started_now = start_runner(config_name)
            self._json({'runner_alive': alive, 'pid': pid, 'started_now': started_now,
                        'config': _runner_config if alive else None})
        elif url.path == '/stop':
            was_alive, code = stop_runner()
            self._json({'runner_alive': False, 'was_alive': was_alive, 'exit_code': code})
        elif url.path == '/tool/start':
            qs = parse_qs(url.query)
            tool_name = qs.get('name', [''])[0]
            if tool_name not in _TOOL_TASKS:
                self._json({'error': f'invalid tool name: {tool_name!r} '
                                     f'(expect one of {list(_TOOL_TASKS)})'}, code=400)
                return
            config_name = qs.get('config', [None])[0] or None
            if config_name is not None and not _CONFIG_RE.match(config_name):
                self._json({'error': 'invalid config name'}, code=400)
                return
            alive, pid, started_now = start_tool(tool_name, config_name)
            self._json({'ok': True, 'tool_alive': alive, 'tool_name': tool_name,
                        'pid': pid, 'started_now': started_now})
        elif url.path == '/tool/stop':
            was_alive, code = stop_tool()
            self._json({'ok': True, 'tool_alive': False,
                        'was_alive': was_alive, 'exit_code': code})
        else:
            self._json({'error': 'not found'}, code=404)


def main():
    _lock_fd = _acquire_instance_lock()  # noqa: F841 - 引用防 GC
    atexit.register(_cleanup)
    signal.signal(signal.SIGTERM, _on_signal)
    signal.signal(signal.SIGINT, _on_signal)
    _arm_stdin_watchdog()
    threading.Thread(target=_runner_supervisor, daemon=True).start()
    threading.Thread(target=_tool_supervisor, daemon=True).start()
    if os.environ.get('ALASAOS_WEBUI', '1') != '0':
        threading.Thread(target=_gui_supervisor, daemon=True).start()
    server = ThreadingHTTPServer((HOST, PORT), _Handler)
    print(f'AlasAos wrapper: listening on http://{HOST}:{PORT}', flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
