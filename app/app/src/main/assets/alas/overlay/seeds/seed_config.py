#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AlasAos：播种实例配置 + deploy.yaml 镜像档同步。

1) 由 config/template.json 生成 config/alas.json 并注入桥接配置。
   幂等：config/alas.json 已存在时不覆盖（改配置请走 WebUI）。
2) 按 ALASAOS_MIRROR（cn|github，缺省 cn）改写 config/deploy.yaml 的
   Repository/PypiMirror 两键，其余逐字节不动：按行首键名原位替换，不整文件
   dump（保住注释），无变化不落盘。镜像档切换后 deploy 配置跟着 App 侧走；
   github 值不在 ALAS config_redirect 的改写名单内，写得住。

在 ~/alas 根目录下运行：python seed_config.py
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
# 允许从任意目录运行：脚本在 termux/ 下，ALAS 根目录取 ~/alas
ALAS = os.environ.get('ALASAOS_ALAS_ROOT', os.path.expanduser('~/alas'))

SRC = os.path.join(ALAS, 'config', 'template.json')
DST = os.path.join(ALAS, 'config', 'alas.json')
DEPLOY = os.path.join(ALAS, 'config', 'deploy.yaml')

# 镜像档 → deploy.yaml 两键（与 App 侧 AlasMirror 枚举一一对应）
MIRRORS = {
    'cn': {
        'Repository': 'git://git.lyoko.io/AzurLaneAutoScript',
        'PypiMirror': 'https://mirrors.aliyun.com/pypi/simple',
    },
    'github': {
        'Repository': 'https://github.com/LmeSzinc/AzurLaneAutoScript',
        'PypiMirror': 'https://pypi.org/simple',
    },
}

OVERRIDES = {
    # Alas.Emulator
    ('Alas', 'Emulator', 'Serial'): 'alasaos',
    ('Alas', 'Emulator', 'PackageName'): 'com.bilibili.azurlane',
    ('Alas', 'Emulator', 'ScreenshotMethod'): 'alasaos',
    ('Alas', 'Emulator', 'ControlMethod'): 'alasaos',
    # 省电与稳定：截图去抖动关闭（虚拟屏无噪点）
    ('Alas', 'Emulator', 'ScreenshotDedithering'): False,
}


def sync_deploy():
    """按镜像档改写 deploy.yaml 的 Repository/PypiMirror 两键；每次启动都跑（档位可切）"""
    mirror = os.environ.get('ALASAOS_MIRROR', 'cn')
    if mirror not in MIRRORS:
        print(f'unknown ALASAOS_MIRROR={mirror!r}, fallback to cn')
        mirror = 'cn'
    if not os.path.exists(DEPLOY):
        print(f'{DEPLOY} missing, skip deploy sync')
        return
    # newline='' 关掉文本模式换行翻译：读写什么结尾就留什么结尾，跨平台逐字节稳定
    with open(DEPLOY, encoding='utf-8', newline='') as f:
        lines = f.readlines()
    out = []
    changed = []
    for line in lines:
        stripped = line.lstrip()
        for key, value in MIRRORS[mirror].items():
            if stripped.startswith(f'{key}:'):
                indent = line[:len(line) - len(stripped)]
                new_line = f'{indent}{key}: {value}\n'
                if line != new_line:
                    changed.append(key)
                    line = new_line
                break
        out.append(line)
    if changed:
        with open(DEPLOY, 'w', encoding='utf-8', newline='') as f:
            f.writelines(out)
    print(f'deploy.yaml mirror={mirror} ({"/".join(changed)} updated)' if changed
          else f'deploy.yaml mirror={mirror} (already in sync)')


def main():
    sync_deploy()
    if os.path.exists(DST):
        print(f'{DST} already exists, skip')
        return
    with open(SRC, encoding='utf-8') as f:
        cfg = json.load(f)
    for (menu, group, arg), value in OVERRIDES.items():
        cfg[menu][group][arg] = value
    with open(DST, 'w', encoding='utf-8') as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)
    print(f'seeded {DST} with alasaos bridge config')


if __name__ == '__main__':
    sys.exit(main())
