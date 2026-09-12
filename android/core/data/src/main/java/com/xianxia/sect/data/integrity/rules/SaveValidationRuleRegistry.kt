package com.xianxia.sect.data.integrity.rules

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 存档验证规则注册表。
 *
 * 所有 [SaveValidationRule] 的注册入口。按 [SaveValidationRule.order] 排序执行。
 * 线程安全，读操作（规则遍历）不需要加锁。
 *
 * ## 使用方式
 *
 * 启动时调用 [registerDefaults] 注册所有内置规则。
 * 测试中调用 [clear] 后单独注册目标规则。
 *
 * ```kotlin
 * SaveValidationRuleRegistry.clear()
 * SaveValidationRuleRegistry.register(SectNameRule)
 * ```
 */
object SaveValidationRuleRegistry {

    private val rules = CopyOnWriteArrayList<SaveValidationRule>()

    /**
     * 注册一条规则。
     *
     * 幂等：如果已存在同 [SaveValidationRule.id] 的规则，先移除旧规则。
     * 注册后按 [SaveValidationRule.order] 重新排序。
     */
    fun register(rule: SaveValidationRule) {
        rules.removeAll { it.id == rule.id }
        rules.add(rule)
        rules.sortBy { it.order }
    }

    /**
     * 批量注册规则。
     * @param rulesToAdd 要注册的规则列表
     */
    fun registerAll(rulesToAdd: List<SaveValidationRule>) {
        rulesToAdd.forEach { register(it) }
    }

    /** 获取当前所有已注册规则（按 [order] 排序） */
    val all: List<SaveValidationRule> get() = rules.toList()

    /** 按 [id] 查找规则 */
    fun findById(id: String): SaveValidationRule? = rules.find { it.id == id }

    /** 移除指定规则 */
    fun unregister(id: String) {
        rules.removeAll { it.id == id }
    }

    /** 清空所有规则（用于测试） */
    fun clear() {
        rules.clear()
    }

    /** 当前已注册规则数量 */
    val size: Int get() = rules.size
}
