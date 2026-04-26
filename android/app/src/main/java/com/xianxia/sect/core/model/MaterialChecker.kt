package com.xianxia.sect.core.model

interface MaterialChecker {
    val requiredMaterials: Map<String, Int>

    fun hasEnoughMaterials(materials: List<Material>): Boolean {
        val materialMap = materials.associateBy { it.id }
        return requiredMaterials.all { (materialId, requiredQuantity) ->
            val available = materialMap[materialId]
            available != null && available.quantity >= requiredQuantity
        }
    }

    fun getMissingMaterials(materials: List<Material>): List<Pair<String, Int>> {
        val materialMap = materials.associateBy { it.id }
        return requiredMaterials.filter { (materialId, requiredQuantity) ->
            val available = materialMap[materialId]
            available == null || available.quantity < requiredQuantity
        }.map { (materialId, requiredQuantity) ->
            val available = materialMap[materialId]
            val have = available?.quantity ?: 0
            materialId to (requiredQuantity - have)
        }
    }
}
