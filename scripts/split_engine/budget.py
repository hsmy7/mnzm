# -*- coding: utf-8 -*-
"""批量预算表：全部目标类的 override/深耦合扩展/可移动统计。"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from splitlib import (mask, find_class_span, build_elements, parse_fn_signature,
                      ext_references_class_state, class_state_names)

BASE = "android/core/engine/src/main/java"
TARGETS = [
    ("com/xianxia/sect/core/engine/domain/exploration/ExplorationService.kt", "ExplorationService", 20),
    ("com/xianxia/sect/core/engine/domain/exploration/MissionSystem.kt", "MissionSystem", 24),
    ("com/xianxia/sect/core/engine/domain/disciple/DiscipleService.kt", "DiscipleService", 25),
    ("com/xianxia/sect/core/engine/service/CaveExplorationProcessor.kt", "CaveExplorationProcessor", 25),
    ("com/xianxia/sect/core/engine/service/CultivationEventProcessor.kt", "CultivationEventProcessor", 25),
    ("com/xianxia/sect/core/engine/domain/building/BuildingService.kt", "BuildingService", 28),
    ("com/xianxia/sect/core/engine/domain/battle/HeavenlyTrialService.kt", "HeavenlyTrialService", 28),
    ("com/xianxia/sect/core/repository/ProductionSlotRepository.kt", "ProductionSlotRepository", 29),
    ("com/xianxia/sect/core/util/BattleCalculator.kt", "BattleCalculator", 33),
    ("com/xianxia/sect/core/engine/service/LawEnforcementProcessor.kt", "LawEnforcementProcessor", 33),
    ("com/xianxia/sect/core/engine/domain/diplomacy/AISectDiscipleManager.kt", "AISectDiscipleManager", 35),
    ("com/xianxia/sect/core/config/BuildingConfigService.kt", "BuildingConfigService", 35),
    ("com/xianxia/sect/core/performance/UnifiedPerformanceMonitor.kt", "UnifiedPerformanceMonitor", 37),
    ("com/xianxia/sect/core/engine/RedeemCodeManager.kt", "RedeemCodeManager", 40),
    ("com/xianxia/sect/core/usecase/SectPolicyToggleUseCase.kt", "SectPolicyToggleUseCase", 40),
    ("com/xianxia/sect/core/engine/service/MailService.kt", "MailService", 42),
    ("com/xianxia/sect/core/engine/domain/battle/AISectAttackManager.kt", "AISectAttackManager", 43),
    ("com/xianxia/sect/core/engine/domain/building/BuildingFacadeImpl.kt", "BuildingFacadeImpl", 43),
    ("com/xianxia/sect/core/engine/service/CultivationService.kt", "CultivationService", 50),
    ("com/xianxia/sect/core/engine/domain/battle/BattleSystem.kt", "BattleSystem", 47),
    ("com/xianxia/sect/core/engine/GameEngineCore.kt", "GameEngineCore", 54),
    ("com/xianxia/sect/core/engine/domain/disciple/DiscipleFacadeImpl.kt", "DiscipleFacadeImpl", 68),
    ("com/xianxia/sect/core/engine/system/InventorySystem.kt", "InventorySystem", 105),
    ("com/xianxia/sect/core/engine/domain/disciple/DiscipleStatCalculator.kt", "DiscipleStatCalculator", 85),
    ("com/xianxia/sect/core/engine/service/ProductionProcessor.kt", "ProductionProcessor", 59),
    ("com/xianxia/sect/core/engine/domain/inventory/InventoryFacadeImpl.kt", "InventoryFacadeImpl", 62),
]

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def main() -> None:
    print(f"{'类':<28}{'总':>4}{'OVR':>5}{'深EXT':>6}{'可移':>5}{'拆后':>5}  判定")
    for rel, cname, baseline in TARGETS:
        p = os.path.join(ROOT, BASE, rel)
        text = open(p, encoding="utf-8").read()
        m = mask(text)
        dpos, o, c = find_class_span(text, cname)
        elems = build_elements(text, o, c, m)
        funs = [e for e in elems if e.kind == "fun"]
        props = class_state_names(text, dpos, o, {x.name for x in elems if x.kind in ("val", "var") and x.name})
        names = {x.name for x in funs if x.name}
        ovr = ext_stuck = 0
        for e in funs:
            sig = parse_fn_signature(e.text)
            if sig is None:
                print(f"      !! 签名解析失败 {cname}.{e.name}")
                continue
            if "override" in sig.modifiers:
                ovr += 1
            elif sig.has_receiver and ext_references_class_state(e.text, props, names, cname):
                ext_stuck += 1
        movable = len(funs) - ovr - ext_stuck
        remain = len(funs) - movable
        verdict = "OK" if remain <= 19 else "需豁免/深拆"
        print(f"{cname:<28}{len(funs):>4}{ovr:>5}{ext_stuck:>6}{movable:>5}{remain:>5}  {verdict}  (baseline {baseline})")


if __name__ == "__main__":
    main()
