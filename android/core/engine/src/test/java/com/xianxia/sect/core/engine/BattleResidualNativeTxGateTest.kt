package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.battle.CombatService
import com.xianxia.sect.core.event.DeathEvent
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.engine.system.InventorySystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import javax.inject.Provider

/**
 * BattleResidualNativeTxGateTest — 战斗域残差 native 臂门控降级守卫
 * （W4-C/C1 · w3-06 伤亡残差 1780）。
 *
 * 守护契约（双实现并行契约 + handover findings 13）：
 * - **降级等价**：flag OFF 与 AUTHORITATIVE（镜像服务缺失 / Provider 缺失）下
 *   `processBattleCasualties` 均回退 Kotlin 原实现，终态一致（标死三列 +
 *   道侣悲痛 + 年死亡计数）
 * - **镜像缺失不 NPE**：`Provider<GameEngineCore>` 惰性边在 `stateSyncServiceRef`
 *   未 stub（null）时可空判空降级（findings 13 在新注入面上的回归网）
 * - **事件面**：宗门外阵亡的 DeathEvent 广播在降级臂照常发射；阶段 3 的 Room
 *   生产槽残差照常执行
 *
 * C++ 侧判定序/零写入/抽取集语义由 GTest `battle_residual_tx_test.cpp` 与
 * `secret_realm_residual_tx_test.cpp` 逐位守护；1781/1782/1800/1801 为
 * GameEngine 扩展臂，与 1780 同构（tryExecuteNative 镜像通道 + 信封草稿回写），
 * 跨语言逐位一致由既有 47 个 Diff 对拍面 + 桌面 JNI 全量门禁守护。
 */
class BattleResidualNativeTxGateTest {

    // `getSlots()` 需显式 Answer：接口同时声明 `val slots: StateFlow` 与
    // `suspend fun getSlots(): List`，Mockito 按同名 getter 反射会误配
    // （progress.md「Mockito 同名 getter 陷阱」登记；PolicyNativeTxGateTest 同款）
    private val productionSlotRepository: ProductionSlotRepository =
        mockSmart<ProductionSlotRepository>().also { repo ->
            Mockito.doAnswer { emptyList<ProductionSlot>() }.`when`(repo).getSlots()
        }
    private val eventBus: EventBusPort = mock()
    private val inventorySystem: InventorySystem = mock()

    /** 双臂构造：flag OFF 臂无 Provider；AUTHORITATIVE 臂 Provider 指向 sync 未 stub 的 mockCore。 */
    private fun makeService(store: FakeAtomicStateStore, withProvider: Boolean): CombatService {
        val provider = if (withProvider) {
            val mockCore = mock<GameEngineCore>()  // stateSyncServiceRef 未 stub → null
            Provider { mockCore }
        } else {
            null
        }
        return CombatService(
            stateStore = store,
            productionSlotRepository = productionSlotRepository,
            eventBus = eventBus,
            inventorySystem = inventorySystem,
            gameEngineCoreProvider = provider
        )
    }

    /** 播种：宗门内最小弟子面（101 阵亡者 + 102 道侣），gameYear = 5 */
    private fun seed(store: FakeAtomicStateStore) {
        store.update {
            gameData = gameData.copy(gameYear = 5)
            discipleTables.addId(101)
            discipleTables.addId(102)
            discipleTables.isAlive[101] = 1
            discipleTables.isAlive[102] = 1
            // assembleAll 幽灵防御要求 isAlive + names + realms 三表齐全
            discipleTables.realms[101] = 5
            discipleTables.realms[102] = 5
            discipleTables.statuses[101] = DiscipleStatus.IDLE
            discipleTables.statuses[102] = DiscipleStatus.IDLE
            discipleTables.names[101] = "阵亡者"
            discipleTables.names[102] = "未亡人"
            discipleTables.partnerIds[102] = "101"
        }
    }

    @Test
    fun `casualty settle degrades identically across flag and provider edges`() {
        val offStore = FakeAtomicStateStore()
        val authWithProviderStore = FakeAtomicStateStore()
        val authNoProviderStore = FakeAtomicStateStore()
        seed(offStore)
        seed(authWithProviderStore)
        seed(authNoProviderStore)

        val off = makeService(offStore, withProvider = false)
        val authWithProvider = makeService(authWithProviderStore, withProvider = true)
        val authNoProvider = makeService(authNoProviderStore, withProvider = false)

        runTest {
            // flag OFF 臂（无 Provider）
            NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
                off.processBattleCasualties(deadMemberIds = setOf("101"), survivorHpMap = emptyMap())
            }
            // AUTHORITATIVE + Provider 镜像缺失（findings 13 回归网）
            NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
                authWithProvider.processBattleCasualties(deadMemberIds = setOf("101"), survivorHpMap = emptyMap())
            }
            // AUTHORITATIVE + 无 Provider（既有测试直构形态）
            NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
                authNoProvider.processBattleCasualties(deadMemberIds = setOf("101"), survivorHpMap = emptyMap())
            }
        }

        for ((label, store) in listOf(
            "off" to offStore,
            "auth-with-provider" to authWithProviderStore,
            "auth-no-provider" to authNoProviderStore
        )) {
            val tables = store.discipleTables
            // 标死经 [InventorySystem.materializeDiscipleBagAndMarkDead]（本测试 mock）；
            // 悲痛期为 Kotlin 路径自算写面——三臂一致
            assertEquals("$label 道侣新入悲痛（year+1）", 6, tables.griefEndYears[102])
            // 丧亲日志：Kotlin 回退臂由 applyGriefUpdatesToTables 直写一条
            assertEquals("$label 丧亲日志一条", 1, tables.lifeEvents[102]?.size ?: 0)
        }
        // 标死统一入口：三臂各调用一次（phase 2 B 段——Kotlin 路径语义）
        verify(inventorySystem, times(3)).materializeDiscipleBagAndMarkDead(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.eq(101),
            org.mockito.kotlin.eq(5),
            org.mockito.kotlin.eq("battle"))
        // 死亡事件广播：三臂各一次（阶段 1 平台面保留 Kotlin）
        verify(eventBus, times(3)).emitSync(DeathEvent("101", "阵亡者", "战斗阵亡"))
        // 阶段 3 Room 残差照常执行
        verify(productionSlotRepository, times(3)).getSlots()
        assertTrue(true)
    }
}
