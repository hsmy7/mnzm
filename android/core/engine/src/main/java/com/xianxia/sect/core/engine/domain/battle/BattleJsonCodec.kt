package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.model.CombatSkill
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 战斗 JSON 编解码（生产通道协议，与 C++ battle_json.h 字段逐键对应）。
 *
 * 从 BattleExecutionRouter 拆分（对象函数数超限）；Combatant/CombatSkill/
 * CombatBuff 双向编解码——字段漂移会致跨语言解析错位，协议键由
 * BattleExecutionRouterTest 守卫。
 */
internal object BattleJsonCodec {

    fun combatantJson(c: Combatant) = buildJsonObject {
        put("id", c.id)
        put("name", c.name)
        put("side", c.side.name)
        put("hp", c.hp)
        put("maxHp", c.maxHp)
        put("mp", c.mp)
        put("maxMp", c.maxMp)
        put("physicalAttack", c.physicalAttack)
        put("magicAttack", c.magicAttack)
        put("physicalDefense", c.physicalDefense)
        put("magicDefense", c.magicDefense)
        put("speed", c.speed)
        put("critRate", c.critRate)
        putJsonArray("skills") { c.skills.forEach { add(skillJson(it)) } }
        putJsonArray("buffs") { c.buffs.forEach { add(buffJson(it)) } }
        put("realm", c.realm)
        put("realmLayer", c.realmLayer)
        put("element", c.element)
        put("isBeast", c.isBeast)
        putJsonObject("physique") {
            put("damageAmplification", c.physique.damageAmplification)
            put("critDamageBonus", c.physique.critDamageBonus)
            put("damageReduction", c.physique.damageReduction)
            put("defenseBonus", c.physique.defenseBonus)
        }
        putJsonObject("affix") {
            put("damageAmplification", c.affix.damageAmplification)
            put("critDamageBonus", c.affix.critDamageBonus)
            put("damageReduction", c.affix.damageReduction)
            put("defenseBonus", c.affix.defenseBonus)
        }
    }

    fun skillJson(skill: CombatSkill) = buildJsonObject {
        put("name", skill.name)
        put("skillType", skill.skillType.name)
        put("damageType", skill.damageType.name)
        put("damageMultiplier", skill.damageMultiplier)
        put("mpCost", skill.mpCost)
        put("cooldown", skill.cooldown)
        put("hits", skill.hits)
        put("healPercent", skill.healPercent)
        put("healFixed", skill.healFixed)
        put("healType", skill.healType.name)
        if (skill.buffType != null) put("buffType", skill.buffType!!.name)
        put("buffValue", skill.buffValue)
        put("buffDuration", skill.buffDuration)
        putJsonArray("buffs") {
            skill.buffs.forEach { (t, v, d) ->
                add(buildJsonObject {
                    put("type", t.name); put("value", v); put("duration", d)
                })
            }
        }
        put("currentCooldown", skill.currentCooldown)
        put("isAoe", skill.isAoe)
        put("targetScope", skill.targetScope)
        put("shieldPercent", skill.shieldPercent)
        put("turnAdvancePercent", skill.turnAdvancePercent)
        put("damageSharePercent", skill.damageSharePercent)
        put("damageLinkPercent", skill.damageLinkPercent)
    }

    fun buffJson(buff: CombatBuff) = buildJsonObject {
        put("type", buff.type.name)
        put("value", buff.value)
        put("remainingDuration", buff.remainingDuration)
        put("sourceRealm", buff.sourceRealm)
        put("sourceRealmLayer", buff.sourceRealmLayer)
    }

    fun combatantFromJson(j: JsonObject): Combatant {
        val buffs = (j["buffs"] as? JsonArray)
            ?.mapNotNull { buffFromJson(it.jsonObject) } ?: emptyList()
        val skills = (j["skills"] as? JsonArray)
            ?.mapNotNull { skillFromJson(it.jsonObject) } ?: emptyList()
        val physique = j["physique"]?.jsonObject
        val affix = j["affix"]?.jsonObject
        return Combatant(
            id = j.str("id"),
            name = j.str("name"),
            side = if (j.str("side") == "ATTACKER") CombatantSide.ATTACKER
            else CombatantSide.DEFENDER,
            hp = j.int("hp"),
            maxHp = j.int("maxHp"),
            mp = j.int("mp"),
            maxMp = j.int("maxMp"),
            physicalAttack = j.int("physicalAttack"),
            magicAttack = j.int("magicAttack"),
            physicalDefense = j.int("physicalDefense"),
            magicDefense = j.int("magicDefense"),
            speed = j.int("speed"),
            critRate = j.dbl("critRate", 0.05),
            skills = skills,
            buffs = buffs,
            realm = j.int("realm", 9),
            realmLayer = j.int("realmLayer"),
            element = j.str("element"),
            isBeast = j["isBeast"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            physique = PhysiqueCombatFactors(
                damageAmplification = physique?.dbl("damageAmplification") ?: 0.0,
                critDamageBonus = physique?.dbl("critDamageBonus") ?: 0.0,
                damageReduction = physique?.dbl("damageReduction") ?: 0.0,
                defenseBonus = physique?.dbl("defenseBonus") ?: 0.0
            ),
            affix = com.xianxia.sect.core.registry.AffixCombatEffects(
                damageAmplification = affix?.dbl("damageAmplification") ?: 0.0,
                critDamageBonus = affix?.dbl("critDamageBonus") ?: 0.0,
                damageReduction = affix?.dbl("damageReduction") ?: 0.0,
                defenseBonus = affix?.dbl("defenseBonus") ?: 0.0
            )
        )
    }

    fun buffFromJson(o: JsonObject): CombatBuff = CombatBuff(
        type = BuffType.valueOf(o.str("type", "HP_BOOST")),
        value = o.dbl("value"),
        remainingDuration = o.int("remainingDuration"),
        sourceRealm = o.int("sourceRealm", 9),
        sourceRealmLayer = o.int("sourceRealmLayer")
    )

    fun skillFromJson(o: JsonObject): CombatSkill {
        val multiBuffs = (o["buffs"] as? JsonArray)?.mapNotNull { be ->
            val bo = be.jsonObject
            Triple(
                BuffType.valueOf(bo.str("type", "HP_BOOST")),
                bo.dbl("value"),
                bo.int("duration")
            )
        } ?: emptyList()
        return CombatSkill(
            name = o.str("name"),
            skillType = if (o.str("skillType") == "SUPPORT") SkillType.SUPPORT
            else SkillType.ATTACK,
            damageType = if (o.str("damageType") == "MAGIC") DamageType.MAGIC
            else DamageType.PHYSICAL,
            damageMultiplier = o.dbl("damageMultiplier", 1.0),
            mpCost = o.int("mpCost"),
            cooldown = o.int("cooldown"),
            hits = o.int("hits", 1),
            healPercent = o.dbl("healPercent"),
            healFixed = o.int("healFixed"),
            healType = if (o.str("healType") == "MP") HealType.MP else HealType.HP,
            buffType = o["buffType"]?.jsonPrimitive?.content?.let { BuffType.valueOf(it) },
            buffValue = o.dbl("buffValue"),
            buffDuration = o.int("buffDuration"),
            buffs = multiBuffs,
            currentCooldown = o.int("currentCooldown"),
            isAoe = o["isAoe"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            targetScope = o.str("targetScope", "self"),
            shieldPercent = o.dbl("shieldPercent"),
            turnAdvancePercent = o.dbl("turnAdvancePercent"),
            damageSharePercent = o.dbl("damageSharePercent"),
            damageLinkPercent = o.dbl("damageLinkPercent")
        )
    }

    // ── JSON 数值/字符串读取辅助 ──────────────────────────────────

    private fun JsonObject.int(key: String, default: Int = 0): Int =
        this[key]?.jsonPrimitive?.content?.toIntOrNull() ?: default

    private fun JsonObject.dbl(key: String, default: Double = 0.0): Double =
        this[key]?.jsonPrimitive?.content?.toDoubleOrNull() ?: default

    private fun JsonObject.str(key: String, default: String = ""): String =
        this[key]?.jsonPrimitive?.content ?: default
}
