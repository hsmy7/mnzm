package com.xianxia.sect.core.model

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.TalentDatabase
import kotlin.math.roundToInt

@Entity(
    tableName = "disciple_compact",
    indices = [
        androidx.room.Index(value = ["slot_id"], name = "index_disciple_compact_slot_id"),
        androidx.room.Index(value = ["slot_id", "isAlive"], name = "index_disciple_compact_slot_id_isAlive")
    ]
)
@Immutable
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

    @ColumnInfo(name = "cultivationSpeed", defaultValue = "8.0")
    val cultivationSpeed: Double = 8.0,

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
        /**
         * 计算弟子战力（含层数乘数 + 天赋加成 + 血炼百分比乘区）。
         *
         * 公式：
         *   战力 = (物攻 + 法攻) × 5 + (物防 + 法防) × 3 + 气血 × 4 + 速度 × 2
         * 属性 = 境界基础 × 方差 × 层数 × (1 + 天赋% + 血炼%)
         */
        private fun computeCombatPower(
            disciple: Disciple,
            bloodRefinementPct: BloodRefinementPctTotal? = null
        ): Long {
            val realmConfig = GameConfig.Realm.get(disciple.realm)
            val layerMult = 1.0 + (disciple.realmLayer - 1) * 0.1
            val c = disciple.combat

            val hpVar = 1.0 + c.hpVariance / 100.0
            val paVar = 1.0 + c.physicalAttackVariance / 100.0
            val maVar = 1.0 + c.magicAttackVariance / 100.0
            val pdVar = 1.0 + c.physicalDefenseVariance / 100.0
            val mdVar = 1.0 + c.magicDefenseVariance / 100.0
            val spdVar = 1.0 + c.speedVariance / 100.0

            // 天赋效果汇总
            val talentEffects = mutableMapOf<String, Double>()
            TalentDatabase.getTalentsByIds(disciple.talentIds).forEach { t ->
                t.effects.forEach { (k, v) ->
                    talentEffects[k] = (talentEffects[k] ?: 0.0) + v
                }
            }

            // 血炼与天赋同乘区加算
            val br = bloodRefinementPct
            val attackBonus = (talentEffects["physicalAttack"] ?: 0.0) + (br?.physicalAttackBonusPct ?: 0.0)
            val magicAttackBonus = (talentEffects["magicAttack"] ?: 0.0) + (br?.magicAttackBonusPct ?: 0.0)
            val defenseBonus = (talentEffects["physicalDefense"] ?: 0.0) + (br?.physicalDefenseBonusPct ?: 0.0)
            val magicDefenseBonus = (talentEffects["magicDefense"] ?: 0.0) + (br?.magicDefenseBonusPct ?: 0.0)
            val hpBonus = (talentEffects["maxHp"] ?: 0.0) + (br?.hpBonusPct ?: 0.0)
            val speedBonus = (talentEffects["speed"] ?: 0.0) + (br?.speedBonusPct ?: 0.0)

            val pa = (realmConfig.basePhysicalAttack * paVar * layerMult * (1.0 + attackBonus)).roundToInt()
            val ma = (realmConfig.baseMagicAttack * maVar * layerMult * (1.0 + magicAttackBonus)).roundToInt()
            val pd = (realmConfig.basePhysicalDefense * pdVar * layerMult * (1.0 + defenseBonus)).roundToInt()
            val md = (realmConfig.baseMagicDefense * mdVar * layerMult * (1.0 + magicDefenseBonus)).roundToInt()
            val hp = (realmConfig.baseHp * hpVar * layerMult * (1.0 + hpBonus)).roundToInt()
            val spd = (realmConfig.baseSpeed * spdVar * layerMult * (1.0 + speedBonus)).roundToInt()

            return (pa.toLong() + ma.toLong()) * 5L +
                    hp.toLong() * 4L +
                    (pd.toLong() + md.toLong()) * 3L +
                    spd.toLong() * 2L
        }

        fun fromDisciple(
            disciple: Disciple,
            bloodRefinementPctTotals: Map<String, BloodRefinementPctTotal>? = null
        ): DiscipleCompact = DiscipleCompact(
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
            combatPower = computeCombatPower(disciple, bloodRefinementPctTotals?.get(disciple.id)),
            cultivationSpeed = GameConfig.Cultivation.getRealmPerPhase(disciple.realm) /
                disciple.spiritRoot.types.size.coerceAtLeast(1),
            cultivationSpeedBonus = disciple.cultivationSpeedBonus,
            cultivationSpeedDuration = disciple.cultivationSpeedDuration,
            status = disciple.status.ordinal,
            age = disciple.age
        )
    }
}
