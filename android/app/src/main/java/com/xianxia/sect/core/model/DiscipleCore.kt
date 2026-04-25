package com.xianxia.sect.core.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.xianxia.sect.core.GameConfig

@Entity(
    tableName = "disciples_core",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["name"]),
        Index(value = ["realm", "realmLayer"]),
        Index(value = ["isAlive", "realm"]),
        Index(value = ["isAlive", "status"]),
        Index(value = ["discipleType"]),
        Index(value = ["age"])
    ]
)
data class DiscipleCore(
    @ColumnInfo(name = "id")
    var id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    var name: String = "",
    @ColumnInfo(name = "surname")
    var surname: String = "",
    /** 境界等级：0=仙人(最高), 1=渡劫, 2=大乘, ..., 9=炼气(最低)。数字越小境界越高 */
    var realm: Int = 9,
    /** 境界层数：1~9层，9层满后突破到下一大境界(realm-1) */
    var realmLayer: Int = 1,
    var cultivation: Double = 0.0,
    var isAlive: Boolean = true,
    var status: String = DiscipleStatus.IDLE.name,
    var discipleType: String = "outer",
    var age: Int = 16,
    var lifespan: Int = 80,
    var gender: String = "male",
    var spiritRootType: String = "metal",
    var recruitedMonth: Int = 0,
    var updatedAt: Long = System.currentTimeMillis()
) {
    val canCultivate: Boolean get() = age >= 5
    val realmName: String get() {
        if (age < 5 || realmLayer == 0) return "无境界"
        if (realm == 0) return GameConfig.Realm.getName(realm)
        return "${GameConfig.Realm.getName(realm)}${realmLayer}层"
    }
    val realmNameOnly: String get() = GameConfig.Realm.getName(realm)
    val maxCultivation: Double get() {
        if (realm == 0) return cultivation
        val base = GameConfig.Realm.get(realm).cultivationBase
        return base * (1.0 + (realmLayer - 1) * 0.2)
    }
    val cultivationProgress: Double get() = if (maxCultivation > 0) cultivation / maxCultivation else 0.0
    val spiritRoot: SpiritRoot get() = SpiritRoot(spiritRootType)
    val spiritRootName: String get() = spiritRoot.name
    val genderName: String get() = if (gender == "male") "男" else "女"
    val genderSymbol: String get() = if (gender == "male") "♂" else "♀"
    
    fun canBreakthrough(): Boolean = cultivation >= maxCultivation
    
    companion object {
        fun fromDisciple(disciple: Disciple): DiscipleCore {
            return DiscipleCore(
                id = disciple.id,
                name = disciple.name,
                surname = disciple.surname,
                realm = disciple.realm,
                realmLayer = disciple.realmLayer,
                cultivation = disciple.cultivation,
                isAlive = disciple.isAlive,
                status = disciple.status.name,
                discipleType = disciple.discipleType,
                age = disciple.age,
                lifespan = disciple.lifespan,
                gender = disciple.gender,
                spiritRootType = disciple.spiritRootType,
                recruitedMonth = disciple.usage.recruitedMonth,
                updatedAt = System.currentTimeMillis()
            )
        }
    }
}
