# -*- coding: utf-8 -*-
"""扫描所有拆分产物，为跨包调用方补 import；报告外部模块调用。"""
import sys
import json
import os
import re
import subprocess

ANDROID = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MAIN = os.path.join(ANDROID, "core/engine/src/main/java")
TEST = os.path.join(ANDROID, "core/engine/src/test")
EXT = [os.path.join(ANDROID, m) for m in ("app/src", "feature/game/src", "core/data/src",
                                          "core/domain/src", "core/ui/src")]
E = "android/core/engine/src/main/java/com/xianxia/sect/core"


def grep_e(pattern, roots):
    out = subprocess.run(["grep", "-rnE", pattern, "--include=*.kt"] + roots,
                         capture_output=True, text=True)
    return [l for l in out.stdout.splitlines() if l]


# 1) 收集全部新文件（git status 未跟踪 + 已知清单）
out = subprocess.run(["git", "-c", "core.quotepath=false", "status", "--short", "android/core/engine/src/main"],
                     cwd=ANDROID, capture_output=True, text=True)
new_files = []
for line in out.stdout.splitlines():
    if line.startswith("??") and line.strip()[2:].strip().endswith(".kt"):
        new_files.append(os.path.join(ANDROID, line.strip()[2:].strip()))
new_files = [os.path.normpath(p) for p in new_files]
print("DEBUG new_files:", len(new_files), file=sys.stderr)

need_import = {}
ext_real = []
for mfp in new_files:
    mt = open(mfp, encoding="utf-8").read()
    pkg = re.search(r"^package\s+(\S+)", mt, re.M).group(1)
    fns = set(re.findall(r"internal (?:suspend |inline |crossinline )*fun [A-Za-z_][A-Za-z0-9_]*\.([A-Za-z_][A-Za-z0-9_]*)", mt))
    if not fns:
        continue
    for fn in fns:
        pat = fn + r"\s*[(]"
        for l in grep_e(pat, [MAIN]):
            path = re.search(r"(java[\\/].*?\.kt):", l)
            if not path:
                continue
            caller = path.group(1).replace(chr(92), "/")
            caller_pkg_m = None
            # 调用方包名 = java/ 后去掉文件名的目录
            cdir = os.path.dirname(caller).split("java/")[-1].replace("/", ".")
            if cdir != pkg:
                imp = "import %s.%s" % (pkg, fn)
                key = "android/core/engine/src/main/" + caller.split("java/")[-1]
                need_import.setdefault(key, set()).add(imp)
        for l in grep_e(pat, EXT):
            ext_real.append((fn, mfp, l.split("android")[-1][:130]))

import sys
print("DEBUG need_import:", len(need_import), file=sys.stderr)
applied = 0
for path, imps in sorted(need_import.items()):
    if not os.path.isfile(path):
        print("skip", path)
        continue
    t = open(path, encoding="utf-8").read()
    add = sorted(i for i in imps if i not in t)
    if not add:
        continue
    lines = t.split("\n")
    last = max(i for i, l in enumerate(lines) if l.startswith("import "))
    for k, imp in enumerate(add):
        lines.insert(last + 1 + k, imp)
    open(path, "w", encoding="utf-8", newline="").write("\n".join(lines))
    applied += len(add)
print("import 补齐:", applied, "处 /", len(need_import), "文件")
print("\n外部模块命中（需甄别）:")
seen = set()
for fn, mfp, line in ext_real:
    k = (fn, line[:80])
    if k in seen:
        continue
    seen.add(k)
    print(f"  {fn} <- {line}")
