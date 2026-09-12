package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.service.RecruitService
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EnemyType
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.MissionDifficulty
import com.xianxia.sect.core.model.MissionRewardConfig
import com.xianxia.sect.core.model.MissionTemplate
import com.xianxia.sect.core.model.MissionType
import com.xianxia.sect.core.model.ActiveMission
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.RngPartition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffMissionSettlementTest — 任务完成结算跨语言差分对拍。
 *
 * 守护目标：C++ `mission_completion.h`（month_settlement 子事件 5：完成判定 +
 * 三分支结算 + 奖励生成 + 战斗组装 createBattle/convertDiscipleToCombatant/
 * EnemyGenerator + applyMissionRewards）与 Kotlin `MonthSettlementExecutor`
 * 全链（真实 CultivationEventProcessor.processCompletedMissionsLazy →
 * MissionSystem.processMissionCompletion → 真实 BattleSystem）**逐位一致**。
 *
 * 战斗组装首次入 C++——本场景是该面的端到端守护：Side B 经真实 BattleSystem
 * 组装战斗体（Kotlin DiscipleStatCalculator.getFinalStats + skills）并本地执行
 * （桌面 JVM 无生产 bridge → BattleExecutionRouter 回退本地）；Side A 在 C++
 * runMonthSettlement 内组装+执行。两臂自同一 rngStates 播种，任一装配/执行
 * 分歧即存活者/魂力/rngStates 结构分歧（全量对拍兜底）。
 *
 * 场景（buildMissionSceneSnapshot 族；任务 start (1,1) duration 1 → (1,2)
 * 月界完成）：
 *  - NO_COMBAT：灵石 roll（400+0..100）+ 材料 2..3（rarity 1..2）+ 丹 1..1
 *    （rarity 1..1）——MISSION 分区 roll 序双端一致（装备/功法 chance 0 规避
 *    模板抽取分歧面——该面由 C++ GTest 黄金锁定）
 *  - COMBAT_REQUIRED（SIMPLE/BEAST，仙人九层双弟子 + 高攻功法 → 必胜）：
 *    幸存者=全员、魂力 +1、状态回 IDLE、BATTLE 分区消耗
 *  - COMBAT_RANDOM（triggerChance 0.0 → 恒不触发）：base 灵石 200、零战斗
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffMissionSettlementTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private companion object {
        const val PHASES = 3
        const val SEED = 20260901L
    }

    // ── 场景装配 ────────────────────────────────────────────────────

    // 弟子 id 用稠密小整数（生产真实口径——Kotlin applyMissionRewards 的
    // 状态重置循环带 tid < ids.size 稠密守卫，稀疏 id 场景属协议外）
    private val memberA = "1"
    private val memberB = "2"

    /** 初始 RNG 分区状态：seed+partitionId 播种后各抽取 3 次（非平凡状态） */
    private fun initialRngStates(seed: Long): MutableMap<Int, Long> {
        val states = mutableMapOf<Int, Long>()
        RngPartition.values().forEach { partition ->
            val rng = DeterministicRng.fromSeed(seed + partition.id)
            repeat(3) { rng.nextInt() }
            states[partition.id] = rng.snapshot()
        }
        return states
    }

    /** 战斗弟子（仙人九层 + 高攻功法——对 SIMPLE 妖兽必胜） */
    private fun strongDisciple(id: String, name: String) = Disciple(
        id = id, name = name, realm = 0, realmLayer = 9,
        cultivation = 10.0, spiritRootType = "metal",
        age = 20, gender = "male",
        manualIds = listOf("man-$id"),
        combat = CombatAttributes(currentHp = -1, currentMp = -1)
    )

    /** 高攻功法实例（physicalAttack 100000 × 熟练度 NOVICE 1.5 → 一击秒杀） */
    private fun strongManual(id: String) = ManualInstance(
        id = "man-$id", name = "测试功法$id", rarity = 1, type = ManualType.ATTACK,
        stats = mapOf("maxHp" to 50000, "physicalAttack" to 100000),
        skillName = "测试斩", skillType = "attack", skillDamageType = "physical",
        skillDamageMultiplier = 3.0, skillMpCost = 0, skillCooldown = 0,
        skillHits = 1, skillTargetScope = "enemy"
    )

    private fun baseSnapshot(missions: List<ActiveMission>): NativeGameState {
        val gameData = GameData(
            gameYear = 1, gameMonth = 1, gamePhase = 0,
            spiritStones = 10000L
        ).apply {
            rngStates = initialRngStates(SEED)
            worldLevelLastRefreshMonth = 1 * 12 + 1
            activeMissions = missions
        }
        // aiSectDisciples 置一条空池（既有口径）；无灵田/秘境/侦察/附庸/政策
        // → 其余步骤零抽取；洞天 AI/兽战余量（残留扇出）零效果
        gameData.aiSectDisciples = mapOf("ai-3" to emptyList<Disciple>())
        return NativeGameState(
            gameData = gameData,
            aiSectDisciples = gameData.aiSectDisciples,
            disciples = listOf(
                strongDisciple(memberA, "甲一"),
                strongDisciple(memberB, "乙二")
            ),
            manualInstances = listOf(strongManual(memberA), strongManual(memberB))
        )
    }

    private fun storeOf(snapshot: NativeGameState) = FakeGameStateStore().also {
        it.gameDataValue = snapshot.gameData
        it.disciplesValue = snapshot.disciples
        it.equipmentStacksValue = snapshot.equipmentStacks
        it.equipmentInstancesValue = snapshot.equipmentInstances
        it.manualStacksValue = snapshot.manualStacks
        it.manualInstancesValue = snapshot.manualInstances
        it.pillsValue = snapshot.pills
        it.materialsValue = snapshot.materials
        it.herbsValue = snapshot.herbs
        it.seedsValue = snapshot.seeds
        it.storageBagsValue = snapshot.storageBags
    }

    /** 双臂推进：返回 (Kotlin 期望面, C++ 实际面) */
    private fun runMissionDiff(snapshot: NativeGameState): Pair<NativeGameState, NativeGameState> {
        RecruitService.RecruitLazyState.autoRecruitIdle = false
        val encoded = json.encodeToString(NativeGameState.serializer(), snapshot)
        val expected = advanceKotlinMonthSide(buildMonthDiffHarness(storeOf(snapshot),
            snapshot.gameData.rngStates), PHASES)
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(
            encoded.encodeToByteArray()))
        DiffRngBridge.nativeCoreAdvancePhases(PHASES)
        val actual = json.decodeFromString(
            NativeGameState.serializer(),
            DiffRngBridge.nativeCoreExportState().decodeToString()
        )
        return Pair(expected, actual)
    }

    // ── 场景 1：NO_COMBAT 奖励 roll ─────────────────────────────────

    @Test
    fun `no combat mission rewards match Kotlin bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val mission = ActiveMission(
            id = "m-noCombat", missionId = "tpl-nc",
            missionName = "简单押镖", template = MissionTemplate.ESCORT_CARAVAN,
            difficulty = MissionDifficulty.SIMPLE,
            discipleIds = listOf(memberA), discipleNames = listOf("甲一"),
            startYear = 1, startMonth = 1, duration = 1,
            rewards = MissionRewardConfig(
                spiritStones = 400, spiritStonesMax = 500,
                materialCountMin = 2, materialCountMax = 3,
                materialMinRarity = 1, materialMaxRarity = 2,
                pillCountMin = 1, pillCountMax = 1,
                pillMinRarity = 1, pillMaxRarity = 1
            ),
            missionType = MissionType.NO_COMBAT
        )
        val snapshot = baseSnapshot(listOf(mission))
        val (expected, actual) = runMissionDiff(snapshot)

        val gd = actual.gameData
        assertEquals("任务消费", 0, gd.activeMissions.size)
        assertTrue("灵石入账（400+0..100）实际=" + gd.spiritStones,
            gd.spiritStones in 10400L..10500L)
        assertTrue("材料 2..3 实际=" + actual.materials.size, actual.materials.size in 2..3)
        assertEquals("丹药 1", 1, actual.pills.size)
        assertEquals("成员回 IDLE", "IDLE",
            actual.disciples.first { it.id == memberA }.status.name)

        diffAssertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                    json.encodeToJsonElement(actual))
    }

    // ── 场景 2：COMBAT_REQUIRED 妖兽战斗（胜利臂） ──────────────────

    @Test
    fun `combat required beast victory matches Kotlin bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val mission = ActiveMission(
            id = "m-combat", missionId = "tpl-cr",
            missionName = "简单除妖", template = MissionTemplate.SUPPRESS_LOW_BEASTS,
            difficulty = MissionDifficulty.SIMPLE,
            discipleIds = listOf(memberA, memberB),
            discipleNames = listOf("甲一", "乙二"),
            startYear = 1, startMonth = 1, duration = 1,
            rewards = MissionRewardConfig(spiritStones = 100),
            missionType = MissionType.COMBAT_REQUIRED,
            enemyType = EnemyType.BEAST
        )
        val snapshot = baseSnapshot(listOf(mission))
        val (expected, actual) = runMissionDiff(snapshot)

        val gd = actual.gameData
        assertEquals("任务消费", 0, gd.activeMissions.size)
        assertEquals("灵石入账", 10100L, gd.spiritStones)
        val a = actual.disciples.first { it.id == memberA }
        val b = actual.disciples.first { it.id == memberB }
        assertEquals("甲一幸存魂力 +1", 1, a.soulPower)
        assertEquals("乙二幸存魂力 +1", 1, b.soulPower)
        assertEquals("甲一回 IDLE", "IDLE", a.status.name)
        assertEquals("乙二回 IDLE", "IDLE", b.status.name)

        diffAssertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                    json.encodeToJsonElement(actual))
    }

    // ── 场景 3：COMBAT_RANDOM 未触发 ────────────────────────────────

    @Test
    fun `combat random untriggered base rewards match Kotlin bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val mission = ActiveMission(
            id = "m-random", missionId = "tpl-cx",
            missionName = "普通护送", template = MissionTemplate.ESCORT_SPIRIT_CARAVAN,
            difficulty = MissionDifficulty.NORMAL,
            discipleIds = listOf(memberA), discipleNames = listOf("甲一"),
            startYear = 1, startMonth = 1, duration = 1,
            rewards = MissionRewardConfig(baseSpiritStones = 200),
            missionType = MissionType.COMBAT_RANDOM,
            triggerChance = 0.0
        )
        val snapshot = baseSnapshot(listOf(mission))
        val (expected, actual) = runMissionDiff(snapshot)

        val gd = actual.gameData
        assertEquals("任务消费", 0, gd.activeMissions.size)
        assertEquals("base 灵石入账", 10200L, gd.spiritStones)
        assertEquals("无战斗（魂力不变）", 0,
            actual.disciples.first { it.id == memberA }.soulPower)

        diffAssertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                    json.encodeToJsonElement(actual))
    }
}
