package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 弟子 ID 数值范围校验规则（C3-b，2026-08-05）。
 *
 * 背景：`ComponentTable.MAX_SAFE_CAPACITY`（原 10M）只拦 `id >= 上限` 的扩容，
 * crafted 大 id 弟子（如 9,999,999）触发 ~60 张 Int/Double 平铺表扩容至千万容量
 * （≈7GB 内存）→ OutOfMemoryError 崩溃且重试即崩溃循环。
 *
 * 本规则在插入前拦截：弟子 id 为**数字**且超出安全上限时判 [RuleOutcome.Corrupted]
 * （走备份恢复）。非数字 id（uuid 等）不误伤——交给 [DuplicateDiscipleIdRule] 等处理。
 *
 * order=1：位于数值消毒（order=0）之后、一切容量敏感的规则之前执行；
 * 纯列表遍历无内存风险。
 */
object DiscipleIdBoundsRule : SaveValidationRule {
    override val id = "disciple_id_bounds"
    override val order = 1

    /**
     * 弟子 ID 安全上限：200K = 单档累计招募 20 万弟子（约 20 万游戏年），
     * 远超任何真实长局；该上限下最坏扩容 ≈ 60 表 × 3 数组 × 4B × 200K ≈ 144MB，
     * 512MB 内存设备可存活。
     */
    private const val MAX_DISCIPLE_ID_CAP = 200_000

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val offenders = data.disciples.mapNotNull { disciple ->
            val id = disciple.id.toIntOrNull() ?: return@mapNotNull null
            if (id < 0 || id > MAX_DISCIPLE_ID_CAP) {
                "弟子[${disciple.name.ifBlank { "ID=${disciple.id}" }}] id=$id 超出安全上限 $MAX_DISCIPLE_ID_CAP"
            } else {
                null
            }
        }
        return if (offenders.isEmpty()) {
            RuleOutcome.Passed
        } else {
            RuleOutcome.Corrupted(offenders)
        }
    }
}
