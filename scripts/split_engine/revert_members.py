# -*- coding: utf-8 -*-
"""成员回退：把移动到新文件的扩展函数逆向变换回类成员（字节校验逆操作）。

用法: python revert_members.py <类文件> <类名> <移动文件> <fn1> <fn2> ...
- internal fun Class.name( → fun name( / private fun name(（--private 时）
- 缩进 +4；KDoc/注解随行
- 从移动文件删除（文件无函数剩留则删除文件）
- 插入到类体闭括号前
"""
from __future__ import annotations

import os
import re
import sys

sys.path.insert(0, os.path.dirname(__file__))
from splitlib import mask, find_class_span, build_elements, parse_fn_signature


def main() -> None:
    cls_path, cls_name, moved_path = sys.argv[1], sys.argv[2], sys.argv[3]
    private_mode = "--private" in sys.argv
    fns = [a for a in sys.argv[4:] if not a.startswith("--")]
    ct = open(cls_path, encoding="utf-8").read()
    mt = open(moved_path, encoding="utf-8").read()

    mm = mask(mt)
    # 移动文件是顶层函数文件：锚定每个 internal fun 段（用类体方式不可行，直接按函数边界切）
    decls = []
    for m in re.finditer(r"^(?:@[^\n]*\n)*(?:/\*\*[\s\S]*?\*/\n)?(internal[^\n]*?fun %s\.)" % re.escape(cls_name), mt, re.M):
        decls.append(m.start())
    bounds = decls + [len(mt)]
    segs = {}
    for a, b in zip(decls, bounds[1:]):
        seg = mt[a:b]
        nm = re.search(r"fun %s\.([A-Za-z_][A-Za-z0-9_]*)" % re.escape(cls_name), seg).group(1)
        segs[nm] = seg

    restored = []
    for fn in fns:
        seg = segs.get(fn)
        if seg is None:
            print("FATAL: 移动文件中未找到", fn)
            sys.exit(1)
        seg_clean = seg.rstrip("\n")
        lines = seg_clean.split("\n")
        out = []
        for ln in lines:
            if re.match(r"^\s*internal(\s+suspend|\s+inline|\s+suspend inline)*\s+fun\s+", ln) and (cls_name + ".") in ln:
                new = re.sub(r"\binternal\s+", "", ln, count=1)
                new = new.replace(f"fun {cls_name}.", "fun ")
                if private_mode:
                    new = re.sub(r"\bfun\s+", "private fun ", new, count=1)
                out.append("    " + new)
            else:
                out.append(("    " + ln) if ln.strip() else ln)
        restored.append("\n".join(out))

    # 从移动文件删除这些段
    for fn in fns:
        mt = mt.replace(segs[fn], "", 1)
    # 类体插入（闭括号前）
    _, o, c = find_class_span(ct, cls_name)
    insert_at = ct.rfind("}", 0, c)
    block = "\n".join(restored) + "\n"
    new_ct = ct[:insert_at] + "\n" + block + ct[insert_at:]
    new_ct = re.sub(r"\n{4,}", "\n\n\n", new_ct)

    with open(cls_path, "w", encoding="utf-8", newline="") as f:
        f.write(new_ct)
    remain = [l for l in mt.split("\n") if re.match(r"^internal .*\bfun\s", l)]
    if not remain:
        os.remove(moved_path)
        print(f"{moved_path} 已无函数，删除")
    else:
        with open(moved_path, "w", encoding="utf-8", newline="") as f:
            f.write(mt)
    print("回退:", fns)


if __name__ == "__main__":
    main()
