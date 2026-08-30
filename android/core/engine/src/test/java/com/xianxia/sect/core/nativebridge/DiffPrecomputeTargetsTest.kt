package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.core.model.WorldLevel
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffPrecomputeTargetsTest — AI 兽袭目标预计算跨语言差分对拍（批 13-1）。
 *
 * 守护目标：C++ `gamecore::system::detail::precomputeTargets`（经
 * [DiffRngBridge.nativeCorePrecomputeTargets] 直调）与 Kotlin
 * `AISectBeastAttackProcessor.precomputeTargets` 语义**逐位一致**：
 * 妖兽筛选（type==BEAST/未击败/未过期/未被锁定 + id 升序）、AI 候选距离
 * 排序（Float 精度 + 稳定序）、门控序（冷却→弟子数→战力）、EXPLORATION
 * 抽取序（每妖兽每合格宗门恰 1 次）、冷却记录/过期清理、targets 写入。
 *
 * 直调设计：不经过完整月变管线——规避步骤 4e moveBeasts 的 EXPLORATION
 * 干扰，抽取序仅含本函数（Kotlin 对拍臂的 SystemManager 未装
 * ExplorationSystem，完整管线场景妖兽非空会双端消费不一致）。
 * 生产路径经 runMonthSettlement 步骤 3 接线（month_settlement.h）。
 *
 * 场景（一次对拍覆盖全路径）：
 * - beast-a（beastMaxHp=100 → beastPower=400 << aiPower → prob=0.9，
 *   种子命中）→ targets[beast-a]=[ai-1]
 * - beast-b（beastMaxHp=100000 → beastPower=400000 > aiPower → 记冷却跳过，
 *   零抽取）
 * - beast-locked（被玩家锁定 → 排除不评估）
 * - ai-1（近，10 存活弟子）；ai-2（远，预置冷却 14 >= 绝对月 14 → 跳过）；
 *   ai-3（更远，5 弟子 < 10 → 跳过）
 * - stale 冷却（0 < 14-12 → 过期清理）
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffPrecomputeTargetsTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private companion object {
        const val SEED = 20260902L

        /** 月变时刻（1 年 2 月）绝对月 = 14 */
        const val ABSOLUTE_MONTH = 14

        /** 跳过攻击冷却期（Kotlin SKIP_COOLDOWN_MONTHS = 12） */
        const val SKIP_COOLDOWN_MONTHS = 12
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

    /** 妖兽关卡（type=BEAST，未击败未过期） */
    private fun beastLevel(id: String, x: Float, y: Float, maxHp: Int) = WorldLevel(
        id = id, type = LevelType.BEAST, x = x, y = y,
        expiryYear = 99, expiryMonth = 12,
        beastMaxHp = maxHp
    )

    /** 场景快照（gameData 侧 @Transient 字段 + NativeGameState 顶层双通道） */
    private fun buildSnapshot(): NativeGameState {
        val gameData = GameData(
            gameYear = 1, gameMonth = 2, gamePhase = 0,
            spiritStones = 10000L
        ).apply {
            rngStates = initialRngStates(SEED)
            // 世界关卡：beast-a 命中（prob=0.9）、beast-b 战力不足记冷却、
            // beast-locked 被锁定排除
            worldLevels = listOf(
                beastLevel("beast-a", 100f, 100f, 100),
                beastLevel("beast-b", 300f, 100f, 100000),
                beastLevel("beast-locked", 500f, 100f, 100)
            )
            // AI 宗门：p1 玩家 + ai-1 近（10 弟子）+ ai-2 远（预置冷却）+
            // ai-3 更远（5 弟子不足）
            worldMapSects = listOf(
                WorldSect(id = "p1", name = "青云宗", isPlayerSect = true, x = 0f, y = 0f),
                WorldSect(id = "ai-1", name = "青岚宗", x = 200f, y = 100f),
                WorldSect(id = "ai-2", name = "赤水宗", x = 1500f, y = 100f),
                WorldSect(id = "ai-3", name = "玄水宗", x = 1600f, y = 100f)
            )
            // @Transient 瞬态字段（批 13-1：C++ 快照协议顶层承载）
            aiSectDisciples = mapOf(
                "ai-1" to (1..10).map { aiDisciple("a$it") },
                "ai-3" to (1..5).map { aiDisciple("c$it") }
            )
            aiSectBeastSkipCooldowns = mapOf(
                "ai-2" to ABSOLUTE_MONTH,          // >= 绝对月 → 跳过
                "stale" to (ABSOLUTE_MONTH - SKIP_COOLDOWN_MONTHS - 1)  // 过期 → 清理
            )
            lockedBeastIds = setOf("beast-locked")
        }
        return NativeGameState(
            gameData = gameData,
            // 批 10-4 + 批 13-1：@Transient 字段经顶层承载（与 C++ 导出键对齐）
            aiSectDisciples = gameData.aiSectDisciples,
            aiSectBeastDirectTargets = gameData.aiSectBeastDirectTargets,
            aiSectBeastSkipCooldowns = gameData.aiSectBeastSkipCooldowns,
            lockedBeastIds = gameData.lockedBeastIds
        )
    }

    /** Kotlin 基准侧：真实 AISectBeastAttackProcessor.precomputeTargets */
    private fun advanceKotlinSide(snapshot: NativeGameState): NativeGameState {
        // precomputeTargets 的 ManualDatabase.isInitialized 门——空表初始化即可
        ManualDatabase.initializeWithManuals(mapOf())
        val store = FakeGameStateStore().also {
            it.gameDataValue = snapshot.gameData
        }
        val gameRng = GameRngManager().also { it.restoreStates(snapshot.gameData.rngStates) }
        val processor = AISectBeastAttackProcessor(
            stateStore = store,
            battleSystem = mockSmart(),
            rngManager = gameRng,
            encounterBattleService = mockSmart()
        )
        store.update {
            processor.precomputeTargets(this, gameData.gameYear, gameData.gameMonth)
        }
        val result = store.gameDataValue.copy(
            rngStates = gameRng.exportStates().toMutableMap()
        )
        return NativeGameState(
            gameData = result,
            aiSectDisciples = result.aiSectDisciples,
            aiSectBeastDirectTargets = result.aiSectBeastDirectTargets,
            aiSectBeastSkipCooldowns = result.aiSectBeastSkipCooldowns,
            lockedBeastIds = result.lockedBeastIds
        )
    }

    @Test
    fun `precompute targets matches Kotlin bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val snapshot = buildSnapshot()
        val encoded = json.encodeToString(NativeGameState.serializer(), snapshot)

        val expected = advanceKotlinSide(snapshot)

        // ── C++ 被测侧 ──
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(
            encoded.encodeToByteArray()))
        DiffRngBridge.nativeCorePrecomputeTargets()
        val actual = json.decodeFromString(
            NativeGameState.serializer(),
            DiffRngBridge.nativeCoreExportState().decodeToString()
        )

        // ── 显式断言（可读性优先；全量结构对拍兜底）──
        // 命中：beast-a 写入 [ai-1]（唯一合格宗门——ai-2 冷却跳过、ai-3 弟子不足）
        assertEquals(
            "beast-a 应命中 ai-1",
            listOf("ai-1"), actual.aiSectBeastDirectTargets?.get("beast-a")
        )
        // 战力不足：beast-b 无目标写入
        assertNull("beast-b 不应有目标", actual.aiSectBeastDirectTargets?.get("beast-b"))
        // 锁定妖兽排除：不评估不写入
        assertNull("锁定妖兽不应有目标", actual.aiSectBeastDirectTargets?.get("beast-locked"))
        // 冷却：ai-1 记录（beast-b 战力不足记冷却 14）、ai-2 预置保留、stale 清理
        assertEquals("ai-1 应记录跳过冷却", ABSOLUTE_MONTH,
            actual.aiSectBeastSkipCooldowns?.get("ai-1"))
        assertEquals("ai-2 预置冷却应保留", ABSOLUTE_MONTH,
            actual.aiSectBeastSkipCooldowns?.get("ai-2"))
        assertNull("stale 冷却应被清理", actual.aiSectBeastSkipCooldowns?.get("stale"))
        // 锁定妖兽集合保持（纯运行态字段，导入导出往返一致）
        assertEquals("锁定妖兽集合应保留", setOf("beast-locked"), actual.lockedBeastIds)

        // EXPLORATION 分区终态逐位一致（beast-a 的 ai-1 恰抽 1 次）
        assertEquals("EXPLORATION 分区终态不一致",
            expected.gameData.rngStates[RngPartition.EXPLORATION.id],
            actual.gameData.rngStates[RngPartition.EXPLORATION.id])
    }
}
