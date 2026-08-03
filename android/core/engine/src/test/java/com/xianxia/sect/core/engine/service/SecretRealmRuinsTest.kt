package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.domain.exploration.SecretRealmChoiceResult
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SecretRealmBackpack
import com.xianxia.sect.core.model.SecretRealmEventParams
import com.xianxia.sect.core.model.SecretRealmEventRecord
import com.xianxia.sect.core.model.SecretRealmEventType
import com.xianxia.sect.core.model.SecretRealmMemberState
import com.xianxia.sect.core.model.SecretRealmOption
import com.xianxia.sect.core.model.SecretRealmState
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.`when`
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * 远古秘境"发现遗迹"事件测试——独立类（避免 SecretRealmServiceTest 超 detekt LargeClass 阈值）。
 *
 * 覆盖：直接离开/简单搜寻/仔细搜寻（含扣 2 体力）、50% 空无一物 / 发现秘宝（1~5 件灵品~宝品、
 * 2~7 件灵品~玄品）、秘宝入背包、子事件继续前进、体力耗尽自动结束秘宝不丢、
 * 篡改档 staminaCost clamp、同种子同动作序列确定性。
 */
@RunWith(RobolectricTestRunner::class)
class SecretRealmRuinsTest {

    private lateinit var rngManager: GameRngManager
    private lateinit var service: SecretRealmService
    private lateinit var inventorySystem: com.xianxia.sect.core.engine.system.InventorySystem

    @get:Rule
    val writeGuardRule = WriteGuardRule()

    @Before
    fun setUp() {
        rngManager = mock(GameRngManager::class.java)
        // 默认固定 RNG（resolveRuinsExplore 的 rng 参数非空校验；各用例可覆盖为 mockRng）
        `when`(rngManager.getRng(RngPartition.SECRET_REALM))
            .thenReturn(DeterministicRng.fromSeed(20260731L))
        inventorySystem = mock(com.xianxia.sect.core.engine.system.InventorySystem::class.java)
        // withTrackingSource 透传 block（否则结算的物品操作不执行）
        whenever(
            inventorySystem.withTrackingSource<Any>(any(), any())
        ).thenAnswer { inv ->
            inv.getArgument<() -> Any>(1).invoke()
        }
        service = SecretRealmService(
            rngManager = rngManager,
            battleSystem = mock(com.xianxia.sect.core.engine.domain.battle.BattleSystem::class.java),
            inventorySystem = inventorySystem,
            spiritStoneWallet = mock(com.xianxia.sect.core.wallet.SpiritStoneWallet::class.java)
        )
    }

    private fun createState(): MutableGameState = MutableGameState(
        gameData = GameData(),
        discipleTables = com.xianxia.sect.core.state.DiscipleTables(),
        equipmentStacks = EntityStore(emptyList()),
        equipmentInstances = EntityStore(emptyList()),
        manualStacks = EntityStore(emptyList()),
        manualInstances = EntityStore(emptyList()),
        pills = EntityStore(emptyList()),
        materials = EntityStore(emptyList()),
        herbs = EntityStore(emptyList()),
        seeds = EntityStore(emptyList()),
        storageBags = EntityStore(emptyList()),
        teams = emptyList(),
        battleLogs = emptyList(),
        isPaused = false,
        isLoading = false,
        isSaving = false
    )

    /** 构造活跃会话（4 名成员 + 秘境 + 指定当前事件与体力；backpack 预置背包） */
    private fun setupSession(
        state: MutableGameState,
        event: SecretRealmEventRecord,
        stamina: Int = 20,
        backpack: SecretRealmBackpack = SecretRealmBackpack()
    ) {
        state.gameData = state.gameData.copy(
            secretRealmState = SecretRealmState(id = "realm_1", spawnYear = 1),
            secretRealmSession = state.gameData.secretRealmSession.copy(
                secretRealmId = "realm_1",
                members = (1..4).map {
                    SecretRealmMemberState(
                        discipleId = it.toString(), name = "弟子$it", portraitRes = "",
                        realm = 5, realmName = "化神"
                    )
                },
                stamina = stamina,
                backpack = backpack,
                currentEvent = event
            )
        )
    }

    /** 预置探索背包（100 灵石 + 1 种子 + 1 材料），用于背包保留断言 */
    private fun presetBackpack(): SecretRealmBackpack = SecretRealmBackpack(
        spiritStones = 100L,
        materials = listOf(
            com.xianxia.sect.core.model.Material(
                id = "m1", name = "虎骨", rarity = 2,
                description = "", category = com.xianxia.sect.core.model.MaterialCategory.BEAST_BONE,
                quantity = 1
            )
        ),
        seeds = listOf(
            com.xianxia.sect.core.model.Seed(
                id = "s1", name = "聚灵草种", rarity = 2,
                description = "", growTime = 3, yield = 1, quantity = 1
            )
        )
    )

    /** 构造发现遗迹事件（直接离开 / 简单搜寻 / 仔细搜寻扣 2） */
    private fun ruinsEvent(): SecretRealmEventRecord = SecretRealmEventRecord(
        eventType = SecretRealmEventType.RUIN_EXPLORE.name,
        title = "发现遗迹",
        description = "发现未知遗迹可能存在未知宝物",
        options = listOf(
            SecretRealmOption("直接离开", ""),
            SecretRealmOption("简单搜寻", ""),
            SecretRealmOption("仔细搜寻", "", staminaCost = 2)
        )
    )

    /** 构造遗迹结果子事件（空无一物 / 发现秘宝） */
    private fun ruinsResultEvent(
        title: String,
        rewards: List<com.xianxia.sect.core.model.SecretRealmRewardItem> = emptyList()
    ): SecretRealmEventRecord = SecretRealmEventRecord(
        eventType = SecretRealmEventType.RUIN_RESULT.name,
        title = title,
        description = if (rewards.isEmpty()) "这里什么都没有" else "发现物品：测试",
        options = listOf(SecretRealmOption("继续前进", "")),
        params = SecretRealmEventParams(itemRewards = rewards)
    )

    /** 秘宝路径 RNG：nextDouble → 0.1（< 0.50 发现秘宝），nextInt 全 0（数量下限、装备、灵品、首件模板） */
    private fun stubTreasureRng(mockRng: DeterministicRng) {
        `when`(mockRng.nextDouble()).thenReturn(0.1)
        `when`(mockRng.nextInt(anyInt())).thenReturn(0, 0, 0, 0, 0, 0, 0, 0, 0)
    }

    /** 空无一物路径 RNG：nextDouble → 0.9（>= 0.50 空无一物，不消费 nextInt） */
    private fun stubEmptyRng(mockRng: DeterministicRng) {
        `when`(mockRng.nextDouble()).thenReturn(0.9)
    }

    // ── 发现遗迹事件 ──────────────────────────────────────────────────

    @Test
    fun `chooseOption - 发现遗迹直接离开扣 1 体力进衔接事件`() {
        val state = createState()
        setupSession(state, ruinsEvent())
        val result = service.chooseOption(0, state)
        assertTrue(result.isSuccess)
        val session = state.gameData.secretRealmSession
        assertEquals(19, session.stamina)
        assertEquals(
            SecretRealmEventType.BRIDGE.name,
            session.currentEvent?.eventType
        )
        assertTrue(session.resultMessage.contains("离开遗迹"))
        // 未搜寻，背包无变化
        assertEquals(0, session.backpack.totalItemCount)
    }

    @Test
    fun `chooseOption - 简单搜寻空无一物进入子事件且背包保留`() {
        val state = createState()
        setupSession(state, ruinsEvent(), backpack = presetBackpack())
        val mockRng = mock(DeterministicRng::class.java)
        stubEmptyRng(mockRng)
        `when`(rngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(mockRng)
        val result = service.chooseOption(1, state)
        assertTrue(result.isSuccess)
        val session = state.gameData.secretRealmSession
        assertEquals(19, session.stamina)
        assertEquals(SecretRealmEventType.RUIN_RESULT.name, session.currentEvent?.eventType)
        assertEquals("空无一物", session.currentEvent?.title)
        assertEquals("这里什么都没有", session.currentEvent?.description)
        assertEquals(listOf("继续前进"), session.currentEvent?.options?.map { it.label })
        // 搜寻不改变背包：预置的灵石/材料/种子全部保留（对抗性审查：空 resolution 覆盖）
        assertEquals(100L, session.backpack.spiritStones)
        assertEquals(2, session.backpack.totalItemCount)
        // 空无一物分支不消费 nextInt（仅 1 次 nextDouble 判定）
        verify(mockRng, never()).nextInt(anyInt())
    }

    @Test
    fun `chooseOption - 简单搜寻发现秘宝物品入背包`() {
        val state = createState()
        setupSession(state, ruinsEvent())
        val mockRng = mock(DeterministicRng::class.java)
        stubTreasureRng(mockRng)
        `when`(rngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(mockRng)
        val result = service.chooseOption(1, state)
        assertTrue(result.isSuccess)
        val session = state.gameData.secretRealmSession
        assertEquals(19, session.stamina)
        // 1 件装备（nextInt 全 0：count=1、type=equipment、rarity=灵品、首件模板）
        assertEquals(1, session.backpack.equipment.size)
        assertTrue(session.backpack.equipment[0].name.isNotEmpty())
        assertEquals(SecretRealmEventType.RUIN_RESULT.name, session.currentEvent?.eventType)
        assertEquals("发现秘宝", session.currentEvent?.title)
        assertTrue(session.currentEvent?.description?.startsWith("发现物品：") == true)
        assertEquals(1, session.currentEvent?.params?.itemRewards?.size)
    }

    @Test
    fun `chooseOption - 仔细搜寻扣 2 体力且获得 2 件物品`() {
        val state = createState()
        setupSession(state, ruinsEvent())
        val mockRng = mock(DeterministicRng::class.java)
        stubTreasureRng(mockRng)
        `when`(rngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(mockRng)
        val result = service.chooseOption(2, state)
        assertTrue(result.isSuccess)
        val session = state.gameData.secretRealmSession
        // 仔细搜寻扣 2：20 - 2 = 18
        assertEquals(18, session.stamina)
        // nextInt 全 0：countRoll=0 → count=2 件装备
        assertEquals(2, session.backpack.equipment.size)
        assertEquals(2, session.currentEvent?.params?.itemRewards?.size)
    }

    @Test
    fun `chooseOption - 秘宝子事件继续前进进入衔接事件且背包保留`() {
        val state = createState()
        setupSession(
            state,
            ruinsResultEvent(
                title = "发现秘宝",
                rewards = listOf(
                    com.xianxia.sect.core.model.SecretRealmRewardItem(
                        type = "equipment", itemId = "e1", name = "青锋剑", rarity = 2, quantity = 1
                    )
                )
            ),
            backpack = presetBackpack()
        )
        val result = service.chooseOption(0, state)
        assertTrue(result.isSuccess)
        val session = state.gameData.secretRealmSession
        assertEquals(SecretRealmEventType.BRIDGE.name, session.currentEvent?.eventType)
        assertTrue(session.resultMessage.contains("携秘宝"))
        // 子事件选项同样扣 1 体力
        assertEquals(19, session.stamina)
        // 继续前进不改变背包（对抗性审查：此前空 resolution.backpack 覆盖清空背包，
        // 体力耗尽场景会导致秘宝永久丢失）
        assertEquals(100L, session.backpack.spiritStones)
        assertEquals(2, session.backpack.totalItemCount)
        assertEquals(1, session.backpack.seeds.size)
    }

    @Test
    fun `chooseOption - 空无一物子事件继续前进进入衔接事件且背包保留`() {
        val state = createState()
        setupSession(state, ruinsResultEvent(title = "空无一物"), backpack = presetBackpack())
        val result = service.chooseOption(0, state)
        assertTrue(result.isSuccess)
        val session = state.gameData.secretRealmSession
        assertEquals(SecretRealmEventType.BRIDGE.name, session.currentEvent?.eventType)
        assertTrue(session.resultMessage.contains("空无一物"))
        assertEquals(100L, session.backpack.spiritStones)
        assertEquals(2, session.backpack.totalItemCount)
    }

    @Test
    fun `chooseOption - 衔接事件选方向不改变背包`() {
        val state = createState()
        setupSession(
            state,
            SecretRealmEventRecord(
                eventType = SecretRealmEventType.BRIDGE.name,
                title = "探索方向",
                description = "请选择探索方向",
                options = listOf(
                    SecretRealmOption("走左路", ""),
                    SecretRealmOption("直线前进", ""),
                    SecretRealmOption("走右路", "")
                )
            ),
            backpack = presetBackpack()
        )
        val result = service.chooseOption(0, state)
        assertTrue(result.isSuccess)
        val session = state.gameData.secretRealmSession
        // 选方向后背包保留（预存严重 bug 回归：此前战斗胜利的灵石/材料在选方向后被清空）
        assertEquals(100L, session.backpack.spiritStones)
        assertEquals(2, session.backpack.totalItemCount)
        assertEquals(1, session.backpack.materials.size)
        assertEquals(1, session.backpack.seeds.size)
    }

    @Test
    fun `chooseOption - 体力 1 选仔细搜寻自动结束且秘宝已入背包结算`() {
        val state = createState()
        setupSession(state, ruinsEvent(), stamina = 1)
        val mockRng = mock(DeterministicRng::class.java)
        stubTreasureRng(mockRng)
        `when`(rngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(mockRng)
        // 结算入仓 stub（秘宝为装备）
        whenever(inventorySystem.addEquipmentStack(any())).thenAnswer { inv ->
            DomainResult.Success(inv.getArgument<com.xianxia.sect.core.model.EquipmentStack>(0))
        }
        val result = service.chooseOption(2, state)
        // 1 - 2 → clamp 0 → 体力耗尽自动结束
        assertTrue(result.isSuccess)
        assertTrue((result as SecretRealmChoiceResult.Success).sessionEnded)
        assertFalse(state.gameData.secretRealmSession.isActive)
        // 秘宝先入背包再结束：结算调用 addEquipmentStack（nextInt 全 0 → 2 件装备，秘宝不丢）
        verify(inventorySystem, atLeastOnce()).addEquipmentStack(any())
    }

    // ── 篡改档防御 ────────────────────────────────────────────────────

    @Test
    fun `chooseOption - 选项体力消耗篡改为 0 或负数按 1 扣`() {
        for (badCost in listOf(0, -100)) {
            val state = createState()
            setupSession(
                state,
                ruinsEvent().copy(
                    options = listOf(
                        SecretRealmOption("直接离开", ""),
                        SecretRealmOption("简单搜寻", ""),
                        SecretRealmOption("仔细搜寻", "", staminaCost = badCost)
                    )
                )
            )
            val mockRng = mock(DeterministicRng::class.java)
            stubEmptyRng(mockRng)
            `when`(rngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(mockRng)
            service.chooseOption(2, state)
            // 0/负值 clamp 到 1：20 - 1 = 19，选项永不免费
            assertEquals(19, state.gameData.secretRealmSession.stamina)
        }
    }

    @Test
    fun `chooseOption - 选项体力消耗篡改为超大值按整管扣并正常结束不为负`() {
        val state = createState()
        setupSession(
            state,
            ruinsEvent().copy(
                options = listOf(
                    SecretRealmOption("直接离开", ""),
                    SecretRealmOption("简单搜寻", ""),
                    SecretRealmOption("仔细搜寻", "", staminaCost = 999999)
                )
            )
        )
        val mockRng = mock(DeterministicRng::class.java)
        stubEmptyRng(mockRng)
        `when`(rngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(mockRng)
        val result = service.chooseOption(2, state)
        // 超大值 clamp 到 20：20 - 20 = 0 → EXHAUSTED 自动结束
        assertTrue(result.isSuccess)
        assertTrue((result as SecretRealmChoiceResult.Success).sessionEnded)
        assertTrue(state.gameData.secretRealmSession.stamina >= 0)
        assertEquals(20, state.gameData.secretRealmSession.stamina.coerceAtMost(20))
    }

    @Test
    fun `chooseOption - 篡改档遗迹多选项按仔细搜寻语义分派不崩溃`() {
        val state = createState()
        setupSession(
            state,
            ruinsEvent().copy(
                options = ruinsEvent().options + SecretRealmOption("第四个选项", "")
            )
        )
        val mockRng = mock(DeterministicRng::class.java)
        stubEmptyRng(mockRng)
        `when`(rngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(mockRng)
        val result = service.chooseOption(3, state)
        // 界内多选项按仔细搜寻处理（>= 2 视为仔细搜寻），不崩溃
        assertTrue(result.isSuccess)
        assertEquals(SecretRealmEventType.RUIN_RESULT.name, state.gameData.secretRealmSession.currentEvent?.eventType)
    }

    @Test
    fun `chooseOption - 篡改档秘宝子事件空奖励仍可继续`() {
        val state = createState()
        // title 为"发现秘宝"但 itemRewards 为空（篡改档 title 与数据不一致）
        setupSession(state, ruinsResultEvent(title = "发现秘宝"))
        val result = service.chooseOption(0, state)
        // 文案判定用 itemRewards 不依赖 title，走空文案分支，无异常
        assertTrue(result.isSuccess)
        assertEquals(SecretRealmEventType.BRIDGE.name, state.gameData.secretRealmSession.currentEvent?.eventType)
    }

    // ── 确定性 ────────────────────────────────────────────────────────

    @Test
    fun `chooseOption - 相同种子相同动作序列结果完全一致`() {
        val seed = 20260803L
        val first = runRuinsFlow(seed)
        val second = runRuinsFlow(seed)
        assertEquals(first.first, second.first)
        assertEquals(first.second, second.second)
    }

    /** 跑固定动作序列：遗迹简单搜寻 → 结果子事件继续前进 → 衔接事件，返回最终状态 */
    private fun runRuinsFlow(seed: Long): Pair<SecretRealmEventRecord?, Map<String, Int>> {
        val rngMgr = mock(GameRngManager::class.java)
        `when`(rngMgr.getRng(RngPartition.SECRET_REALM)).thenReturn(DeterministicRng.fromSeed(seed))
        val s = SecretRealmService(
            rngManager = rngMgr,
            battleSystem = mock(com.xianxia.sect.core.engine.domain.battle.BattleSystem::class.java),
            inventorySystem = inventorySystem,
            spiritStoneWallet = mock(com.xianxia.sect.core.wallet.SpiritStoneWallet::class.java)
        )
        val state = createState()
        setupSession(state, ruinsEvent())
        s.chooseOption(1, state)
        if (state.gameData.secretRealmSession.currentEvent?.eventType ==
            SecretRealmEventType.RUIN_RESULT.name
        ) {
            s.chooseOption(0, state)
        }
        return state.gameData.secretRealmSession.currentEvent to
            backpackSummary(state.gameData.secretRealmSession.backpack)
    }

    /**
     * 背包内容摘要（物品名 → 总数量；忽略实例 id/UUID——确定性只保证内容与随机序列一致，
     * 实例 UUID 为幂等唯一标识，每次实例化必然不同）。
     */
    private fun backpackSummary(backpack: SecretRealmBackpack): Map<String, Int> {
        val summary = mutableMapOf<String, Int>()
        fun add(name: String, quantity: Int) {
            summary[name] = (summary[name] ?: 0) + quantity
        }
        if (backpack.spiritStones > 0L) {
            summary["灵石"] = backpack.spiritStones.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        backpack.equipment.forEach { add(it.name, it.quantity) }
        backpack.manuals.forEach { add(it.name, it.quantity) }
        backpack.pills.forEach { add(it.name, it.quantity) }
        backpack.materials.forEach { add(it.name, it.quantity) }
        backpack.herbs.forEach { add(it.name, it.quantity) }
        backpack.seeds.forEach { add(it.name, it.quantity) }
        return summary
    }
}
