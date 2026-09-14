# -*- coding: utf-8 -*-
"""拆分后可见性收敛：被新文件引用的源类 private 成员 → internal。

- 类体属性/主构造器注入属性 val/var（含 companion const）
- 留守成员函数（被移动函数调用）
- companion 常量在移动侧经 `private val X = Class.X` 别名引用（§2.29 惯例）
"""
from __future__ import annotations

import re
import sys
import os

sys.path.insert(0, os.path.dirname(__file__))
from splitlib import mask, find_class_span, build_elements, parse_fn_signature, class_state_names


def main() -> None:
    src_path, class_name = sys.argv[1], sys.argv[2]
    moved_paths = sys.argv[3:]
    text = open(src_path, encoding="utf-8").read()
    dcl, o, c = find_class_span(text, class_name)
    elems = build_elements(text, o, c, mask(text))

    all_state = class_state_names(text, dcl, o, set())

    priv_body_props: set[str] = set()
    priv_funs: set[str] = set()
    for e in elems:
        if e.kind in ("val", "var") and e.name:
            if re.search(r"\bprivate\b", text[e.decl_start:min(e.decl_start + 200, e.end)]):
                priv_body_props.add(e.name)
        elif e.kind == "fun" and e.name:
            sig = parse_fn_signature(e.text)
            if sig and "private" in sig.modifiers:
                priv_funs.add(e.name)
    # companion 内 private const/val
    for e in elems:
        seg = text[e.start:e.end]
        if e.kind == "other" or (e.kind == "object" and "companion object" in seg):
            for cmo in re.finditer(r"private\s+(?:const\s+)?(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)", seg):
                priv_body_props.add(cmo.group(1))
    ctor_priv = []
    for cmo in re.finditer(r"private\s+val\s+([A-Za-z_][A-Za-z0-9_]*)\s*:", text[dcl:o]):
        ctor_priv.append(cmo.group(1))

    moved_text = "\n".join(open(p, encoding="utf-8").read() for p in moved_paths)

    new_text = text
    changed = []
    # 1) 构造器属性
    for nm in ctor_priv:
        if re.search(r"\b%s\b" % re.escape(nm), moved_text):
            pat = re.compile(r"(\b)private(\s+val\s+%s\s*:)" % re.escape(nm))
            new_text, n = pat.subn(r"\1internal\2", new_text)
            if n:
                changed.append(f"ctor-prop {nm}")
    # 2) 类体/companion 属性
    for nm in sorted(priv_body_props):
        if re.search(r"\b%s\b" % re.escape(nm), moved_text):
            pat = re.compile(r"(\b)private(\s+(?:const\s+)?(?:val|var)\s+%s\b)" % re.escape(nm))
            new_text, n = pat.subn(r"\1internal\2", new_text)
            if n:
                changed.append(f"prop {nm}")
    # 3) 留守私有函数
    for nm in sorted(priv_funs):
        if re.search(r"\b%s\s*(?:<[^>]*>)?\(" % re.escape(nm), moved_text):
            pat = re.compile(r"(\b)private(\s+fun\s+(?:[A-Za-z_][A-Za-z0-9_<>?,\s.]*)?\.?%s\b)" % re.escape(nm))
            new_text, n = pat.subn(r"\1internal\2", new_text)
            if n:
                changed.append(f"fun {nm}")

    with open(src_path, "w", encoding="utf-8", newline="") as f:
        f.write(new_text)
    print("internal 化：", changed if changed else "（无）")
    for p in moved_paths:
        mt = open(p, encoding="utf-8").read()
        refs = sorted(n for n in all_state if re.search(r"\b%s\b" % re.escape(n), mt))
        print(f"{os.path.basename(p)}: 引用类状态 {refs}")
        if any(re.search(r"\b%s\b" % n, mt) for n in refs):
            comp_only = [n for n in refs if n not in priv_body_props and n not in
                         {x.name for x in elems if x.kind in ('val', 'var')}]
            if comp_only:
                print(f"  注意（companion 成员需文件级别名或保留原位）: {comp_only}")


if __name__ == "__main__":
    main()
