#!/usr/bin/env python3
# =============================================================================
# AlasAos · ALAS CDN pack 更新通道（上游 deploy/git_over_cdn/client.py 的零依赖复刻）
#
# 协议（与上游逐条对齐）：
#   1. GET {base}/latest.json（超时 3s，两个 base 轮试）→ {"commit": <sha>}
#   2. latest == current → UPTODATE
#   3. GET {base}/{latest}/{current}.zip（读超时 20s）→ pack-{latest}.pack/.idx
#   4. 解进 .git/objects/pack/（先 .tmp 后 os.replace）→ 写 refs/remotes/origin/master
#
# 不直接对 App：仅供 alasaos_update.sh 调用，最后一行打印四态之一供 bash 分流：
#   UPTODATE              本地已是最新（exit 0）
#   PACK_READY <sha>      pack 与 refs 已就位，bash 侧 git reset --hard 即可（exit 0）
#   NO_PACK <reason>      CDN 正常但没有此 current 的增量包（404 等），应回落 git://（exit 1）
#   UNAVAILABLE <reason>  CDN 本身不可达/超时/包损坏，应回落 git://（exit 1）
#
# 只用标准库（urllib + zipfile），proot 内 python3 即可跑，不引第三方依赖。
# =============================================================================

import io
import json
import os
import re
import shutil
import sys
import time
import urllib.error
import urllib.request
import zipfile

BASE_URLS = [
    'https://1818706573.cdn.123clouddisk.com/1818706573/pack/LmeSzinc_AzurLaneAutoScript_master',
    'https://vip.123pan.cn/1818706573/pack/LmeSzinc_AzurLaneAutoScript_master',
]

# 与上游同：latest.json 3s、pack 读 20s（socket 级读超时，非全程总超时）
LATEST_TIMEOUT = 3
PACK_TIMEOUT = 20

SHA_RE = re.compile(r'[0-9a-f]{40}')


def fetch_latest():
    """两个 CDN base 轮试 latest.json；返回 (base, sha) 或 (None, '')。"""
    for base in BASE_URLS:
        url = base + '/latest.json'
        print(f'cdn| Fetch url: {url}')
        try:
            # trust_env=False 等价物：urllib 默认读 http_proxy 环境变量，显式建无代理 opener
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(url, timeout=LATEST_TIMEOUT) as resp:
                if resp.status != 200:
                    print(f'cdn| latest.json status={resp.status}')
                    continue
                commit = json.loads(resp.read().decode('utf-8'))['commit']
            if not SHA_RE.fullmatch(commit):
                print(f'cdn| latest.json bad commit: {commit!r}')
                continue
            print(f'cdn| LatestCommit {commit}')
            return base, commit
        except Exception as e:
            print(f'cdn| latest.json fail: {e}')
    return None, ''


def download_pack(base, latest, current, alas_dir):
    """下增量包并把 pack/idx 落进 .git/objects/pack/；返回 (ok, reason)。

    分块读 + 进度旁路：每 256KB 往 ALASAOS_UPDATE_PROGRESS 写一行
    `CDN 12.3/45.6 MB · 280 KB/s`，App 侧轮询喂给启动清单卡片。
    """
    url = f'{base}/{latest}/{current}.zip'
    print(f'cdn| Fetch url: {url}')
    progress_file = os.environ.get('ALASAOS_UPDATE_PROGRESS', '')
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(url, timeout=PACK_TIMEOUT) as resp:
            total = int(resp.headers.get('Content-Length') or 0)
            chunks, done, start = [], 0, time.monotonic()
            while True:
                chunk = resp.read(262144)
                if not chunk:
                    break
                chunks.append(chunk)
                done += len(chunk)
                if progress_file:
                    elapsed = max(time.monotonic() - start, 1e-3)
                    speed = done / elapsed
                    speed_txt = f'{speed / 1048576:.1f} MB/s' if speed >= 1048576 else f'{speed / 1024:.0f} KB/s'
                    total_txt = f'{total / 1048576:.1f}' if total else '?'
                    with open(progress_file, 'w', encoding='utf-8') as f:
                        f.write(f'CDN {done / 1048576:.1f}/{total_txt} MB · {speed_txt}')
            data = b''.join(chunks)
    except urllib.error.HTTPError as e:
        return False, f'NO_PACK http-{e.code}'
    except Exception as e:
        return False, f'UNAVAILABLE pack: {e}'

    pack_dir = os.path.join(alas_dir, '.git', 'objects', 'pack')
    try:
        zipped = zipfile.ZipFile(io.BytesIO(data))
        os.makedirs(pack_dir, exist_ok=True)
        for name in [f'pack-{latest}.pack', f'pack-{latest}.idx']:
            print(f'cdn| Unzip {name}')
            with zipped.open(zipped.getinfo(name)) as source:
                tmp = os.path.join(pack_dir, name + '.tmp')
                out = os.path.join(pack_dir, name)
                with open(tmp, 'wb') as target:
                    shutil.copyfileobj(source, target)
                os.replace(tmp, out)
    except zipfile.BadZipFile:
        return False, 'UNAVAILABLE not-a-zip'
    except KeyError as e:
        return False, f'UNAVAILABLE zip-missing {e}'
    except Exception as e:
        return False, f'UNAVAILABLE unzip: {e}'

    # 更新远端引用（上游 update_refs 同款）；reset 由 bash 侧统一做
    ref_file = os.path.join(alas_dir, '.git', 'refs', 'remotes', 'origin', 'master')
    os.makedirs(os.path.dirname(ref_file), exist_ok=True)
    with open(ref_file, 'w', encoding='utf-8', newline='') as f:
        f.write(latest + '\n')
    print(f'cdn| Update refs: {ref_file}')
    return True, ''


def main():
    alas_dir = sys.argv[1]
    current = sys.argv[2].strip() if len(sys.argv) > 2 else ''
    print(f'cdn| CurrentCommit {current or "unknown"}')

    base, latest = fetch_latest()
    if not latest:
        print('UNAVAILABLE latest.json')
        return 1
    if latest == current:
        print('UPTODATE')
        return 0
    if not SHA_RE.fullmatch(current or ''):
        # 无本地 sha 就没有对应增量包（URL 必 404），直接让调用方回落 git://
        print('NO_PACK unknown-current')
        return 1

    ok, reason = download_pack(base, latest, current, alas_dir)
    if ok:
        print(f'PACK_READY {latest}')
        return 0
    print(reason)
    return 1


if __name__ == '__main__':
    sys.exit(main())
