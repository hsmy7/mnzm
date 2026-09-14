# -*- coding: utf-8 -*-
"""盘点目标文件：类、成员函数（修饰符/行号/行数）。"""
from __future__ import annotations

import sys
import os

sys.path.insert(0, os.path.dirname(__file__))
from splitlib import (mask, find_class_span, build_elements, parse_fn_signature,
                      find_matching_brace, class_state_names, ext_references_class_state)


def inventory(path: str, only_class: str | None = None) -> None:
    text = open(path, encoding="utf-8").read()
    m = mask(text)
    # 顶层类/对象（去重：KDoc/注释中的引用经 mask 过滤）
    import re
    tops = []
    seen = set()
    for mo in re.finditer(r"\b(class|interface|object)\s+`?([A-Z][A-Za-z0-9_]*)`?", text):
        if 1 in m[mo.start():mo.end()]:
            continue
        cname = mo.group(2)
        if cname in seen:
            continue
        seen.add(cname)
        tops.append((mo.group(1), cname, mo.start()))
    lines_idx = None

    def lineno(pos: int) -> int:
        return text.count("\n", 0, pos) + 1

    print(f"== {path} ({text.count(chr(10)) + 1} 行) ==")
    for kind, cname, cstart in tops:
        try:
            decl_start, o, c = find_class_span(text, cname)
        except ValueError as e:
            print(f"  [{kind} {cname}] 无类体: {e}")
            continue
        if only_class and cname != only_class:
            continue
        elems = build_elements(text, o, c, m)
        fns = []
        props = 0
        others = []
        nested = []
        for e in elems:
            if e.kind == "fun":
                sig = parse_fn_signature(e.text)
                mods = ",".join(sig.modifiers) if sig else "?"
                nl = lineno(e.start)
                sz = text.count("\n", e.start, e.end) + 1
                expr = "=式" if sig and sig.is_expr_body else ""
                # 成员扩展检测
                if sig and sig.has_receiver:
                    from splitlib import ext_references_class_state
                    decl_pos, ob, cb = find_class_span(text, cname)
                    props = class_state_names(text, decl_pos, ob, {x.name for x in elems if x.kind in ("val", "var") and x.name})
                    names = {x.name for x in elems if x.kind == "fun" and x.name}
                    ext = "EXT!" if ext_references_class_state(e.text, props, names, cname) else "EXT-"
                else:
                    ext = ""
                ovr = "OVR" if sig and "override" in sig.modifiers else ""
                tag = "+".join(t for t in (ovr, ext) if t)
                fns.append(f"    L{nl:>5} {sz:>4}行 {mods:<28} {e.name} {tag} {expr}")
            elif e.kind in ("val", "var"):
                props += 1
            elif e.kind in ("class", "object", "interface"):
                nested.append(f"{e.kind} {e.name}")
            else:
                others.append(e.kind)
        print(f"  [{kind} {cname}] 体 {lineno(o)}-{lineno(c)} 行, 函数 {len(fns)}, 属性 {props}, 嵌套 {nested}, 其他 {others}")
        ovr = sum(1 for f in fns if "OVR" in f)
        ext_stuck = sum(1 for f in fns if "EXT!" in f)
        ext_clean = sum(1 for f in fns if "EXT" in f and "EXT!" not in f)
        movable = sum(1 for f in fns if "OVR" not in f and "EXT!" not in f)
        print(f"    >>> 预算: 总{len(fns)} = override {ovr} + 深耦合扩展 {ext_stuck} + 可移动 {movable}（含干净扩展 {ext_clean}）; 拆后需 ≤19")
        for f in fns:
            print(f)


if __name__ == "__main__":
    target = sys.argv[1]
    only = sys.argv[2] if len(sys.argv) > 2 else None
    inventory(target, only)
