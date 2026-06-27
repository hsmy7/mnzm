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
    suspend fun addPill(item: Pill): DomainResult<Pill>
    suspend fun addMaterial(item: Material): DomainResult<Material>
    suspend fun addHerb(item: Herb): DomainResult<Herb>
    suspend fun addSeed(item: Seed): DomainResult<Seed>

    suspend fun addEquipmentStack(item: EquipmentStack): DomainResult<EquipmentStack>
    suspend fun addEquipmentInstance(item: EquipmentInstance): DomainResult<EquipmentInstance>
    suspend fun addManualStack(item: ManualStack, merge: Boolean = true): DomainResult<ManualStack>
    suspend fun addManualInstance(item: ManualInstance): DomainResult<ManualInstance>
}
