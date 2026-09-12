package com.xianxia.sect.core.model.production

/**
 * 排班启动规格：一次生产启动所需配方/时段/弟子/材料/产出字段的共享载体。
 *
 * [ProductionTransactionManager]（事务校验 + 槽位写入）与
 * [SlotStateMachine.startProduction]（状态转换）消费同一份规格，
 * 避免逐参数透传造成的长参数面。
 *
 * 注意 [materials] 是配方需求量（写入槽位 requiredMaterials 供完成结算/回滚使用），
 * 可用余量快照不随规格携带——它只参与启动前的充足性校验，不落槽位状态。
 */
data class ProductionStartSpec(
    val recipeId: String,
    val recipeName: String,
    val duration: Int,
    val currentYear: Int,
    val currentMonth: Int,
    val discipleId: String?,
    val discipleName: String,
    val successRate: Double,
    val materials: Map<String, Int>,
    val outputItemId: String?,
    val outputItemName: String,
    val outputItemRarity: Int
)
