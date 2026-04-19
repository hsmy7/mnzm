@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.model

import com.xianxia.sect.core.engine.DiscipleStatCalculator

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
    
    // 计算属性 - 与旧 Disciple 类保持 API 一致性
    val spiritRoot: SpiritRoot get() = SpiritRoot(spiritRootType)
    val spiritRootName: String get() = spiritRoot.name
    val realmName: String get() {
        if (age < 5 || realmLayer == 0) return "无境界"
        // 仙人境界不显示层数
        if (realm == 0) return com.xianxia.sect.core.GameConfig.Realm.getName(realm)
        return "${com.xianxia.sect.core.GameConfig.Realm.getName(realm)}${realmLayer}层"
    }
    
    val baseHp: Int get() = combatStats?.baseHp ?: 100
    val baseMp: Int get() = combatStats?.baseMp ?: 50
    val maxHp: Int get() = baseHp  // 保持与旧 Disciple 类 API 一致性
    val maxMp: Int get() = baseMp  // 保持与旧 Disciple 类 API 一致性
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
    
    val pillPhysicalAttackBonus: Int get() = combatStats?.pillPhysicalAttackBonus ?: 0
    val pillMagicAttackBonus: Int get() = combatStats?.pillMagicAttackBonus ?: 0
    val pillPhysicalDefenseBonus: Int get() = combatStats?.pillPhysicalDefenseBonus ?: 0
    val pillMagicDefenseBonus: Int get() = combatStats?.pillMagicDefenseBonus ?: 0
    val pillHpBonus: Int get() = combatStats?.pillHpBonus ?: 0
    val pillMpBonus: Int get() = combatStats?.pillMpBonus ?: 0
    val pillSpeedBonus: Int get() = combatStats?.pillSpeedBonus ?: 0
    val pillEffectDuration: Int get() = combatStats?.pillEffectDuration ?: 0
    val pillCritRateBonus: Double get() = combatStats?.pillCritRateBonus ?: 0.0
    val pillCritEffectBonus: Double get() = combatStats?.pillCritEffectBonus ?: 0.0
    val pillCultivationSpeedBonus: Double get() = combatStats?.pillCultivationSpeedBonus ?: 0.0
    val pillSkillExpSpeedBonus: Double get() = combatStats?.pillSkillExpSpeedBonus ?: 0.0
    val pillNurtureSpeedBonus: Double get() = combatStats?.pillNurtureSpeedBonus ?: 0.0
    val activePillCategory: String get() = combatStats?.activePillCategory ?: ""
    val totalCultivation: Long get() = combatStats?.totalCultivation ?: 0
    val breakthroughCount: Int get() = combatStats?.breakthroughCount ?: 0
    val breakthroughFailCount: Int get() = combatStats?.breakthroughFailCount ?: 0
    val currentHp: Int get() = combatStats?.currentHp ?: -1
    val currentMp: Int get() = combatStats?.currentMp ?: -1
    
    val weaponId: String get() = equipment?.weaponId ?: ""
    val armorId: String get() = equipment?.armorId ?: ""
    val bootsId: String get() = equipment?.bootsId ?: ""
    val accessoryId: String get() = equipment?.accessoryId ?: ""
    val weaponNurture: EquipmentNurtureData get() = equipment?.weaponNurture ?: EquipmentNurtureData("", 0)
    val armorNurture: EquipmentNurtureData get() = equipment?.armorNurture ?: EquipmentNurtureData("", 0)
    val bootsNurture: EquipmentNurtureData get() = equipment?.bootsNurture ?: EquipmentNurtureData("", 0)
    val accessoryNurture: EquipmentNurtureData get() = equipment?.accessoryNurture ?: EquipmentNurtureData("", 0)
    val storageBagItems: List<StorageBagItem> get() = equipment?.storageBagItems ?: emptyList()
    val storageBagSpiritStones: Long get() = equipment?.storageBagSpiritStones ?: 0
    val spiritStones: Int get() = equipment?.spiritStones ?: 0
    val soulPower: Int get() = equipment?.soulPower ?: 10
    
    val manualIds: List<String> get() = extended?.manualIds ?: emptyList()
    val talentIds: List<String> get() = extended?.talentIds ?: emptyList()
    val manualMasteries: Map<String, Int> get() = extended?.manualMasteries ?: emptyMap()
    val statusData: Map<String, String> get() = extended?.statusData ?: emptyMap()
    val cultivationSpeedBonus: Double get() = extended?.cultivationSpeedBonus ?: 0.0
    val cultivationSpeedDuration: Int get() = extended?.cultivationSpeedDuration ?: 0
    val partnerId: String? get() = extended?.partnerId
    val partnerSectId: String? get() = extended?.partnerSectId
    val parentId1: String? get() = extended?.parentId1
    val parentId2: String? get() = extended?.parentId2
    val lastChildYear: Int get() = extended?.lastChildYear ?: 0
    val griefEndYear: Int? get() = extended?.griefEndYear
    val usedFunctionalPillTypes: List<String> get() = extended?.usedFunctionalPillTypes ?: emptyList()
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
    
    // ==================== 从 DiscipleCore 委托的便捷属性 ====================
    val canCultivate: Boolean get() = core.canCultivate
    val realmNameOnly: String get() = core.realmNameOnly
    val maxCultivation: Double get() = core.maxCultivation
    val cultivationProgress: Double get() = core.cultivationProgress
    val genderName: String get() = core.genderName
    val genderSymbol: String get() = core.genderSymbol
    
    // ==================== 计算属性（与旧 Disciple 类保持一致）====================
    
    /**
     * 物理攻击（基础值，不含装备/功法加成）
     * 与旧 Disciple.physicalAttack 保持一致：通过 getBaseStats() 计算
     */
    val physicalAttack: Int get() = getBaseStats().physicalAttack
    
    /** 物理防御 */
    val physicalDefense: Int get() = getBaseStats().physicalDefense
    
    /** 法术攻击 */
    val magicAttack: Int get() = getBaseStats().magicAttack
    
    /** 法术防御 */
    val magicDefense: Int get() = getBaseStats().magicDefense
    
    /** 速度 */
    val speed: Int get() = getBaseStats().speed
    
    /** 最大生命值（通过完整计算）*/
    val maxHpFinal: Int get() = getBaseStats().maxHp
    
    /** 最大灵力值（通过完整计算）*/
    val maxMpFinal: Int get() = getBaseStats().maxMp
    
    /** 当前生命百分比 */
    val hpPercent: Double get() = if (maxHpFinal > 0) baseHp.toDouble() / maxHpFinal * 100 else 100.0
    
    /** 当前灵力百分比 */
    val mpPercent: Double get() = if (maxMpFinal > 0) baseMp.toDouble() / maxMpFinal * 100 else 100.0
    
    /** 是否有道侣 */
    val hasPartner: Boolean get() = partnerId != null
    
    /** 悟性速度加成 */
    val comprehensionSpeedBonus: Double get() = comprehension / 100.0
    
    // 已装备的物品映射（简化版，返回空map）
    val equippedItems: Map<EquipmentSlot, Equipment?> get() = emptyMap()
    
    // ==================== 计算方法（委托给 DiscipleStatCalculator）====================
    
    /**
     * 计算弟子的基础属性（不含装备和功法加成）
     * 与旧 Disciple.getBaseStats() 保持完全一致
     */
    fun getBaseStats(): DiscipleStats {
        // 将 DiscipleAggregate 转换为 Disciple 后调用计算器
        return DiscipleStatCalculator.getBaseStats(this.toDisciple())
    }
    
    /**
     * 获取弟子所有天赋的效果汇总
     */
    fun getTalentEffects(): Map<String, Double> {
        return DiscipleStatCalculator.getTalentEffects(this.toDisciple())
    }
    
    /**
     * 计算弟子穿戴装备后的属性（不含功法和丹药）
     */
    fun getStatsWithEquipment(equipments: Map<String, Equipment>): DiscipleStats {
        return DiscipleStatCalculator.getStatsWithEquipment(this.toDisciple(), equipments)
    }
    
    /**
     * 计算弟子的最终完整属性
     * 与旧 Disciple.getFinalStats() 保持完全一致
     */
    fun getFinalStats(
        equipments: Map<String, Equipment>,
        manuals: Map<String, Manual>,
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()
    ): DiscipleStats {
        return DiscipleStatCalculator.getFinalStats(
            this.toDisciple(), equipments, manuals, manualProficiencies
        )
    }
    
    /**
     * 计算修炼速度（支持外部传入功法和熟练度数据）
     */
    fun calculateCultivationSpeed(
        manuals: Map<String, Manual> = emptyMap(),
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
        additionalBonus: Double = 0.0,
        buildingBonus: Double = 1.0,
        preachingElderBonus: Double = 0.0,
        preachingMastersBonus: Double = 0.0,
        cultivationSubsidyBonus: Double = 0.0
    ): Double {
        return DiscipleStatCalculator.calculateCultivationSpeed(
            this.toDisciple(), manuals, manualProficiencies,
            buildingBonus = buildingBonus,
            additionalBonus = additionalBonus,
            preachingElderBonus = preachingElderBonus,
            preachingMastersBonus = preachingMastersBonus,
            cultivationSubsidyBonus = cultivationSubsidyBonus
        )
    }
    
    /** 判断弟子是否可以突破 */
    fun canBreakthrough(): Boolean = core.canBreakthrough()
    
    /**
     * 计算突破成功率
     */
    fun getBreakthroughChance(): Double {
        return DiscipleStatCalculator.getBreakthroughChance(this.toDisciple())
    }
    
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
            manualIds = manualIds,
            talentIds = talentIds,
            manualMasteries = manualMasteries,
            status = status,
            statusData = statusData,
            cultivationSpeedBonus = cultivationSpeedBonus,
            cultivationSpeedDuration = cultivationSpeedDuration,
            discipleType = discipleType,
            combat = CombatAttributes(
                baseHp = baseHp,
                baseMp = baseMp,
                basePhysicalAttack = basePhysicalAttack,
                baseMagicAttack = baseMagicAttack,
                basePhysicalDefense = basePhysicalDefense,
                baseMagicDefense = baseMagicDefense,
                baseSpeed = baseSpeed,
                hpVariance = hpVariance,
                mpVariance = mpVariance,
                physicalAttackVariance = physicalAttackVariance,
                magicAttackVariance = magicAttackVariance,
                physicalDefenseVariance = physicalDefenseVariance,
                magicDefenseVariance = magicDefenseVariance,
                speedVariance = speedVariance,
                totalCultivation = totalCultivation,
                breakthroughCount = breakthroughCount,
                breakthroughFailCount = breakthroughFailCount,
                currentHp = currentHp,
                currentMp = currentMp
            ),
            pillEffects = PillEffects(
                pillPhysicalAttackBonus = pillPhysicalAttackBonus,
                pillMagicAttackBonus = pillMagicAttackBonus,
                pillPhysicalDefenseBonus = pillPhysicalDefenseBonus,
                pillMagicDefenseBonus = pillMagicDefenseBonus,
                pillHpBonus = pillHpBonus,
                pillMpBonus = pillMpBonus,
                pillSpeedBonus = pillSpeedBonus,
                pillCritRateBonus = pillCritRateBonus,
                pillCritEffectBonus = pillCritEffectBonus,
                pillCultivationSpeedBonus = pillCultivationSpeedBonus,
                pillSkillExpSpeedBonus = pillSkillExpSpeedBonus,
                pillNurtureSpeedBonus = pillNurtureSpeedBonus,
                pillEffectDuration = pillEffectDuration,
                activePillCategory = activePillCategory
            ),
            usage = UsageTracking(
                usedFunctionalPillTypes = usedFunctionalPillTypes,
                usedExtendLifePillIds = usedExtendLifePillIds,
                recruitedMonth = recruitedMonth,
                hasReviveEffect = hasReviveEffect,
                hasClearAllEffect = hasClearAllEffect
            ),
            equipment = EquipmentSet(
                weaponId = weaponId,
                armorId = armorId,
                bootsId = bootsId,
                accessoryId = accessoryId,
                weaponNurture = weaponNurture,
                armorNurture = armorNurture,
                bootsNurture = bootsNurture,
                accessoryNurture = accessoryNurture,
                storageBagItems = storageBagItems,
                storageBagSpiritStones = storageBagSpiritStones,
                spiritStones = spiritStones,
                soulPower = soulPower
            ),
            social = SocialData(
                partnerId = partnerId,
                partnerSectId = partnerSectId,
                parentId1 = parentId1,
                parentId2 = parentId2,
                lastChildYear = lastChildYear,
                griefEndYear = griefEndYear
            ),
            skills = SkillStats(
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
                salaryMissedCount = salaryMissedCount
            )
        )
    }

    fun toCompactDisciple(): Disciple {
        return toDisciple()
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
