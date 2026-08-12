package com.xianxia.sect.data

import com.xianxia.sect.data.local.DiscipleAttributesDao
import com.xianxia.sect.data.local.DiscipleCombatStatsDao
import com.xianxia.sect.data.local.DiscipleCoreDao
import com.xianxia.sect.data.local.DiscipleDao
import com.xianxia.sect.data.local.DiscipleEquipmentDao
import com.xianxia.sect.data.local.DiscipleExtendedDao
import com.xianxia.sect.data.local.EquipmentInstanceDao
import com.xianxia.sect.data.local.EquipmentStackDao
import com.xianxia.sect.data.local.HerbDao
import com.xianxia.sect.data.local.ManualInstanceDao
import com.xianxia.sect.data.local.ManualStackDao
import com.xianxia.sect.data.local.MaterialDao
import com.xianxia.sect.data.local.PillDao
import com.xianxia.sect.data.local.SeedDao
import com.xianxia.sect.data.local.StorageBagDao
import com.xianxia.sect.data.local.BattleLogDao
import com.xianxia.sect.data.local.BuildingSlotDao
import com.xianxia.sect.data.local.ProductionSlotDao
import com.xianxia.sect.data.local.RecipeDao
import com.xianxia.sect.data.incremental.ChangeLogDao

/**
 * DAO 领域分组（P4B）：收敛 GameStateRepository 的 22 个平铺 DAO 构造依赖。
 *
 * 分组仅为构造参数收敛，DAO 行为与调用方不变。
 */
data class DiscipleDaos(
    val discipleDao: DiscipleDao,
    val discipleCoreDao: DiscipleCoreDao,
    val discipleCombatStatsDao: DiscipleCombatStatsDao,
    val discipleEquipmentDao: DiscipleEquipmentDao,
    val discipleExtendedDao: DiscipleExtendedDao,
    val discipleAttributesDao: DiscipleAttributesDao
)

/** 物品域 DAO 分组 */
data class ItemDaos(
    val equipmentStackDao: EquipmentStackDao,
    val equipmentInstanceDao: EquipmentInstanceDao,
    val manualStackDao: ManualStackDao,
    val manualInstanceDao: ManualInstanceDao,
    val pillDao: PillDao,
    val materialDao: MaterialDao,
    val seedDao: SeedDao,
    val herbDao: HerbDao,
    val storageBagDao: StorageBagDao
)

/** 世界/系统域 DAO 分组 */
data class WorldDaos(
    val buildingSlotDao: BuildingSlotDao,
    val recipeDao: RecipeDao,
    val battleLogDao: BattleLogDao,
    val productionSlotDao: ProductionSlotDao,
    val changeLogDao: ChangeLogDao
)
