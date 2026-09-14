# -*- coding: utf-8 -*-
"""wrap_long_lines.py: 修 signature 类 MaxLineLength——参数逐行折行。"""
import os
import re
import html

ANDROID = os.path.abspath(".")

def main():
    txt = open("android/core/engine/build/reports/detekt/detekt.xml", encoding="utf-8", errors="replace").read()
    files = re.findall(r'<file name="([^"]+)">(.*?)</file>', txt, re.S)
    fixed = 0
    skipped = []
    for fname, body in files:
        fname = html.unescape(fname)
        lines_html = re.findall(r'<error line="(\d+)"[^>]*source="detekt\.MaxLineLength"', body)
        if not lines_html:
            continue
        p = fname.replace("file:///", "")
        if not os.path.exists(p):
            continue
        rows = open(p, encoding="utf-8").read().split("\n")
        changed = False
        for lns in sorted({int(x) for x in lines_html}, reverse=True):
            i = lns - 1
            if i >= len(rows):
                continue
            ln = rows[i]
            m = re.match(r"^(\s*)(internal (?:suspend |inline )*fun [A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*)\((.+)\)(:?\s*[={].*)?$", ln)
            if m and len(ln) > 120:
                indent, head, params, tail = m.group(1), m.group(2), m.group(3), m.group(4) or " {"
                params = params.strip()
                if "{" in params or "(" in params:
                    skipped.append((os.path.basename(p), lns, "复杂参数"))
                    continue
                ps = [x.strip() for x in params.split(",")]
                inner = indent + "    "
                newln = (f"{indent}{head}(\n"
                         + "\n".join(f"{inner}{x}," for x in ps[:-1]) + f"\n{inner}{ps[-1]}\n"
                         + f"{indent}){tail}")
                rows[i:i + 1] = newln.split("\n")
                changed = True
                fixed += 1
            else:
                if len(ln) > 120:
                    skipped.append((os.path.basename(p), lns, ln.strip()[:60]))
        if changed:
            open(p, "w", encoding="utf-8", newline="").write("\n".join(rows))
    print("wrapped:", fixed)
    for s in skipped:
        print("MANUAL:", s)


if __name__ == "__main__":
    main()
