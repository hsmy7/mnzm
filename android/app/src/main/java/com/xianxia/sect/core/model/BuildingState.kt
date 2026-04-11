package com.xianxia.sect.core.model

import com.xianxia.sect.core.model.production.ProductionSlot
import kotlinx.serialization.Serializable

/**
 * 建筑与槽位状态
 *
 * 包含灵药园种植槽位、炼器槽位、炼丹槽位、统一生产槽位、
 * 灵矿槽位、藏经阁弟子槽位等所有建筑相关数据。
 * 从 GameData 上帝对象中拆分出来，降低 copy() 时的复制开销。
 */
@Serializable
data class BuildingState(
    val productionSlots: List<ProductionSlot> = emptyList(),
    val spiritMineSlots: List<SpiritMineSlot> = emptyList(),
    val librarySlots: List<LibrarySlot> = emptyList()
)
