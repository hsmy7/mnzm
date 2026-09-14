# -*- coding: utf-8 -*-
"""按新鲜 detekt 报告修剪 UnusedImports（保留通配导入；幂等）。

用法: python prune_imports.py <detekt.xml 报告> [<模块源根目录>...]
仅删除报告中 UnusedImports 条目指向的具体导入行；`*` 通配导入永不删。
"""
from __future__ import annotations

import os
import re
import sys
import html


def main() -> None:
    report = sys.argv[1]
    x = open(report, encoding="utf-8").read()
    files = re.findall(r'<file name="([^"]+)">(.*?)</file>', x, re.S)
    pruned = 0
    for fname, body in files:
        if "detekt.UnusedImports" not in body:
            continue
        lines = re.findall(r'<error line="(\d+)"[^>]*source="detekt\.UnusedImports"', body)
        if not lines:
            continue
        fname = html.unescape(fname)
        p = fname.replace("file:///", "").replace("file://", "")
        if not os.path.exists(p):
            print("跳过（不存在）:", p)
            continue
        src = open(p, encoding="utf-8").read()
        srows = src.split("\n")
        drop = set()
        for lns in lines:
            i = int(lns) - 1
            if i < len(srows):
                row = srows[i]
                if row.strip().startswith("import ") and not row.strip().endswith(".*"):
                    drop.add(i)
                elif row.strip().startswith("import ") and row.strip().endswith(".*"):
                    print(f"保留通配导入 {p}:{lns}")
        if drop:
            kept = [r for i, r in enumerate(srows) if i not in drop]
            open(p, "w", encoding="utf-8", newline="").write("\n".join(kept))
            pruned += len(drop)
            print(f"{os.path.basename(p)}: 修剪 {len(drop)} import")
    print("共修剪", pruned)


if __name__ == "__main__":
    main()
