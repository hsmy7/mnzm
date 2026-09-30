package com.xianxia.sect.core.model

/**
 * 弟子固有伤害属性（`Disciple.combat.innateDamageType`）的**唯一派生口**。
 *
 * 口径（五行属性伤害系统后，2026-09-30 架构修正）：
 * 1. 普攻伤害类型**角色配置驱动**——来源是**角色模板**（`CharacterTemplate.innateDamageType`，
 *    创建时继承、创建后永不改变）。当前全部角色设定为物理，这是内容设定而非架构恒等式：
 *    未来法术/五行普攻角色在模板配对应 `DamageType` 值即可，架构无需改动；
 * 2. 模板缺失（随机招募弟子 `templateId` 为空串，或未知 id）→ 兜底 `PHYSICAL`。
 *    旧「按首灵根物法二分派生（金/土→物理、水/木/火→法术）」随 `MAGIC` 类型退役而退役
 *    （法术不再是合法伤害类型；无显式配置的弟子保守取物理）；
 * 3. 灵根也为空（理论不可达，防御性兜底）→ `PHYSICAL`。
 *
 * 历史口径：Room v62 迁移 SQL 的回填 CASE（金/土→physical、水/木/火→magic）为 MAGIC
 * 时代产物，存量档残留的 "MAGIC" 值经 `resolvedInnateDamageType` 的非法值兜底链归物理
 * （由 [InnateDamageTypeGuardTest] 守卫现行口径）。
 */
object InnateDamageType {

    const val PHYSICAL = "PHYSICAL"

    /** 退役段常量（MAGIC 时代模板/存档残留语义；现行配置禁用） */
    const val MAGIC = "MAGIC"

    /**
     * 按模板 id 派生固有伤害属性 name。
     *
     * @param templateId 角色模板 id；空串或未知 id 兜底物理
     * @return [DamageType] name（现行配置应为 "PHYSICAL"）
     */
    @Suppress("UnusedParameter") // spiritRootType 保留签名兼容（AI 弟子构造调用点）；旧灵根派生已随 MAGIC 退役
    fun derive(templateId: String, spiritRootType: String): String {
        if (templateId.isNotEmpty()) {
            CharacterTemplateDb.byId(templateId)?.let { return it.innateDamageType }
        }
        return PHYSICAL
    }
}
