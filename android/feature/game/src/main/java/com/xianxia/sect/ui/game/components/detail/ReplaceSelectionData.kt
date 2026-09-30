package com.xianxia.sect.ui.game.components.detail

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.util.watchKey
import com.xianxia.sect.ui.components.getRarityName
import com.xianxia.sect.ui.game.components.addManualSkillInfo
import com.xianxia.sect.ui.game.components.addManualStackSkillInfo
import com.xianxia.sect.ui.game.components.getStatDisplayName

/**
 * 更换界面详情数据：右侧详情面板四个区域的展示数据。
 *
 * @param name 功法/装备名称（区域1，颜色由调用方按品阶决定）
 * @param rarity 品阶（1-6，决定名称颜色与精灵图背景）
 * @param spriteName SpriteResRegistry 精灵图键（功法 "manual_$rarity"、装备为物品名）
 * @param subtitle 副标题（如 "凡品 · 攻击型" / "武器 · 灵品"）
 * @param attributeLines 区域2 属性加成行
 * @param skillTitle 区域3 标题（功法"技能描述"、装备"装备描述"）
 * @param skillLines 区域3 内容行
 */
internal data class ReplaceDetailData(
    val name: String,
    val rarity: Int,
    val spriteName: String?,
    val subtitle: String,
    val attributeLines: List<String>,
    val skillTitle: String,
    val skillLines: List<String>
)

/**
 * 更换界面左侧列表项。
 *
 * @param isDisabled 置底置灰不可点击（心法规则：弟子已有心法且更换原功法非心法时）
 * @param isFollowed 关注标记（排序首键：已关注在前；卡片同时保留金色关注描边）
 * @param detail 右侧详情面板数据
 */
internal data class ReplaceSelectionItem(
    val id: String,
    val name: String,
    val rarity: Int,
    val quantity: Int = 1,
    val isLocked: Boolean = false,
    val isManual: Boolean = false,
    val isDisabled: Boolean = false,
    val isFollowed: Boolean = false,
    val detail: ReplaceDetailData
)

/**
 * 构建装备更换列表：堆叠 + 游离实例合并，
 * 关注优先 → 品阶降序（同品阶名称升序）。
 *
 * 过滤规则与原 EquipmentSelectionDialog 一致：堆叠按槽位/境界达标；实例按槽位/境界达标/
 * 排除当前装备/归属（无主或属于当前弟子）。
 */
internal fun buildEquipmentReplaceItems(
    instances: List<EquipmentInstance>,
    slot: EquipmentSlot,
    currentEquipmentId: String?,
    currentDiscipleId: String,
    discipleRealm: Int,
    watchedKeys: Set<String> = emptySet()
): List<ReplaceSelectionItem> {
    // B3 单轨实例：候选 = 袋内同部位实例（排除当前装备；归属无主或当前弟子；
    // 境界达标——装备堆叠轨已退役，stackItems 删除）
    val instanceItems = instances.asSequence()
        .filter {
            it.part == slot &&
            it.id != currentEquipmentId &&
            (it.ownerId == null || it.ownerId == currentDiscipleId) &&
            GameConfig.Realm.meetsRealmRequirement(discipleRealm, it.minRealm)
        }
        .map { instance ->
            ReplaceSelectionItem(
                id = instance.id,
                name = instance.name,
                rarity = instance.rarity,
                quantity = 1,
                isManual = false,
                isFollowed = watchKey("equipment", instance.id) in watchedKeys,
                detail = equipmentInstanceDetail(instance)
            )
        }
    return instanceItems.toList().sortedWith(replaceSelectionComparator())
}

/**
 * 构建功法更换/学习列表。
 *
 * 过滤：已学名称排除 + 境界达标；[mindItemsDisabled] 为 true 时心法项置底置灰（isDisabled）。
 * 排序：可用项在前（关注优先 → 品阶降序 → 名称升序），禁用项（心法）整体置底
 * （其内部同样关注优先 → 品阶降序 → 名称升序）。
 */
internal fun buildManualReplaceItems(
    stacks: List<ManualStack>,
    learnedNames: Set<String>,
    discipleRealm: Int,
    mindItemsDisabled: Boolean,
    watchedKeys: Set<String> = emptySet()
): List<ReplaceSelectionItem> {
    val items = stacks.asSequence()
        .filter {
            it.name !in learnedNames &&
            GameConfig.Realm.meetsRealmRequirement(discipleRealm, it.minRealm)
        }
        .map { stack ->
            ReplaceSelectionItem(
                id = stack.id,
                name = stack.name,
                rarity = stack.rarity,
                quantity = stack.quantity,
                isLocked = stack.isLocked,
                isManual = true,
                isDisabled = stack.type == ManualType.MIND && mindItemsDisabled,
                isFollowed = stack.watchKey() in watchedKeys,
                detail = manualStackDetail(stack)
            )
        }
        .toList()
    val comparator = replaceSelectionComparator()
    val (enabled, disabled) = items.partition { !it.isDisabled }
    return enabled.sortedWith(comparator) + disabled.sortedWith(comparator)
}

/** 关注优先（isFollowed 在前）→ 品阶降序 → 名称升序比较器 */
internal fun replaceSelectionComparator(): Comparator<ReplaceSelectionItem> =
    compareByDescending<ReplaceSelectionItem> { it.isFollowed }
        .thenByDescending { it.rarity }
        .thenBy { it.name }

/**
 * 功法堆叠详情构建。
 *
 * 区域2 属性加成取堆叠 stats（为空时回退模板）；区域3 技能描述取堆叠技能数据
 * （无技能时回退模板技能；仍无则回退功法描述文本）。
 */
internal fun manualStackDetail(stack: ManualStack): ReplaceDetailData {
    val template = if (ManualDatabase.isInitialized) ManualDatabase.getByName(stack.name) else null
    val effectiveStats = stack.stats.ifEmpty { template?.stats ?: emptyMap() }
    val attributeLines = buildList {
        effectiveStats.forEach { (key, value) ->
            val statName = getStatDisplayName(key)
            add(if (key.contains("Percent")) "  $statName +$value%" else "  $statName +$value")
        }
    }
    val skillLines = buildList {
        val skillName = stack.skillName
        if (!skillName.isNullOrEmpty()) {
            addManualStackSkillInfo(stack, skillName)
        } else if (template != null && !template.skillName.isNullOrEmpty()) {
            add("技能: ${template.skillName}")
            addManualSkillInfo(template)
        } else if (stack.description.isNotEmpty()) {
            add(stack.description)
        }
    }.filter { it.isNotBlank() }
    return ReplaceDetailData(
        name = stack.name,
        rarity = stack.rarity,
        spriteName = "manual_${stack.rarity}",
        subtitle = "${getRarityName(stack.rarity)} · ${stack.type.displayName}",
        attributeLines = attributeLines,
        skillTitle = "技能描述",
        skillLines = skillLines
    )
}

/**
 * 装备实例详情构建：属性按最终属性（含孕养加成），差值用 (↑x) 标注。
 */
internal fun equipmentInstanceDetail(instance: EquipmentInstance): ReplaceDetailData {
    // B3 语义从简：词条摘要（主词条×等级成长 + 副词条×强化次数——
    // EquipStatValue.toString 已给中文摘要面）；旧 7 项面板/孕养差值标注退役
    val attributeLines = instance.totalBonus().map { sv -> "  $sv" }
    return ReplaceDetailData(
        name = instance.name,
        rarity = instance.rarity,
        spriteName = instance.name,
        subtitle = "${instance.part.displayName} · ${getRarityName(instance.rarity)} · Lv${instance.level}",
        attributeLines = attributeLines,
        skillTitle = "装备描述",
        skillLines = if (instance.description.isNotEmpty()) listOf(instance.description) else emptyList()
    )
}
