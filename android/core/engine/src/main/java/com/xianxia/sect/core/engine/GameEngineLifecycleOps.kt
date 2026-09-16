package com.xianxia.sect.core.engine

import kotlinx.coroutines.flow.map
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.engine.domain.diplomacy.truncateToLimit
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.domain.diplomacy.isGearCompleteForLevel


/** AI 宗门每个宗门的标准弟子数 */
private const val MAX_AI_SECT_DISCIPLES = 50


// ── Game lifecycle ──────────────────────────────────────────────────

suspend fun GameEngine.initializeNewGameSuspend(gameData: GameData) {
    return engineContextDispatcher.withEngineContext {
        stateStore.update { this.gameData = gameData }
    }
}

suspend fun GameEngine.ensureHeavyDataLoaded() {
    if (heavyDataLoaded) return
    val snapshot = stateStore.gameDataSnapshot
    if (snapshot.worldMapSects.isNotEmpty()) {
        heavyDataLoaded = true
        DomainLog.d(
            "GameEngine",
            "ensureHeavyDataLoaded: 完成 slot=${snapshot.currentSlot} worldMapSects=${snapshot.worldMapSects.size}"
        )
    } else {
        // 数据缺失时不标记完成——空时保持 false，后续调用重试，
        // 由相邻 ensureGameDataIntegrity 重生
        DomainLog.w("GameEngine", "ensureHeavyDataLoaded: worldMapSects 为空，不标记完成（由 ensureGameDataIntegrity 重生）")
    }
}

// ════════════════════════════════════════════════════════════════════════
// 游戏数据完整性守卫 — 行业第4层防御：Data Regeneration
// ════════════════════════════════════════════════════════════════════════

/**
 * 游戏数据完整性守卫 — 检查并修复加载后所有依赖重型数据管线的关键字段。
 *
 * ## 对标（行业第4层防御：Data Regeneration）
 * 行业存档防御链共4层：① Atomic Write ② Backup Fallback ③ Schema Migration
 * ④ Data Regeneration。①-③已实现，此函数补齐第4层。
 *
 * 重型数据管线（game_heavy_data 表）可能因写入中断、反序列化版本不匹配或 Migration
 * 遗漏导致部分字段在合并后仍为空。此函数在加载管线终点集中从权威源数据恢复。
 *
 * ## 设计
 * - 正常路径零开销（前置 isEmpty 判定）
 * - 每个字段独立检查+修复，使用最新 snapshot 避免中间状态污染
 * - 仅修复可自动重生的字段；不可逆字段（exploredSects/scoutInfo）仅记录告警
 */
suspend fun GameEngine.ensureGameDataIntegrity() {
    checkAndRepairWorldMapSects()
    checkAndRepairAiSectDisciples()
    checkAndRepairMerchantAndRecruit()
    checkAndRepairWatchedItemIds()
}

/** 关注列表上限收敛：恶意/损坏存档可注入超长列表，超限会令每次保存失败 */
private suspend fun GameEngine.checkAndRepairWatchedItemIds() {
    val gd = stateStore.gameDataSnapshot
    val watched = gd.watchedItemIds
    if (watched.size <= GameData.MAX_WATCHED_ITEMS && watched.size == watched.toSet().size) return
    stateStore.update {
        gameData = gameData.copy(
            watchedItemIds = gameData.watchedItemIds
                .takeLast(GameData.MAX_WATCHED_ITEMS)
                .distinct()
        )
    }
}

private suspend fun GameEngine.checkAndRepairWorldMapSects() {
    val gd = stateStore.gameDataSnapshot
    // 阶段1：列表为空 → 重生
    if (gd.worldMapSects.isEmpty()) {
        regenerateAllWorldSects(gd.sectName)
        return
    }
    // 阶段2：列表非空但缺少玩家宗门 → 修复重生
    if (gd.worldMapSects.none { it.isPlayerSect }) {
        DomainLog.w("ensureGameDataIntegrity",
            "worldMapSects 非空 (${gd.worldMapSects.size} 个) 但缺少玩家宗门，修复重生" +
            " (sectName=${gd.sectName})")
        regenerateAllWorldSects(gd.sectName)
    }
}

/** 通过 WorldMapGenerator 重生全部宗门数据（含 sectRelations/aiSectDisciples） */
private suspend fun GameEngine.regenerateAllWorldSects(sectName: String) {
    if (sectName.isBlank()) {
        DomainLog.e("ensureGameDataIntegrity", "worldMapSects 为空/缺且 sectName 为空，无法重生")
        return
    }
    DomainLog.w("ensureGameDataIntegrity",
        "从 FixedSectPositions 重生 worldMapSects (sectName=$sectName)")
    val generationResult = WorldMapGenerator.generateWorldSects(sectName)
    val newRelations = WorldMapGenerator.initializeSectRelations(generationResult.sects)
    // 世界重生保留玩家占领状态——按 sectDetails.isOwned（持久化、不随重生被清）
    // 推导玩家持有宗门，在重新生成的 roster 上重标 isPlayerOccupied，避免占领进度被重生抹掉。
    // 驻军槽位随重生丢失，交由玩家重新驻守。
    val playerSectId = generationResult.sects.find { it.isPlayerSect }?.id ?: ""
    stateStore.update {
        // 合并式重生：sectRelations 按 sectId 合并保留存量——
        // 整体替换会重置外交关系（结盟/附庸/敌对清空）；重生触发原因若是
        // 重型数据写入中断（非真损坏），玩家外交进度会被静默抹除。新宗门 id
        // 无旧关系，用初始关系补齐
        val oldByKey = gameData.sectRelations.associateBy { relationKey(it) }
        val mergedRelations = newRelations.map { newRel ->
            oldByKey[relationKey(newRel)] ?: newRel
        }
        val playerOwnedSectIds = derivePlayerOwnedSectIds(gameData.sectDetails, gameData.worldMapSects)
        gameData = gameData.copy(
            worldMapSects = generationResult.sects.map { sect ->
                if (sect.id in playerOwnedSectIds) {
                    sect.copy(isPlayerOccupied = true, occupierSectId = playerSectId)
                } else {
                    sect
                }
            },
            sectRelations = mergedRelations,
            aiSectDisciples = if (gameData.aiSectDisciples.isEmpty())
                generationResult.aiSectDisciples else gameData.aiSectDisciples
        )
    }
}

/** SectRelation 键（按 sectId1/sectId2 无序组合——关系是双向的） */
private fun relationKey(rel: com.xianxia.sect.core.model.SectRelation): String {
    val a = rel.sectId1
    val b = rel.sectId2
    return if (a <= b) "$a|$b" else "$b|$a"
}

private suspend fun GameEngine.checkAndRepairAiSectDisciples() {
    val gd = stateStore.gameDataSnapshot
    val sectById = gd.worldMapSects.associateBy { it.id }
    // 非占领宗门全部满员且装备/功法数量达到宗门等级标准才跳过。
    // 注意：① 占领宗门弟子池只会减少（防守战/年度流入招募），永久无法满员，须排除；
    // ② 数量判定而非"有"判定——装备不足 1 件的旧档弟子需补齐到等级标准
    val canSkip = gd.aiSectDisciples.isNotEmpty() && gd.aiSectDisciples.all { (sectId, list) ->
        val sect = sectById[sectId]
        if (sect == null || sect.isPlayerOccupied) return@all true
        list.size >= MAX_AI_SECT_DISCIPLES &&
            list.all { AISectDiscipleManager.isGearCompleteForLevel(it, sect.level) }
    }
    if (canSkip) return
    if (gd.worldMapSects.isEmpty()) return
    DomainLog.w("ensureGameDataIntegrity", "aiSectDisciples 不足或装备/功法未达标，填充/补全")
    val regenerated = mutableMapOf<String, List<Disciple>>()
    for (sect in gd.worldMapSects) {
        if (sect.isPlayerSect || sect.isPlayerOccupied) continue
        val existing = gd.aiSectDisciples[sect.id].orEmpty()
        val filled = if (existing.isEmpty()) {
            val (d, _) = AISectDiscipleManager.initializeSectDisciples(
                sect.name, sect.level)
            AISectDiscipleManager.fillDisciplesToTarget(
                sect.name, d, MAX_AI_SECT_DISCIPLES, sect.level)
        } else {
            // 老档补全：fillDisciplesToTarget 内部对存量弟子 ensureDiscipleGear（只补缺）、
            // 对新弟子 applyGearToDisciple；满员时早退分支同样执行 ensureDiscipleGear
            AISectDiscipleManager.fillDisciplesToTarget(
                sect.name, existing, MAX_AI_SECT_DISCIPLES, sect.level)
        }
        // 超限宗门（损坏存档）截断至硬上限 MAX_AI_DISCIPLES_PER_SECT
        regenerated[sect.id] = AISectDiscipleManager.truncateToLimit(filled)
    }
    if (regenerated.isNotEmpty()) {
        stateStore.update {
            val merged = gameData.aiSectDisciples.toMutableMap()
            merged.putAll(regenerated)
            gameData = gameData.copy(aiSectDisciples = merged)
        }
    }
}

private suspend fun GameEngine.checkAndRepairMerchantAndRecruit() {
    val gd = stateStore.gameDataSnapshot
    if (gd.travelingMerchantItems.isEmpty()) {
        DomainLog.w("ensureGameDataIntegrity", "travelingMerchantItems 为空，刷新")
        cultivationService.refreshTravelingMerchant(gd.gameYear, gd.gameMonth)
    }
    // 商人商品 id 去重净化：损坏/旧存档可能出现重复或空 id 商品，
    // 多个空 id 商品同时展示会触发 LazyGrid key="" 崩溃（Bugly #5079/#3091）
    if (gd.travelingMerchantItems.size != gd.travelingMerchantItems.distinctBy { it.id }.size) {
        val removed = gd.travelingMerchantItems.size - gd.travelingMerchantItems.distinctBy { it.id }.size
        DomainLog.w("ensureGameDataIntegrity", "travelingMerchantItems 存在重复 id，净化 $removed 条")
        stateStore.update {
            gameData = gameData.copy(
                travelingMerchantItems = gameData.travelingMerchantItems.distinctBy { it.id }
            )
        }
    }
    if (gd.recruitList.isEmpty() && gd.gameYear - gd.lastRecruitYear >= 3) {
        DomainLog.w("ensureGameDataIntegrity", "recruitList 为空，刷新")
        cultivationService.refreshRecruitList(gd.gameYear)
    }
    // w3-13 通道关闭配套：本函数的修复写面（aiSectDisciples 池/商人/招募列表）在
    // AUTHORITATIVE 中途路径（upgradeSectLevel 修复重试）不再经反向通道回导——
    // 发生过修复即重建 native 基线（boot 路径 native 未就绪时为静默跳过，
    // 基线由首旬 ensureAuthoritativeNative 的全量导入吸收）
    rebaselineNativeMirror("ensureGameDataIntegrity 修复")
}

// ── Cross-domain: Sect / Map ────────────────────────────────────────

suspend fun GameEngine.enterSect(sectId: String) {
    return engineContextDispatcher.withEngineContext {
        var normalized = false
        stateStore.update {
            // boot 自愈只在读档时跑一次，世界重生后（worldSects 曾为空，
            // 归一化整体跳过）进入宗门时旧 sectId 建筑永不匹配 → 不可见不可点。
            // 复用读档自愈纯函数（幂等）在每次进入宗门时收敛，与 boot 语义一致。
            // B4（R3 已知瞬态不修）：enterSect 后 UI 侧建筑索引（LaunchedEffect）异步重建，
            // 切换瞬间可能有单次点击落在旧索引上——无累积损坏，由 onTap 诊断日志观测。
            val worldSects = gameData.worldMapSects
            // 问题1 选项2：推导"玩家持有（占领）宗门"权威集合，归一化/净化以其为准。
            val playerOwnedSectIds = derivePlayerOwnedSectIds(gameData.sectDetails, worldSects)
            val norm = normalizeOrphanBuildingSectIds(
                gameData.placedBuildings, gameData.spiritMineSlots, worldSects, playerOwnedSectIds
            )
            val purified = purifyStaleActiveSectId(sectId, worldSects, playerOwnedSectIds)
            normalized = norm.buildings != gameData.placedBuildings ||
                norm.spiritMineSlots != gameData.spiritMineSlots ||
                purified != sectId
            if (normalized) {
                DomainLog.w(
                    "GameEngine",
                    "enterSect 收敛：activeSectId=$sectId→\"$purified\"，" +
                        "孤儿建筑/矿场槽位归入本宗"
                )
            }
            gameData = gameData.copy(
                activeSectId = purified,
                placedBuildings = norm.buildings,
                spiritMineSlots = norm.spiritMineSlots
            )
        }
        // w3-13 通道关闭配套：收敛写面（activeSectId/spiritMineSlots §2.79 关闭）发生后
        // 全量重建 native 基线（§2.75④ "自愈后全量重建基线"）；未发生写入时零成本。
        if (normalized) rebaselineNativeMirror("enterSect 收敛")
    }
}

/**
 * 宗门改名（UI 入口 SectDelegate.renameSect 迁入引擎层——w3-13 通道关闭配套）：
 * 写 sectName + worldMapSects 玩家宗门名（worldMapSects §2.78 已关闭、sectName
 * §2.79 关闭——本写入为二者唯一的非 boot 稳态 Kotlin 写者），写后全量重建
 * native 基线回导 C++。改名是低频用户动作，O(状态) 一次性成本可接受（§2.78 同口径）。
 */
suspend fun GameEngine.renameSect(newName: String) {
    engineContextDispatcher.withEngineContext {
        stateStore.update {
            gameData = gameData.copy(
                sectName = newName,
                worldMapSects = gameData.worldMapSects.map { ws ->
                    if (ws.isPlayerSect) ws.copy(name = newName) else ws
                }
            )
        }
        rebaselineNativeMirror("宗门改名")
    }
}

fun GameEngine.currentActiveSectId(): String = stateStore.gameDataSnapshot.activeSectId
