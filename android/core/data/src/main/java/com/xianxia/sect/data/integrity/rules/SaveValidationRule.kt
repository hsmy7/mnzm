package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 存档验证规则接口。
 *
 * 每条规则封装一项独立的检查逻辑，通过 [SaveValidationRuleRegistry] 注册。
 * 实现此接口即可新增检查，无需修改 [SaveValidator]。
 *
 * ## 规则顺序
 *
 * [order] 控制执行顺序。部分规则有顺序依赖：
 * - [GhostDiscipleCleanupRule] (order=10) 必须在 [GhostRefCleanupRule] (order=11) 之前
 * - 无依赖的规则使用中间值（5~15 范围）
 *
 * ## 规则幂等性
 *
 * 每条规则应假设 [data] 可能已被前面规则修改过。
 * 规则不应抛出异常——异常在 [SaveValidator.validate] 中捕获转为 [RuleOutcome.Corrupted]。
 */
interface SaveValidationRule {
    /** 规则唯一标识（用于日志、测试、动态管理） */
    val id: String

    /** 排序优先级（小 → 大执行） */
    val order: Int

    /**
     * 执行规则验证与修复。
     *
     * @param data 当前 SaveData（可能已被前面规则修改过）
     * @param context 共享上下文（预计算数据 + 中间状态）
     * @return 规则执行结果
     */
    fun execute(data: SaveData, context: RuleContext): RuleOutcome
}
