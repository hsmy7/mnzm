# -*- coding: utf-8 -*-
"""检查候选移动函数的调用点分布（引擎主源/引擎测试/其他模块）。ERE 安全版。"""
from __future__ import annotations

import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ANDROID = os.path.join(ROOT, "android")


def grep_e(pattern: str, roots: list[str]) -> list[str]:
    out = subprocess.run(["grep", "-rnE", pattern, "--include=*.kt"] + roots,
                         capture_output=True, text=True)
    return [l for l in out.stdout.splitlines() if l]


def main() -> None:
    src = sys.argv[1]
    fns = sys.argv[2:]
    engine_main = [os.path.join(ANDROID, "core/engine/src/main/java")]
    engine_test = [os.path.join(ANDROID, "core/engine/src/test")]
    external = [os.path.join(ANDROID, m) for m in
                ("app/src", "feature/game/src", "core/data/src", "core/domain/src", "core/ui/src")]
    print(f"{'函数':<44}{'main':>6}{'test':>6}{'ext':>5}")
    for fn in fns:
        pat = fn + r"\s*[(]"
        c_main = [l for l in grep_e(pat, engine_main) if src not in l]
        c_test = grep_e(pat, engine_test)
        c_ext = grep_e(pat, external)
        print(f"{fn:<44}{len(c_main):>6}{len(c_test):>6}{len(c_ext):>5}")
        for l in (c_ext[:3]):
            print("      EXT:", l.split('android')[-1][:120])


if __name__ == "__main__":
    main()
