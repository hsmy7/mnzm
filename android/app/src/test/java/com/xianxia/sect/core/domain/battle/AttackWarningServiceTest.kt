package com.xianxia.sect.core.domain.battle

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.AttackWarning
import com.xianxia.sect.core.model.WarningStage
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.GameStateStoreImpl
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.data.GameStateRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class AttackWarningServiceTest {

    private lateinit var service: AttackWarningService
    private lateinit var stateStore: GameStateStore
    private lateinit var scopeProvider: CoroutineScopeProvider

    @Before
    fun setUp() {
        scopeProvider = CoroutineScopeProvider()
        stateStore = GameStateStoreImpl(scopeProvider, mock(GameStateRepository::class.java))
        service = AttackWarningService(stateStore, scopeProvider)
        runBlocking { stateStore.reset() }
    }

    @After
    fun tearDown() {
        runBlocking { stateStore.reset() }
    }

    // --- 创建谴责预警 ---

    @Test
    fun `createDenunciationWarning generates correct structure`() {
        val warning = service.createDenunciationWarning("sect_1", "天剑宗")
        assertEquals("sect_1", warning.attackerSectId)
        assertEquals(WarningStage.DENUNCIATION, warning.stage)
        assertTrue(warning.warningId.isNotEmpty())
    }

    @Test
    fun `createDenunciationWarning attackMonth is 6 months ahead`() = runBlocking {
        stateStore.update {
            gameData = gameData.copy(gameYear = 10, gameMonth = 3)
        }
        val nowMonth = 10 * 12 + 3
        val warning = service.createDenunciationWarning("sect_1", "test")
        val expected = nowMonth + GameConfig.AIAttack.DENUNCIATION_BEFORE_ATTACK_MONTHS
        assertEquals(expected, warning.attackMonth)
    }

    // --- 推进为战书 ---

    @Test
    fun `advanceToWarDeclaration changes stage`() {
        val warning = AttackWarning("w1", "s1", "test", WarningStage.DENUNCIATION, 100, 90)
        val advanced = service.advanceToWarDeclaration(warning)
        assertEquals(WarningStage.WAR_DECLARATION, advanced.stage)
        assertEquals("w1", advanced.warningId)
    }

    // --- 添加预警 ---

    @Test
    fun `addWarning adds to active list`() = runBlocking {
        val warning = AttackWarning("w1", "s1", "test", WarningStage.DENUNCIATION, 100, 90)
        service.addWarning(warning)
        delay(100) // 等待 scope.launch 完成
        assertEquals(1, service.getActiveWarnings().size)
    }

    // --- 取消预警 ---

    @Test
    fun `cancelWarningsForAttacker removes matching`() = runBlocking {
        stateStore.update {
            gameData = gameData.copy(activeAttackWarnings = listOf(
                AttackWarning("w1", "sect_a", "A", WarningStage.DENUNCIATION, 100, 90),
                AttackWarning("w2", "sect_b", "B", WarningStage.DENUNCIATION, 110, 95)
            ))
        }
        val cancelled = service.cancelWarningsForAttacker("sect_a")
        assertEquals(1, cancelled)
        delay(100)
        assertEquals(1, service.getActiveWarnings().size)
    }

    // --- 到期预警 ---

    @Test
    fun `checkExpiredWarnings returns expired war declarations`() = runBlocking {
        val nowMonth = 150
        stateStore.update {
            gameData = gameData.copy(
                gameYear = nowMonth / 12, gameMonth = nowMonth % 12,
                activeAttackWarnings = listOf(
                    AttackWarning("w1", "s1", "A", WarningStage.WAR_DECLARATION, 145, 130),
                    AttackWarning("w2", "s2", "B", WarningStage.WAR_DECLARATION, 160, 140)
                )
            )
        }
        val expired = service.checkExpiredWarnings()
        assertEquals(1, expired.size)
        assertEquals("w1", expired[0].warningId)
    }

    // --- 推进阶段 ---

    @Test
    fun `advanceWarningsIfNeeded promotes denunciation to war`() = runBlocking {
        val nowMonth = 100
        stateStore.update {
            gameData = gameData.copy(
                gameYear = nowMonth / 12, gameMonth = nowMonth % 12,
                activeAttackWarnings = listOf(
                    AttackWarning("w1", "s1", "A", WarningStage.DENUNCIATION, 103, 97)
                )
            )
        }
        val advanced = service.advanceWarningsIfNeeded()
        assertEquals(1, advanced.size)
        assertEquals(WarningStage.WAR_DECLARATION, advanced[0].stage)
    }

    // --- 空闲状态 ---

    @Test
    fun `getActiveWarnings returns empty initially`() {
        assertTrue(service.getActiveWarnings().isEmpty())
    }
}
