package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SocialData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.mockSmart
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * L1c 等价性安全网：年俸计划列直读重构（assembleAll → 列级过滤）前先写本测试。
 *
 * 参照实现内联旧算法（assembleAll 过滤），与生产 [CultivationSettlement.calculateSalaryPlan]
 * 逐位对比（eligibleSalaries 键值对 + totalRequired + null 语义）。
 * 重构后本测试仍绿 = 等价性成立。
 *
 * 等价性依据：`assembleAll()` 的 Disciple 字段即列数据（isAlive = isAlive.getOrDefault(id,1)==1、
 * realm = realms.getOrDefault(id,9)），过滤谓词（isAlive + enabledConfig[realm] + salary>0）
 * 仅依赖这两个字段与配置表 ⇒ 列直读与 assembleAll 过滤逐位一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
class SalaryPlanColumnEquivalenceTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var tables: DiscipleTables
    private lateinit var mockStore: GameStateStore
    private lateinit var settlement: CultivationSettlement

    @Before
    fun setUp() {
        val store = FakeAtomicStateStore()
        mockStore = store
        tables = store.discipleTables

        settlement = CultivationSettlement(
            stateStore = mockStore,
            scopeProvider = mockSmart(CoroutineScopeProvider::class.java),
            spiritStoneWallet = mockSmart(SpiritStoneWallet::class.java),
            lawEnforcementProcessor = mockSmart(LawEnforcementProcessor::class.java),
            gameConfigProvider = mockSmart(GameConfigProvider::class.java)
        )
    }

    // ==================== 参照实现：旧 assembleAll 算法（与重构前逐行一致） ====================

    private fun referenceCalculateSalaryPlan(): CultivationSettlement.SalaryPlan? {
        val data = mockStore.gameData.value
        val salaryConfig = data.yearlySalary
        val enabledConfig = data.yearlySalaryEnabled
        val eligible = tables.assembleAll()
            .filter { it.isAlive && enabledConfig[it.realm] == true }
            .map { it to (salaryConfig[it.realm]?.toLong() ?: 0L) }
            .filter { it.second > 0L }
        val totalRequired = eligible.sumOf { it.second }
        if (totalRequired <= 0L) return null
        return CultivationSettlement.SalaryPlan(
            eligibleSalaries = eligible.associate { it.first.id to it.second },
            totalRequired = totalRequired
        )
    }

    private fun assertEquivalent(desc: String) {
        val reference = referenceCalculateSalaryPlan()
        val actual = settlement.calculateSalaryPlan()
        assertEquals(
            "$desc：参照与列直读应逐位等价（ref=$reference, act=$actual）",
            reference, actual
        )
    }

    // ==================== 辅助 ====================

    private fun insertDisciple(id: Int, realm: Int, name: String = "弟子$id") {
        val disciple = Disciple(
            id = id.toString(),
            name = name,
            realm = realm,
            realmLayer = 3,
            status = DiscipleStatus.IDLE,
            social = SocialData()
        )
        tables.insert(disciple)
        tables.isAlive[id] = 1
    }

    private fun setSalaryConfig(config: Map<Int, Int>, enabled: Map<Int, Boolean>) {
        mockStore.update {
            gameData = gameData.copy(
                yearlySalary = config,
                yearlySalaryEnabled = enabled
            )
        }
    }

    // ==================== 用例 ====================

    @Test
    fun `salary plan matches assembly for mixed realms with enabled config`() {
        // 境界 0..9 中部分配置俸禄，部分未启用
        insertDisciple(1, realm = 9)   // 炼气（配置 100）
        insertDisciple(2, realm = 6)   // 配置 500
        insertDisciple(3, realm = 2)   // 未启用
        insertDisciple(4, realm = 0)   // 未配置（map 缺失 key）
        setSalaryConfig(
            config = mapOf(9 to 100, 6 to 500, 2 to 1000),
            enabled = mapOf(9 to true, 6 to true, 2 to false)
        )
        assertEquivalent("多境界混合：启用/未启用/未配置")
    }

    @Test
    fun `zero salary realm is excluded`() {
        insertDisciple(1, realm = 9)
        insertDisciple(2, realm = 8)
        insertDisciple(3, realm = 7)
        setSalaryConfig(
            config = mapOf(9 to 0, 8 to 300, 7 to 200),
            enabled = mapOf(9 to true, 8 to true, 7 to true)
        )
        assertEquivalent("零俸境界（realm 9）被排除")
    }

    @Test
    fun `dead disciple is excluded`() {
        insertDisciple(1, realm = 9)
        insertDisciple(2, realm = 8)
        tables.isAlive[2] = 0 // 死亡
        setSalaryConfig(
            config = mapOf(9 to 100, 8 to 300),
            enabled = mapOf(9 to true, 8 to true)
        )
        assertEquivalent("死亡弟子被排除")
    }

    @Test
    fun `empty name disciple is excluded by both paths`() {
        insertDisciple(1, realm = 9)
        insertDisciple(2, realm = 8, name = "") // assembleAll 空名跳过
        setSalaryConfig(
            config = mapOf(9 to 100, 8 to 300),
            enabled = mapOf(9 to true, 8 to true)
        )
        assertEquivalent("空名弟子两路径均排除")
    }

    @Test
    fun `no disciples returns null`() {
        setSalaryConfig(
            config = mapOf(9 to 100),
            enabled = mapOf(9 to true)
        )
        assertEquivalent("无弟子 → null")
    }

    @Test
    fun `all excluded returns null`() {
        insertDisciple(1, realm = 9)
        insertDisciple(2, realm = 8)
        // 全部零俸 + 未启用
        setSalaryConfig(
            config = mapOf(9 to 0, 8 to 0),
            enabled = mapOf(9 to false, 8 to false)
        )
        assertEquivalent("全被排除 → null")
    }

    @Test
    fun `total required accumulates across multiple disciples`() {
        // 同境界 2 人：每人 300，合计 600
        insertDisciple(1, realm = 6)
        insertDisciple(2, realm = 6)
        insertDisciple(3, realm = 9)
        setSalaryConfig(
            config = mapOf(6 to 300, 9 to 100),
            enabled = mapOf(6 to true, 9 to true)
        )
        assertEquivalent("多弟子累计 totalRequired")
    }
}
