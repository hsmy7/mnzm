# -*- coding: utf-8 -*-
"""拆分驱动器：把类成员函数按域移入同包新文件（顶层扩展函数）。

用法：
  python split_run.py <源文件> <类名> <spec.json>

spec.json:
{
  "files": [
    {"name": "XxxOps",                        // 新文件名（不含 .kt）
     "header": "// ── xx 域 ──",
     "fns": ["fnA", "fnB", "Class.fnA:L123"]  // 支持名字或 名字:L行号 消歧重载
    }
  ]
}

纪律：
- override/external/abstract 函数拒绝移动（契约面/符号面）。
- 移动文本 = dedent4 + 仅签名行改写；逐函数 diff 校验（仅允许声明行 ±1 行变化）。
- 新文件：package + 源文件全量 import + 域头注释 + 函数；文件尾换行。
- 源文件移除段后连续 3+ 空行压成 1 空行。
"""
from __future__ import annotations

import difflib
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(__file__))
from splitlib import (mask, find_class_span, build_elements, parse_fn_signature,
                      transform_to_extension, dedent4, class_state_names)


def fail(msg: str) -> None:
    print("FATAL:", msg)
    sys.exit(1)


def main() -> None:
    src_path, class_name, spec_path = sys.argv[1], sys.argv[2], sys.argv[3]
    spec = json.load(open(spec_path, encoding="utf-8"))
    text = open(src_path, encoding="utf-8").read()
    m = mask(text)
    dcl, o, c = find_class_span(text, class_name)
    elems = build_elements(text, o, c, m)
    funs = [e for e in elems if e.kind == "fun"]

    def lineno(pos: int) -> int:
        return text.count("\n", 0, pos) + 1

    # 索引：名字 -> [元素]
    by_name: dict[str, list] = {}
    for e in funs:
        by_name.setdefault(e.name, []).append(e)

    moved: dict[str, list[tuple]] = {}  # 新文件名 -> [(elem, transformed)]
    to_remove: list = []
    moved_names = set()
    moved_keys = set()

    # 类属性名/成员函数名集合（成员扩展干净度检查用）
    class_props = class_state_names(text, dcl, o, {e.name for e in elems if e.kind in ("val", "var") and e.name})
    member_names = {e.name for e in funs}

    for fout in spec["files"]:
        lst = moved.setdefault(fout["name"], [])
        for raw in fout["fns"]:
            name, lnspec = raw, None
            if ":L" in raw:
                name, lnspec = raw.split(":L")
            cands = by_name.get(name, [])
            if not cands:
                fail(f"函数未找到: {name}")
            elem = None
            if lnspec:
                want = int(lnspec)
                elem = next((e for e in cands if lineno(e.decl_start) == want), None)
                if elem is None:
                    fail(f"函数 {name} 无 L{want} 声明（候选: {[lineno(e.decl_start) for e in cands]}）")
            elif len(cands) > 1:
                fail(f"函数 {name} 有 {len(cands)} 个重载，需 名字:L行号 消歧: "
                     f"{[lineno(e.decl_start) for e in cands]}")
            else:
                elem = cands[0]
            dup_key = (elem.name, lineno(elem.decl_start))
            if lnspec is None and elem.name in moved_names:
                fail(f"函数重复移动: {name}")
            if dup_key in moved_keys:
                fail(f"函数重复移动: {name}:L{lineno(elem.decl_start)}")
            moved_names.add(elem.name)
            moved_keys.add(dup_key)
            sig = parse_fn_signature(elem.text)
            if sig is None:
                fail(f"签名解析失败: {name} L{lineno(elem.decl_start)}")
            if "override" in sig.modifiers:
                fail(f"拒绝移动 override 函数（契约面）: {name} L{lineno(elem.decl_start)}")
            if sig.has_receiver:
                from splitlib import ext_references_class_state
                if ext_references_class_state(elem.text, class_props, member_names, class_name):
                    fail(f"拒绝移动成员扩展（引用类状态，移出失去外层接收者）: {name} L{lineno(elem.decl_start)}")
            if "external" in sig.modifiers or "abstract" in sig.modifiers:
                fail(f"拒绝移动 external/abstract 函数: {name}")
            vis = fout.get("visibility", "internal")
            transformed = transform_to_extension(elem.text, class_name, vis)
            # 字节校验：dedent4(原段) 与 变换结果 diff 只允许签名行
            orig = dedent4(elem.text)
            diff = list(difflib.unified_diff(orig.split("\n"), transformed.split("\n"), lineterm="", n=0))
            changed = [l for l in diff if l.startswith(("+", "-")) and not l.startswith(("+++", "---"))]
            if len(changed) != 2 or not changed[0].startswith("-") or not changed[1].startswith("+") \
                    or "fun " not in changed[0] or "fun " not in changed[1]:
                fail(f"字节校验失败 {name}: diff={changed}")
            lst.append((elem, transformed))
            to_remove.append((elem.start, elem.end, name))

    # 移除源段（从后往前）
    new_text = text
    for start, end, name in sorted(to_remove, reverse=True):
        new_text = new_text[:start] + new_text[end:]
    # 类体内 3+ 连续换行压成 2（保留单一空行分隔）
    _, no, nc = find_class_span(new_text, class_name)
    body = new_text[no:nc]
    squeezed = re.sub(r"\n{3,}", "\n\n", body)
    new_text = new_text[:no] + squeezed + new_text[nc:]

    # 新文件内容
    pkg_mo = re.search(r"^package\s+(\S+)", text, re.M)
    pkg = pkg_mo.group(1)
    import_block = extract_imports(text)
    base_dir = os.path.dirname(src_path)
    for fout in spec["files"]:
        name = fout["name"]
        funcs = moved[name]
        parts = [f"package {pkg}", ""]
        parts.append(import_block.rstrip("\n"))
        parts.append("")
        if fout.get("header"):
            parts.append(fout["header"])
        for _, transformed in funcs:
            parts.append(transformed.rstrip("\n"))
            parts.append("")
        content = "\n".join(parts).rstrip("\n") + "\n"
        # 同文件 private 保留语义检查：private 扩展只能同文件——此处全 internal
        out_path = os.path.join(base_dir, name + ".kt")
        if os.path.exists(out_path):
            fail(f"新文件已存在: {out_path}")
        with open(out_path, "w", encoding="utf-8", newline="") as f:
            f.write(content)
        print(f"写出 {out_path}: {len(funcs)} 函数")

    with open(src_path, "w", encoding="utf-8", newline="") as f:
        f.write(new_text)
    remain = len([e for e in funs if e.name not in moved_names])
    print(f"源文件剩余成员函数 {remain}（原 {len(funs)}）")
    print("移动清单：")
    for fout in spec["files"]:
        for elem, _ in moved[fout["name"]]:
            print(f"  {fout['name']}.kt <- {elem.name} (L{lineno(elem.decl_start)})")


def extract_imports(text: str) -> str:
    lines = []
    for ln in text.split("\n"):
        if ln.startswith("import "):
            lines.append(ln)
    return "\n".join(lines)


if __name__ == "__main__":
    main()
