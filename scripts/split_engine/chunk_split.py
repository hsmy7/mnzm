# -*- coding: utf-8 -*-
"""chunk_split.py: 声明序均衡分块拆分（≤14 函数/文件，语义命名）。"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(__file__))
from splitlib import (mask, find_class_span, build_elements, parse_fn_signature,
                      ext_references_class_state, class_state_names)

E = "android/core/engine/src/main/java/com/xianxia/sect/core"
EXTERNAL = [os.path.join(os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")), m)
            for m in ("app/src", "feature/game/src", "core/data/src", "core/domain/src", "core/ui/src")]

KEYWORDS = [
    ("breakthrough", "突破"), ("cultivat", "修炼"), ("gear", "装备"), ("equip", "装备"),
    ("manual", "功法"), ("pill", "丹药"), ("warehouse", "仓库"), ("recruit", "招募"),
    ("combat", "战斗"), ("battle", "战斗"), ("damage", "伤害"), ("attack", "攻击"),
    ("defen", "防御"), ("heal", "治疗"), ("dot", "持续"), ("buff", "增益"),
    ("skill", "技能"), ("turn", "回合"), ("reward", "奖励"), ("stat", "属性"),
    ("load", "加载"), ("save", "存档"), ("settle", "结算"), ("month", "月度"),
    ("year", "年度"), ("sync", "同步"), ("log", "日志"), ("reward", "奖励"),
    ("process", "处理"), ("update", "更新"), ("generate", "生成"), ("resolve", "解析"),
    ("collect", "收集"), ("grant", "发放"), ("validate", "校验"), ("build", "构筑"),
]


def keyword_of(fn: str) -> str:
    low = fn.lower()
    for kw, cn in KEYWORDS:
        if kw in low:
            return cn
    m = re.search(r"[a-z]+", fn)
    return m.group(0)[:4].capitalize() if m else "域"


def chunk_name(cls: str, first_fn: str, idx: int, total_chunks: int) -> str:
    base = cls + keyword_of(first_fn) + "Ops"
    return base if total_chunks == 1 else f"{base}{idx}"


def movable_fns(src: str, cls: str, keep: set[str]):
    text = open(src, encoding="utf-8").read()
    dcl, o, c = find_class_span(text, cls)
    elems = build_elements(text, o, c, mask(text))
    funs = [e for e in elems if e.kind == "fun"]
    props = class_state_names(text, dcl, o, {x.name for x in elems if x.kind in ("val", "var") and x.name})
    names = {x.name for x in funs if x.name}
    movable = []
    for e in funs:
        sig = parse_fn_signature(e.text)
        if sig is None or "override" in sig.modifiers or e.name in keep:
            continue
        if sig.has_receiver and ext_references_class_state(e.text, props, names, cls):
            continue
        if grep_ext(e.name):
            continue
        movable.append(e)
    return movable, funs


def grep_ext(name: str) -> bool:
    out = subprocess.run(["grep", "-rnE", name + r"\s*[(]", "--include=*.kt"] + EXTERNAL,
                         capture_output=True, text=True)
    return bool(out.stdout.strip())


def main() -> None:
    cls = sys.argv[1]
    rel = sys.argv[2]
    keep = set(sys.argv[3].split(",")) if len(sys.argv) > 3 and sys.argv[3] else set()
    src = os.path.join(E, rel)
    movable, all_funs = movable_fns(src, cls, keep)
    text_all = open(src, encoding='utf-8').read()
    n = len(movable)
    nchunks = (n + 13) // 14
    per = (n + nchunks - 1) // nchunks
    files = []
    for i in range(nchunks):
        chunk = movable[i * per: (i + 1) * per]
        if not chunk:
            continue
        name = chunk_name(cls, chunk[0].name, i + 1, nchunks)
        from collections import Counter
        cnt = Counter(e.name for e in all_funs)
        files.append({"name": name,
                      "header": f"// ── {cls} 拆分域 {i+1}/{nchunks}（行为零变更） ──",
                      "fns": [e.name + (f":L{text_all.count(chr(10), 0, e.decl_start) + 1}"
                                        if cnt[e.name] > 1 else "")
                              for e in chunk]})
    spec_path = os.path.join(os.path.dirname(__file__), "specs", f"chunk_{cls}.json")
    json.dump({"files": files}, open(spec_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"{cls}: 移{n} -> {[(f['name'], len(f['fns'])) for f in files]}")
    r = subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), "split_run.py"),
                        src, cls, spec_path], capture_output=True, text=True)
    if "FATAL" in (r.stdout + r.stderr):
        print("  !! FATAL:", (r.stdout + r.stderr).strip()[:400])
    moved_files = [os.path.join(os.path.dirname(src), f["name"] + ".kt") for f in files]
    r2 = subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), "fix_visibility.py"),
                         src, cls] + moved_files, capture_output=True, text=True)
    print("  vis:", r2.stdout.strip().splitlines()[0] if r2.stdout else r2.stderr[:150])


if __name__ == "__main__":
    main()
