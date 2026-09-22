package com.xianxia.sect.data.engine

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentNurtureData
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ExploredSectInfo
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.SectScoutInfo
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.UsageTracking
import com.xianxia.sect.core.model.YearlyReport
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.data.compression.CompressionAlgorithm
import com.xianxia.sect.data.compression.DataCompressor
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.unified.CompressionType
import com.xianxia.sect.data.serialization.unified.SerializationContext
import com.xianxia.sect.data.serialization.unified.SerializationFormat
import com.xianxia.sect.data.serialization.unified.UnifiedSerializationEngine
import kotlinx.serialization.serializer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * 云档 payload 尺寸分布实测台架（SR-0 前置侦察批 T1）。
 *
 * 测量对象 = 云存档上传字节的生产同路径：
 * `SaveData → serialize（NullSafeProtoBuf PROTOBUF）→ LZ4 + checksum`
 * （与 `SerializationModule.serializeAndCompressSaveData` 完全相同的
 * `SerializationContext`；DI 外壳以直构 `UnifiedSerializationEngine(DataCompressor())` 等价复现）。
 *
 * **世界常量与序列化面（代码事实，非假设）**：
 * - 宗门数固定 30（`FixedSectPositions.ALL`，1 玩家 + 29 AI）；
 * - **`GameData.aiSectDisciples` 带 `@kotlinx.serialization.Transient`，不进 SaveData
 *   proto ⇒ 云档 payload 不含 AI 宗弟子**（它们走 Room `game_heavy_data` 独立通道，
 *   云存档链无侧车补偿——`GameData.kt:892` 注释自证；下载侧由
 *   `GameEngineLifecycleOps.ensureGameDataIntegrity` 按 ~初始态重生成 50/宗）。
 *   同为 @Transient 的还有 aiSectBeastSkipCooldowns / aiBeastEncounterTargets /
 *   lockedBeastIds / aiSectBeastDirectTargets / slotId / autoSaveIntervalMonths；
 * - **`terrainTiles`（@ProtoNumber(1001)，16384 瓦片 flat）在云档 payload 内**；
 * - battleLogs 由 `SaveDataTrimmer`（feature/game）`takeLast(1000)` 封顶；
 * - exploredSects/scoutInfo/sectDetails ≤ 29（每宗一份）。
 *
 * **曲线取样假设**（表驱动，档位=游戏年）：玩家弟子 20→300（招募+建筑位），
 * battleLogs 100→1000（月级战斗频率 20 年内饱和），装备/丹药等库存线性积累。
 * 单条样本内容取生产形状（中文 2-3 字名、长 id、丰富子结构）。
 *
 * **IN5 CI 断言已落地**（方案 §3 IN5：CI 构造老玩家样本档断言 payload ≤ 红线）：
 * 曲线逐档断言 `≤ StorageConstants.CLOUD_PAYLOAD_RED_LINE_BYTES`（2,000,000B，
 * 定值出处见该常量 KDoc），另保留 TapTap 10MB 硬上限断言作为第二道兜底。
 */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [34])
class CloudPayloadSizeBenchTest {

    private val engine = UnifiedSerializationEngine(DataCompressor(CompressionAlgorithm.LZ4))

    private val context = SerializationContext(
        format = SerializationFormat.PROTOBUF,
        compression = CompressionType.LZ4,
        compressThreshold = 1024,
        includeChecksum = true
    )

    // ==================== 档位定义（游戏年 → 世界/库存规模） ====================

    private data class Profile(
        val label: String,
        val gameYear: Int,
        val playerDisciples: Int,
        val battleLogs: Int,
        val exploredSects: Int,
        val equipmentInstances: Int,
        val manualInstances: Int,
        val materials: Int
    )

    // ==================== 主测量：尺寸-游戏年曲线 ====================

    @Test
    fun `云档 payload 尺寸-游戏年曲线`() {
        val rows = mutableListOf(
            "| 档位 | 玩家弟子 | 战斗日志 | 装备/功法/材料 | proto原始B | LZ4后B | LZ4后MB | 压缩率 |",
            "|---|---|---|---|---|---|---|---|"
        )
        for (p in PROFILES) {
            val saveData = buildSaveData(p)
            val result = engine.serialize(saveData, context, serializer<SaveData>())
            val raw = result.originalSize
            val compressed = result.data.size
            val ratio = if (raw > 0) compressed.toDouble() / raw else 1.0
            rows.add(
                "| ${p.label} | ${p.playerDisciples} | ${p.battleLogs} | " +
                    "${p.equipmentInstances}/${p.manualInstances}/${p.materials} | $raw | $compressed | " +
                    String.format(Locale.US, "%.2f", compressed / 1024.0 / 1024.0) + "MB | " +
                    String.format(Locale.US, "%.3f", ratio) + " |"
            )
            // IN5 红线（SR-7 落地）：撞 10MB 才判红 = 玩家档已经顶到 SDK 墙，太晚。
            // 红线 = StorageConstants.CLOUD_PAYLOAD_RED_LINE_BYTES（SR-0 实测 0.29MB ⇒ ~7x 余量）
            assertTrue(
                "IN5 红线：${p.label} 的 LZ4 后 payload ${compressed}B 不得超过 " +
                    "${StorageConstants.CLOUD_PAYLOAD_RED_LINE_BYTES}B" +
                    "（新字段/日志膨胀第一时间在此判红，而非撞到 TapTap 硬上限）",
                compressed <= StorageConstants.CLOUD_PAYLOAD_RED_LINE_BYTES
            )
            assertTrue("payload 应小于 TapTap 10MB 硬上限（${p.label}）",
                compressed < 10L * 1024 * 1024)
        }
        println("[payload-curve]\n" + rows.joinToString("\n"))
    }

    // ==================== 成分归因：主导项单项成本 ====================

    @Test
    fun `payload 成分归因-单项成本`() {
        val baseline = saveBytes(
            SaveData(gameData = minimalGameData(), disciples = emptyList(), pills = emptyList(),
                materials = emptyList(), herbs = emptyList(), seeds = emptyList())
        )
        val oneDisciple = saveBytes(
            SaveData(gameData = minimalGameData(), disciples = listOf(bulkDisciple(0, "sect_1")),
                pills = emptyList(), materials = emptyList(), herbs = emptyList(), seeds = emptyList())
        ) - baseline
        val oneBattleLog = saveBytes(
            SaveData(gameData = minimalGameData(), battleLogs = listOf(bulkBattleLog(0)),
                disciples = emptyList(), pills = emptyList(), materials = emptyList(),
                herbs = emptyList(), seeds = emptyList())
        ) - baseline
        val terrainDelta = saveBytes(
            SaveData(gameData = minimalGameData().copy(terrainTiles = fullTerrainTiles()),
                disciples = emptyList(), pills = emptyList(), materials = emptyList(),
                herbs = emptyList(), seeds = emptyList())
        ) - baseline
        val fullWorldGameData = saveBytes(
            SaveData(gameData = bulkGameData(8, 50), disciples = emptyList(),
                pills = emptyList(), materials = emptyList(), herbs = emptyList(), seeds = emptyList())
        )

        println(
            "[payload-attribution]\n" +
                "单弟子(LZ4后增量)≈${oneDisciple}B\n" +
                "单战斗日志(中等密度4-9回合, LZ4后增量)≈${oneBattleLog}B\n" +
                "terrainTiles 16384 瓦片(LZ4后增量)≈${terrainDelta / 1024}KB\n" +
                "满探索世界Game(30宗+AI宗50人+8探索, LZ4后)≈${fullWorldGameData / 1024}KB"
        )
        assertTrue("增量测量应为正（弟子）", oneDisciple > 0)
        assertTrue("增量测量应为正（战斗日志）", oneBattleLog > 0)
        assertTrue("增量测量应为正（terrainTiles）", terrainDelta > 0)
    }

    // ==================== 裁剪决策曲线：battleLogs 条数敏感性 ====================

    @Test
    fun `battleLogs 条数-尺寸敏感性（裁剪决策输入）`() {
        val rows = mutableListOf("| 条数 | LZ4后B | LZ4后KB |", "|---|---|---|")
        for (count in listOf(100, 300, 500, 750, MAX_BATTLE_LOGS)) {
            val bytes = saveBytes(
                SaveData(gameData = minimalGameData(), battleLogs = (0 until count).map { bulkBattleLog(it) },
                    disciples = emptyList(), pills = emptyList(), materials = emptyList(),
                    herbs = emptyList(), seeds = emptyList())
            )
            rows.add("| $count | $bytes | ${String.format(Locale.US, "%.2f", bytes / 1024.0)}KB |")
        }
        println("[battlelog-sensitivity]\n" + rows.joinToString("\n"))
    }

    // ==================== 样本构造（生产形状） ====================

    private fun saveBytes(data: SaveData): Int = engine.serialize(data, context, serializer<SaveData>()).data.size

    private fun minimalGameData(): GameData = GameData(
        gameYear = 1, gameMonth = 1, sectName = "青云宗"
    )

    /** 16384 瓦片（128×128）模拟地形：带成片走向的 0..8 值（真实地形成片、可压）。 */
    private fun fullTerrainTiles(): List<Int> = List(16384) { i -> ((i / 32) + (i % 128) / 16) % 9 }

    private fun bulkDisciples(count: Int, sectIndex: Int): List<Disciple> =
        (0 until count).map { bulkDisciple(it, "sect_$sectIndex") }

    private fun bulkDisciple(idx: Int, sectName: String): Disciple = Disciple(
        id = "disciple-${UUID_PAD}${idx.toString().padStart(6, '0')}-a1b2c3",
        name = AI_NAMES[idx % AI_NAMES.size] + (idx / AI_NAMES.size),
        surname = AI_NAMES[idx % AI_NAMES.size].substring(0, 1),
        realm = idx % 9,
        realmLayer = idx % 3,
        cultivation = 100.0 + idx,
        cultivationCheckpoint = 90.0 + idx,
        cultivationCheckpointGameMonth = idx % 1200,
        spiritRootType = SPIRIT_ROOTS[idx % SPIRIT_ROOTS.size],
        age = 16 + idx % 200,
        lifespan = 300,
        isAlive = true,
        gender = if (idx % 2 == 0) "male" else "female",
        portraitRes = "portrait_${idx % 64}",
        manualIds = listOf("manual_${idx % 50}", "manual_${(idx + 1) % 50}"),
        talentIds = listOf("talent_${idx % 30}"),
        physiqueIds = listOf("physique_${idx % 20}"),
        affixIds = listOf("affix_${idx % 40}"),
        manualMasteries = mapOf("manual_${idx % 50}" to idx % 10),
        status = DiscipleStatus.IDLE,
        statusData = mapOf("sect" to sectName),
        cultivationSpeedBonus = 0.1,
        cultivationSpeedDuration = 12,
        discipleType = if (idx % 3 == 0) "inner" else "outer",
        soulPower = idx % 100
    ).copy(
        equipment = EquipmentSet(
            weaponId = "weapon_${idx % 200}",
            armorId = "armor_${idx % 200}",
            bootsId = "boots_${idx % 100}",
            accessoryId = "accessory_${idx % 100}",
            weaponNurture = EquipmentNurtureData("weapon_${idx % 200}", idx % 3, nurtureLevel = idx % 10,
                nurtureProgress = 0.5),
            storageBagSpiritStones = (1000 + idx).toLong(),
            spiritStones = 100 + idx
        ),
        skills = SkillStats(
            intelligence = idx % 100, charm = idx % 90, loyalty = idx % 100, comprehension = idx % 100,
            aptitude = idx % 100, alchemyLevel = idx % 9, alchemyPromotionCount = idx % 5, forgeLevel = idx % 7
        ),
        combat = CombatAttributes(
            baseHp = 1000 + idx * 7, baseMp = 500 + idx * 3, basePhysicalAttack = 50 + idx % 300,
            baseSpeed = idx % 100, totalCultivation = 100_000L + idx, breakthroughCount = idx % 10,
            breakthroughFailCount = idx % 3, currentHp = 1000 + idx * 7, currentMp = 500
        ),
        usage = UsageTracking(
            recruitedMonth = idx % 600,
            usedFunctionalPillTypes = listOf("pill_atk", "pill_def"),
            usedExtendLifePillIds = listOf("pill_life_${idx % 5}"),
            hasReviveEffect = idx % 7 == 0
        )
    )

    /** 中等密度战斗日志（4-9 回合 × 2-4 动作），取生产形状中位数偏上。 */
    private fun bulkBattleLog(idx: Int): BattleLog {
        val roundCount = 4 + idx % 6
        val rounds = (1..roundCount).map { roundNo ->
            BattleLogRound(
                roundNumber = roundNo,
                actions = (1..(2 + idx % 3)).map { actNo ->
                    BattleLogAction(
                        type = if (actNo % 2 == 0) "skill" else "attack",
                        attacker = AI_NAMES[(idx + actNo) % AI_NAMES.size],
                        attackerType = "disciple",
                        target = "敌方${AI_NAMES[(idx + actNo + 3) % AI_NAMES.size]}",
                        damage = 100 + (idx * actNo) % 900,
                        damageType = if (actNo % 3 == 0) "magic" else "physical",
                        isCrit = (actNo + idx) % 5 == 0,
                        isKill = actNo == 3 && roundNo == roundCount,
                        message = "${AI_NAMES[(idx + actNo) % AI_NAMES.size]}施展武功命中敌方目标"
                    )
                }
            )
        }
        return BattleLog(
            id = "battle-${UUID_PAD}${idx.toString().padStart(6, '0')}",
            slotId = 1,
            timestamp = 1_700_000_000_000L + idx * 60_000L,
            year = idx % 50 + 1,
            month = idx % 12 + 1,
            type = BattleType.entries[idx % BattleType.entries.size],
            attackerName = "青云宗",
            defenderName = "血煞门",
            result = if (idx % 3 == 0) BattleResult.WIN else BattleResult.LOSE,
            details = "宗门战第${idx % 100}场：争夺灵矿脉",
            drops = listOf("spirit_stone_x${50 + idx % 200}", "material_${idx % 30}"),
            dungeonName = "血煞秘境",
            teamMembers = (1..4).map { m ->
                BattleLogMember(id = "d$m", name = AI_NAMES[(idx + m) % AI_NAMES.size], realm = m % 9,
                    realmName = "金丹", hp = 4000 + m, maxHp = 5000, isAlive = m != 2)
            },
            enemies = (1..4).map { m ->
                BattleLogEnemy(id = "e$m", name = "敌方${AI_NAMES[(idx + m) % AI_NAMES.size]}", realm = m % 9,
                    hp = 3500 + m, maxHp = 4200, isAlive = m != 1)
            },
            rounds = rounds,
            turns = roundCount,
            teamCasualties = idx % 2,
            beastsDefeated = idx % 3
        )
    }

    private fun bulkWorldSect(idx: Int): WorldSect = WorldSect(
        id = "sect_$idx",
        name = "宗门${AI_NAMES[idx % AI_NAMES.size]}",
        level = idx % 4,
        levelName = LEVEL_NAMES[idx % LEVEL_NAMES.size],
        x = idx * 137.5f,
        y = idx * 91.25f,
        distance = 100 + idx * 10,
        isPlayerSect = idx == 0,
        discovered = idx < 10,
        isKnown = idx < 20,
        relation = 20 + idx,
        disciples = (0..8).associateWith { it * 7 + idx % 5 },
        isOccupied = idx % 7 == 0,
        occupierSectId = if (idx % 7 == 0) "sect_${(idx + 1) % AI_SECTS}" else "",
        isRighteous = idx % 2 == 0
    )

    // aiDisciples 形参已移除：aiSectDisciples @Transient 不进 payload，不构造（见类 KDoc）
    private fun bulkGameData(exploredCount: Int, yearlyReports: Int): GameData {
        val sects = (0..AI_SECTS).map { bulkWorldSect(it) }
        return GameData(
            gameYear = 50, gameMonth = 6, sectName = "青云宗",
            spiritStones = 12_345_678L, sectCultivation = 9_876_543.0,
            worldMapSects = sects,
            sectDetails = sects.take(12).associate { it.id to SectDetail(sectId = it.id, isOwned = it.isPlayerSect) },
            // aiSectDisciples @Transient 不进 payload，不构造（见类 KDoc）
            terrainTiles = fullTerrainTiles(),
            exploredSects = sects.take(exploredCount).associate { it.id to
                ExploredSectInfo(sectId = it.id, sectName = it.name, year = 40 + it.level, month = it.level * 3,
                    duration = 3, memberIds = listOf("disciple-000001", "disciple-000002"),
                    memberNames = listOf("林寒", "苏瑶"), events = listOf("探索完成", "遭遇战胜利"),
                    rewards = listOf("spirit_stone_x200"), battleCount = 2, casualties = 0, discipleCount = 45) },
            scoutInfo = sects.take(exploredCount).associate { it.id to
                SectScoutInfo(sectId = it.id, sectName = it.name, scoutYear = 39, scoutMonth = 8,
                    discipleCount = 45, maxRealm = 6,
                    resources = mapOf("灵石矿" to 3, "药材园" to 2), isKnown = true) },
            recruitList = bulkDisciples(8, 0),
            yearlyReports = (1..yearlyReports).map { y ->
                YearlyReport(
                    year = y, totalIncome = 100_000L + y * 37, totalExpenditure = 80_000L + y * 21,
                    incomeBySource = mapOf("灵田" to 40_000L, "矿脉" to 35_000L, "坊市" to 25_000L + y),
                    expenditureByReason = mapOf("俸禄" to 30_000L, "采购" to 28_000L, "建设" to 22_000L),
                    forgeCompleted = y % 12, alchemyCompleted = y % 15, herbsHarvested = y % 40,
                    equipmentBySource = mapOf("forge:3" to y % 9, "battle:4" to y % 5),
                    pillBySource = mapOf("alchemy:HIGH" to y % 7),
                    herbBySource = mapOf("spirit_field" to y % 30, "exploration" to y % 10),
                    newDisciples = y % 8, deceasedDisciples = y % 3, desertedDisciples = y % 2
                )
            }
        )
    }

    private fun buildSaveData(p: Profile): SaveData = SaveData(
        gameData = bulkGameData(p.exploredSects, minOf(p.gameYear, MAX_YEARLY_REPORTS)),
        disciples = bulkDisciples(p.playerDisciples, 0),
        equipmentStacks = (0 until p.equipmentInstances / 4).map {
            EquipmentStack(id = "stack-${UUID_PAD}$it", name = "精铁剑·堆叠${it % 20}")
        },
        equipmentInstances = (0 until p.equipmentInstances).map {
            EquipmentInstance(id = "equip-${UUID_PAD}${it.toString().padStart(6, '0')}",
                name = ITEM_NAMES[it % ITEM_NAMES.size] + (it / ITEM_NAMES.size))
        },
        manualStacks = (0 until p.manualInstances / 5).map {
            ManualStack(id = "mstack-${UUID_PAD}$it", name = "功法·${ITEM_NAMES[it % ITEM_NAMES.size]}")
        },
        manualInstances = (0 until p.manualInstances).map {
            ManualInstance(id = "manual-${UUID_PAD}${it.toString().padStart(6, '0')}",
                name = "《青元剑诀》第${it % 9}层")
        },
        pills = (0 until p.materials / 2).map { Pill(id = "pill-${UUID_PAD}$it", name = "回气丹") },
        materials = (0 until p.materials).map {
            Material(id = "mat-${UUID_PAD}${it.toString().padStart(6, '0')}", name = "千年灵草")
        },
        herbs = (0 until p.materials / 4).map { Herb(id = "herb-${UUID_PAD}$it", name = "紫芝") },
        seeds = (0 until p.materials / 8).map { Seed(id = "seed-${UUID_PAD}$it", name = "灵稻种") },
        storageBags = (0 until 6).map { StorageBag(id = "bag-${UUID_PAD}$it", name = "储物袋·$it") },
        battleLogs = (0 until p.battleLogs).map { bulkBattleLog(it) },
        alliances = emptyList(),
        productionSlots = (0 until 24).map { ProductionSlot(id = "slot_$it") },
        stacksSerialized = true
    )

    private companion object {
        const val AI_SECTS = 29
        const val MAX_BATTLE_LOGS = 1000 // SaveDataTrimmer 现役封顶
        const val MAX_YEARLY_REPORTS = 100 // GameConfig.Logs.MAX_YEARLY_REPORTS
        const val UUID_PAD = "3f9a2c"

        val AI_NAMES = listOf("林寒", "苏瑶", "叶孤鸿", "云无月", "沈青崖", "白止水", "秦无名", "洛惊鸿",
            "顾长风", "温初雪", "祁连峰", "阮清商", "霍去疾", "姜别鹤", "闻人夜", "司徒南")
        val SPIRIT_ROOTS = listOf("ice", "fire", "wood", "metal", "earth", "thunder", "wind")
        val LEVEL_NAMES = listOf("小型宗门", "中型宗门", "大型宗门", "顶级宗门")
        val ITEM_NAMES = listOf("精铁剑", "玄铁甲", "疾风靴", "青玉环", "赤焰刀", "寒冰盾", "流云履", "紫金冠")

        val PROFILES = listOf(
            Profile("第1年", 1, playerDisciples = 20, battleLogs = 100,
                exploredSects = 8, equipmentInstances = 60, manualInstances = 30, materials = 40),
            Profile("第5年", 5, playerDisciples = 60, battleLogs = 400,
                exploredSects = 16, equipmentInstances = 180, manualInstances = 80, materials = 120),
            Profile("第20年", 20, playerDisciples = 120, battleLogs = MAX_BATTLE_LOGS,
                exploredSects = AI_SECTS, equipmentInstances = 350, manualInstances = 150, materials = 250),
            Profile("第50年", 50, playerDisciples = 200, battleLogs = MAX_BATTLE_LOGS,
                exploredSects = AI_SECTS, equipmentInstances = 500, manualInstances = 200, materials = 300),
            Profile("第50年·重玩家档", 50, playerDisciples = 300,
                battleLogs = MAX_BATTLE_LOGS, exploredSects = AI_SECTS,
                equipmentInstances = 800, manualInstances = 300, materials = 400),
            Profile("极限档·年报复盘满100(200年)", 200, playerDisciples = 300,
                battleLogs = MAX_BATTLE_LOGS, exploredSects = AI_SECTS,
                equipmentInstances = 800, manualInstances = 300, materials = 400)
        )
    }
}
