package com.xianxia.sect.core.model

interface MaterialChecker {
    val requiredMaterials: Map<String, Int>

    fun hasEnoughMaterials(materials: List<Material>): Boolean {
        val materialMap = materials.groupBy { it.name }.mapValues { (_, list) -> list.sumOf { it.quantity } }
        return requiredMaterials.all { (materialName, requiredQuantity) ->
            val available = materialMap[materialName] ?: 0
            available >= requiredQuantity
        }
    }

    fun getMissingMaterials(materials: List<Material>): List<Pair<String, Int>> {
        val materialMap = materials.groupBy { it.name }.mapValues { (_, list) -> list.sumOf { it.quantity } }
        return requiredMaterials.filter { (materialName, requiredQuantity) ->
            val available = materialMap[materialName] ?: 0
            available < requiredQuantity
        }.map { (materialName, requiredQuantity) ->
            val available = materialMap[materialName] ?: 0
            materialName to (requiredQuantity - available)
        }
    }
}
