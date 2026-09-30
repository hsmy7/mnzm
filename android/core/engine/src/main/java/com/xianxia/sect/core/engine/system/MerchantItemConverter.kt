package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.EquipmentFactory
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillEffect
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.Seed
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class MerchantItemConverter @Inject constructor() {

    /**
     * 商人货单装备条目 → 装备实例（B3 实例轨：经 [EquipmentFactory] 唯一产出入口）。
     *
     * 条目名按套装部件名（如「裂天罡煞·头冠」）反查部件得 (setId, part)；
     * 旧档遗留的已退役模板名无法对应部件时回退物理套随机部件（装备照常产出，不丢购买）。
     *
     * @param rng 装备 RNG（RngPartition.EQUIPMENT 流），由调用方显式传入保确定性
     */
    fun toEquipment(item: MerchantItem, rng: Random): EquipmentInstance {
        val piece = EquipmentDatabase.setPieces.find { it.name == item.name }
        val setId = piece?.setId ?: DEFAULT_EQUIPMENT_SET_ID
        val part = piece?.part ?: EquipmentFactory.pickPart(setId, rng)
        return EquipmentFactory.create(setId = setId, part = part, rarity = item.rarity, rng = rng)
    }

    /** 货单装备条目对应部位（按部件名反查；查不到返回 null） */
    fun equipmentPartOf(item: MerchantItem): EquipmentSlot? =
        EquipmentDatabase.setPieces.find { it.name == item.name }?.part

    fun toManual(item: MerchantItem): ManualStack {
        val template = ManualDatabase.getByName(item.name)
        if (template != null) {
            val skillBuffsJson = template.skillBuffs.joinToString("|") { buff ->
                "${buff.type},${buff.value},${buff.duration}"
            }
            return ManualStack(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                description = template.description,
                type = template.type,
                stats = template.stats,
                skillName = template.skillName,
                skillDescription = template.skillDescription,
                skillType = template.skillType,
                skillDamageType = template.skillDamageType,
                skillHits = template.skillHits,
                skillDamageMultiplier = template.skillDamageMultiplier,
                skillCooldown = template.skillCooldown,
                skillMpCost = template.skillMpCost,
                skillHealPercent = template.skillHealPercent,
                skillHealType = template.skillHealType,
                skillBuffType = template.skillBuffType,
                skillBuffValue = template.skillBuffValue,
                skillBuffDuration = template.skillBuffDuration,
                skillBuffsJson = skillBuffsJson,
                skillIsAoe = template.skillIsAoe,
                skillTargetScope = template.skillTargetScope,
                minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity),
                quantity = 1
            )
        }
        return ManualDatabase.generateRandom(item.rarity, item.rarity).copy(
            id = UUID.randomUUID().toString(),
            rarity = item.rarity
        )
    }

    fun toPill(item: MerchantItem): Pill {
        val grade = item.grade?.let { gradeName ->
            PillGrade.entries.find { it.displayName == gradeName } ?: PillGrade.MEDIUM
        } ?: PillGrade.MEDIUM
        val template = PillRecipeDatabase.getRecipeByNameAndGrade(item.name, grade)
            ?: PillRecipeDatabase.getRecipeByName(item.name)
        if (template != null) {
            return Pill(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                quantity = 1,
                description = template.description,
                category = template.category,
                grade = grade,
                pillType = template.pillType,
                effects = PillEffect(
                    breakthroughChance = template.breakthroughChance,
                    targetRealm = template.targetRealm,
                    cultivationSpeedPercent = template.cultivationSpeedPercent,
                    duration = template.duration,
                    cultivationAdd = template.cultivationAdd,
                    skillExpAdd = template.skillExpAdd,
                    attackAdd = template.attackAdd,
                    defenseAdd = template.defenseAdd,
                    hpAdd = template.hpAdd,
                    mpAdd = template.mpAdd,
                    speedAdd = template.speedAdd,
                    critRateAdd = template.critRateAdd,
                    critEffectAdd = template.critEffectAdd,
                    intelligenceAdd = template.intelligenceAdd,
                    charmAdd = template.charmAdd,
                    comprehensionAdd = template.comprehensionAdd,
                    artifactRefiningAdd = template.artifactRefiningAdd,
                    pillRefiningAdd = template.pillRefiningAdd,
                    spiritPlantingAdd = template.spiritPlantingAdd,
                    teachingAdd = template.teachingAdd,
                    moralityAdd = template.moralityAdd
                ),
                minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity)
            )
        }
        val randomPill = ItemDatabase.generateRandomPill(minRarity = item.rarity, maxRarity = item.rarity)
        return randomPill.copy(quantity = 1, grade = grade)
    }

    fun toMaterial(item: MerchantItem): Material {
        val template = BeastMaterialDatabase.getMaterialByName(item.name)
        if (template != null) {
            return Material(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                quantity = 1,
                description = template.description,
                category = try { MaterialCategory.valueOf(template
                    .category) } catch (ignored: IllegalArgumentException) { MaterialCategory.BEAST_HIDE }
            )
        }
        val randomMaterial = ItemDatabase.generateRandomMaterial(minRarity = item.rarity, maxRarity = item.rarity)
        return randomMaterial.copy(quantity = 1)
    }

    fun toHerb(item: MerchantItem): Herb {
        val template = HerbDatabase.getHerbByName(item.name)
        if (template != null) {
            return Herb(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                description = template.description,
                category = template.category,
                quantity = 1
            )
        }
        val herbTemplate = HerbDatabase.generateRandomHerb(minRarity = item.rarity, maxRarity = item.rarity)
        return Herb(
            id = UUID.randomUUID().toString(),
            name = herbTemplate.name,
            rarity = herbTemplate.rarity,
            description = herbTemplate.description,
            category = herbTemplate.category,
            quantity = 1
        )
    }

    fun toSeed(item: MerchantItem): Seed {
        val template = HerbDatabase.getSeedByName(item.name)
        if (template != null) {
            return Seed(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                description = template.description,
                growTime = template.growTime,
                yield = template.yield,
                quantity = 1
            )
        }
        val seedTemplate = HerbDatabase.generateRandomSeed(minRarity = item.rarity, maxRarity = item.rarity)
        return Seed(
            id = UUID.randomUUID().toString(),
            name = seedTemplate.name,
            rarity = seedTemplate.rarity,
            description = seedTemplate.description,
            growTime = seedTemplate.growTime,
            yield = seedTemplate.yield,
            quantity = 1
        )
    }

    fun getCapacityCheckParams(item: MerchantItem): CapacityCheckParams {
        return when (item.type.lowercase()) {
            "equipment" -> CapacityCheckParams.EquipmentParams(item.rarity)
            "manual" -> manualCapacityParams(item)
            "pill" -> pillCapacityParams(item)
            "material" -> materialCapacityParams(item)
            "herb" -> herbCapacityParams(item)
            "seed" -> seedCapacityParams(item)
            else -> CapacityCheckParams.Unknown
        }
    }

    /** 功法容量检查参数：模板缺失时类型回退 SUPPORT */
    private fun manualCapacityParams(item: MerchantItem): CapacityCheckParams {
        val t = ManualDatabase.getByName(item.name)
        return CapacityCheckParams.ManualParams(item.name, item.rarity, t?.type ?: ManualType.SUPPORT)
    }

    /** 丹药容量检查参数：品级显示名映射（默认中品），模板缺失时类目回退 FUNCTIONAL */
    private fun pillCapacityParams(item: MerchantItem): CapacityCheckParams {
        val t = PillRecipeDatabase.getRecipeByName(item.name)
        val grade = item.grade?.let { gradeName ->
            PillGrade.entries.find { it.displayName == gradeName } ?: PillGrade.MEDIUM
        } ?: PillGrade.MEDIUM
        return CapacityCheckParams.PillParams(item.name, item.rarity, t?.category ?: PillCategory.FUNCTIONAL, grade)
    }

    /** 材料容量检查参数：类目名非法/模板缺失时回退 BEAST_HIDE */
    private fun materialCapacityParams(item: MerchantItem): CapacityCheckParams {
        val t = BeastMaterialDatabase.getMaterialByName(item.name)
        val cat = t?.category?.let { try { MaterialCategory.valueOf(it) } catch (ignored:
            IllegalArgumentException) { MaterialCategory.BEAST_HIDE } } ?: MaterialCategory.BEAST_HIDE
        return CapacityCheckParams.MaterialParams(item.name, item.rarity, cat)
    }

    /** 草药容量检查参数：模板缺失时类目回退 "spirit" */
    private fun herbCapacityParams(item: MerchantItem): CapacityCheckParams {
        val t = HerbDatabase.getHerbByName(item.name)
        return CapacityCheckParams.HerbParams(item.name, item.rarity, t?.category ?: "spirit")
    }

    /** 种子容量检查参数：模板缺失时生长周期回退 12 */
    private fun seedCapacityParams(item: MerchantItem): CapacityCheckParams {
        val t = HerbDatabase.getSeedByName(item.name)
        return CapacityCheckParams.SeedParams(item.name, item.rarity, t?.growTime ?: 12)
    }

    sealed class CapacityCheckParams {
        data class EquipmentParams(val rarity: Int) : CapacityCheckParams()
        data class ManualParams(val name: String, val rarity: Int, val type: ManualType) : CapacityCheckParams()
        data class PillParams(val name: String, val rarity: Int, val category: PillCategory,
            val grade: PillGrade = PillGrade.MEDIUM) : CapacityCheckParams()
        data class MaterialParams(val name: String, val rarity: Int,
            val category: MaterialCategory) : CapacityCheckParams()
        data class HerbParams(val name: String, val rarity: Int, val category: String) : CapacityCheckParams()
        data class SeedParams(val name: String, val rarity: Int, val growTime: Int) : CapacityCheckParams()
        object Unknown : CapacityCheckParams()
    }

    // -- 向后兼容：companion 桥接，现有调用点无需改动 --

    companion object {
        /** 货单名无法对应部件时的兜底套装 id（物理套「裂天罡煞」） */
        private const val DEFAULT_EQUIPMENT_SET_ID = "lietian"

        @Volatile
        private var _instance: MerchantItemConverter? = null

        internal fun initialize(instance: MerchantItemConverter) {
            _instance = instance
        }

        private val instance: MerchantItemConverter
            get() = _instance ?: MerchantItemConverter().also { _instance = it }

        /** 供其他 companion 桥接使用的内部访问器 */
        internal val companionInstance: MerchantItemConverter get() = instance

        fun toEquipment(item: MerchantItem, rng: Random) = instance.toEquipment(item, rng)
        fun equipmentPartOf(item: MerchantItem) = instance.equipmentPartOf(item)
        fun toManual(item: MerchantItem) = instance.toManual(item)
        fun toPill(item: MerchantItem) = instance.toPill(item)
        fun toMaterial(item: MerchantItem) = instance.toMaterial(item)
        fun toHerb(item: MerchantItem) = instance.toHerb(item)
        fun toSeed(item: MerchantItem) = instance.toSeed(item)
        fun getCapacityCheckParams(item: MerchantItem) = instance.getCapacityCheckParams(item)
    }
}
