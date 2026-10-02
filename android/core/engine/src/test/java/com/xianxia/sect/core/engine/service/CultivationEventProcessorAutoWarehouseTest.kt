package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.config.ConfigLoader
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.engine.domain.disciple.DiscipleEquipmentManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleManualManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipGrowth
import com.xianxia.sect.core.model.EquipInstanceMeta
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.SecretRealmExplorationSession
import com.xianxia.sect.core.model.SecretRealmMemberState
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * processAutoFromWarehouse（天枢殿自动装备/学习）回归测试。
 * （B3 装备重构：自动装配候选源 = 弟子储物袋 equipment_instance 条目；
 *  宗门仓库装备堆叠语义已随堆叠轨退役）
 *
 * 覆盖 860bd2a4 引入的回归：Phase 2 预过滤误用弟子储物袋内容过滤，
 * 导致背包为空的弟子（常态）永不进入自动学习/自动装备处理——
 * 天枢殿"自动学习仓库中功法"因此完全失效。
 *
 * 本测试使用真实 DiscipleManualManager / DiscipleEquipmentManager 验证端到端行为，
 * 其余依赖 mock。
 *
 * 需要 Robolectric：DiscipleTables 组件表底层为 android.util.SparseArray，
 * 纯 JVM 环境中 put 为静默空操作（写入无效）。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class CultivationEventProcessorAutoWarehouseTest {

    @get:Rule
    val writeGuardRule = WriteGuardRule()

    // ── 测试基础设施 ────────────────────────────────────────────────

    private fun createProcessor(discipleService: DiscipleService = mock()): CultivationEventProcessor {
        val inventoryConfig = mock<InventoryConfig>()
        whenever(inventoryConfig.getMaxStackSize(any())).thenReturn(999)
        return CultivationEventProcessor(
            stateStore = mock(),
            spiritStoneWallet = mock(),
            inventorySystem = mock(),
            inventoryConfig = inventoryConfig,
            scopeProvider = mock(),
            discipleService = discipleService,
            cultivationCore = mock(),
            breakthroughHandler = mock(),
            cultivationSettlement = mock(),
            battleSystem = mock(),
            merchantAndRecruitService = mock(),
            caveExplorationProcessor = mock(),
            discipleLifecycleProcessor = mock(),
            diplomacyEventProcessor = mock(),
            diplomacyService = mock(),
            equipmentManager = DiscipleEquipmentManager(),
            manualManager = DiscipleManualManager(),
            autoBuyService = mock(),
            vassalService = mock(),
            disciplePurchaseService = mock(),
            aiSectBeastAttackProcessor = mock(),
            rngManager = mock(),
            secretRealmService = mock(),
            secretRealmAIProcessor = mock(),
            deathHandler = mock(),
            gameConfigProvider = GameConfigProvider(ConfigLoader({ null }))
        )
    }

    /** 自动策略配置（focused 关注 + 灵根数），对应 gameData 级 autoLearn/autoEquip 开关 */
    private data class AutoPolicy(
        val focused: Boolean = false,
        val rootCounts: Set<Int> = emptySet()
    )

    private fun state(
        disciples: List<Disciple>,
        learnPolicy: AutoPolicy = AutoPolicy(),
        equipPolicy: AutoPolicy = AutoPolicy(),
        manualStacks: List<ManualStack> = emptyList(),
        equipmentInstances: List<EquipmentInstance> = emptyList(),
        secretRealmMemberIds: List<String> = emptyList()
    ): MutableGameState {
        val tables = DiscipleTables()
        disciples.forEach { tables.insert(it) }
        return MutableGameState(
            gameData = GameData(
                autoLearnFromWarehouseFocused = learnPolicy.focused,
                autoLearnFromWarehouseRootCounts = learnPolicy.rootCounts,
                autoEquipFromWarehouseFocused = equipPolicy.focused,
                autoEquipFromWarehouseRootCounts = equipPolicy.rootCounts,
                secretRealmSession = SecretRealmExplorationSession(
                    members = secretRealmMemberIds.map {
                        SecretRealmMemberState(discipleId = it, isDead = false)
                    }
                )
            ),
            discipleTables = tables,
            equipmentInstances = EntityStore(equipmentInstances),
            manualStacks = EntityStore(manualStacks),
            manualInstances = EntityStore(),
            pills = EntityStore(),
            materials = EntityStore(),
            herbs = EntityStore(),
            seeds = EntityStore(),
            storageBags = EntityStore(),
                        battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )
    }

    /** 弟子：背包为空是常态（bug 核心场景） */
    private fun disciple(
        id: Int,
        spiritRootType: String = "火",
        followed: Boolean = false,
        realm: Int = 9,
        isAlive: Boolean = true,
        bag: List<StorageBagItem> = emptyList()
    ) = Disciple(
        id = id.toString(),
        name = "弟子$id",
        realm = realm,
        spiritRootType = spiritRootType,
        isAlive = isAlive,
        statusData = mapOf("followed" to if (followed) "true" else "false"),
        equipment = com.xianxia.sect.core.model.EquipmentSet(storageBagItems = bag)
    )

    private fun manualStack(id: String, quantity: Int = 2, minRealm: Int = 9) = ManualStack(
        id = id,
        name = "火云诀$id",
        type = ManualType.ATTACK,
        minRealm = minRealm,
        quantity = quantity
    )

    /** 实例轨装备（随 StorageBagItem 袋条目携带，参与自动装配） */
    private fun equipmentInstance(id: String) = EquipmentInstance(
        id = id,
        name = "铁剑$id",
        part = EquipmentSlot.HANDS,
        growth = EquipGrowth(
            affix = EquipAffixSet(mainStat = EquipStatValue(EquipStat.ATTACK, 10.0))
        ),
        meta = EquipInstanceMeta(rarity = 1, minRealm = 9)
    )

    /** 袋内装备实例条目（候选源：equipment_instance payload） */
    private fun bagWeapon(id: String, ownerDiscipleId: String = "1") = StorageBagItem(
        itemId = id, itemType = "equipment_instance", name = "铁剑$id",
        rarity = 1, quantity = 1,
        equipmentInstance = equipmentInstance(id).copy(ownerId = ownerDiscipleId)
    )

    // ── 核心回归：背包为空 + 仓库有堆叠 ──────────────────────────────

    @Test
    fun `自动学习 - 弟子背包为空仓库有功法堆叠 → 学会功法且堆叠数量减一`() {
        val s = state(
            disciples = listOf(disciple(id = 1)),
            learnPolicy = AutoPolicy(rootCounts = setOf(1)),
            manualStacks = listOf(manualStack(id = "m1", quantity = 2))
        )
        createProcessor().processAutoFromWarehouseRealtime(s)

        val learned = s.discipleTables.manualIds.getOrNull(1)
        assertEquals("背包为空的合格弟子必须学会功法", 1, learned?.size)
        assertEquals("仓库堆叠应减一", 1, s.manualStacks.all().single().quantity)
        val instance = s.manualInstances.all().single()
        assertEquals("1", instance.ownerId)
        assertTrue(instance.isLearned)
        assertTrue("弟子背包不应被仓库学习污染", s.discipleTables.storageBagItems.getOrDefault(1, emptyList()).isEmpty())
    }

    @Test
    fun `自动学习 - 仓库功法数量为1 → 学会后堆叠被删除新实例仍添加`() {
        val s = state(
            disciples = listOf(disciple(id = 1)),
            learnPolicy = AutoPolicy(rootCounts = setOf(1)),
            manualStacks = listOf(manualStack(id = "m1", quantity = 1))
        )
        createProcessor().processAutoFromWarehouseRealtime(s)

        assertEquals("数量为1的堆叠学完后应删除", 0, s.manualStacks.all().size)
        assertEquals(1, s.manualInstances.all().size)
        assertEquals(1, s.discipleTables.manualIds.getOrNull(1)?.size)
    }

    @Test
    fun `自动装备 - 袋内装备实例 → 装备且袋条目移除实例入表`() {
        // B3 实例轨：自动装配候选源 = 袋内 equipment_instance 条目（仓库堆叠语义退役）
        val s = state(
            disciples = listOf(disciple(id = 1, bag = listOf(bagWeapon("e1")))),
            equipPolicy = AutoPolicy(rootCounts = setOf(1))
        )
        createProcessor().processAutoFromWarehouseRealtime(s)

        // insert 时 handsIds 列为空字符串，装备后为实例 id，用非空字符串判定"已装备"
        assertFalse("袋内有装备实例的合格弟子必须自动装备", s.discipleTables.handsIds.getOrDefault(1, "").isEmpty())
        assertEquals("袋条目装配后应移除", 0,
            s.discipleTables.storageBagItems.getOrDefault(1, emptyList()).size)
        assertEquals("装配实例应入实例表", 1, s.equipmentInstances.all().size)
    }

    // ── 资格判定（预过滤正确性）──────────────────────────────────────

    @Test
    fun `自动学习 - 已关注弟子被处理 未关注且灵根数不匹配弟子不被处理`() {
        val s = state(
            disciples = listOf(disciple(id = 1, spiritRootType = "火"), disciple(id = 2, spiritRootType = "火,水")),
            learnPolicy = AutoPolicy(focused = true, rootCounts = setOf(2)),
            manualStacks = listOf(manualStack(id = "m1", quantity = 2))
        )
        createProcessor().processAutoFromWarehouseRealtime(s)

        // insert 时 manualIds 列为空列表而非 null，用 isEmpty 判定"未学习"
        assertTrue("未关注且灵根数不匹配(1根)的弟子不应学习", s.discipleTables.manualIds.getOrDefault(1, emptyList()).isEmpty())
        assertEquals("灵根数匹配(2根)的弟子应学习", 1, s.discipleTables.manualIds.getOrDefault(2, emptyList()).size)
    }

    @Test
    fun `自动学习 - focused未followed但灵根数匹配 → 回退灵根判定仍被处理`() {
        val s = state(
            disciples = listOf(disciple(id = 1, spiritRootType = "火,水")),
            learnPolicy = AutoPolicy(focused = true, rootCounts = setOf(2)),
            manualStacks = listOf(manualStack(id = "m1", quantity = 2))
        )
        createProcessor().processAutoFromWarehouseRealtime(s)

        assertEquals("focused分支失败后应回退灵根数判定", 1, s.discipleTables.manualIds.getOrNull(1)?.size)
    }

    @Test
    fun `自动学习 - focused已followed优先于灵根数不匹配`() {
        val s = state(
            disciples = listOf(disciple(id = 1, spiritRootType = "火,水,木", followed = true)),
            learnPolicy = AutoPolicy(focused = true, rootCounts = setOf(1)),
            manualStacks = listOf(manualStack(id = "m1", quantity = 2))
        )
        createProcessor().processAutoFromWarehouseRealtime(s)

        assertEquals("已关注弟子应优先于灵根数判定", 1, s.discipleTables.manualIds.getOrNull(1)?.size)
    }

    @Test
    fun `自动策略 - equip与learn资格分开判定且为或语义`() {
        val s = state(
            disciples = listOf(
                disciple(id = 1, spiritRootType = "火", bag = listOf(bagWeapon("e1"))),
                disciple(id = 2, spiritRootType = "火,水")
            ),
            equipPolicy = AutoPolicy(rootCounts = setOf(1)),
            learnPolicy = AutoPolicy(rootCounts = setOf(2)),
            manualStacks = listOf(manualStack(id = "m1", quantity = 2))
        )
        createProcessor().processAutoFromWarehouseRealtime(s)

        // id=1 仅匹配 equip（1根），id=2 仅匹配 learn（2根）
        // insert 时 handsIds 列默认为空字符串、manualIds 列为空列表，用 isEmpty 判定"未处理"
        assertFalse("1根弟子应只被装备", s.discipleTables.handsIds.getOrDefault(1, "").isEmpty())
        assertTrue("1根弟子不应被学习", s.discipleTables.manualIds.getOrDefault(1, emptyList()).isEmpty())
        assertTrue("2根弟子不应被装备", s.discipleTables.handsIds.getOrDefault(2, "").isEmpty())
        assertEquals("2根弟子应只被学习", 1, s.discipleTables.manualIds.getOrDefault(2, emptyList()).size)
    }

    @Test
    fun `自动学习 - 勾选全部灵根数 → 所有活弟子均被处理`() {
        val s = state(
            disciples = listOf(
                disciple(id = 1, spiritRootType = "火"),
                disciple(id = 2, spiritRootType = "火,水"),
                disciple(id = 3, spiritRootType = "火,水,木")
            ),
            learnPolicy = AutoPolicy(rootCounts = setOf(1, 2, 3, 4, 5)),
            manualStacks = listOf(manualStack(id = "m1", quantity = 3))
        )
        createProcessor().processAutoFromWarehouseRealtime(s)

        assertEquals(1, s.discipleTables.manualIds.getOrNull(1)?.size)
        assertEquals(1, s.discipleTables.manualIds.getOrNull(2)?.size)
        assertEquals(1, s.discipleTables.manualIds.getOrNull(3)?.size)
        assertEquals(0, s.manualStacks.all().size)
    }

    // ── 排除条件 ────────────────────────────────────────────────────

    @Test
    fun `自动学习 - 秘境探索中的弟子被跳过`() {
        val s = state(
            disciples = listOf(disciple(id = 1, followed = true)),
            learnPolicy = AutoPolicy(focused = true),
            manualStacks = listOf(manualStack(id = "m1", quantity = 2)),
            secretRealmMemberIds = listOf("1")
        )
        createProcessor().processAutoFromWarehouseRealtime(s)

        assertTrue("秘境探索中弟子不应被自动学习", s.discipleTables.manualIds.getOrDefault(1, emptyList()).isEmpty())
        assertEquals(2, s.manualStacks.all().single().quantity)
    }

    @Test
    fun `自动学习 - 已故弟子不被处理`() {
        val s = state(
            disciples = listOf(disciple(id = 1, followed = true, isAlive = false)),
            learnPolicy = AutoPolicy(focused = true),
            manualStacks = listOf(manualStack(id = "m1", quantity = 2))
        )
        createProcessor().processAutoFromWarehouseRealtime(s)

        assertTrue("已故弟子不应被自动学习", s.discipleTables.manualIds.getOrDefault(1, emptyList()).isEmpty())
    }

    @Test
    fun `自动策略 - 全部开关关闭 → 状态零变化`() {
        val s = state(
            disciples = listOf(disciple(id = 1, followed = true)),
            manualStacks = listOf(manualStack(id = "m1", quantity = 2)),
            equipmentInstances = listOf(equipmentInstance(id = "e1"))
        )
        createProcessor().processAutoFromWarehouseRealtime(s)

        assertEquals(2, s.manualStacks.all().single().quantity)
        assertEquals("实例轨零变化（无袋内候选不装配）", 1, s.equipmentInstances.all().size)
        assertTrue(s.manualInstances.all().isEmpty())
        assertTrue("未装配不应写槽位", s.discipleTables.handsIds.getOrDefault(1, "").isEmpty())
    }

    // ── 写回完整性 ──────────────────────────────────────────────────

    @Test
    fun `自动学习 - 学会后manualIds与manualInstances引用一致且触发生活日志`() {
        val discipleService = mock<DiscipleService>()
        val s = state(
            disciples = listOf(disciple(id = 1)),
            learnPolicy = AutoPolicy(rootCounts = setOf(1)),
            manualStacks = listOf(manualStack(id = "m1", quantity = 2))
        )
        createProcessor(discipleService = discipleService).processAutoFromWarehouseRealtime(s)

        val learnedId = s.discipleTables.manualIds.getOrNull(1)?.single()
        assertNotNull("弟子应学到功法", learnedId)
        val instance = s.manualInstances.all().find { it.id == learnedId }
        assertNotNull("manualIds 引用的实例必须存在于 manualInstances", instance)
        assertEquals("1", instance?.ownerId)
        assertEquals(1, s.discipleTables.isAlive[1])
        verify(discipleService).addLifeEvent(eq("1"), any())
    }
}
