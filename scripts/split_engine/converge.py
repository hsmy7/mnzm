# -*- coding: utf-8 -*-
"""编译错误驱动的收敛：Unresolved reference → 自动补扩展 import / 别名。"""
import os
import re
import subprocess
import sys

ANDROID = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MAIN = os.path.join(ANDROID, "android/core/engine/src/main/java")
NEW_FILES = []
out = subprocess.run(["git", "-c", "core.quotepath=false", "status", "--short",
                      "android/core/engine/src/main"], cwd=ANDROID, capture_output=True, text=True)
for line in out.stdout.splitlines():
    if line.startswith("??") and line.strip()[2:].strip().endswith(".kt"):
        NEW_FILES.append(os.path.join(ANDROID, line.strip()[2:].strip()))

# 索引：函数名 -> (包, 接收类)
index = {}
for mfp in NEW_FILES:
    mt = open(mfp, encoding="utf-8").read()
    pkg = re.search(r"^package\s+(\S+)", mt, re.M).group(1)
    for m in re.finditer(r"internal (?:suspend |inline )*fun (?:<[^>]*> )?([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)", mt):
        index.setdefault(m.group(2), (pkg, m.group(1)))

# 常量/属性别名索引：companion internal 常量名 -> (包, 类)
const_index = {}
for mfp in NEW_FILES:
    pass


def compile_errors():
    txt = open(sys.argv[1], encoding="utf-8", errors="replace").read()
    errs = []
    for m in re.finditer(r"e: (file:///[^\n]+\.kt):(\d+):\d+ (.+)", txt):
        errs.append((m.group(1)[len("file:///"):], int(m.group(2)), m.group(3)))
    return errs


def main():
    errs = compile_errors()
    fixed = 0
    for path, lineno, msg in errs:
        if any(x in path for x in ("GameEngineManualOps", "GameEngineDiscipleSlotOps", "GameEngineLoadDataOps",
                                   "CultivationEventMonthlyOps", "CultivationService.kt", "RoadFacadeImpl")):
            continue
        m = re.search(r"Unresolved reference '([A-Za-z_][A-Za-z0-9_]*)'", msg)
        if not m:
            continue
        name = m.group(1)
        if name in index:
            pkg, cls = index[name]
            imp = f"import {pkg}.{name}"
            t = open(path, encoding="utf-8").read()
            if imp not in t:
                lines = t.split("\n")
                lasts = [i for i, l in enumerate(lines) if l.startswith("import ")]
                if lasts:
                    lines.insert(lasts[-1] + 1, imp)
                    open(path, "w", encoding="utf-8", newline="").write("\n".join(lines))
                    fixed += 1
                    print(f"{os.path.basename(path)}: +{imp}")
    print("fixed:", fixed)


if __name__ == "__main__":
    main()
