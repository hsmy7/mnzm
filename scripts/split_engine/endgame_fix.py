# -*- coding: utf-8 -*-
"""endgame_fix.py: 终局收敛——泛型成员扩展 internal 化 + 嵌套类型 import 全量补齐。"""
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(__file__))
from splitlib import mask, find_class_span, build_elements

ANDROID = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
E = "android/core/engine/src/main/java/com/xianxia/sect/core"


def git_new_files():
    out = subprocess.run(["git", "-c", "core.quotepath=false", "status", "--short",
                          "android/core/engine/src/main"], cwd=ANDROID, capture_output=True, text=True)
    return [os.path.join(ANDROID, l.strip()[2:].strip()) for l in out.stdout.splitlines()
            if l.startswith("??") and l.strip()[2:].strip().endswith(".kt")]


CLASSES = {
    "BattleSystem": "engine/domain/battle/BattleSystem.kt",
    "GameEngineCore": "engine/GameEngineCore.kt",
    "InventorySystem": "engine/system/InventorySystem.kt",
    "DiscipleStatCalculator": "engine/domain/disciple/DiscipleStatCalculator.kt",
    "ProductionProcessor": "engine/service/ProductionProcessor.kt",
    "DiscipleFacadeImpl": "engine/domain/disciple/DiscipleFacadeImpl.kt",
    "InventoryFacadeImpl": "engine/domain/inventory/InventoryFacadeImpl.kt",
    "BuildingFacadeImpl": "engine/domain/building/BuildingFacadeImpl.kt",
    "BuildingConfigService": "config/BuildingConfigService.kt",
    "AISectDiscipleManager": "engine/domain/diplomacy/AISectDiscipleManager.kt",
    "MailService": "engine/service/MailService.kt",
    "ExplorationService": "engine/domain/exploration/ExplorationService.kt",
}


def main():
    names = set(sys.argv[1:])
    # 1) 源类 internal 化：嵌套类型（含 enum）+ 带泛型的私有成员扩展
    for cls, rel in CLASSES.items():
        p = os.path.join(ANDROID, E, rel)
        if not os.path.exists(p):
            continue
        s = open(p, encoding="utf-8").read()
        orig = s
        for nm in names:
            s2 = re.sub(r"(\b)private(\s+(?:data\s+|enum\s+|sealed\s+)*(?:class|interface|object)\s+%s\b)" % nm,
                        r"\1internal\2", s)
            if s2 != s:
                print("nested internal:", cls, nm)
                s = s2
            # 带泛型形参的成员扩展/成员函数
            s2 = re.sub(r"(\b)private(\s+(?:suspend\s+|inline\s+)*fun\s+<[^>]*>\s*)", r"\1internal\2", s)
            if s2 != s:
                print("generic fun internalized:", cls)
                s = s2
            # 带接收者的私有成员扩展 fun Name.xxx
            s2 = re.sub(r"(\b)private(\s+(?:suspend\s+|inline\s+)*fun\s+[A-Za-z_][A-Za-z0-9_<>?,\s.?]*\.%s\b)" % nm,
                        r"\1internal\2", s)
            if s2 != s:
                print("member-ext internal:", cls, nm)
                s = s2
        if s != orig:
            open(p, "w", encoding="utf-8", newline="").write(s)
    # 2) 嵌套类型 import：所有新文件 × 其接收类
    new_files = git_new_files()
    nested_map = {}
    src_of = {}
    for cls, rel in CLASSES.items():
        p = os.path.join(ANDROID, E, rel)
        if not os.path.exists(p):
            continue
        text = open(p, encoding="utf-8").read()
        try:
            _, o, c = find_class_span(text, cls)
        except ValueError:
            continue
        elems = build_elements(text, o, c, mask(text))
        pkg = re.search(r"^package\s+(\S+)", text, re.M).group(1)
        body = text[o:c]
        nested = ({e.name for e in elems if e.kind in ("class", "interface", "object") and e.name}
                  | set(re.findall(r"(?:internal\s+)?(?:data\s+|enum\s+|sealed\s+)*(?:class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)", body)))
        nested_map[cls] = (pkg, nested)
        src_of[cls] = p
    added = 0
    for mfp in new_files:
        mt = open(mfp, encoding="utf-8").read()
        rxs = re.findall(r"internal (?:suspend |inline )*fun ([A-Za-z_][A-Za-z0-9_]*)\.", mt)
        if not rxs:
            continue
        cls = max(set(rxs), key=rxs.count)
        if cls not in nested_map:
            continue
        pkg, nested = nested_map[cls]
        add = [f"import {pkg}.{cls}.{n}" for n in sorted(nested)
               if re.search(r"\b%s\b" % n, mt) and f"import {pkg}.{cls}.{n}" not in mt]
        if add:
            lines = mt.split("\n")
            last = max(i for i, l in enumerate(lines) if l.startswith("import "))
            for k, imp in enumerate(add):
                lines.insert(last + 1 + k, imp)
            open(mfp, "w", encoding="utf-8", newline="").write("\n".join(lines))
            added += len(add)
    print("nested imports added:", added)


if __name__ == "__main__":
    main()
