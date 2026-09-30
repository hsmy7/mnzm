package com.xianxia.sect.core.model

/**
 * 弟子固有伤害属性（`Disciple.combat.innateDamageType`）的**唯一派生口**。
 *
 * 口径（方案 §15.3，Q1 已拍板）：
 * 1. 普攻伤害类型每个角色固定一种，来源是**角色模板**（`CharacterTemplate.innateDamageType`，
 *    创建时继承、创建后永不改变）；
 * 2. 模板缺失（随机招募弟子 `templateId` 为空串，或未知 id）时按**首灵根**派生：
 *    金/土 → `PHYSICAL`，水/木/火 → `MAGIC`；
 * 3. 灵根也为空（理论不可达，防御性兜底）→ `PHYSICAL`。
 *
 * Room v62 迁移 SQL 内的 `innateDamageType` 回填 CASE 与本派生口径逐条一致
 * （`GameDatabaseMigrationsV62`），两侧由 [InnateDamageTypeGuardTest] 钉住。
 */
object InnateDamageType {

    const val PHYSICAL = "PHYSICAL"
    const val MAGIC = "MAGIC"

    /** 派生为物理的首灵根元素（金/土） */
    private val PHYSICAL_ROOTS = setOf("metal", "earth")

    /**
     * 按模板 id + 灵根派生固有伤害属性 name。
     *
     * @param templateId 角色模板 id；空串或未知 id 走灵根派生
     * @param spiritRootType 灵根序列（逗号拼接，取首段；空串兜底物理）
     * @return [DamageType] name（"PHYSICAL"/"MAGIC"）
     */
    fun derive(templateId: String, spiritRootType: String): String {
        if (templateId.isNotEmpty()) {
            CharacterTemplateDb.byId(templateId)?.let { return it.innateDamageType }
        }
        val firstRoot = spiritRootType.split(",").firstOrNull()?.trim().orEmpty()
        return deriveFromRoot(firstRoot)
    }

    /**
     * 首灵根派生：金/土 → 物理，水/木/火 → 法术，空串/未知元素 → 兜底物理
     * （与 Room v62 迁移 SQL 的 CASE ELSE 'PHYSICAL' 逐条一致）。
     */
    fun deriveFromRoot(firstRoot: String): String =
        if (firstRoot in PHYSICAL_ROOTS || firstRoot.isEmpty() ||
            firstRoot !in setOf("water", "wood", "fire")
        ) PHYSICAL else MAGIC
}
