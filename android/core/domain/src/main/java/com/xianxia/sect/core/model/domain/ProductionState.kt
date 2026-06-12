package com.xianxia.sect.core.model.domain

import com.xianxia.sect.core.model.*

/**
 * 生产领域状态 — 从 GameData 中提取的生产相关字段聚合。
 *
 * 纯领域模型，仅用于业务层传递。序列化由 GameData 负责，本类不标 @Serializable，
 * 以允许使用 Map 类型（与 GameData 字段类型一致），避免 ProtoBuf 兼容问题。
 */
data class ProductionDomainState(
    val spiritFieldPlants: List<SpiritFieldPlant> = emptyList(),
    val unlockedRecipes: List<String> = emptyList(),
    val unlockedManuals: List<String> = emptyList(),
    val manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap()
)

/** 从 GameData 提取生产领域状态 */
fun GameData.extractProductionState(): ProductionDomainState = ProductionDomainState(
    spiritFieldPlants = spiritFieldPlants,
    unlockedRecipes = unlockedRecipes,
    unlockedManuals = unlockedManuals,
    manualProficiencies = manualProficiencies
)

/** 将生产领域状态合并回 GameData */
fun GameData.mergeProductionState(state: ProductionDomainState): GameData = copy(
    spiritFieldPlants = state.spiritFieldPlants,
    unlockedRecipes = state.unlockedRecipes,
    unlockedManuals = state.unlockedManuals,
    manualProficiencies = state.manualProficiencies
)
