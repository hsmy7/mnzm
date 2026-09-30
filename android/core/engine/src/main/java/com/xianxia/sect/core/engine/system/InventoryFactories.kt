package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 仓库物品工厂方法
 * 从 InventorySystem.kt 提取的无状态纯函数
 *
 * 装备条目已随 B3 迁移实例轨：产出唯一入口为
 * [com.xianxia.sect.core.engine.domain.EquipmentFactory]，本类不再提供装备工厂。
 */
@Singleton
class InventoryFactories @Inject constructor(
    private val converter: MerchantItemConverter
) {

    /**
     * 商人货单装备条目 → 装备实例（B3 实例轨；委托 [MerchantItemConverter.toEquipment]，
     * rng 显式传入保确定性——EQUIPMENT 分区流）。
     */
    fun createEquipmentFromMerchantItem(item: MerchantItem, rng: Random): EquipmentInstance =
        converter.toEquipment(item, rng)

    fun createManualFromMerchantItem(item: MerchantItem): ManualStack {
        val manual = converter.toManual(item)
        return manual.copy(quantity = 1)
    }

    fun createPillFromMerchantItem(item: MerchantItem): Pill =
        converter.toPill(item)

    fun createMaterialFromMerchantItem(item: MerchantItem): Material =
        converter.toMaterial(item)

    fun createHerbFromMerchantItem(item: MerchantItem): Herb =
        converter.toHerb(item)

    fun createSeedFromMerchantItem(item: MerchantItem): Seed =
        converter.toSeed(item)

    // -- 向后兼容：companion 桥接 --

    companion object {
        @Volatile
        private var _instance: InventoryFactories? = null

        internal fun initialize(instance: InventoryFactories) {
            _instance = instance
        }

        private val instance: InventoryFactories
            get() = _instance ?: InventoryFactories(MerchantItemConverter.companionInstance).also { _instance = it }

        fun createEquipmentFromMerchantItem(item: MerchantItem, rng: Random) =
            instance.createEquipmentFromMerchantItem(item, rng)
        fun createManualFromMerchantItem(item: MerchantItem) = instance.createManualFromMerchantItem(item)
        fun createPillFromMerchantItem(item: MerchantItem) = instance.createPillFromMerchantItem(item)
        fun createMaterialFromMerchantItem(item: MerchantItem) = instance.createMaterialFromMerchantItem(item)
        fun createHerbFromMerchantItem(item: MerchantItem) = instance.createHerbFromMerchantItem(item)
        fun createSeedFromMerchantItem(item: MerchantItem) = instance.createSeedFromMerchantItem(item)
    }
}
