package com.xianxia.sect.core.model

import com.xianxia.sect.core.GameConfig

/**
 * 角色星级乘区（口径 A：`1★` 为基线，`star > 1` 时每多一星按百分比上浮）。
 *
 * 星级来自 [GameData.gachaStarMap]（**稀疏**账本：0 星不落键），故未解锁角色与
 * `templateId` 为空的存量旧弟子一律 0 星。业务口径权威：
 * `docs/character-gacha-redesign-2026-09-23.md` §15.5、
 * `docs/design/gacha-batches/TASKBOOK-G09.md` §3.8。
 *
 * ## 三端同名同参
 * 与 C++ `gamecore/system/star_zone.h` 的 `struct StarZone` / `starZoneOf` /
 * [resolveStar] 逐位同式：战斗侧「先算属性加权和、再整体乘 [battleMult]、最后向零
 * 截断」，修炼侧以「加成量」[cultivationBonus] 参与乘区连乘（乘区序固定为
 * 资源→社交→状态→临时→星级，浮点乘法不可交换）。
 *
 * ## 为什么 `star <= 1` 必须逐位得 ×1.00
 * 把 `star = 0` 直接套进 `1 + (star - 1) × pct` 会得到 ×0.92 / ×0.95——凭空削掉
 * 未解锁与存量弟子的下限。基线钳制使开局送的 1★ 角色不改变任何既有属性/战力期望。
 *
 * @property star 原始星级（0 = 未解锁 / 存量旧弟子；1 = 基线；上限 `MAX_STAR`）
 */
data class StarZone(val star: Int) {

    /** 战斗属性/战力乘数（1★ 基线 ⇒ 每多一星 +8%） */
    val battleMult: Double
        get() = 1.0 + extraStarLevels * GameConfig.Gacha.STAR_BATTLE_PCT_PER_STAR

    /** 修炼速度加成量（1★ 基线 ⇒ 每多一星 +5%，与其余修炼乘区同为加算后乘算） */
    val cultivationBonus: Double
        get() = extraStarLevels * GameConfig.Gacha.STAR_CULT_PCT_PER_STAR

    /** 超出基线的星数（0 与 1 星同为 0，负数按 0 处理） */
    private val extraStarLevels: Double
        get() = (star - BASE_STAR).coerceAtLeast(0).toDouble()

    companion object {
        /** 解锁即 1 星：星级与「无加成」的分界 */
        const val BASE_STAR = 1

        /** 战斗侧快捷：按账本与模板 id 取战力乘数 */
        fun battleMult(gachaStarMap: Map<String, Int>, templateId: String?): Double =
            StarZone(starOf(gachaStarMap, templateId)).battleMult

        /** 修炼侧快捷：按账本与模板 id 取修炼加成 */
        fun cultivationBonus(gachaStarMap: Map<String, Int>, templateId: String?): Double =
            StarZone(starOf(gachaStarMap, templateId)).cultivationBonus

        /**
         * 从稀疏星级账本反查星级。
         *
         * 只读：`templateId` 为空（存量旧弟子）或账本无键（未解锁 / 有碎片但未足一星）
         * 均按 0 星处理，与 C++ `resolveStar` 同判据。
         */
        fun starOf(gachaStarMap: Map<String, Int>, templateId: String?): Int =
            if (templateId.isNullOrEmpty()) 0 else gachaStarMap[templateId] ?: 0
    }
}
