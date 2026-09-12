package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.ElderSlotType
import org.junit.Assert.*
import org.junit.Test

/**
 * 自动守卫：新增 [ElderSlotType] 枚举值时，若忘记同步更新相关函数，测试将失败。
 *
 * ## 新增长老类型的必改清单
 *
 * 当你在 [ElderSlotType] 添加了新的枚举值，测试会在此文件中报错。
 * 请同步更新以下 5 处：
 *
 * 1. [ElderSlotType.key] — 添加新的 key 映射
 * 2. [com.xianxia.sect.core.domain.disciple.DiscipleAssignmentGate.scanElderSlots] — 读档重建时扫描新长老类型
 * 3. [com.xianxia.sect.core.domain.disciple.DiscipleSlotCleanup.clearAllSlots] — 死亡/释放时清理
 * 4. [com.xianxia.sect.core.usecase.ElderManagementUseCase.productionElderTypes] — 若新类型影响生产效率
 * 5. [com.xianxia.sect.core.usecase.ElderManagementUseCase.cultivationElderTypes] — 若新类型影响修炼速度
 * 6. 本测试文件 — 将新 [ElderSlotType] 加入下方对应的检查集合
 */
class ElderSlotTypeCoverageTest {

    // ==================== key 映射完整性 ====================

    @Test
    fun `all ElderSlotType values have non-empty key mapping`() {
        ElderSlotType.values().forEach { type ->
            assertTrue(
                "ElderSlotType.${type.name}.key 返回了空字符串！请在 ElderSlotType.kt 中添加 key 映射",
                type.key.isNotEmpty()
            )
        }
    }

    // ==================== productionElderTypes 覆盖检查 ====================

    /**
     * 影响生产效率的长老类型 — 变更后需触发 [checkpointAllProduction]。
     * 对应 [ElderManagementUseCase.productionElderTypes]。
     */
    private val productionElderTypes = setOf(
        ElderSlotType.ALCHEMY,      // 炼丹效率
        ElderSlotType.FORGE,        // 锻造效率
        ElderSlotType.HERB_GARDEN,  // 灵植效率
    )

    /**
     * 不属于 productionElderTypes 但故意排除的类型。
     * 这些长老类型不影响生产效率，无需 checkpoint。
     */
    private val excludedFromProduction = setOf(
        ElderSlotType.VICE_SECT_MASTER,        // 副宗主，无生产职能
        ElderSlotType.OUTER_ELDER,              // 外门长老，无生产职能
        ElderSlotType.PREACHING,                // 讲道长老，影响修炼而非生产
        ElderSlotType.LAW_ENFORCEMENT,          // 执法长老，无生产职能
        ElderSlotType.INNER_ELDER,              // 内门长老，影响修炼而非生产
        ElderSlotType.RECRUITING,               // 招募长老，无生产职能
        ElderSlotType.CLOUD_PREACHING,          // 云游讲道长老，影响修炼而非生产
    )

    @Test
    fun `all ElderSlotType values are classified for productionElderTypes`() {
        val allTypes = ElderSlotType.values().toSet()
        val covered = productionElderTypes + excludedFromProduction
        val missing = allTypes - covered

        assertTrue(
            """
            |新增 ElderSlotType 未在 productionElderTypes 分类中覆盖！
            |
            |以下类别需要更新 ElderManagementUseCase.productionElderTypes 或加入排除列表：
            |  $missing
            |
            |操作指引：
            |  1. 如果新类型影响生产效率（炼丹/锻造/灵植）→ 加入 productionElderTypes
            |  2. 如果不影响生产 → 加入本测试的 excludedFromProduction 集合并注明原因
            """.trimMargin(),
            missing.isEmpty()
        )
    }

    // ==================== cultivationElderTypes 覆盖检查 ====================

    /**
     * 影响修炼速度的长老类型 — 变更后需触发 [checkpointAllDisciples]。
     * 对应 [ElderManagementUseCase.cultivationElderTypes]。
     */
    private val cultivationElderTypes = setOf(
        ElderSlotType.PREACHING,        // 传道加成
        ElderSlotType.CLOUD_PREACHING,  // 云游讲道加成
        ElderSlotType.INNER_ELDER,      // 内门指导加成
        ElderSlotType.OUTER_ELDER,      // 外门指导加成
        ElderSlotType.VICE_SECT_MASTER, // 副宗主加成
    )

    /**
     * 不属于 cultivationElderTypes 但故意排除的类型。
     * 这些长老类型不影响修炼速度，无需 checkpoint。
     */
    private val excludedFromCultivation = setOf(
        ElderSlotType.ALCHEMY,            // 炼丹长老，影响生产而非修炼
        ElderSlotType.FORGE,              // 锻造长老，影响生产而非修炼
        ElderSlotType.HERB_GARDEN,        // 灵植长老，影响生产而非修炼
        ElderSlotType.LAW_ENFORCEMENT,    // 执法长老，无修炼加成
        ElderSlotType.RECRUITING,         // 招募长老，无修炼加成
    )

    @Test
    fun `all ElderSlotType values are classified for cultivationElderTypes`() {
        val allTypes = ElderSlotType.values().toSet()
        val covered = cultivationElderTypes + excludedFromCultivation
        val missing = allTypes - covered

        assertTrue(
            """
            |新增 ElderSlotType 未在 cultivationElderTypes 分类中覆盖！
            |
            |以下类别需要更新 ElderManagementUseCase.cultivationElderTypes 或加入排除列表：
            |  $missing
            |
            |操作指引：
            |  1. 如果新类型影响修炼速度（讲道/指导/加成）→ 加入 cultivationElderTypes
            |  2. 如果不影响修炼 → 加入本测试的 excludedFromCultivation 集合并注明原因
            """.trimMargin(),
            missing.isEmpty()
        )
    }

    // ==================== scanElderSlots 覆盖检查 ====================

    /**
     * DiscipleAssignmentGate.scanElderSlots 中扫描的长老类型。
     * 如果新增的 ElderSlotType 在 ElderSlots 数据类中有对应字段，需要加入 scanElderSlots。
     */
    private val scannedInScanElderSlots = setOf(
        ElderSlotType.VICE_SECT_MASTER,
        ElderSlotType.HERB_GARDEN,
        ElderSlotType.ALCHEMY,
        ElderSlotType.FORGE,
        ElderSlotType.OUTER_ELDER,
        ElderSlotType.PREACHING,
        ElderSlotType.LAW_ENFORCEMENT,
        ElderSlotType.INNER_ELDER,
        ElderSlotType.RECRUITING,
        ElderSlotType.CLOUD_PREACHING,
    )

    @Test
    fun `all ElderSlotType values are covered by scanElderSlots`() {
        val allTypes = ElderSlotType.values().toSet()
        val uncovered = allTypes - scannedInScanElderSlots

        assertTrue(
            """
            |新增 ElderSlotType 未在 DiscipleAssignmentGate.scanElderSlots 中覆盖！
            |
            |以下类别需要在 scanAndRegister 的 scanElderSlots() 中添加扫描逻辑：
            |  $uncovered
            |
            |操作指引：
            |  1. 在 scanElderSlots() 中添加 registerIfNotEmpty 调用对应 ElderSlots 字段
            |  2. 如果该类型在 ElderSlots 中无对应字段 → 加入本测试的 scannedInScanElderSlots 集合（罕见情况）
            """.trimMargin(),
            uncovered.isEmpty()
        )
    }
}
