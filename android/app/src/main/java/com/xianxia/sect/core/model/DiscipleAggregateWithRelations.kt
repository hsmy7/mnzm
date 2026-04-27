package com.xianxia.sect.core.model

import androidx.room.Embedded
import androidx.room.Relation

data class DiscipleAggregateWithRelations(
    @Embedded val core: DiscipleCore,
    
    @Relation(
        parentColumn = "id",
        entityColumn = "discipleId"
    )
    val combatStats: DiscipleCombatStats?,
    
    @Relation(
        parentColumn = "id",
        entityColumn = "discipleId"
    )
    val equipment: DiscipleEquipment?,
    
    @Relation(
        parentColumn = "id",
        entityColumn = "discipleId"
    )
    val extended: DiscipleExtended?,
    
    @Relation(
        parentColumn = "id",
        entityColumn = "discipleId"
    )
    val attributes: DiscipleAttributes?
) {
    fun toAggregate(): DiscipleAggregate {
        return DiscipleAggregate(
            core = core,
            combatStats = combatStats,
            equipment = equipment,
            extended = extended,
            attributes = attributes
        )
    }
    
    // TODO(U-01 Phase3): 移除 toDisciple()，调用方应直接使用 toAggregate()
    fun toDisciple(): Disciple {
        return toAggregate().toDisciple()
    }
}
