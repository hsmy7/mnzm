package com.xianxia.sect.core.registry

import kotlin.random.Random

/**
 * 模板注册表抽象基类
 *
 * 提供通用的模板管理实现，子类只需关注：
 * 1. 如何加载模板数据 [loadTemplates]
 * 2. 如何提取模板的稀有度 [extractRarity]
 *
 * ## 使用示例
 *
 * ```kotlin
 * class EquipmentRegistry : BaseTemplateRegistry<EquipmentTemplate>() {
 *     override fun loadTemplates(): Map<String, EquipmentTemplate> {
 *         // 从硬编码、JSON、数据库等来源加载
 *     }
 *
 *     override fun extractRarity(template: EquipmentTemplate): Int {
 *         return template.rarity
 *     }
 * }
 * ```
 *
 * @param T 模板数据类型
 */
abstract class BaseTemplateRegistry<T> : TemplateRegistry<T> {

    // ==================== 内部状态 ====================

    /**
     * 模板数据缓存（懒加载）
     * 使用 lazy 保证线程安全的单次初始化
     */
    private val _templates: Map<String, T> by lazy { loadTemplates() }

    /**
     * 初始化标志位
     */
    @Volatile
    private var _isInitialized = false

    /**
     * 初始化锁（用于需要外部依赖的延迟初始化场景）
     */
    protected val initLock = Any()

    // ==================== 接口实现 ====================

    override val allTemplates: Map<String, T>
        get() = _templates

    override fun getById(id: String): T? = _templates[id]

    override fun getByRarity(rarity: Int): List<T> =
        _templates.values.filter { extractRarity(it) == rarity }

    override fun getRandom(minRarity: Int, maxRarity: Int): T {
        val candidates = _templates.values.filter {
            val rarity = extractRarity(it)
            rarity in minRarity..maxRarity
        }
        if (candidates.isEmpty()) {
            throw NoSuchElementException(
                "No templates found in rarity range [$minRarity - $maxRarity]. " +
                "Total templates: ${_templates.size}"
            )
        }
        return candidates.random()
    }

    override fun isInitialized(): Boolean = _isInitialized

    override fun getCount(): Int = _templates.size

    // ==================== 抽象方法（子类必须实现） ====================

    /**
     * 加载所有模板数据
     *
     * 此方法会在首次访问模板数据时自动调用（通过 lazy 委托）。
     * 子类应在此方法中实现具体的数据加载逻辑：
     * - 硬编码数据：直接构建 Map
     * - JSON 文件：解析并转换
     * - 数据库查询：执行查询并映射结果
     *
     * @return ID 到模板的不可变映射
     */
    protected abstract fun loadTemplates(): Map<String, T>

    /**
     * 从模板中提取稀有度值
     *
     * 用于支持 [getByRarity] 和 [getRandom] 方法的稀有度过滤。
     *
     * @param template 模板实例
     * @return 稀有度等级（通常为 1-6）
     */
    protected abstract fun extractRarity(template: T): Int

    // ==================== 可选钩子方法 ====================

    /**
     * 标记初始化完成
     *
     * 对于需要额外初始化步骤的子类（如从 JSON 加载后校验），
     * 应在初始化完成后调用此方法。
     */
    protected fun markInitialized() {
        synchronized(initLock) {
            _isInitialized = true
        }
    }

    /**
     * 自动标记初始化完成（用于纯硬编码的简单场景）
     *
     * 如果子类没有重写 [loadTemplates] 的初始化逻辑，
     * 可以在构造函数中调用此方法。
     */
    fun autoInitialize() {
        // 触发 lazy 初始化
        val __templates = _templates
        markInitialized()
    }

    // ==================== 通用工具方法 ====================

    /**
     * 根据权重分布随机选择一个元素
     *
     * @param candidates 候选元素列表
     * @param weightExtractor 权重提取函数
     * @return 随机选中的元素
     */
    protected fun <E> pickWeightedRandom(
        candidates: List<E>,
        weightExtractor: (E) -> Double
    ): E {
        if (candidates.isEmpty()) {
            throw IllegalArgumentException("candidates cannot be empty")
        }

        val totalWeight = candidates.sumOf { weightExtractor(it) }
        var random = Random.nextDouble() * totalWeight

        for (candidate in candidates) {
            random -= weightExtractor(candidate)
            if (random <= 0) return candidate
        }

        // 浮点精度问题时的兜底
        return candidates.last()
    }

    /**
     * 生成带权重的稀有度值
     *
     * 用于实现非均匀分布的稀有度生成（如装备掉落）。
     *
     * @param rarityDistribution 稀有度到概率的映射（概率之和应为1.0）
     * @return 生成的稀有度值
     */
    protected fun generateWeightedRarity(
        rarityDistribution: List<Pair<Int, Double>>
    ): Int {
        val roll = Random.nextDouble()
        var cumulative = 0.0

        for ((rarity, probability) in rarityDistribution) {
            cumulative += probability
            if (roll <= cumulative) {
                return rarity
            }
        }

        // 兜底返回最后一个
        return rarityDistribution.last().first
    }

    /**
     * 生成阶梯式稀有度（用于突破、炼丹等场景）
     *
     * 分布特点：低稀有度概率高，高稀有度概率低
     *
     * @param minRarity 最低稀有度
     * @param maxRarity 最高稀有度
     * @return 生成的稀有度值
     */
    protected fun generateTieredRarity(minRarity: Int, maxRarity: Int): Int {
        val roll = Random.nextDouble()
        return when {
            roll < 0.50 -> minRarity.coerceAtMost(maxRarity)
            roll < 0.75 -> (minRarity + 1).coerceIn(minRarity, maxRarity)
            roll < 0.90 -> (minRarity + 2).coerceIn(minRarity, maxRarity)
            roll < 0.97 -> (minRarity + 3).coerceIn(minRarity, maxRarity)
            roll < 0.99 -> (minRarity + 4).coerceIn(minRarity, maxRarity)
            else -> maxRarity
        }
    }
}
