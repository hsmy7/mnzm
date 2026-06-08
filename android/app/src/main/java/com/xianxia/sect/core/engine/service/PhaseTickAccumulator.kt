package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack

data class PhaseTickAccumulator(
    val equipInstancesToAdd: MutableList<EquipmentInstance> = mutableListOf(),
    val equipInstanceIdsToRemove: MutableSet<String> = mutableSetOf(),
    val equipStackDeletions: MutableSet<String> = mutableSetOf(),
    val equipStackQuantityDeltas: MutableMap<String, Int> = mutableMapOf(),
    val equipStackAdditions: MutableList<EquipmentStack> = mutableListOf(),
    val manualInstancesToAdd: MutableList<ManualInstance> = mutableListOf(),
    val manualInstanceIdsToRemove: MutableSet<String> = mutableSetOf(),
    val manualStackDeletions: MutableSet<String> = mutableSetOf(),
    val manualStackQuantityDeltas: MutableMap<String, Int> = mutableMapOf(),
    val manualStackAdditions: MutableList<ManualStack> = mutableListOf(),
    val profRemovals: MutableMap<String, MutableSet<String>> = mutableMapOf()
)
