package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed

interface ItemAdder {
    fun addPill(item: Pill): AddResult
    fun addMaterial(item: Material): AddResult
    fun addHerb(item: Herb): AddResult
    fun addSeed(item: Seed): AddResult

    fun addEquipmentStack(item: EquipmentStack): AddResult
    fun addEquipmentInstance(item: EquipmentInstance): AddResult
    fun addManualStack(item: ManualStack, merge: Boolean = true): AddResult
    fun addManualInstance(item: ManualInstance): AddResult
}
