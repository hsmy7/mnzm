package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.util.DomainResult

interface ItemAdder {
    fun addPill(item: Pill): DomainResult<Pill>
    fun addMaterial(item: Material): DomainResult<Material>
    fun addHerb(item: Herb): DomainResult<Herb>
    fun addSeed(item: Seed): DomainResult<Seed>

    fun addEquipmentStack(item: EquipmentStack): DomainResult<EquipmentStack>
    fun addEquipmentInstance(item: EquipmentInstance): DomainResult<EquipmentInstance>
    fun addManualStack(item: ManualStack, merge: Boolean = true): DomainResult<ManualStack>
    fun addManualInstance(item: ManualInstance): DomainResult<ManualInstance>
}
