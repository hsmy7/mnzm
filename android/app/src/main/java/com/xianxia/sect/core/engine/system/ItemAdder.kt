package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Manual
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed

interface ItemAdder {
    fun addEquipment(item: Equipment): AddResult
    fun addManual(item: Manual): AddResult
    fun addPill(item: Pill): AddResult
    fun addMaterial(item: Material): AddResult
    fun addHerb(item: Herb): AddResult
    fun addSeed(item: Seed): AddResult
}
