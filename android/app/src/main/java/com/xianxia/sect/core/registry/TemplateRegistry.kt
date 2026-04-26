package com.xianxia.sect.core.registry

/**
 * 泛型模板注册表接口
 *
 * 统一所有静态模板数据（装备、丹药、功法、材料等）的访问接口，
 * 提供一致的 API 风格和查询能力。
 *
 * @param T 模板数据类型（如 EquipmentTemplate、PillTemplate 等）
 */
interface TemplateRegistry<T> {

    /**
     * 获取所有模板的不可变映射（ID -> 模板）
     */
    val allTemplates: Map<String, T>

    /**
     * 根据 ID 获取模板
     *
     * @param id 模板唯一标识符
     * @return 对应的模板，不存在则返回 null
     */
    fun getById(id: String): T?

    /**
     * 根据稀有度获取模板列表
     *
     * @param rarity 稀有度等级（1-6）
     * @return 符合条件的模板列表
     */
    fun getByRarity(rarity: Int): List<T>

    /**
     * 在指定稀有度范围内随机获取一个模板
     *
     * @param minRarity 最低稀有度（默认1）
     * @param maxRarity 最高稀有度（默认6）
     * @return 随机选中的模板
     * @throws NoSuchElementException 如果没有符合条件的模板
     */
    fun getRandom(minRarity: Int = 1, maxRarity: Int = 6): T

    /**
     * 检查注册表是否已完成初始化
     *
     * 对于需要从外部资源（如 JSON）加载的注册表，
     * 应在初始化完成前返回 false
     */
    fun isInitialized(): Boolean

    /**
     * 获取模板总数
     */
    fun getCount(): Int
}
