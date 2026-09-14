package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.engine.domain.battle.aisRngManager
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.xianxia.sect.core.engine.domain.battle.decidePlayerAttack

/**
 * DiffSectAttackDecisionTest — AI 宗门攻击决策跨语言差分对拍）。
 *
 * 守护目标：C++ `gamecore::system::detail::checkAttackConditions`（经
 * [DiffRngBridge.nativeCoreCheckAttackConditions] 直调）与 Kotlin
 * `AISectAttackManager.checkAttackConditions` 语义**逐位一致**：
 * - 前置门（身份/人数/联盟/守军战力 0）早退不消费 BATTLE 抽取（Kotlin return 早退
 *   与 C++ 早退同序，snapshot 逐位一致）
 * - 门通过时恰消费 1 次 BATTLE nextDouble（`< chance`），BATTLE rngStates 终态逐位一致
 *
 * 直调设计（仿 DiffPrecomputeTargetsTest）：不经过完整月变管线——规避
 * AISectBattleProcessor 完整编排的 BATTLE 干扰，抽取序仅含本决策函数。
 * 生产路径经 AISectAttackManager.checkAttackConditions native 路由。
 *
 * 场景：ai-1 攻 ai-2（各 10 名 realm-9 弟子），无联盟/好感/战报 → 门通过 → 恰抽 1 次。
 */
class DiffSectAttackDecisionTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private companion object {
        const val SEED = 20260902L
    }

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

    /** AI 弟子：realm 9 默认属性（战力 1071/人，10 人 = 10710） */
    private fun aiDisciple(id: String) = Disciple(
        id = id, name = "AI弟子$id", realm = 9, realmLayer = 1,
        cultivation = 10.0, spiritRootType = "metal",
        age = 20, gender = "male",
        combat = CombatAttributes(currentHp = -1, currentMp = -1)
    )

    private fun buildSnapshot(): NativeGameState {
        val gameData = GameData(
            gameYear = 10, gameMonth = 3, gamePhase = 0,
            spiritStones = 10000L
        ).apply {
            rngStates = initialRngStates(SEED)
            worldMapSects = listOf(
                WorldSect(id = "ai-1", name = "青岚宗"),
                WorldSect(id = "ai-2", name = "赤水宗")
            )
            aiSectDisciples = mapOf(
                "ai-1" to (1..10).map { aiDisciple("att$it") },
                "ai-2" to (1..10).map { aiDisciple("def$it") }
            )
        }
        return NativeGameState(
            gameData = gameData,
            aiSectDisciples = gameData.aiSectDisciples
        )
    }

    @Test
    fun `checkAttackConditions matches Kotlin bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val snapshot = buildSnapshot()
        val encoded = json.encodeToString(NativeGameState.serializer(), snapshot)

        // ── Kotlin 基准侧：authoritative=OFF（路由回退纯 Kotlin 逻辑） ──
        NativeEngineFlag.mode = NativeEngineFlag.Mode.OFF
        try {
        val gameRng = GameRngManager().also { it.restoreStates(snapshot.gameData.rngStates) }
        aisRngManager = gameRng
        val attacker = snapshot.gameData.worldMapSects.first { it.id == "ai-1" }
        val defender = snapshot.gameData.worldMapSects.first { it.id == "ai-2" }
        val expectedCanAttack = AISectAttackManager.checkAttackConditions(
            attacker, defender, snapshot.gameData, snapshot.gameData.aiSectDisciples, emptyMap()
        )
        val expectedBattleState = gameRng.getRng(RngPartition.BATTLE).snapshot()
        val initialBattleState = snapshot.gameData.rngStates.getValue(RngPartition.BATTLE.id)

        // ── C++ 被测侧 ──
        assertEquals("C++ 导入失败", true,
            DiffRngBridge.nativeCoreImportState(encoded.encodeToByteArray()))
        val actualCanAttack = DiffRngBridge.nativeCoreCheckAttackConditions("ai-1", "ai-2", "{}")
        val actualBattleState = DiffRngBridge.nativeCoreRngSnapshotPartition(RngPartition.BATTLE.id)

        // 显式断言：决策一致、BATTLE 终态逐位一致、且确实消费了（终态 != 初始）
        assertEquals("攻击判定不一致", expectedCanAttack, actualCanAttack)
        assertEquals("BATTLE 分区终态不一致", expectedBattleState, actualBattleState)
        assertEquals("BATTLE 分区应消费 1 次（终态 != 初始）", false,
            initialBattleState == actualBattleState)
        } finally {
            NativeEngineFlag.mode = NativeEngineFlag.Mode.AUTHORITATIVE
        }
    }

    /** AI 攻玩家场景：游戏第 101 年（保护期 100 年届满 → 不保护）+ 玩家宗门 + AI 攻方 */
    private fun buildPlayerAttackSnapshot(): NativeGameState {
        val playerDisciples = (1..10).map { aiDisciple("pl$it") }
        val gameData = GameData(
            gameYear = 101, gameMonth = 3, gamePhase = 0,
            spiritStones = 10000L
        ).apply {
            rngStates = initialRngStates(SEED)
            worldMapSects = listOf(
                WorldSect(id = "p1", name = "青云宗", isPlayerSect = true),
                WorldSect(id = "ai-1", name = "青岚宗"),
                WorldSect(id = "ai-2", name = "赤水宗")
            )
            // 玩家 p1 守军池：aiSectDisciples["p1"] = Kotlin decidePlayerAttack
            // 基准侧数据源（aiDisciplesMap[playerSectId]）；C++ 侧（P2-18 Stage 1
            // playerDefenders 修正）改读 GameState.disciples 玩家弟子权威存储——
            // 两侧同池同属性，战力/机会/RNG 抽取逐位一致。
            // ai-1 攻方 10 名弟子（门通过）；ai-2 仅 5 名（少于 10 → 闸前跳过不消费）
            aiSectDisciples = mapOf(
                "p1" to playerDisciples,
                "ai-1" to (1..10).map { aiDisciple("att$it") },
                "ai-2" to (1..5).map { aiDisciple("att2$it") }
            )
        }
        return NativeGameState(
            gameData = gameData,
            aiSectDisciples = gameData.aiSectDisciples,
            disciples = playerDisciples
        )
    }

    @Test
    fun `decidePlayerAttack matches Kotlin bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val snapshot = buildPlayerAttackSnapshot()
        val encoded = json.encodeToString(NativeGameState.serializer(), snapshot)

        NativeEngineFlag.mode = NativeEngineFlag.Mode.OFF
        try {
            val gameRng = GameRngManager().also { it.restoreStates(snapshot.gameData.rngStates) }
            aisRngManager = gameRng
            val expectedDecision = AISectAttackManager.decidePlayerAttack(snapshot.gameData)
            val expectedBattleState = gameRng.getRng(RngPartition.BATTLE).snapshot()

            assertEquals("C++ 导入失败", true,
                DiffRngBridge.nativeCoreImportState(encoded.encodeToByteArray()))
            val actualJson = DiffRngBridge.nativeCoreDecidePlayerAttack()
            val expectedType = when (expectedDecision) {
                is AISectAttackManager.PlayerAttackDecision.GenerateWarning -> "GENERATE_WARNING"
                is AISectAttackManager.PlayerAttackDecision.Skip -> "SKIP"
            }
            val actualType = if (actualJson.contains("GENERATE_WARNING")) "GENERATE_WARNING" else "SKIP"
            val actualBattleState = DiffRngBridge.nativeCoreRngSnapshotPartition(RngPartition.BATTLE.id)

            assertEquals("预警决策类型不一致", expectedType, actualType)
            assertEquals("BATTLE 分区终态不一致", expectedBattleState, actualBattleState)
            assertEquals("BATTLE 分区应消费（ai-1 门通过恰抽 1 次；ai-2 早退不消费）", false,
                snapshot.gameData.rngStates.getValue(RngPartition.BATTLE.id) == actualBattleState)
        } finally {
            NativeEngineFlag.mode = NativeEngineFlag.Mode.AUTHORITATIVE
        }
    }
}
