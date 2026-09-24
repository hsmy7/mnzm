package com.xianxia.sect.core.model

import com.xianxia.sect.core.GameConfig

/**
 * 招募列表数据完整性工具。
 *
 * 负责招募列表（[GameData.recruitList]）的损坏条目校验与净化：
 * - 移除损坏条目（name 空 / 境界越界 / 灵根空）
 * - 按 id 去重
 * - 按内容去重（历史 Bug 产生的同内容双胞胎）
 * - 按"同人"签名去重（稳定字段签名一致的克隆）
 * - 移除已入宗门的残留条目（招募成功但未从列表移除）
 *
 * 净化按稳定字段签名判定同人，不涉及年龄。
 */
object RecruitIntegrity {

    /** 合法境界范围 0..9（0=仙人 最高，9=炼气 最低） */
    val VALID_REALM_RANGE: IntRange =
        GameConfig.Realm.CONFIGS.keys.let { it.min()..it.max() }

    /** 内容去重的归一化 id（丢弃 id 后比较全字段） */
    private const val NORMALIZED_ID = "same"

    /** 签名分隔符： 不可能出现在游戏字段中（防跨字段注入碰撞） */
    private const val SIGNATURE_SEPARATOR = ""

    /** 净化结果：清洗后的列表 + 移除数量 + 逐条明细日志 */
    data class SanitizeReport(
        val cleaned: List<Disciple>,
        val removedCount: Int,
        val details: List<String>
    )

    /**
     * 校验招募条目是否完好（招募守卫统一谓词）。
     * 仅做范围检查，不校验境界与其它字段的交叉匹配。
     */
    fun isValidRecruit(d: Disciple): Boolean =
        d.name.isNotBlank() &&
            d.realm in VALID_REALM_RANGE &&
            d.spiritRootType.split(",").all { it.isNotBlank() }

    /**
     * 跨表同人判定：稳定字段签名相等即同人（年龄容差随年龄字段下线）。
     * 入宗门后 cultivation/realm/status 等会分化，但姓名/性别/灵根/
     * 天赋永不变。
     *
     * @param a 候选条目（招募列表侧）
     * @param b 已入宗门弟子（可能已死亡）
     * @return 是否判定为同一个人
     */
    fun isSamePerson(a: Disciple, b: Disciple): Boolean =
        samePersonSignature(a) == samePersonSignature(b)

    /**
     * 列表内去重：按 id → 全字段（归一化 slotId）→ "同人"签名三级去重，
     * 均保留首个。
     * 供净化流程与批量招募路径（自动/一键）共用，杜绝双胞胎重复招募。
     *
     * @param recruits 待去重的列表
     * @return 去重后的列表
     */
    fun dedupeRecruits(recruits: List<Disciple>): List<Disciple> {
        // 按 id 去重（保留首个）
        val idSeen = mutableSetOf<String>()
        val idDeduped = mutableListOf<Disciple>()
        for (d in recruits) {
            if (idSeen.add(d.id)) idDeduped.add(d)
        }
        // 按内容去重（丢弃 id/slotId 后全字段相等判定，data class 哈希）
        val contentSeen = mutableSetOf<Disciple>()
        val contentDeduped = mutableListOf<Disciple>()
        for (d in idDeduped) {
            val normalized = d.copy(id = NORMALIZED_ID, slotId = 0)
            if (contentSeen.add(normalized)) contentDeduped.add(d)
        }
        // 按"同人"签名去重（稳定字段签名一致的克隆）。
        // O(R²)→O(N)：isSamePerson 先比签名（不同签名必 false），旧算法的
        // "kept 内 none 判定"只可能命中同签名者 ⇒ 按签名分组、组内保序去重，
        // 最后按原始位置全局排序恢复全局保序（组间交错时组内后续保留元素
        // 与异组元素的相对顺序不能被组间拼接破坏）。
        // 等价性由 RecruitDedupeEquivalenceTest 守卫（fuzz 覆盖交错顺序）。
        val personDeduped = contentDeduped
            .mapIndexed { index, d -> index to d }
            .groupBy({ (_, d) -> samePersonSignature(d) }, { it })
            .values
            .flatMap { group ->
                group.fold(emptyList<Pair<Int, Disciple>>()) { kept, pair ->
                    if (kept.none { isSamePerson(it.second, pair.second) }) kept + pair else kept
                }
            }
            .sortedBy { it.first }
            .map { it.second }
        return personDeduped
    }

    /**
     * 净化招募列表：
     * 1. 移除损坏条目（必须先于去重——否则损坏条目与正常条目同 id 且
     *    损坏者在前时，按 id 去重会保留损坏条目、误删正常条目）
     * 2. [dedupeRecruits] 三级去重（id / 内容 / 同人签名）
     * 3. 移除已入宗门的残留条目（[isSamePerson] 跨表匹配）
     *
     * @param recruits 待净化的招募列表
     * @param sectDisciples 正式弟子表数据（用于跨表残留判定）
     * @return 净化报告
     */
    fun sanitizeRecruitList(
        recruits: List<Disciple>,
        sectDisciples: List<Disciple>
    ): SanitizeReport {
        if (recruits.isEmpty()) {
            return SanitizeReport(recruits, 0, emptyList())
        }
        val details = mutableListOf<String>()

        // 1. 损坏条目
        val valid = recruits.filter { isValidRecruit(it) }
        val invalidCount = recruits.size - valid.size
        if (invalidCount > 0) {
            details.add("移除损坏条目 $invalidCount 条（name空/境界越界/灵根空）")
        }

        // 2. 三级去重（id / 内容 / 同人签名）
        val deduped = dedupeRecruits(valid)
        val dupCount = valid.size - deduped.size
        if (dupCount > 0) {
            details.add("移除重复条目 $dupCount 条（同id/同内容/同人）")
        }

        // 3. 已入宗门残留（按签名分组一次构建，避免 O(n·m) 全量拼接）
        val sectBySignature = sectDisciples.groupBy { samePersonSignature(it) }
        val result = mutableListOf<Disciple>()
        for (d in deduped) {
            val signature = samePersonSignature(d)
            val matches = sectBySignature[signature].orEmpty()
            val alreadyInSect = matches.any { isSamePerson(d, it) }
            if (alreadyInSect) {
                details.add("移除已入宗门残留条目（name=${d.name}）")
            } else {
                result.add(d)
            }
        }

        val removedCount = recruits.size - result.size
        return SanitizeReport(result, removedCount, details)
    }

    /**
     * "同人"稳定签名：仅 5 个列表侧可稳定恢复的不可变字段。
     *
     * 刻意排除 portraitRes/physiqueIds/affixIds：旧存档经
     * [DiscipleSerializer] 序列化时这些字段可能缺失（读档后列表侧
     * 恒空或失真；旧存档的 physiqueIds/affixIds 缺失），
     * 参与签名会使跨表残留匹配永久失效；而
     * 同名+同灵根+同天赋的多重巧合误判概率可忽略
     * （约 1e-11 量级）。
     *
     * talentIds 排序后拼接，消除顺序不稳定。
     */
    private fun samePersonSignature(d: Disciple): String = listOf(
        d.name,
        d.surname,
        d.gender,
        d.spiritRootType,
        d.talentIds.sorted().joinToString(",")
    ).joinToString(SIGNATURE_SEPARATOR)
}
