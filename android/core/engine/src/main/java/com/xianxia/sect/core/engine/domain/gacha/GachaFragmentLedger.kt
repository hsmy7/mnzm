package com.xianxia.sect.core.engine.domain.gacha

import com.xianxia.sect.core.GameConfig

/**
 * 角色碎片账本（Kotlin 侧 `gachaFragmentCounts` / `gachaStarMap` 两张账本的**唯一写者**）。
 *
 * 抽卡 / 兑换码 / 邮件 / 活动四类发放渠道的碎片入账一律经 [GachaFacade.grantFragments]
 * 落到本账本，禁止在别处出现 `fragmentCounts[key]++` 式散写
 * （口径：`docs/character-gacha-redesign-2026-09-23.md` §15.6「活动送碎片」行）。
 *
 * ## 与 C++ 的分工（双实现并行契约）
 * AUTHORITATIVE 稳态下的账本写者是 C++
 * `android/app/src/main/cpp/gamecore/include/gamecore/system/gacha_fragment.h` 的
 * `addFragment`；本对象是它的 Kotlin 等价回退臂，两者**逐字同式**（拒绝条件、
 * 入账累加、升星循环、满星后进度口径、两键写入语义全部一致），同一组 golden 向量
 * 两侧各跑一遍做对拍。
 *
 * ## 入账语义
 * - `gachaFragmentCounts[tid]` 是**当前星级内进度**：先在既有进度上累加，
 *   再按 `while (star < maxStar && progress >= fragmentsPerStar)` 逐星扣减；
 * - 满星后进度继续累加，**不截断、不折算成灵石或其它货币**（碎片不占仓库容量，
 *   故无溢出/转邮件语义——这是它能下沉 C++ 而物品不能下沉的分界）；
 * - 星级账本保持**稀疏**：`star == 0` 时不写键（缺键即等价 0 星），因此「有进度但未
 *   足一星」的模板只出现在 `gachaFragmentCounts`，不出现在 `gachaStarMap`；
 *   无效授予（[grant]）则两键都不建。
 *
 * 常量单源：[fragmentsPerStar] / [maxStar] 取自 `GameConfig.Gacha`，与 C++
 * `kFragmentsPerStar` / `kMaxStar` 及配置表 `db.gachaPools[0]` 三向一致，
 * 漂移由常量三向比对守卫判红。
 */
internal object GachaFragmentLedger {

    /** 升一星所需碎片数（单源 `GameConfig.Gacha.FRAGMENTS_PER_STAR`）。 */
    val fragmentsPerStar: Int = GameConfig.Gacha.FRAGMENTS_PER_STAR

    /** 星级上限（单源 `GameConfig.Gacha.MAX_STAR`）。 */
    val maxStar: Int = GameConfig.Gacha.MAX_STAR

    /**
     * 一次入账的结果快照（账本新副本 + 升星差值）。
     *
     * @property fragmentCounts 入账后的碎片进度账本（原 map 不被修改）
     * @property starMap 入账后的星级账本（原 map 不被修改）
     * @property starBefore 入账前星级（无键按 0）
     * @property starAfter 入账后星级（本次可连升多星，上限 [maxStar]）
     * @property fragmentsAfter 入账后当前星级内进度（满星后可超过 [fragmentsPerStar]）
     */
    data class GrantOutcome(
        val fragmentCounts: Map<String, Int>,
        val starMap: Map<String, Int>,
        val starBefore: Int,
        val starAfter: Int,
        val fragmentsAfter: Int,
    )

    /**
     * 碎片入账 + 满 100 升星（纯函数，入参账本不被修改）。
     *
     * 零 RNG、零时间依赖：同一 (账本, templateId, count) 恒得同一结果，
     * 且连续两次入账与一次合并入账的 (star, progress) 相同。
     *
     * @param fragmentCounts 当前碎片进度账本（模板 id → 当前星级内进度）
     * @param starMap 当前星级账本（模板 id → 星）
     * @param templateId 角色模板 id；空串 ⇒ 拒绝入账（与 C++ `std::string::empty()` 同判据）
     * @param count 本次入账的碎片数；非正 ⇒ 拒绝入账
     * @return 入账结果；入参无效时为 null（两张账本零改动）
     */
    fun grant(
        fragmentCounts: Map<String, Int>,
        starMap: Map<String, Int>,
        templateId: String,
        count: Int,
    ): GrantOutcome? {
        if (templateId.isEmpty() || count <= 0) return null

        val starBefore = starMap[templateId] ?: 0
        // 累加与升星需要就地演进，故用 var（与 C++ addFragment 同式的唯一理由）
        var progress = (fragmentCounts[templateId] ?: 0) + count
        var star = starBefore
        while (star < maxStar && progress >= fragmentsPerStar) {
            progress -= fragmentsPerStar
            star += 1
        }
        return GrantOutcome(
            fragmentCounts = fragmentCounts + (templateId to progress),
            // 星级账本保持稀疏：0 星等价于无键，未解锁角色不得占位（同 C++ addFragment）
            starMap = if (star > 0) starMap + (templateId to star) else starMap,
            starBefore = starBefore,
            starAfter = star,
            fragmentsAfter = progress,
        )
    }
}
