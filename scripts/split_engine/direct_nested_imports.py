# -*- coding: utf-8 -*-
"""direct_nested_imports.py: 按 err.log 为新文件补接收类的嵌套类型 import。"""
import os
import re
import subprocess

ANDROID = os.path.abspath(".")
E = "android/core/engine/src/main/java/com/xianxia/sect/core"
BS = chr(92)

CLASSES = {
    "DiscipleStatCalculator": "engine/domain/disciple/DiscipleStatCalculator.kt",
    "BattleSystem": "engine/domain/battle/BattleSystem.kt",
    "GameEngineCore": "engine/GameEngineCore.kt",
    "InventorySystem": "engine/system/InventorySystem.kt",
    "ProductionProcessor": "engine/service/ProductionProcessor.kt",
    "DiscipleFacadeImpl": "engine/domain/disciple/DiscipleFacadeImpl.kt",
    "InventoryFacadeImpl": "engine/domain/inventory/InventoryFacadeImpl.kt",
    "BuildingFacadeImpl": "engine/domain/building/BuildingFacadeImpl.kt",
    "BuildingConfigService": "config/BuildingConfigService.kt",
    "AISectDiscipleManager": "engine/domain/diplomacy/AISectDiscipleManager.kt",
    "MailService": "engine/service/MailService.kt",
    "ExplorationService": "engine/domain/exploration/ExplorationService.kt",
}

txt = open("err.log", encoding="utf-8", errors="replace").read()
errs = {}
pair = re.compile(r"e: file:///[^\n]*android/(core/engine/src/main/[^\n]+\.kt):\d+:\d+ "
                  r"Unresolved reference '([A-Za-z_][A-Za-z0-9_]*)'")
for m in pair.finditer(txt):
    f = m.group(1).replace(BS, "/")
    errs.setdefault("android/" + f, set()).add(m.group(2))

out = subprocess.run(["git", "-c", "core.quotepath=false", "status", "--short",
                      "android/core/engine/src/main"], cwd=ANDROID, capture_output=True, text=True)
new = [l.strip()[2:].strip() for l in out.stdout.splitlines()
       if l.startswith("??") and l.strip()[2:].strip().endswith(".kt")]

pkg_of, rec_of = {}, {}
for f in new:
    p = os.path.join(ANDROID, f)
    t = open(p, encoding="utf-8").read()
    pkg_of[f] = re.search(r"^package\s+(\S+)", t, re.M).group(1)
    rxs = re.findall(r"internal (?:suspend |inline )*fun (?:<[^>]*> )?([A-Za-z_][A-Za-z0-9_]*)\.", t)
    if rxs:
        rec_of[f] = max(set(rxs), key=rxs.count)

# 类 -> 嵌套类型全集（含 data class）
nested_of = {}
for cls, rel in CLASSES.items():
    p = os.path.join(ANDROID, E, rel)
    if not os.path.exists(p):
        continue
    body = open(p, encoding="utf-8").read()
    nested_of[cls] = set(re.findall(r"\b(?:internal\s+)?(?:data\s+|enum\s+|sealed\s+)*"
                                    r"(?:class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)", body))

added = 0
for f, names in sorted(errs.items()):
    if f not in rec_of or f not in pkg_of:
        continue
    cls = rec_of[f]
    nested = nested_of.get(cls, set())
    pkg = pkg_of[f]
    path = os.path.join(ANDROID, f)
    t = open(path, encoding="utf-8").read()
    add = [f"import {pkg}.{cls}.{n}" for n in sorted(names & nested)
           if f"import {pkg}.{cls}.{n}" not in t]
    if add:
        lines = t.split("\n")
        last = max(i for i, l in enumerate(lines) if l.startswith("import "))
        for k, imp in enumerate(add):
            lines.insert(last + 1 + k, imp)
        open(path, "w", encoding="utf-8", newline="").write("\n".join(lines))
        added += len(add)
        print(os.path.basename(f), "+", add)
print("added", added)
