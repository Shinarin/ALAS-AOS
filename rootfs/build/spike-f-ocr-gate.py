#!/usr/bin/env python3
"""Spike F · OCR 精度门禁（CI / 真机两用）。

驱动 rootfs/overlays/module/ocr/rpc.py 的**同一条** in-proc PP-OCR 管线
（通过 stub 掉 module.exception / module.logger / module.webui.setting 后按路径 import，
不复制推理代码，避免门禁与被测实现漂移）。

用法
----
本机 / CI（rootfs 外）：
    python spike-f-ocr-gate.py --model-dir <models/ocr> [--real-dir <dir>] [--screenshot <png>]

rootfs 内（构建脚本已把 overlays 应用到 /opt/alas，并把本脚本 cp 到 /opt/alas/overlays_copy/）：
    chroot work/rootfs /usr/bin/python3 /opt/alas/overlays_copy/spike-f-ocr-gate.py \
        --model-dir /opt/alas/models/ocr --real-dir /opt/alas/tests/ocr

模式
----
默认（CI 模式）：
    1. 模型加载（det+rec session 建立，alive() 为真）
    2. azur_lane numpy 引擎断言：ModelProxy._al 非 None，回落 PP-OCR 即 FAIL
      （门监会盲区修复：模型缺失时 rpc 静默回落，不断言则 numpy 引擎零验证）
    3. 合成数字行图 rec：PIL 白底黑字 10 组 + 反相（浅色字深色底）3 组，断言完全匹配
    4. 2D 灰度单通道（ndim==2）输入，验证 3ch 堆叠分支
--real-dir DIR：
    对目录内 *.png 真实行图（文件名 stem 即期望文本，如 13750.png）逐张 rec，
    输出逐样本结果与总正确率，>=98% 才 PASS（m0 DoD 口径）。
--screenshot PNG：
    整屏 det+rec 冒烟（非断言）：打印前 20 个识别框（坐标+文本），供人工 sanity。

输出结尾打印 `OCR_GATE PASS` / `OCR_GATE FAIL` 与指标摘要；PASS=exit 0，FAIL=exit 1。
依赖：stdlib + numpy + onnxruntime 必需；PIL 缺失时合成图用例记 SKIP；cv2 由 rpc.py 自用。
"""
import argparse
import glob
import importlib.util
import os
import sys
import types

PASS_THRESHOLD = 0.98  # m0 DoD：真实行图集正确率 >= 98%

# 合成用例：覆盖 Digit / DigitCounter / Duration 三类 alphabet（数字、'/'、':'）
SYNTH_CASES = [
    '13750', '2400/2400', '88888', '1024', '0/0',
    '59:59', '01:30:00', '6', '31415926', '120/120',
]
SYNTH_INVERTED_CASES = ['13750', '2400/2400', '59:59']
GRAY2D_CASES = ['13750', '2400/2400']

_results = []  # (name, status, detail) status in PASS/FAIL/SKIP


def _record(name, ok, detail=''):
    status = 'PASS' if ok else 'FAIL'
    _results.append((name, status, detail))
    print(f'[{status}] {name}' + (f'  {detail}' if detail else ''))


def _skip(name, reason):
    _results.append((name, 'SKIP', reason))
    print(f'[SKIP] {name}  {reason}')


# ---------------------------------------------------------------- ALAS stub + rpc 加载

def install_alas_stubs():
    """rpc.py 顶层 import 的三个 module.* 依赖全部打桩，使其脱离 ALAS 树可 import。"""
    module_pkg = types.ModuleType('module')
    module_pkg.__path__ = []
    sys.modules['module'] = module_pkg

    exc = types.ModuleType('module.exception')

    class RequestHumanTakeover(Exception):
        pass

    exc.RequestHumanTakeover = RequestHumanTakeover
    sys.modules['module.exception'] = exc

    log_mod = types.ModuleType('module.logger')

    class _Logger:
        @staticmethod
        def _p(level, msg):
            print(f'[rpc.{level}] {msg}', file=sys.stderr)

        def info(self, msg):
            self._p('info', msg)

        def warning(self, msg):
            self._p('warning', msg)

        def critical(self, msg):
            self._p('critical', msg)

    log_mod.logger = _Logger()
    sys.modules['module.logger'] = log_mod

    webui_pkg = types.ModuleType('module.webui')
    webui_pkg.__path__ = []
    sys.modules['module.webui'] = webui_pkg
    setting = types.ModuleType('module.webui.setting')

    class _DeployConfig:
        OcrClientAddress = '127.0.0.1:22300'

    class _State:
        deploy_config = _DeployConfig()

    setting.State = _State
    sys.modules['module.webui.setting'] = setting
    return RequestHumanTakeover


def load_rpc(rpc_path):
    spec = importlib.util.spec_from_file_location('maa_alas_ocr_rpc', rpc_path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def load_al_numpy(rpc_path):
    """按路径预载 rpc.py 旁边的 al_numpy.py，注册成 module.ocr.al_numpy。

    不打这个补丁，门禁 stub 的 module 包 __path__ 为空，rpc.py 顶层
    `from module.ocr.al_numpy import AlNumpyOcr` 必失败 → AlNumpyOcr=None →
    azur_lane 恒回落 PP-OCR，numpy 引擎永远验不到（模型文件铺了也白铺）。
    """
    al_path = os.path.join(os.path.dirname(os.path.abspath(rpc_path)), 'al_numpy.py')
    if not os.path.isfile(al_path):
        print(f'note: al_numpy.py not found next to rpc.py ({al_path}); azur_lane will fall back to PP-OCR')
        return False
    ocr_pkg = types.ModuleType('module.ocr')
    ocr_pkg.__path__ = []
    sys.modules['module.ocr'] = ocr_pkg
    spec = importlib.util.spec_from_file_location('module.ocr.al_numpy', al_path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules['module.ocr.al_numpy'] = mod
    spec.loader.exec_module(mod)
    return True


def find_rpc_path(arg):
    if arg:
        return arg
    here = os.path.dirname(os.path.abspath(__file__))
    candidates = [
        os.path.join(here, '..', 'overlays', 'module', 'ocr', 'rpc.py'),  # 仓库布局 rootfs/build/
        os.path.join(here, 'rpc.py'),                                     # 平铺拷贝
        os.path.join(os.getcwd(), 'module', 'ocr', 'rpc.py'),             # ALAS 根（overlays 已应用）
        '/opt/alas/module/ocr/rpc.py',                                    # rootfs 内绝对路径
    ]
    for c in candidates:
        if os.path.isfile(c):
            return c
    return None


# ---------------------------------------------------------------- 合成图

def make_line_image(text, font, inverted=False):
    """PIL 画单行文字图 → np 3ch uint8（RGB，与 ALAS 图像约定一致）。反相=浅字深底。"""
    from PIL import Image, ImageDraw
    import numpy as np
    pad = 6
    probe = Image.new('L', (8, 8))
    d = ImageDraw.Draw(probe)
    bbox = d.textbbox((0, 0), text, font=font)
    w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
    img = Image.new('L', (w + pad * 2, h + pad * 2), color=0 if inverted else 255)
    d = ImageDraw.Draw(img)
    d.text((pad - bbox[0], pad - bbox[1]), text, font=font, fill=255 if inverted else 0)
    arr = np.asarray(img, dtype=np.uint8)
    return arr


def load_font(arg):
    from PIL import ImageFont
    candidates = [arg] if arg else []
    candidates += [
        os.environ.get('ALASAOS_OCR_FONT', ''),
        'C:/Windows/Fonts/arial.ttf',
        '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
        '/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed.ttf',
    ]
    for path in candidates:
        if path and os.path.isfile(path):
            return ImageFont.truetype(path, 28)
    try:
        return ImageFont.load_default(size=28)  # Pillow >= 10.1 支持 size
    except TypeError:
        return ImageFont.load_default()


# ---------------------------------------------------------------- 测试步骤

def test_model_load(rpc, request_human_takeover):
    try:
        ok = rpc.alive()
    except Exception as e:  # noqa: BLE001 - 门禁要抓一切
        _record('model_load', False, f'alive() raised: {e!r}')
        return False
    _record('model_load', ok, 'det+rec sessions ready' if ok else 'alive() is False')
    return ok


def test_azur_lane_engine(rpc):
    """azur_lane 必须真实加载 numpy 字体模型，回落 PP-OCR 即 FAIL。

    判定信号 = ModelProxy._al 句柄（rpc.py `_get_al_engine` 的返回值）：
    模型缺失/加载失败时 rpc 静默回落 PP-OCR（_al=None），门禁若不断言它，
    全绿也从未验过 numpy 引擎——这正是本断言存在的原因（批次 G2）。
    """
    proxy = rpc.ModelProxy(lang='azur_lane')
    ok = proxy._al is not None
    _record('azur_lane_numpy_engine', ok,
            'numpy engine loaded' if ok else
            'azur_lane weights missing/load failed -> PP-OCR fallback (gate requires the real numpy engine)')
    return ok


def test_synthetic(rpc, font, request_human_takeover):
    import numpy as np
    proxy = rpc.ModelProxy(lang='azur_lane')
    total = 0
    failed = 0
    for text in SYNTH_CASES:
        arr = make_line_image(text, font, inverted=False)
        arr3 = np.stack([arr] * 3, axis=-1)  # 3ch RGB 行图（模拟 ALAS crop 后未转灰度的输入）
        try:
            got = ''.join(proxy.ocr_for_single_line(arr3))
        except request_human_takeover as e:
            got = f'<RequestHumanTakeover: {e}>'
        ok = got == text
        total += 1
        failed += 0 if ok else 1
        print(f'  synth 3ch   expect={text!r:<14} got={got!r:<14} {"OK" if ok else "MISMATCH"}')
        if not ok:
            _record(f'synth:{text}', False, f'got {got!r}')
    for text in SYNTH_INVERTED_CASES:
        arr = make_line_image(text, font, inverted=True)
        arr3 = np.stack([arr] * 3, axis=-1)
        try:
            got = ''.join(proxy.ocr_for_single_line(arr3))
        except request_human_takeover as e:
            got = f'<RequestHumanTakeover: {e}>'
        ok = got == text
        total += 1
        failed += 0 if ok else 1
        print(f'  synth inv   expect={text!r:<14} got={got!r:<14} {"OK" if ok else "MISMATCH"}')
        if not ok:
            _record(f'synth_inv:{text}', False, f'got {got!r}')
    name = 'synthetic_digits'
    if failed == 0:
        _record(name, True, f'{total}/{total} exact match')
    else:
        _record(name, False, f'{total - failed}/{total} exact match')
    return failed == 0


def test_gray2d(rpc, font, request_human_takeover):
    """2D 灰度单通道（ndim==2）直接喂，验证 rpc.py 的 3ch 堆叠分支（m0 硬坑）。"""
    proxy = rpc.ModelProxy(lang='azur_lane')
    failed = 0
    for text in GRAY2D_CASES:
        arr2d = make_line_image(text, font, inverted=False)  # shape (h, w) uint8
        assert arr2d.ndim == 2
        try:
            got = ''.join(proxy.ocr_for_single_line(arr2d))
        except request_human_takeover as e:
            got = f'<RequestHumanTakeover: {e}>'
        ok = got == text
        failed += 0 if ok else 1
        print(f'  gray 2d     expect={text!r:<14} got={got!r:<14} {"OK" if ok else "MISMATCH"}')
        if not ok:
            _record(f'gray2d:{text}', False, f'got {got!r}')
    _record('gray2d_stacking', failed == 0, f'{len(GRAY2D_CASES) - failed}/{len(GRAY2D_CASES)} exact match')
    return failed == 0


def test_real_dir(rpc, real_dir, request_human_takeover):
    proxy = rpc.ModelProxy(lang='azur_lane')
    paths = sorted(glob.glob(os.path.join(real_dir, '*.png')))
    if not paths:
        _record('real_dir', False, f'no *.png in {real_dir}')
        return False
    import numpy as np
    try:
        from PIL import Image
    except ImportError:
        _skip('real_dir', 'PIL not installed')
        return True
    hits = 0
    for p in paths:
        expect = os.path.splitext(os.path.basename(p))[0]
        img = Image.open(p)
        if img.mode not in ('L', 'RGB'):
            img = img.convert('RGB')
        arr = np.asarray(img, dtype=np.uint8)  # 保持原通道数：2D 走堆叠分支，3ch 直喂（贴近生产）
        try:
            got = ''.join(proxy.ocr_for_single_line(arr))
        except request_human_takeover as e:
            got = f'<RequestHumanTakeover: {e}>'
        ok = got == expect
        hits += 1 if ok else 0
        print(f'  real  {os.path.basename(p):<24} got={got!r:<24} {"OK" if ok else "MISMATCH"}')
    acc = hits / len(paths)
    _record('real_dir', acc >= PASS_THRESHOLD,
            f'{hits}/{len(paths)} = {acc:.2%} (threshold {PASS_THRESHOLD:.0%})')
    return acc >= PASS_THRESHOLD


def test_screenshot(rpc, screenshot):
    """整屏 det+rec 冒烟：打印前 20 个识别框，不断言，供人工 sanity。"""
    try:
        from PIL import Image
        import numpy as np
    except ImportError:
        _skip('screenshot', 'PIL not installed')
        return True
    engine = rpc.ModelProxy._get_engine()
    image = np.asarray(Image.open(screenshot).convert('RGB'), dtype=np.uint8)
    results = engine.det_rec_debug(image)
    print(f'  det_rec: {len(results)} boxes, first 20:')
    for box, text in results[:20]:
        x1, y1 = int(box[:, 0].min()), int(box[:, 1].min())
        x2, y2 = int(box[:, 0].max()), int(box[:, 1].max())
        print(f'    [{x1:>4},{y1:>4} ~ {x2:>4},{y2:>4}] {text!r}')
    _record('screenshot', True, f'{len(results)} boxes (sanity only, no assert)')
    return True


# ---------------------------------------------------------------- main

def main():
    parser = argparse.ArgumentParser(description='Spike F OCR gate (AlasAos v3)')
    parser.add_argument('--model-dir', default=os.environ.get('ALASAOS_OCR_MODEL_DIR', './models/ocr'),
                        help='含 det.onnx/rec.onnx/keys.txt 的目录（默认 $ALASAOS_OCR_MODEL_DIR 或 ./models/ocr）')
    parser.add_argument('--rpc-path', default=None, help='rpc.py 路径（默认按仓库/rootfs 布局自动探测）')
    parser.add_argument('--real-dir', default=None, help='真实行图目录（*.png，文件名=期望文本）')
    parser.add_argument('--screenshot', default=None, help='整屏截图路径，跑 det+rec 冒烟打印')
    parser.add_argument('--font', default=None, help='合成图 ttf 字体路径')
    args = parser.parse_args()

    os.environ['ALASAOS_OCR_MODEL_DIR'] = os.path.abspath(args.model_dir)

    rpc_path = find_rpc_path(args.rpc_path)
    if not rpc_path:
        print('OCR_GATE FAIL: rpc.py not found (use --rpc-path)')
        return 1
    print(f'rpc.py     : {os.path.abspath(rpc_path)}')
    print(f'model dir  : {os.path.abspath(args.model_dir)}')

    request_human_takeover = install_alas_stubs()
    try:
        import numpy  # noqa: F401
        import onnxruntime  # noqa: F401
    except ImportError as e:
        print(f'OCR_GATE FAIL: missing dependency: {e}')
        return 1
    load_al_numpy(rpc_path)
    rpc = load_rpc(rpc_path)

    all_ok = True
    loaded = test_model_load(rpc, request_human_takeover)
    all_ok &= loaded
    all_ok &= test_azur_lane_engine(rpc)
    if loaded:
        try:
            from PIL import Image, ImageDraw, ImageFont  # noqa: F401
            font = load_font(args.font)
            all_ok &= test_synthetic(rpc, font, request_human_takeover)
            all_ok &= test_gray2d(rpc, font, request_human_takeover)
        except ImportError:
            _skip('synthetic_digits', 'PIL not installed')
            _skip('gray2d_stacking', 'PIL not installed')
        if args.real_dir:
            all_ok &= test_real_dir(rpc, args.real_dir, request_human_takeover)
        if args.screenshot:
            all_ok &= test_screenshot(rpc, args.screenshot)

    n_pass = sum(1 for _, s, _ in _results if s == 'PASS')
    n_fail = sum(1 for _, s, _ in _results if s == 'FAIL')
    n_skip = sum(1 for _, s, _ in _results if s == 'SKIP')
    print('-' * 60)
    print(f'summary: {n_pass} PASS, {n_fail} FAIL, {n_skip} SKIP')
    print('OCR_GATE PASS' if all_ok and n_fail == 0 else 'OCR_GATE FAIL')
    return 0 if all_ok and n_fail == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
