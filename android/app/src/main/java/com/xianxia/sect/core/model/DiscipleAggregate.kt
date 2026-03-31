package com.xianxia.sect.core.model

data class DiscipleAggregate(
    val core: DiscipleCore,
    val combatStats: DiscipleCombatStats?,
    val equipment: DiscipleEquipment?,
    val extended: DiscipleExtended?,
    val attributes: DiscipleAttributes?
) {
    val id: String get() = core.id
    val name: String get() = core.name
    val realm: Int get() = core.realm
    val realmLayer: Int get() = core.realmLayer
    val cultivation: Double get() = core.cultivation
    val isAlive: Boolean get() = core.isAlive
    val status: DiscipleStatus get() = runCatching { 
        DiscipleStatus.valueOf(core.status) 
    }.getOrElse { DiscipleStatus.IDLE }
    val discipleType: String get() = core.discipleType
    val age: Int get() = core.age
    val lifespan: Int get() = core.lifespan
    val gender: String get() = core.gender
    val spiritRootType: String get() = core.spiritRootType
    val recruitedMonth: Int get() = core.recruitedMonth
    
    val baseHp: Int get() = combatStats?.baseHp ?: 100
    val baseMp: Int get() = combatStats?.baseMp ?: 50
    val basePhysicalAttack: Int get() = combatStats?.basePhysicalAttack ?: 7
    val baseMagicAttack: Int get() = combatStats?.baseMagicAttack ?: 7
    val basePhysicalDefense: Int get() = combatStats?.basePhysicalDefense ?: 5
    val baseMagicDefense: Int get() = combatStats?.baseMagicDefense ?: 3
    val baseSpeed: Int get() = combatStats?.baseSpeed ?: 10
    
    val hpVariance: Int get() = combatStats?.hpVariance ?: 0
    val mpVariance: Int get() = combatStats?.mpVariance ?: 0
    val physicalAttackVariance: Int get() = combatStats?.physicalAttackVariance ?: 0
    val magicAttackVariance: Int get() = combatStats?.magicAttackVariance ?: 0
    val physicalDefenseVariance: Int get() = combatStats?.physicalDefenseVariance ?: 0
    val magicDefenseVariance: Int get() = combatStats?.magicDefenseVariance ?: 0
    val speedVariance: Int get() = combatStats?.speedVariance ?: 0
    
    val pillPhysicalAttackBonus: Double get() = combatStats?.pillPhysicalAttackBonus ?: 0.0
    val pillMagicAttackBonus: Double get() = combatStats?.pillMagicAttackBonus ?: 0.0
    val pillPhysicalDefenseBonus: Double get() = combatStats?.pillPhysicalDefenseBonus ?: 0.0
    val pillMagicDefenseBonus: Double get() = combatStats?.pillMagicDefenseBonus ?: 0.0
    val pillHpBonus: Double get() = combatStats?.pillHpBonus ?: 0.0
    val pillMpBonus: Double get() = combatStats?.pillMpBonus ?: 0.0
    val pillSpeedBonus: Double get() = combatStats?.pillSpeedBonus ?: 0.0
    val pillEffectDuration: Int get() = combatStats?.pillEffectDuration ?: 0
    val battlesWon: Int get() = combatStats?.battlesWon ?: 0
    val totalCultivation: Long get() = combatStats?.totalCultivation ?: 0
    val breakthroughCount: Int get() = combatStats?.breakthroughCount ?: 0
    val breakthroughFailCount: Int get() = combatStats?.breakthroughFailCount ?: 0
    
    val weaponId: String? get() = equipment?.weaponId
    val armorId: String? get() = equipment?.armorId
    val bootsId: String? get() = equipment?.bootsId
    val accessoryId: String? get() = equipment?.accessoryId
    val weaponNurture: EquipmentNurtureData? get() = equipment?.weaponNurture
    val armorNurture: EquipmentNurtureData? get() = equipment?.armorNurture
    val bootsNurture: EquipmentNurtureData? get() = equipment?.bootsNurture
    val accessoryNurture: EquipmentNurtureData? get() = equipment?.accessoryNurture
    val storageBagItems: List<StorageBagItem> get() = equipment?.storageBagItems ?: emptyList()
    val storageBagSpiritStones: Long get() = equipment?.storageBagSpiritStones ?: 0
    val spiritStones: Int get() = equipment?.spiritStones ?: 0
    val soulPower: Int get() = equipment?.soulPower ?: 10
    
    val manualIds: List<String> get() = extended?.manualIds ?: emptyList()
    val talentIds: List<String> get() = extended?.talentIds ?: emptyList()
    val manualMasteries: Map<String, Int> get() = extended?.manualMasteries ?: emptyMap()
    val statusData: Map<String, String> get() = extended?.statusData ?: emptyMap()
    val cultivationSpeedBonus: Double get() = extended?.cultivationSpeedBonus ?: 1.0
    val cultivationSpeedDuration: Int get() = extended?.cultivationSpeedDuration ?: 0
    val partnerId: String? get() = extended?.partnerId
    val partnerSectId: String? get() = extended?.partnerSectId
    val parentId1: String? get() = extended?.parentId1
    val parentId2: String? get() = extended?.parentId2
    val lastChildYear: Int get() = extended?.lastChildYear ?: 0
    val griefEndYear: Int? get() = extended?.griefEndYear
    val monthlyUsedPillIds: List<String> get() = extended?.monthlyUsedPillIds ?: emptyList()
    val usedExtendLifePillIds: List<String> get() = extended?.usedExtendLifePillIds ?: emptyList()
    val hasReviveEffect: Boolean get() = extended?.hasReviveEffect ?: false
    val hasClearAllEffect: Boolean get() = extended?.hasClearAllEffect ?: false
    
    val intelligence: Int get() = attributes?.intelligence ?: 50
    val charm: Int get() = attributes?.charm ?: 50
    val loyalty: Int get() = attributes?.loyalty ?: 50
    val comprehension: Int get() = attributes?.comprehension ?: 50
    val artifactRefining: Int get() = attributes?.artifactRefining ?: 50
    val pillRefining: Int get() = attributes?.pillRefining ?: 50
    val spiritPlanting: Int get() = attributes?.spiritPlanting ?: 50
    val teaching: Int get() = attributes?.teaching ?: 50
    val morality: Int get() = attributes?.morality ?: 50
    val salaryPaidCount: Int get() = attributes?.salaryPaidCount ?: 0
    val salaryMissedCount: Int get() = attributes?.salaryMissedCount ?: 0
    
    fun toDisciple(): Disciple {
        return Disciple(
            id = id,
            name = name,
            realm = realm,
            realmLayer = realmLayer,
            cultivation = cultivation,
            spiritRootType = spiritRootType,
            age = age,
            lifespan = lifespan,
            isAlive = isAlive,
            gender = gender,
            partnerId = partnerId,
            partnerSectId = partnerSectId,
            parentId1 = parentId1,
            parentId2 = parentId2,
            lastChildYear = lastChildYear,
            griefEndYear = griefEndYear,
            weaponId = weaponId,
            armorId = armorId,
            bootsId = bootsId,
            accessoryId = accessoryId,
            manualIds = manualIds,
            talentIds = talentIds,
            manualMasteries = manualMasteries,
            weaponNurture = weaponNurture,
            armorNurture = armorNurture,
            bootsNurture = bootsNurture,
            accessoryNurture = accessoryNurture,
            spiritStones = spiritStones,
            soulPower = soulPower,
            storageBagItems = storageBagItems,
            storageBagSpiritStones = storageBagSpiritStones,
            status = status,
            statusData = statusData,
            cultivationSpeedBonus = cultivationSpeedBonus,
            cultivationSpeedDuration = cultivationSpeedDuration,
            pillPhysicalAttackBonus = pillPhysicalAttackBonus,
            pillMagicAttackBonus = pillMagicAttackBonus,
            pillPhysicalDefenseBonus = pillPhysicalDefenseBonus,
            pillMagicDefenseBonus = pillMagicDefenseBonus,
            pillHpBonus = pillHpBonus,
            pillMpBonus = pillMpBonus,
            pillSpeedBonus = pillSpeedBonus,
            pillEffectDuration = pillEffectDuration,
            totalCultivation = totalCultivation,
            breakthroughCount = breakthroughCount,
            breakthroughFailCount = breakthroughFailCount,
            battlesWon = battlesWon,
            intelligence = intelligence,
            charm = charm,
            loyalty = loyalty,
            comprehension = comprehension,
            artifactRefining = artifactRefining,
            pillRefining = pillRefining,
            spiritPlanting = spiritPlanting,
            teaching = teaching,
            morality = morality,
            salaryPaidCount = salaryPaidCount,
            salaryMissedCount = salaryMissedCount,
            recruitedMonth = recruitedMonth,
            hpVariance = hpVariance,
            mpVariance = mpVariance,
            physicalAttackVariance = physicalAttackVariance,
            magicAttackVariance = magicAttackVariance,
            physicalDefenseVariance = physicalDefenseVariance,
            magicDefenseVariance = magicDefenseVariance,
            speedVariance = speedVariance,
            baseHp = baseHp,
            baseMp = baseMp,
            basePhysicalAttack = basePhysicalAttack,
            baseMagicAttack = baseMagicAttack,
            basePhysicalDefense = basePhysicalDefense,
            baseMagicDefense = baseMagicDefense,
            baseSpeed = baseSpeed,
            discipleType = discipleType,
            monthlyUsedPillIds = monthlyUsedPillIds,
            usedExtendLifePillIds = usedExtendLifePillIds,
            hasReviveEffect = hasReviveEffect,
            hasClearAllEffect = hasClearAllEffect
        )
    }
    
    companion object {
        fun fromDisciple(disciple: Disciple): DiscipleAggregate {
            return DiscipleAggregate(
                core = DiscipleCore.fromDisciple(disciple),
                combatStats = DiscipleCombatStats.fromDisciple(disciple),
                equipment = DiscipleEquipment.fromDisciple(disciple),
                extended = DiscipleExtended.fromDisciple(disciple),
                attributes = DiscipleAttributes.fromDisciple(disciple)
            )
        }
    }
}
