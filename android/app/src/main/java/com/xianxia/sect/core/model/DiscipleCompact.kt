package com.xianxia.sect.core.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "disciple_compact",
    indices = [
        androidx.room.Index(value = ["slot_id"], name = "index_disciple_compact_slot_id"),
        androidx.room.Index(value = ["slot_id", "isAlive"], name = "index_disciple_compact_slot_id_isAlive")
    ]
)
data class DiscipleCompact(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "slot_id", defaultValue = "0")
    val slotId: Int = 0,

    @ColumnInfo(name = "name", defaultValue = "")
    val name: String,

    @ColumnInfo(name = "cultivation", defaultValue = "0.0")
    val cultivation: Double = 0.0,

    @ColumnInfo(name = "realm", defaultValue = "0")
    val realm: Int = 0,

    @ColumnInfo(name = "realmLayer", defaultValue = "0")
    val realmLayer: Int = 0,

    @ColumnInfo(name = "lifespan", defaultValue = "0")
    val lifespan: Int = 0,

    @ColumnInfo(name = "maxLifespan", defaultValue = "0")
    val maxLifespan: Int = 0,

    @ColumnInfo(name = "isAlive", defaultValue = "1")
    val isAlive: Boolean = true,

    @ColumnInfo(name = "spiritRoot", defaultValue = "0")
    val spiritRoot: Int = 0,

    @ColumnInfo(name = "combatPower", defaultValue = "0")
    val combatPower: Long = 0,

    @ColumnInfo(name = "cultivationSpeed", defaultValue = "1.0")
    val cultivationSpeed: Double = 1.0,

    @ColumnInfo(name = "cultivationSpeedBonus", defaultValue = "0.0")
    val cultivationSpeedBonus: Double = 0.0,

    @ColumnInfo(name = "cultivationSpeedDuration", defaultValue = "0")
    val cultivationSpeedDuration: Int = 0,

    @ColumnInfo(name = "status", defaultValue = "0")
    val status: Int = 0,

    @ColumnInfo(name = "age", defaultValue = "0")
    val age: Int = 0
) {
    fun toDisciple(fullDisciple: Disciple): Disciple = fullDisciple.copy(
        cultivation = cultivation,
        realm = realm,
        realmLayer = realmLayer,
        lifespan = lifespan,
        isAlive = isAlive,
        cultivationSpeedBonus = cultivationSpeedBonus,
        cultivationSpeedDuration = cultivationSpeedDuration
    )

    companion object {
        fun fromDisciple(disciple: Disciple): DiscipleCompact = DiscipleCompact(
            id = disciple.id,
            slotId = disciple.slotId,
            name = disciple.name,
            cultivation = disciple.cultivation,
            realm = disciple.realm,
            realmLayer = disciple.realmLayer,
            lifespan = disciple.lifespan,
            maxLifespan = disciple.lifespan,
            isAlive = disciple.isAlive,
            spiritRoot = disciple.spiritRoot.types.size,
            combatPower = disciple.combat.totalCultivation,
            cultivationSpeed = disciple.spiritRoot.cultivationBonus,
            cultivationSpeedBonus = disciple.cultivationSpeedBonus,
            cultivationSpeedDuration = disciple.cultivationSpeedDuration,
            status = disciple.status.ordinal,
            age = disciple.age
        )
    }
}
