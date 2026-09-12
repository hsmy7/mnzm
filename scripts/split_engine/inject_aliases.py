# -*- coding: utf-8 -*-
"""为所有拆分产物注入 companion 常量别名（TAG/CONFIG_PATH 等）。"""
import os
import re
import subprocess

ANDROID = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ROOT = ANDROID
E = "android/core/engine/src/main/java/com/xianxia/sect/core"
mask_cache = {}


def main():
    out = subprocess.run(["git", "-c", "core.quotepath=false", "status", "--short",
                          "android/core/engine/src/main"], cwd=ROOT, capture_output=True, text=True)
    new_files = [os.path.join(ROOT, l.strip()[2:].strip()) for l in out.stdout.splitlines()
                 if l.startswith("??") and l.strip()[2:].strip().endswith(".kt")]
    injected = 0
    for mfp in new_files:
        mt = open(mfp, encoding="utf-8").read()
        rx = re.findall(r"internal (?:suspend |inline )*fun ([A-Za-z_][A-Za-z0-9_]*)\.", mt)
        if not rx:
            continue
        cls = max(set(rx), key=rx.count)
        # 源类文件定位
        cand = subprocess.run(["grep", "-rl", "-E", "(class|object)[ ]+" + cls + "([^A-Za-z0-9_]|$)",
                               "--include=*.kt", os.path.join(ANDROID, "android/core/engine/src/main/java")],
                              capture_output=True, text=True).stdout.split()
        src = None
        for c in cand:
            ct = open(c, encoding="utf-8").read()
            if re.search(r"(class|object)\s+" + cls + r"\b[^{]*\{", ct):
                src = c
                break
        if not src:
            continue
        st = open(src, encoding="utf-8").read()
        # companion 块
        cm = re.search(r"companion object[^{]*\{", st)
        consts = []
        if cm:
            depth = 1
            i = cm.end()
            while i < len(st) and depth:
                if st[i] == "{":
                    depth += 1
                elif st[i] == "}":
                    depth -= 1
                i += 1
            comp = st[cm.end():i]
            consts = re.findall(r"internal\s+(?:const\s+)?val\s+([A-Za-z_][A-Za-z0-9_]*)", comp)
        need = [n for n in consts
                if re.search(r"\b%s\b" % n, mt) and ("private val %s =" % n) not in mt]
        if not need:
            continue
        anchors = [l for l in mt.split("\n") if l.startswith("// ──")]
        inj = "\n".join(f"private val {n} = {cls}.{n}" for n in need)
        if anchors:
            mt = mt.replace(anchors[0], anchors[0] + "\n\n" + inj, 1)
        else:
            lines = mt.split("\n")
            last = max(i for i, l in enumerate(lines) if l.startswith("import "))
            lines.insert(last + 1, "\n" + inj)
            mt = "\n".join(lines)
        open(mfp, "w", encoding="utf-8", newline="").write(mt)
        injected += 1
        print(os.path.basename(mfp), "+", need)
    print("注入文件数:", injected)


if __name__ == "__main__":
    main()
