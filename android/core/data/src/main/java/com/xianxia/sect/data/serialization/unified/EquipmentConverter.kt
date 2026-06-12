package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.*

internal class EquipmentConverter {

    fun convertEquipment(equipment: com.xianxia.sect.core.model.EquipmentInstance): SerializableEquipment {
        return SerializableEquipment(
            id = equipment.id,
            name = equipment.name,
            type = equipment.slot.name,
            rarity = equipment.rarity,
            level = equipment.nurtureLevel,
            stats = mapOf(
                "physicalAttack" to equipment.physicalAttack,
                "magicAttack" to equipment.magicAttack,
                "physicalDefense" to equipment.physicalDefense,
                "magicDefense" to equipment.magicDefense,
                "speed" to equipment.speed,
                "hp" to equipment.hp,
                "mp" to equipment.mp
            ),
            description = equipment.description,
            critChance = equipment.critChance,
            isEquipped = equipment.isEquipped,
            nurtureLevel = equipment.nurtureLevel,
            nurtureProgress = equipment.nurtureProgress,
            minRealm = equipment.minRealm,
            ownerId = equipment.ownerId ?: "",
            quantity = 1
        )
    }

    fun convertBackEquipment(data: SerializableEquipment): com.xianxia.sect.core.model.EquipmentInstance {
        val ownerId = data.ownerId.ifEmpty { null }

        return com.xianxia.sect.core.model.EquipmentInstance(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            slot = safeEnumValueOf(data.type, com.xianxia.sect.core.model.EquipmentSlot.WEAPON, "type", "Equipment"),
            physicalAttack = data.stats["physicalAttack"] ?: 0,
            magicAttack = data.stats["magicAttack"] ?: 0,
            physicalDefense = data.stats["physicalDefense"] ?: 0,
            magicDefense = data.stats["magicDefense"] ?: 0,
            speed = data.stats["speed"] ?: 0,
            hp = data.stats["hp"] ?: 0,
            mp = data.stats["mp"] ?: 0,
            description = data.description,
            critChance = data.critChance,
            nurtureLevel = data.nurtureLevel,
            nurtureProgress = data.nurtureProgress,
            minRealm = data.minRealm ?: 9,
            ownerId = ownerId,
            isEquipped = data.isEquipped
        )
    }

    fun convertAIRandomEquipment(equipment: com.xianxia.sect.core.model.AIRandomEquipment): SerializableAIRandomEquipment {
        return SerializableAIRandomEquipment(
            slot = equipment.slot.name,
            name = equipment.name,
            rarity = equipment.rarity,
            nurtureLevel = equipment.nurtureLevel,
            physicalAttack = equipment.physicalAttack,
            magicAttack = equipment.magicAttack,
            physicalDefense = equipment.physicalDefense,
            magicDefense = equipment.magicDefense,
            speed = equipment.speed,
            hp = equipment.hp,
            mp = equipment.mp
        )
    }

    fun convertBackAIRandomEquipment(data: SerializableAIRandomEquipment): com.xianxia.sect.core.model.AIRandomEquipment {
        return com.xianxia.sect.core.model.AIRandomEquipment(
            slot = safeEnumValueOf(data.slot, com.xianxia.sect.core.model.EquipmentSlot.WEAPON, "slot", "AIRandomEquipment"),
            name = data.name,
            rarity = data.rarity,
            nurtureLevel = data.nurtureLevel,
            physicalAttack = data.physicalAttack,
            magicAttack = data.magicAttack,
            physicalDefense = data.physicalDefense,
            magicDefense = data.magicDefense,
            speed = data.speed,
            hp = data.hp,
            mp = data.mp
        )
    }

    fun convertAIRandomManual(manual: com.xianxia.sect.core.model.AIRandomManual): SerializableAIRandomManual {
        return SerializableAIRandomManual(
            name = manual.name,
            rarity = manual.rarity,
            mastery = manual.mastery,
            stats = manual.stats
        )
    }

    fun convertBackAIRandomManual(data: SerializableAIRandomManual): com.xianxia.sect.core.model.AIRandomManual {
        return com.xianxia.sect.core.model.AIRandomManual(
            name = data.name,
            rarity = data.rarity,
            mastery = data.mastery,
            stats = data.stats
        )
    }

    fun convertAICaveDisciple(disciple: com.xianxia.sect.core.model.AICaveDisciple): SerializableAICaveDisciple {
        return SerializableAICaveDisciple(
            id = disciple.id,
            name = disciple.name,
            realm = disciple.realm,
            realmName = disciple.realmName,
            hp = disciple.hp,
            maxHp = disciple.maxHp,
            mp = disciple.mp,
            maxMp = disciple.maxMp,
            physicalAttack = disciple.physicalAttack,
            magicAttack = disciple.magicAttack,
            physicalDefense = disciple.physicalDefense,
            magicDefense = disciple.magicDefense,
            speed = disciple.speed,
            critRate = disciple.critRate,
            equipments = disciple.equipments.map { convertAIRandomEquipment(it) },
            manuals = disciple.manuals.map { convertAIRandomManual(it) }
        )
    }

    fun convertBackAICaveDisciple(data: SerializableAICaveDisciple): com.xianxia.sect.core.model.AICaveDisciple {
        return com.xianxia.sect.core.model.AICaveDisciple(
            id = data.id,
            name = data.name,
            realm = data.realm,
            realmName = data.realmName,
            hp = data.hp,
            maxHp = data.maxHp,
            mp = data.mp,
            maxMp = data.maxMp,
            physicalAttack = data.physicalAttack,
            magicAttack = data.magicAttack,
            physicalDefense = data.physicalDefense,
            magicDefense = data.magicDefense,
            speed = data.speed,
            critRate = data.critRate,
            equipments = data.equipments.map { convertBackAIRandomEquipment(it) },
            manuals = data.manuals.map { convertBackAIRandomManual(it) }
        )
    }

    fun convertAICaveTeam(team: com.xianxia.sect.core.model.AICaveTeam): SerializableAICaveTeam {
        return SerializableAICaveTeam(
            id = team.id,
            sectId = team.sectId,
            sectName = team.sectName,
            targetCaveId = team.caveId,
            disciples = team.disciples.map { convertAICaveDisciple(it) },
            status = team.status.name,
            startYear = 0,
            startMonth = 0,
            memberCount = team.memberCount,
            avgRealm = team.avgRealm,
            avgRealmName = team.avgRealmName
        )
    }

    fun convertBackAICaveTeam(data: SerializableAICaveTeam): com.xianxia.sect.core.model.AICaveTeam {
        return com.xianxia.sect.core.model.AICaveTeam(
            id = data.id,
            caveId = data.targetCaveId,
            sectId = data.sectId,
            sectName = data.sectName,
            disciples = data.disciples.map { convertBackAICaveDisciple(it) },
            status = safeEnumValueOf(data.status, com.xianxia.sect.core.model.AITeamStatus.EXPLORING, "status", "AICaveTeam"),
            memberCount = data.memberCount,
            avgRealm = data.avgRealm,
            avgRealmName = data.avgRealmName
        )
    }
}
