package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.AttackWarning
import com.xianxia.sect.core.model.WarningStage
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.GameStateStoreImpl
import com.xianxia.sect.di.ApplicationScopeProvider
import com.xianxia.sect.data.GameStateRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.Mockito.mock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttackWarningServiceTest {

    private lateinit var service: AttackWarningService
    private lateinit var stateStore: GameStateStore

    @Before
    fun setUp() {
        stateStore = GameStateStoreImpl(ApplicationScopeProvider(), mock(GameStateRepository::class.java))
        (stateStore as GameStateStoreImpl).unsafeAllowMainThreadUpdateForTest = true
        service = AttackWarningService(stateStore)
        runBlocking { stateStore.reset() }
    }

    @After
    fun tearDown() {
        (stateStore as GameStateStoreImpl).unsafeAllowMainThreadUpdateForTest = false
        runBlocking { stateStore.reset() }
    }

    // --- 生成"即将进攻"预警 ---

    @Test
    fun `createImminentAttackWarning generates correct structure`() {
        val warning = service.createImminentAttackWarning("sect_1", "天剑宗")
        assertEquals("sect_1", warning.attackerSectId)
        assertEquals("天剑宗", warning.attackerSectName)
        assertEquals(WarningStage.WAR_DECLARATION, warning.stage)
        assertTrue(warning.warningId.isNotEmpty())
    }

    @Test
    fun `createImminentAttackWarning attackMonth is 1 month ahead`() = runBlocking {
        stateStore.update {
            gameData = gameData.copy(gameYear = 10, gameMonth = 3)
        }
        val nowMonth = 10 * 12 + 3
        val warning = service.createImminentAttackWarning("sect_1", "test")
        val expected = nowMonth + GameConfig.AIAttack.WARNING_BEFORE_ATTACK_MONTHS
        assertEquals(expected, warning.attackMonth)
        assertEquals(nowMonth, warning.createdAtMonth)
    }

    // --- 旧档预警收敛 ---

    @Test
    fun `normalizeImminentWarningsSync converges legacy denunciation to war`() = runBlocking {
        val nowMonth = 100
        stateStore.update {
            gameData = gameData.copy(
                gameYear = nowMonth / 12, gameMonth = nowMonth % 12,
                activeAttackWarnings = listOf(
                    AttackWarning("w1", "s1", "A", WarningStage.DENUNCIATION, nowMonth + 6, nowMonth)
                )
            )
        }
        stateStore.update {
            service.normalizeImminentWarningsSync(this)
        }
        val converged = stateStore.gameData.value.activeAttackWarnings
        assertEquals(1, converged.size)
        assertEquals(WarningStage.WAR_DECLARATION, converged[0].stage)
        assertEquals(
            nowMonth + GameConfig.AIAttack.WARNING_BEFORE_ATTACK_MONTHS,
            converged[0].attackMonth
        )
    }

    @Test
    fun `normalizeImminentWarningsSync converges legacy war declaration not yet due`() = runBlocking {
        val nowMonth = 100
        stateStore.update {
            gameData = gameData.copy(
                gameYear = nowMonth / 12, gameMonth = nowMonth % 12,
                activeAttackWarnings = listOf(
                    AttackWarning("w1", "s1", "A", WarningStage.WAR_DECLARATION, nowMonth + 2, nowMonth - 5)
                )
            )
        }
        stateStore.update {
            service.normalizeImminentWarningsSync(this)
        }
        val converged = stateStore.gameData.value.activeAttackWarnings[0]
        assertEquals(
            nowMonth + GameConfig.AIAttack.WARNING_BEFORE_ATTACK_MONTHS,
            converged.attackMonth
        )
    }

    @Test
    fun `normalizeImminentWarningsSync keeps already-due warnings untouched`() = runBlocking {
        val nowMonth = 100
        stateStore.update {
            gameData = gameData.copy(
                gameYear = nowMonth / 12, gameMonth = nowMonth % 12,
                activeAttackWarnings = listOf(
                    AttackWarning("w1", "s1", "A", WarningStage.WAR_DECLARATION, nowMonth, nowMonth - 3)
                )
            )
        }
        stateStore.update {
            service.normalizeImminentWarningsSync(this)
        }
        // 已到期预警保持原 attackMonth（本批结算立即执行，不得推迟）
        val kept = stateStore.gameData.value.activeAttackWarnings[0]
        assertEquals(WarningStage.WAR_DECLARATION, kept.stage)
        assertEquals(nowMonth, kept.attackMonth)
    }

    @Test
    fun `normalizeImminentWarningsSync is idempotent on new-style warnings`() = runBlocking {
        val nowMonth = 100
        val newStyle = AttackWarning(
            "w1", "s1", "A", WarningStage.WAR_DECLARATION,
            nowMonth + GameConfig.AIAttack.WARNING_BEFORE_ATTACK_MONTHS, nowMonth
        )
        stateStore.update {
            gameData = gameData.copy(
                gameYear = nowMonth / 12, gameMonth = nowMonth % 12,
                activeAttackWarnings = listOf(newStyle)
            )
        }
        stateStore.update {
            service.normalizeImminentWarningsSync(this)
        }
        // 幂等：字段不变
        assertEquals(newStyle, stateStore.gameData.value.activeAttackWarnings[0])
    }

    @Test
    fun `normalizeImminentWarningsSync empty list is no-op`() = runBlocking {
        stateStore.update {
            gameData = gameData.copy(gameYear = 5, gameMonth = 1)
        }
        stateStore.update {
            service.normalizeImminentWarningsSync(this)
        }
        assertTrue(stateStore.gameData.value.activeAttackWarnings.isEmpty())
    }

    // --- 添加预警 ---

    @Test
    fun `addWarningSync adds to active list`() = runBlocking {
        val warning = AttackWarning("w1", "s1", "test", WarningStage.WAR_DECLARATION, 100, 90)
        stateStore.update {
            service.addWarningSync(this, warning)
        }
        assertEquals(1, stateStore.gameData.value.activeAttackWarnings.size)
        assertEquals("w1", stateStore.gameData.value.activeAttackWarnings[0].warningId)
    }

    @Test
    fun `addWarningSync appends to existing list`() = runBlocking {
        stateStore.update {
            gameData = gameData.copy(activeAttackWarnings = listOf(
                AttackWarning("w1", "s1", "A", WarningStage.WAR_DECLARATION, 100, 90)
            ))
        }
        stateStore.update {
            service.addWarningSync(this, AttackWarning("w2", "s2", "B", WarningStage.WAR_DECLARATION, 110, 95))
        }
        assertEquals(2, stateStore.gameData.value.activeAttackWarnings.size)
        assertEquals("w2", stateStore.gameData.value.activeAttackWarnings[1].warningId)
    }

    // --- 空闲状态 ---

    @Test
    fun `activeAttackWarnings returns empty initially`() = runBlocking {
        assertTrue(stateStore.gameData.value.activeAttackWarnings.isEmpty())
    }
}
