# -*- coding: utf-8 -*-
"""split_test_class.py: LC 测试类拆分——拷贝 fixture（属性+@Before）+ 移动部分测试方法到新测试类。"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(__file__))
from splitlib import mask, find_class_span, build_elements, parse_fn_signature

E = "android/core/engine/src/test/java/com/xianxia/sect/core"

def main():
    rel = sys.argv[1]            # 测试文件相对 E 的路径
    cls = sys.argv[2]            # 测试类名
    new_name = sys.argv[3]       # 新类名
    frac = float(sys.argv[4]) if len(sys.argv) > 4 else 0.5  # 移动比例（按行数中点就近）
    p = os.path.join(E, rel)
    t = open(p, encoding="utf-8").read()
    dcl, o, c = find_class_span(t, cls)
    elems = build_elements(t, o, c, mask(t))
    tests = [e for e in elems if e.kind == "fun"]
    # fixture = 非 @Test 成员函数 + val/var 属性段 + @Before
    # 连续前缀 fixture：类体开头到第一个 @Test 之间的全部成员（属性/@Before/辅助函数）
    first_test = min([e.start for e in tests], default=c)
    fixture_text = t[o:first_test] if first_test > o else ""
    # 按行数取中点：排序不动，累计行数到一半处分割
    total_lines = t.count("\n", o, c)
    acc = 0
    split_at = len(tests) // 2
    for i, e in enumerate(tests):
        acc += t.count("\n", e.start, e.end)
        if frac < 0:
            pass
        if acc >= total_lines * frac:
            split_at = i + 1
            break
    move = tests[split_at:]
    keep = tests[:split_at]
    if not move:
        print("无需拆分")
        return
    # 新类：package + imports + fixture 拷贝 + 移动的测试
    pkg = re.search(r"^package\s+(\S+)", t, re.M).group(1)
    imports = "\n".join(l for l in t.split("\n") if l.startswith("import "))
    moved_text = "\n".join(t[e.start:e.end].rstrip() for e in move)
    new_cls = (f"package {pkg}\n\n{imports}\n\n"
               f"/** {cls} 拆分（LC>800 行）：{len(move)} 个用例随 fixture 迁出，行为零变更。 */\n"
               f"class {new_name} {{\n"
               f"{fixture_text.rstrip()}\n\n"
               f"{moved_text}\n"
               f"}}\n")
    out_p = os.path.join(os.path.dirname(p), new_name + ".kt")
    open(out_p, "w", encoding="utf-8", newline="").write(new_cls)
    # 源文件删除被移走的测试
    for e in sorted(move, key=lambda x: -x.start):
        t = t[:e.start] + t[e.end:]
    t = re.sub(r"\n{4,}", "\n\n\n", t)
    open(p, "w", encoding="utf-8", newline="").write(t)
    print(f"{new_name}.kt: 迁出 {len(move)} 用例；源类剩 {len(keep)}")


if __name__ == "__main__":
    main()
