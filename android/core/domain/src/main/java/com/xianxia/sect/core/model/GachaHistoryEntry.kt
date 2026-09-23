package com.xianxia.sect.core.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * 寻访历史单条（按抽记，Q40）。挂在 GameData 环缓冲，上限
 * [com.xianxia.sect.core.GameConfig.Gacha.HISTORY_RING_SIZE]。
 */
@Keep
@Serializable
data class GachaHistoryEntry(
    @ProtoNumber(1) val poolId: String = "standard",
    /** 类别：character / item / pity */
    @ProtoNumber(2) val category: String = "",
    /** 角色碎片 templateId；物品为空 */
    @ProtoNumber(3) val templateId: String = "",
    /** 物品 id；角色碎片为空 */
    @ProtoNumber(4) val itemId: String = "",
    @ProtoNumber(5) val rarity: Int = 0,
    @ProtoNumber(6) val count: Int = 1,
    @ProtoNumber(7) val isPity: Boolean = false,
    /** 游戏绝对月（year*12+month），排序/展示用 */
    @ProtoNumber(8) val gameMonthIndex: Int = 0,
)
