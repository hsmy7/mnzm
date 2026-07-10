package com.xianxia.sect.core.domain.favor

import com.xianxia.sect.core.model.SectRelationLevel

/**
 * 好感度系统业务接口。
 *
 * 提供好感度的统一查询和修改入口。
 * 实现类注入 GameStateStore，委托 [com.xianxia.sect.core.domain.FavorDomain] 做纯计算。
 */
interface FavorService {

    // ═══════════ 查询 ═══════════

    /** 获取玩家对指定宗门的好感度 */
    fun getFavor(sectId: String): Int

    /** 获取玩家对指定宗门的好感度等级 */
    fun getFavorLevel(sectId: String): SectRelationLevel

    /** 获取与指定宗门的交易价格倍率 */
    fun getTradePriceMultiplier(sectId: String): Double

    /** 获取宗门拒绝接收礼物的概率 */
    fun getRejectProbability(sectLevel: Int, rarity: Int): Int

    // ═══════════ 修改 ═══════════

    /** 设定玩家对指定宗门的好感度（绝对值） */
    fun updateFavor(sectId: String, newFavor: Int, year: Int)

    /** 增量修改玩家对指定宗门的好感度 */
    fun modifyFavor(sectId: String, delta: Int)
}
