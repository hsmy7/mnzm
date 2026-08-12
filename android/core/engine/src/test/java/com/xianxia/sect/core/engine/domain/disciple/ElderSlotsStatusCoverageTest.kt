package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.service.SecretRealmService
import com.xianxia.sect.core.engine.service.buildOccupiedSlotDiscipleIds
import com.xianxia.sect.core.engine.service.collectElderSlotDiscipleIds
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import kotlin.reflect.full.memberProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

/**
 * ElderSlots 槽位状态推导守卫测试（2026-08-10）。
 *
 * 玩家反馈"纳徒长老被自动排班调动"——根因：ElderSlots.recruitingElder 已登记
 * 在 DiscipleAssignmentGate，但 [DiscipleStatusService.buildSlotFlagsFor] 的
 * managing 分支与 [DiscipleStatusService] 的 buildManagingIds 均不含该字段 →
 * 纳徒长老被推导为 IDLE 并写回存储 → 月度自动排班/一键任命将其当作空闲弟子调动。
 *
 * 守卫三要素：
 * 1. **枚举/配置驱动** — 反射 ElderSlots 全部弟子字段（String 单槽 + 列表槽）
 *    为锚点，与显式覆盖清单双向校验，未来新增字段立即失败
 * 2. **显式标注排除项** — 当前 ElderSlots 无非弟子字段，反射全集即覆盖集
 * 3. **错误消息带操作指引** — 断言失败时提示需同步注册的推导入口
 */
/**
 * 必须 Robolectric：测试 4 端到端验证 [DiscipleStatusService.syncAllDiscipleStatuses]
 * 写回存储（statuses 组件表底层是 android.util.SparseArray）——纯 JVM 环境
 * mockable android.jar 的 SparseArray 是 stub（put 无操作/get 恒 null/
 * indexOfKey 恒 0），写入读回全失效，删除本注解会让测试 4 假死（NPE 于
 * deriveDiscipleStatus 的 currentStatus 参数）。见 DiscipleReflectionReleaseTest
 * 同款环境要求。
 */
@RunWith(RobolectricTestRunner::class)
class ElderSlotsStatusCoverageTest {

    // ── 测试 1：反射覆盖完整性 + 全字段推导非 IDLE ──

    @Test
    fun `all ElderSlots disciple fields covered by explicit samples`() {
        val reflective = elderSlotsDiscipleFieldNames()

        val missing = reflective - SLOT_SAMPLES.keys
        assertTrue(
            "新增 ElderSlots 字段 $missing 未在守卫测试覆盖——必须同步注册：" +
                "buildSlotFlagsFor 的 managing 分支 + buildManagingIds（否则该槽位弟子" +
                "被推导为 IDLE，被月度自动排班/一键任命当作空闲弟子调动）",
            missing.isEmpty()
        )
        val extra = SLOT_SAMPLES.keys - reflective
        assertTrue("守卫测试覆盖了不存在的 ElderSlots 字段 $extra——请核对", extra.isEmpty())
    }

    @Test
    fun `every ElderSlots field holding a disciple derives non-IDLE status`() {
        for ((fieldName, slots) in SLOT_SAMPLES) {
            val flags = DiscipleStatusService.buildSlotFlagsFor(
                discipleId = DISCIPLE_ID,
                data = GameData(elderSlots = slots)
            )
            val status = DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = flags
            )
            assertTrue(
                "ElderSlots.$fieldName 持人时必须推导非 IDLE（实际 $status）——" +
                    "buildSlotFlagsFor 未注册该字段，弟子会被当作空闲弟子调动",
                status != DiscipleStatus.IDLE
            )
        }
    }

    @Test
    fun `every ElderSlots field holding a disciple is collected by buildOccupiedSlotDiscipleIds`() {
        for ((fieldName, slots) in SLOT_SAMPLES) {
            val collected = buildOccupiedSlotDiscipleIds(
                data = GameData(elderSlots = slots),
                            )
            assertTrue(
                "ElderSlots.$fieldName 持人时 buildOccupiedSlotDiscipleIds 必须收集该弟子" +
                    "（实际 $collected）——月度自动排班互斥化（collectElderSlotDiscipleIds）" +
                    "未注册该字段，占用弟子会被当作空闲捕获制造双槽位",
                DISCIPLE_ID in collected
            )
        }
    }

    @Test
    fun `every ElderSlots field holding a disciple is collected by collectElderSlotDiscipleIds`() {
        for ((fieldName, slots) in SLOT_SAMPLES) {
            val collected = collectElderSlotDiscipleIds(slots)
            assertTrue(
                "ElderSlots.$fieldName 持人时 collectElderSlotDiscipleIds 必须收集该弟子" +
                    "（实际 $collected）——显式清单未注册该字段，占用弟子会被当作空闲捕获",
                DISCIPLE_ID in collected
            )
        }
    }

    @Test
    fun `recruitingElder specifically derives MANAGING`() {
        val flags = DiscipleStatusService.buildSlotFlagsFor(
            discipleId = DISCIPLE_ID,
            data = GameData(elderSlots = ElderSlots(recruitingElder = DISCIPLE_ID))
        )
        assertTrue("纳徒长老必须推导 managing=true", flags.managing)
        assertEquals(
            "纳徒长老推导状态必须为 MANAGING",
            DiscipleStatus.MANAGING,
            DiscipleStatusService.deriveDiscipleStatus(
                isAlive = true,
                currentStatus = DiscipleStatus.IDLE,
                slotFlags = flags
            )
        )
    }

    // ── 测试 2：syncAllDiscipleStatuses 收敛（端到端写回存储） ──

    @Test
    fun `syncAllDiscipleStatuses - recruitingElder converges to MANAGING`() {
        val store = FakeAtomicStateStore()
        store.update {
            discipleTables.addId(1)
            discipleTables.names[1] = "纳徒长老"
            discipleTables.statuses[1] = DiscipleStatus.IDLE
            discipleTables.isAlive[1] = 1
            gameData = gameData.copy(elderSlots = gameData.elderSlots.copy(recruitingElder = "1"))
        }
        val service = DiscipleStatusService(
            stateStore = store,
            discipleLifecycleManager = mock(DiscipleLifecycleManager::class.java),
            secretRealmService = mock(SecretRealmService::class.java)
        )

        service.syncAllDiscipleStatuses()

        assertEquals(
            "读档后 syncAllDiscipleStatuses 必须把纳徒长老收敛为 MANAGING",
            DiscipleStatus.MANAGING,
            store.persistentDiscipleTables.statuses[1]
        )
    }

    // ── fixture ──

    /** 反射收集 ElderSlots 全部弟子字段名（String 单槽 + DirectDiscipleSlot 列表槽） */
    private fun elderSlotsDiscipleFieldNames(): Set<String> {
        val stringFields = ElderSlots::class.memberProperties
            .filter { it.returnType.classifier == String::class }
            .map { it.name }
        val listFields = ElderSlots::class.memberProperties
            .filter { it.returnType.classifier == List::class }
            .map { it.name }
        return (stringFields + listFields).toSet()
    }

    companion object {
        private const val DISCIPLE_ID = "1"
        private val SAMPLE_SLOT = DirectDiscipleSlot(discipleId = DISCIPLE_ID, discipleName = "弟子A")

        /**
         * 显式覆盖清单：每个 ElderSlots 弟子字段名 → 持人后的实例。
         * 与反射收集双向校验（见测试 1），新增字段必须同步加入。
         */
        private val SLOT_SAMPLES: Map<String, ElderSlots> = mapOf(
            "viceSectMaster" to ElderSlots(viceSectMaster = DISCIPLE_ID),
            "herbGardenElder" to ElderSlots(herbGardenElder = DISCIPLE_ID),
            "alchemyElder" to ElderSlots(alchemyElder = DISCIPLE_ID),
            "forgeElder" to ElderSlots(forgeElder = DISCIPLE_ID),
            "outerElder" to ElderSlots(outerElder = DISCIPLE_ID),
            "preachingElder" to ElderSlots(preachingElder = DISCIPLE_ID),
            "lawEnforcementElder" to ElderSlots(lawEnforcementElder = DISCIPLE_ID),
            "innerElder" to ElderSlots(innerElder = DISCIPLE_ID),
            "qingyunPreachingElder" to ElderSlots(qingyunPreachingElder = DISCIPLE_ID),
            "recruitingElder" to ElderSlots(recruitingElder = DISCIPLE_ID),
            "preachingMasters" to ElderSlots(preachingMasters = listOf(SAMPLE_SLOT)),
            "lawEnforcementDisciples" to ElderSlots(lawEnforcementDisciples = listOf(SAMPLE_SLOT)),
            "qingyunPreachingMasters" to ElderSlots(qingyunPreachingMasters = listOf(SAMPLE_SLOT)),
            "herbGardenDisciples" to ElderSlots(herbGardenDisciples = listOf(SAMPLE_SLOT)),
            "alchemyDisciples" to ElderSlots(alchemyDisciples = listOf(SAMPLE_SLOT)),
            "forgeDisciples" to ElderSlots(forgeDisciples = listOf(SAMPLE_SLOT)),
            "spiritMineDeaconDisciples" to ElderSlots(spiritMineDeaconDisciples = listOf(SAMPLE_SLOT))
        )
    }
}
