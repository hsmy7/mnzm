package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.*

internal class ItemConverter {

    fun convertPill(pill: com.xianxia.sect.core.model.Pill): SerializablePill {
        return SerializablePill(
            id = pill.id,
            name = pill.name,
            type = pill.category.name,
            rarity = pill.rarity,
            effects = SerializablePillEffect(
                breakthroughChance = pill.effects.breakthroughChance,
                targetRealm = pill.effects.targetRealm,
                isAscension = pill.effects.isAscension,
                cultivationSpeedPercent = pill.effects.cultivationSpeedPercent,
                skillExpSpeedPercent = pill.effects.skillExpSpeedPercent,
                nurtureSpeedPercent = pill.effects.nurtureSpeedPercent,
                cultivationAdd = pill.effects.cultivationAdd,
                skillExpAdd = pill.effects.skillExpAdd,
                nurtureAdd = pill.effects.nurtureAdd,
                duration = pill.effects.duration,
                cannotStack = pill.effects.cannotStack,
                physicalAttackAdd = pill.effects.physicalAttackAdd,
                magicAttackAdd = pill.effects.magicAttackAdd,
                physicalDefenseAdd = pill.effects.physicalDefenseAdd,
                magicDefenseAdd = pill.effects.magicDefenseAdd,
                hpAdd = pill.effects.hpAdd,
                mpAdd = pill.effects.mpAdd,
                speedAdd = pill.effects.speedAdd,
                critRateAdd = pill.effects.critRateAdd,
                critEffectAdd = pill.effects.critEffectAdd,
                extendLife = pill.effects.extendLife,
                intelligenceAdd = pill.effects.intelligenceAdd,
                charmAdd = pill.effects.charmAdd,
                loyaltyAdd = pill.effects.loyaltyAdd,
                comprehensionAdd = pill.effects.comprehensionAdd,
                artifactRefiningAdd = pill.effects.artifactRefiningAdd,
                pillRefiningAdd = pill.effects.pillRefiningAdd,
                spiritPlantingAdd = pill.effects.spiritPlantingAdd,
                teachingAdd = pill.effects.teachingAdd,
                moralityAdd = pill.effects.moralityAdd,
                miningAdd = pill.effects.miningAdd,
                healMaxHpPercent = pill.effects.healMaxHpPercent,
                mpRecoverMaxMpPercent = pill.effects.mpRecoverMaxMpPercent,
                revive = pill.effects.revive,
                clearAll = pill.effects.clearAll
            ),
            description = pill.description,
            quantity = pill.quantity,
            category = pill.category.name,
            grade = pill.grade.name,
            minRealm = pill.minRealm,
            isLocked = pill.isLocked
        )
    }

    fun convertBackPill(data: SerializablePill): com.xianxia.sect.core.model.Pill {
        val pillEffect = data.effects

        return com.xianxia.sect.core.model.Pill(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            category = safeEnumValueOf(
                    if (data.category == "CULTIVATION" && data.type.isNotEmpty()) data.type else data.category,
                    com.xianxia.sect.core.model.PillCategory.CULTIVATION, "category", "Pill"
                ),
            effects = com.xianxia.sect.core.model.PillEffect(
                breakthroughChance = pillEffect.breakthroughChance,
                targetRealm = pillEffect.targetRealm,
                isAscension = pillEffect.isAscension,
                cultivationSpeedPercent = pillEffect.cultivationSpeedPercent,
                skillExpSpeedPercent = pillEffect.skillExpSpeedPercent,
                nurtureSpeedPercent = pillEffect.nurtureSpeedPercent,
                cultivationAdd = pillEffect.cultivationAdd,
                skillExpAdd = pillEffect.skillExpAdd,
                nurtureAdd = pillEffect.nurtureAdd,
                duration = pillEffect.duration,
                cannotStack = pillEffect.cannotStack,
                physicalAttackAdd = pillEffect.physicalAttackAdd,
                magicAttackAdd = pillEffect.magicAttackAdd,
                physicalDefenseAdd = pillEffect.physicalDefenseAdd,
                magicDefenseAdd = pillEffect.magicDefenseAdd,
                hpAdd = pillEffect.hpAdd,
                mpAdd = pillEffect.mpAdd,
                speedAdd = pillEffect.speedAdd,
                critRateAdd = pillEffect.critRateAdd,
                critEffectAdd = pillEffect.critEffectAdd,
                extendLife = pillEffect.extendLife,
                intelligenceAdd = pillEffect.intelligenceAdd,
                charmAdd = pillEffect.charmAdd,
                loyaltyAdd = pillEffect.loyaltyAdd,
                comprehensionAdd = pillEffect.comprehensionAdd,
                artifactRefiningAdd = pillEffect.artifactRefiningAdd,
                pillRefiningAdd = pillEffect.pillRefiningAdd,
                spiritPlantingAdd = pillEffect.spiritPlantingAdd,
                teachingAdd = pillEffect.teachingAdd,
                moralityAdd = pillEffect.moralityAdd,
                miningAdd = pillEffect.miningAdd,
                healMaxHpPercent = pillEffect.healMaxHpPercent,
                mpRecoverMaxMpPercent = pillEffect.mpRecoverMaxMpPercent,
                revive = pillEffect.revive,
                clearAll = pillEffect.clearAll
            ),
            minRealm = data.minRealm,
            grade = safeEnumValueOf(data.grade, com.xianxia.sect.core.model.PillGrade.MEDIUM, "grade", "Pill"),
            description = data.description,
            quantity = data.quantity,
            isLocked = data.isLocked
        )
    }

    fun convertMaterial(material: com.xianxia.sect.core.model.Material): SerializableMaterial {
        return SerializableMaterial(
            id = material.id,
            name = material.name,
            type = material.category.name,
            rarity = material.rarity,
            quantity = material.quantity,
            description = material.description
        )
    }

    fun convertBackMaterial(data: SerializableMaterial): com.xianxia.sect.core.model.Material {
        val migratedName = data.name
            .replace("蛇皮", "蛇鳞")
            .replace("蛇骨", "蛇血")
            .replace("毒牙", "蛇牙")
            .replace("龙骨", "龙爪")
            .replace("龟甲", "龟血")
        val migratedType = when {
            data.name.contains("蛇骨") -> "BEAST_BLOOD"
            data.name.contains("龙骨") -> "BEAST_CLAW"
            data.name.contains("龟甲") -> "BEAST_BLOOD"
            else -> data.type
        }
        return com.xianxia.sect.core.model.Material(
            id = data.id,
            name = migratedName,
            rarity = data.rarity,
            category = safeEnumValueOf(migratedType, com.xianxia.sect.core.model.MaterialCategory.BEAST_HIDE, "type", "Material"),
            quantity = data.quantity,
            description = data.description
        )
    }

    fun convertHerb(herb: com.xianxia.sect.core.model.Herb): SerializableHerb {
        return SerializableHerb(
            id = herb.id,
            name = herb.name,
            rarity = herb.rarity,
            quantity = herb.quantity,
            age = 0,
            description = herb.description
        )
    }

    fun convertBackHerb(data: SerializableHerb): com.xianxia.sect.core.model.Herb {
        return com.xianxia.sect.core.model.Herb(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            quantity = data.quantity,
            description = data.description
        )
    }

    fun convertSeed(seed: com.xianxia.sect.core.model.Seed): SerializableSeed {
        return SerializableSeed(
            id = seed.id,
            name = seed.name,
            rarity = seed.rarity,
            growTime = seed.growTime,
            yieldMin = seed.yield,
            yieldMax = seed.yield,
            quantity = seed.quantity,
            description = seed.description
        )
    }

    fun convertBackSeed(data: SerializableSeed): com.xianxia.sect.core.model.Seed {
        return com.xianxia.sect.core.model.Seed(
            id = data.id,
            name = data.name,
            rarity = data.rarity,
            growTime = data.growTime,
            yield = data.yieldMin,
            quantity = data.quantity,
            description = data.description
        )
    }
}
