package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.SlotCategory

/**
 * 槽位赢家记录：弟子在首次出现的槽位（按 scanAndRegister 优先级顺序）。
 *
 * 供 [GameEngine.healDuplicateSlotAssignments] 双槽位自愈使用——
 * 清理重复槽位后按赢家信息重写回 GameData。
 */
internal data class SlotWinner(
    val discipleId: String,
    val category: SlotCategory,
    val slotType: String,
    val slotIndex: Int = -1
)
