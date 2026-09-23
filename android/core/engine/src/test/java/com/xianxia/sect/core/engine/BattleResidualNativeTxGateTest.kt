package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.battle.CombatService
import com.xianxia.sect.core.event.DeathEvent
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.engine.system.InventorySystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 守护契约（G07 后口径）：
 * - **玩家侧败北 = 重伤**：`processBattleCasualties` 只把败者气血钳到
 *   [com.xianxia.sect.core.GameConfig.Disciple.INJURED_HP] 并保持 `isAlive=1`
 *   ——不物化行囊、不清袋、不写悲痛期、不计年报死亡、不广播 DeathEvent、
 *   不清 Room 生产槽（重伤期间无任何限制）。
 * - **三臂降级等价**：flag OFF / AUTHORITATIVE+镜像缺失 / AUTHORITATIVE+无 Provider
 *   终态一致（`Provider<GameEngineCore>` 惰性边未 stub 时可空判空降级，findings 13 回归网）。
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
            // G07 重伤不变量：HP=1 且保持存活；不写死亡三元组、不计年报死亡
            assertEquals("$label 重伤 HP", GameConfig.Disciple.INJURED_HP, tables.currentHps[101])
            assertEquals("$label 保持存活", 1, tables.isAlive[101])
            assertFalse("$label 不写 deathYear", tables.deathYears.contains(101))
            // 重伤不传悲痛：道侣不进入哀悼期、无丧亲日志
            assertFalse("$label 道侣不进悲痛", tables.griefEndYears.contains(102))
            assertEquals("$label 无丧亲日志", 0, tables.lifeEvents.getOrNull(102)?.size ?: 0)
            assertEquals("$label 不计年报死亡", 0, store.gameData.value.annualDeceasedDisciples)
        }
        // 不物化行囊 / 不清 Room 生产槽 / 不广播死亡事件（三臂全零）
        verify(inventorySystem, times(0)).materializeDiscipleBagAndMarkDead(
            org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        verify(productionSlotRepository, times(0)).getSlots()
        verify(eventBus, times(0)).emitSync(DeathEvent("101", "阵亡者", "战斗阵亡"))
    }
}
