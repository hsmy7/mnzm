# -*- coding: utf-8 -*-
"""auto_split.py: 批量自动拆分。

对每个目标类：
1. movable = 全部成员函数 - override - 深耦合成员扩展 - 外部模块有调用者 - keep 显式保留
2. 按 ≤14 函数/文件分组，组名 = <Class><关键词>Ops / <Class>Ops<N>
3. 逐函数字节校验移动；写新文件；源文件收缩
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(__file__))
from splitlib import (mask, find_class_span, build_elements, parse_fn_signature,
                      ext_references_class_state, class_state_names,
                      transform_to_extension, dedent4)
import difflib

ANDROID = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
E = "android/core/engine/src/main/java/com/xianxia/sect/core"
EXTERNAL = [os.path.join(ANDROID, m) for m in
            ("app/src", "feature/game/src", "core/data/src", "core/domain/src", "core/ui/src")]


def grep_e(pattern, roots):
    out = subprocess.run(["grep", "-rnE", pattern, "--include=*.kt"] + roots,
                         capture_output=True, text=True)
    return [l for l in out.stdout.splitlines() if l]


# 类 -> (源文件相对 E 的路径, keep 保留函数, 文件分组命名关键词序)
TARGETS = {
    "AISectDiscipleManager": ("engine/domain/diplomacy/AISectDiscipleManager.kt",
        ["initForSlot", "generateRandomDisciple", "recruitYearlyDisciples", "processMonthlyCultivation",
         "processAging", "initializeSectDisciples", "fillDisciplesToTarget", "prepareDisciplesForBattle",
         "truncateToLimit", "generateYearlyRecruits"],
        [("Gear", ["isGearCompleteForLevel", "applyGearToDisciple", "ensureDiscipleGear",
                   "buildEquipmentMapForDisciple", "buildManualDataForDisciple",
                   "buildProficiencyDataFromMasteries", "buildEquipmentEntry"]),
         ("Generation", ["generateSpiritRoot", "rollAptitudeByRootCount", "avoidSentinel50",
                         "generateQiRefiningDisciple", "generateRealmDistribution", "adjustDiscipleRealm",
                         "generateInitialNurture"])]),
    "BuildingConfigService": ("config/BuildingConfigService.kt",
        ["initialize", "reload", "getBuildingConfig", "getBuildingConfigByType", "getAllBuildingConfigs",
         "getSlotCount", "getSlotCountByType", "getBaseSuccessRate", "getBuildingDisplayName",
         "isValidSlotIndex", "isValidSlotIndexByType", "resolveBuildingId", "getBuildingTypeFromId",
         "getBuildingCost", "getBuildingGridSize", "getBuildingSpriteSize", "getAllBuildingSpriteSizes",
         "getBuildingConfigByDisplayName", "getSlotCountByDisplayName"],
        [("Defaults", ["createDefaultConfig", "createDefaultBuildings", "createEarlyStageBuildings",
                       "createGrowthStageBuildings", "createManagementStageBuildings",
                       "createCultivationStageBuildings", "createLeapStageBuildings",
                       "createPeakStageBuildings", "createDefaultBuildingAliases",
                       "createDefaultBuildingAliasesProduction", "createDefaultBuildingAliasesAdministration"]),
         ("Load", ["ensureConfigLoaded", "loadConfig", "loadFromAssets", "normalizeBuildingId",
                   "fixupBuildingSizes"])]),
    "BattleSystem": ("engine/domain/battle/BattleSystem.kt", None, None),
    "GameEngineCore": ("engine/GameEngineCore.kt", None, None),
    "InventorySystem": ("engine/system/InventorySystem.kt", None, None),
    "DiscipleStatCalculator": ("engine/domain/disciple/DiscipleStatCalculator.kt", None, None),
    "ProductionProcessor": ("engine/service/ProductionProcessor.kt", None, None),
    "DiscipleFacadeImpl": ("engine/domain/disciple/DiscipleFacadeImpl.kt", None, None),
    "InventoryFacadeImpl": ("engine/domain/inventory/InventoryFacadeImpl.kt", None, None),
    "BuildingFacadeImpl": ("engine/domain/building/BuildingFacadeImpl.kt", None, None),
}

# 语义命名规则：函数名前缀 -> 中文域后缀
KEYWORDS = [
    ("breakthrough", "突破"), ("cultivation", "修炼"), ("gear", "装备"), ("equipment", "装备"),
    ("manual", "功法"), ("pill", "丹药"), ("warehouse", "仓库"), ("mail", "邮件"),
    ("recruit", "招募"), ("realm", "境界"), ("combat", "战斗"), ("battle", "战斗"),
    ("damage", "伤害"), ("attack", "攻击"), ("defen", "防御"), ("heal", "治疗"),
    ("dot", "持续伤害"), ("buff", "增益"), ("skill", "技能"), ("turn", "回合"),
    ("reward", "奖励"), ("stat", "属性"), ("calculate", "计算"), ("compute", "计算"),
    ("build", "构筑"), ("apply", "应用"), ("process", "处理"), ("settle", "结算"),
    ("sync", "同步"), ("load", "加载"), ("save", "存档"), ("update", "更新"),
    ("generate", "生成"), ("resolve", "解析"), ("collect", "收集"), ("grant", "发放"),
    ("distribute", "发放"), ("validate", "校验"), ("normalize", "归一"), ("log", "日志"),
    ("track", "追踪"), ("month", "月度"), ("year", "年度"), ("aging", "老化"),
]


def group_name(cls: str, fns: list[str], idx: int) -> str:
    joined = " ".join(fns).lower()
    for kw, cn in KEYWORDS:
        if kw in joined:
            return f"{cls}{cn}Ops{idx}" if idx > 1 else f"{cls}{cn}Ops"
    return f"{cls}Ops{idx}" if idx > 1 else f"{cls}DomainOps"


def main() -> None:
    only = sys.argv[1:] if len(sys.argv) > 1 else None
    summary = []
    for cls, (rel, keep, groups) in TARGETS.items():
        if only and cls not in only:
            continue
        src = os.path.join(E, rel)
        text = open(src, encoding="utf-8").read()
        dcl, o, c = find_class_span(text, cls)
        elems = build_elements(text, o, c, mask(text))
        funs = [e for e in elems if e.kind == "fun"]
        props = class_state_names(text, dcl, o, {x.name for x in elems if x.kind in ("val", "var") and x.name})
        names = {x.name for x in funs if x.name}
        keep = keep or []
        # movable 判定
        movable, kept_reason = [], []
        for e in funs:
            sig = parse_fn_signature(e.text)
            if sig is None:
                kept_reason.append((e.name, "parse-fail")); continue
            if "override" in sig.modifiers:
                kept_reason.append((e.name, "override")); continue
            if e.name in keep:
                kept_reason.append((e.name, "keep")); continue
            if sig.has_receiver:
                if ext_references_class_state(e.text, props, names, cls):
                    kept_reason.append((e.name, "deep-ext")); continue
            # 外部模块调用者检查（保守：命中即留守）
            if grep_e(e.name + r"\s*[(]", EXTERNAL):
                kept_reason.append((e.name, "external")); continue
            movable.append(e)
        # 分组
        if groups:
            assigned = {fn: name for name, fns in groups for fn in fns}
            buckets: dict[str, list] = {}
            for e in movable:
                buckets.setdefault(assigned.get(e.name, f"{cls}Misc"), []).append(e)
        else:
            # 语义聚类：按 KEYWORDS 前缀粗分桶，桶上限 14
            buckets = {}
            for e in movable:
                placed = False
                for (gname, glist) in buckets.items():
                    if len(glist) < 14 and keyword_match(gname, e.name):
                        glist.append(e); placed = True; break
                if not placed:
                    gname = group_name(cls, [e.name], 1)
                    for g2 in buckets:
                        if g2.startswith(gname.rstrip("0123456789")) and len(buckets[g2]) < 14:
                            buckets[g2].append(e); placed = True; break
                if not placed:
                    buckets[group_name(cls, [e.name], 1)] = [e]
        spec = {"files": []}
        for gname, glist in buckets.items():
            spec["files"].append({
                "name": gname,
                "header": f"// ── {cls} 拆分域（行为零变更） ──",
                "fns": [e.name + (f":L{text.count(chr(10), 0, e.decl_start) + 1}" if
                                  len([x for x in funs if x.name == e.name]) > 1 else "") for e in glist],
            })
        spec_path = os.path.join(os.path.dirname(__file__), "specs", f"auto_{cls}.json")
        json.dump(spec, open(spec_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        summary.append((cls, len(funs), len(movable), len(funs) - len(movable),
                        [f"{n}:{len(g)}" for n, g in buckets.items()]))
        print(f"{cls}: 总{len(funs)} 移{len(movable)} 留{len(funs)-len(movable)} -> {spec_path}")
        # 执行拆分
        r = subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), "split_run.py"),
                            src, cls, spec_path], capture_output=True, text=True)
        if "FATAL" in r.stdout or "FATAL" in r.stderr:
            print("  !! split_run FATAL:", (r.stdout + r.stderr).strip()[:300])
        # 可见性收敛
        moved_files = [os.path.join(os.path.dirname(src), f["name"] + ".kt") for f in spec["files"]]
        r2 = subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), "fix_visibility.py"),
                             src, cls] + moved_files, capture_output=True, text=True)
        print("  vis:", r2.stdout.strip().splitlines()[0] if r2.stdout else r2.stderr[:200])
    print("\n== 汇总")
    for cls, tot, mv, kept, buckets in summary:
        print(f"  {cls}: {tot} -> 移{mv} 留{kept} {'✓' if kept <= 19 or cls in ('UnifiedPerformanceMonitor',) else '需豁免'} {buckets}")


def keyword_match(group_key: str, fn: str) -> bool:
    m = re.search(r"[A-Z][a-z]+", group_key)
    if not m:
        return False
    return m.group(0).lower() in fn.lower()


if __name__ == "__main__":
    main()
