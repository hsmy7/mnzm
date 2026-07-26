package com.xianxia.sect.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Disciple 的自定义序列化器。
 *
 * ## 为什么需要自定义序列化器
 * Disciple 域类型使用 Room @Embedded 将字段分散在 6 个子类中，但 Protobuf 要求
 * 所有 102 个字段平铺在同一层（与旧 SerializableDisciple 兼容）。直接在每个
 * @Embedded 子类上加 @ProtoNumber 会导致 Protobuf 产生嵌套消息，破坏向后兼容。
 *
 * ## 实现方式
 * 采用「复合 via 代理」（Composite via surrogate）模式：
 * 1. 私有 [DiscipleSurrogate] 数据类模拟旧 SerializableDisciple 的平铺结构
 * 2. 序列化时 Disciple → DiscipleSurrogate → Protobuf bytes
 * 3. 反序列化时 Protobuf bytes → DiscipleSurrogate → Disciple
 * 4. 保证编码后的二进制与旧格式完全一致
 *
 * ## 处理说明
 * - `cultivationCheckpoint`：域模型为 Double，序列化为 Long（与旧格式兼容）
 * - autoEquipFromWarehouse：新增 ProtoNumber(103)，旧格式遗漏的字段
 * - @Ignore 字段（lifeEvents, 运行时 Set 字段）不序列化
 * - slotId 不序列化（Room 复合主键，非游戏字段）
 */
object DiscipleSerializer : KSerializer<Disciple> {
    // Protobuf 不支持 null，使用 -1 表示 null（哨兵值，与 NULL_INT_SENTINEL 一致）
    private const val NULL_INT_SENTINEL = -1
    override val descriptor: SerialDescriptor = DiscipleSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Disciple) {
        val surrogate = DiscipleSurrogate(
            // ===== 直接字段 =====
            id = value.id,
            name = value.name,
            surname = value.surname,
            realm = value.realm,
            realmLayer = value.realmLayer,
            cultivation = value.cultivation,
            cultivationCheckpoint = value.cultivationCheckpoint.toLong(),
            cultivationCheckpointGameMonth = value.cultivationCheckpointGameMonth,
            spiritRootType = value.spiritRootType,
            age = value.age,
            lifespan = value.lifespan,
            isAlive = value.isAlive,
            gender = value.gender,
            portraitRes = value.portraitRes,
            manualIds = value.manualIds,
            talentIds = value.talentIds,
            manualMasteries = value.manualMasteries,
            status = value.status.name,
            statusData = value.statusData,
            cultivationSpeedBonus = value.cultivationSpeedBonus,
            cultivationSpeedDuration = value.cultivationSpeedDuration,
            discipleType = value.discipleType,
            autoLearnFromWarehouse = value.autoLearnFromWarehouse,
            soulPower = value.soulPower,
            cultivationCompletionMonth = value.cultivationCompletionMonth,
            cultivationCompletionPhase = value.cultivationCompletionPhase,
            manualCompletionMonth = value.manualCompletionMonth,
            manualCompletionPhase = value.manualCompletionPhase,
            equipmentNurturingCompletionMonth = value.equipmentNurturingCompletionMonth,
            equipmentNurturingCompletionPhase = value.equipmentNurturingCompletionPhase,

            // ===== CombatAttributes @Embedded =====
            baseHp = value.combat.baseHp,
            baseMp = value.combat.baseMp,
            basePhysicalAttack = value.combat.basePhysicalAttack,
            baseMagicAttack = value.combat.baseMagicAttack,
            basePhysicalDefense = value.combat.basePhysicalDefense,
            baseMagicDefense = value.combat.baseMagicDefense,
            baseSpeed = value.combat.baseSpeed,
            hpVariance = value.combat.hpVariance,
            mpVariance = value.combat.mpVariance,
            physicalAttackVariance = value.combat.physicalAttackVariance,
            magicAttackVariance = value.combat.magicAttackVariance,
            physicalDefenseVariance = value.combat.physicalDefenseVariance,
            magicDefenseVariance = value.combat.magicDefenseVariance,
            speedVariance = value.combat.speedVariance,
            totalCultivation = value.combat.totalCultivation,
            breakthroughCount = value.combat.breakthroughCount,
            breakthroughFailCount = value.combat.breakthroughFailCount,
            currentHp = value.combat.currentHp,
            currentMp = value.combat.currentMp,

            // ===== PillEffects @Embedded =====
            pillPhysicalAttackBonus = value.pillEffects.pillPhysicalAttackBonus,
            pillMagicAttackBonus = value.pillEffects.pillMagicAttackBonus,
            pillPhysicalDefenseBonus = value.pillEffects.pillPhysicalDefenseBonus,
            pillMagicDefenseBonus = value.pillEffects.pillMagicDefenseBonus,
            pillHpBonus = value.pillEffects.pillHpBonus,
            pillMpBonus = value.pillEffects.pillMpBonus,
            pillSpeedBonus = value.pillEffects.pillSpeedBonus,
            pillCritRateBonus = value.pillEffects.pillCritRateBonus,
            pillCritEffectBonus = value.pillEffects.pillCritEffectBonus,
            pillCultivationSpeedBonus = value.pillEffects.pillCultivationSpeedBonus,
            pillSkillExpSpeedBonus = value.pillEffects.pillSkillExpSpeedBonus,
            pillNurtureSpeedBonus = value.pillEffects.pillNurtureSpeedBonus,
            pillEffectDuration = value.pillEffects.pillEffectDuration,
            activePillCategory = value.pillEffects.activePillCategory,
            activePillTypes = value.pillEffects.activePillTypes.toList(),

            // ===== EquipmentSet @Embedded =====
            weaponId = value.equipment.weaponId,
            armorId = value.equipment.armorId,
            bootsId = value.equipment.bootsId,
            accessoryId = value.equipment.accessoryId,
            weaponNurture = value.equipment.weaponNurture,
            armorNurture = value.equipment.armorNurture,
            bootsNurture = value.equipment.bootsNurture,
            accessoryNurture = value.equipment.accessoryNurture,
            autoEquipFromWarehouse = value.equipment.autoEquipFromWarehouse,
            storageBagItems = value.equipment.storageBagItems,
            storageBagSpiritStones = value.equipment.storageBagSpiritStones,
            spiritStones = value.equipment.spiritStones,

            // ===== SocialData @Embedded =====
            partnerId = value.social.partnerId ?: "",
            partnerSectId = value.social.partnerSectId ?: "",
            parentId1 = value.social.parentId1 ?: "",
            parentId2 = value.social.parentId2 ?: "",
            lastChildYear = value.social.lastChildYear,
            childBirthMonth = value.social.childBirthMonth ?: 0,
            griefEndYear = value.social.griefEndYear ?: NULL_INT_SENTINEL,
            masterId = value.social.masterId ?: "",

            // ===== SkillStats @Embedded =====
            intelligence = value.skills.intelligence,
            charm = value.skills.charm,
            loyalty = value.skills.loyalty,
            comprehension = value.skills.comprehension,
            artifactRefining = value.skills.artifactRefining,
            pillRefining = value.skills.pillRefining,
            spiritPlanting = value.skills.spiritPlanting,
            mining = value.skills.mining,
            teaching = value.skills.teaching,
            morality = value.skills.morality,
            salaryPaidCount = value.skills.salaryPaidCount,
            salaryMissedCount = value.skills.salaryMissedCount,

            // ===== UsageTracking @Embedded =====
            usedFunctionalPillTypes = value.usage.usedFunctionalPillTypes,
            usedExtendLifePillIds = value.usage.usedExtendLifePillIds,
            usedPermanentPillKeys = value.usage.usedPermanentPillKeys.toList(),
            usedExtendLifePillTypes = value.usage.usedExtendLifePillTypes.toList(),
            recruitedMonth = value.usage.recruitedMonth,
            hasReviveEffect = value.usage.hasReviveEffect,
            hasClearAllEffect = value.usage.hasClearAllEffect,
        )
        encoder.encodeSerializableValue(DiscipleSurrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): Disciple {
        val surrogate = decoder.decodeSerializableValue(DiscipleSurrogate.serializer())
        return Disciple(
            // ===== 直接字段 =====
            id = surrogate.id,
            slotId = 0, // slotId 由 StorageEngine 写入时赋值
            name = surrogate.name,
            surname = surrogate.surname,
            realm = surrogate.realm,
            realmLayer = surrogate.realmLayer,
            cultivation = surrogate.cultivation,
            cultivationCheckpoint = surrogate.cultivationCheckpoint.toDouble(),
            cultivationCheckpointGameMonth = surrogate.cultivationCheckpointGameMonth,
            spiritRootType = surrogate.spiritRootType,
            age = surrogate.age,
            lifespan = surrogate.lifespan,
            isAlive = surrogate.isAlive,
            gender = surrogate.gender,
            portraitRes = surrogate.portraitRes,
            manualIds = surrogate.manualIds,
            talentIds = surrogate.talentIds,
            manualMasteries = surrogate.manualMasteries,
            status = safeDiscipleStatus(surrogate.status),
            statusData = surrogate.statusData,
            cultivationSpeedBonus = surrogate.cultivationSpeedBonus,
            cultivationSpeedDuration = surrogate.cultivationSpeedDuration,
            discipleType = surrogate.discipleType,
            autoLearnFromWarehouse = surrogate.autoLearnFromWarehouse,
            soulPower = surrogate.soulPower,
            cultivationCompletionMonth = surrogate.cultivationCompletionMonth,
            cultivationCompletionPhase = surrogate.cultivationCompletionPhase,
            manualCompletionMonth = surrogate.manualCompletionMonth,
            manualCompletionPhase = surrogate.manualCompletionPhase,
            equipmentNurturingCompletionMonth = surrogate.equipmentNurturingCompletionMonth,
            equipmentNurturingCompletionPhase = surrogate.equipmentNurturingCompletionPhase,

            // ===== CombatAttributes @Embedded =====
            combat = CombatAttributes(
                baseHp = surrogate.baseHp,
                baseMp = surrogate.baseMp,
                basePhysicalAttack = surrogate.basePhysicalAttack,
                baseMagicAttack = surrogate.baseMagicAttack,
                basePhysicalDefense = surrogate.basePhysicalDefense,
                baseMagicDefense = surrogate.baseMagicDefense,
                baseSpeed = surrogate.baseSpeed,
                hpVariance = surrogate.hpVariance,
                mpVariance = surrogate.mpVariance,
                physicalAttackVariance = surrogate.physicalAttackVariance,
                magicAttackVariance = surrogate.magicAttackVariance,
                physicalDefenseVariance = surrogate.physicalDefenseVariance,
                magicDefenseVariance = surrogate.magicDefenseVariance,
                speedVariance = surrogate.speedVariance,
                totalCultivation = surrogate.totalCultivation,
                breakthroughCount = surrogate.breakthroughCount,
                breakthroughFailCount = surrogate.breakthroughFailCount,
                currentHp = surrogate.currentHp,
                currentMp = surrogate.currentMp,
            ),

            // ===== PillEffects @Embedded =====
            pillEffects = PillEffects(
                pillPhysicalAttackBonus = surrogate.pillPhysicalAttackBonus,
                pillMagicAttackBonus = surrogate.pillMagicAttackBonus,
                pillPhysicalDefenseBonus = surrogate.pillPhysicalDefenseBonus,
                pillMagicDefenseBonus = surrogate.pillMagicDefenseBonus,
                pillHpBonus = surrogate.pillHpBonus,
                pillMpBonus = surrogate.pillMpBonus,
                pillSpeedBonus = surrogate.pillSpeedBonus,
                pillCritRateBonus = surrogate.pillCritRateBonus,
                pillCritEffectBonus = surrogate.pillCritEffectBonus,
                pillCultivationSpeedBonus = surrogate.pillCultivationSpeedBonus,
                pillSkillExpSpeedBonus = surrogate.pillSkillExpSpeedBonus,
                pillNurtureSpeedBonus = surrogate.pillNurtureSpeedBonus,
                pillEffectDuration = surrogate.pillEffectDuration,
                activePillCategory = surrogate.activePillCategory,
                activePillTypes = surrogate.activePillTypes.toSet(),
            ),

            // ===== EquipmentSet @Embedded =====
            equipment = EquipmentSet(
                weaponId = surrogate.weaponId,
                armorId = surrogate.armorId,
                bootsId = surrogate.bootsId,
                accessoryId = surrogate.accessoryId,
                weaponNurture = surrogate.weaponNurture,
                armorNurture = surrogate.armorNurture,
                bootsNurture = surrogate.bootsNurture,
                accessoryNurture = surrogate.accessoryNurture,
                autoEquipFromWarehouse = surrogate.autoEquipFromWarehouse,
                storageBagItems = surrogate.storageBagItems,
                storageBagSpiritStones = surrogate.storageBagSpiritStones,
                spiritStones = surrogate.spiritStones,
            ),

            // ===== SocialData @Embedded =====
            social = SocialData(
                partnerId = surrogate.partnerId.ifEmpty { null },
                partnerSectId = surrogate.partnerSectId.ifEmpty { null },
                parentId1 = surrogate.parentId1.ifEmpty { null },
                parentId2 = surrogate.parentId2.ifEmpty { null },
                lastChildYear = surrogate.lastChildYear,
                childBirthMonth = surrogate.childBirthMonth.takeIf { it != 0 },
                griefEndYear = surrogate.griefEndYear.takeIf { it != NULL_INT_SENTINEL },
                masterId = surrogate.masterId.ifEmpty { null },
            ),

            // ===== SkillStats @Embedded =====
            skills = SkillStats(
                intelligence = surrogate.intelligence,
                charm = surrogate.charm,
                loyalty = surrogate.loyalty,
                comprehension = surrogate.comprehension,
                artifactRefining = surrogate.artifactRefining,
                pillRefining = surrogate.pillRefining,
                spiritPlanting = surrogate.spiritPlanting,
                mining = surrogate.mining,
                teaching = surrogate.teaching,
                morality = surrogate.morality,
                salaryPaidCount = surrogate.salaryPaidCount,
                salaryMissedCount = surrogate.salaryMissedCount,
            ),

            // ===== UsageTracking @Embedded =====
            usage = UsageTracking(
                usedFunctionalPillTypes = surrogate.usedFunctionalPillTypes,
                usedExtendLifePillIds = surrogate.usedExtendLifePillIds,
                usedPermanentPillKeys = surrogate.usedPermanentPillKeys.toSet(),
                usedExtendLifePillTypes = surrogate.usedExtendLifePillTypes.toSet(),
                recruitedMonth = surrogate.recruitedMonth,
                hasReviveEffect = surrogate.hasReviveEffect,
                hasClearAllEffect = surrogate.hasClearAllEffect,
            ),
        )
    }

    private fun safeDiscipleStatus(name: String): DiscipleStatus {
        return try {
            DiscipleStatus.valueOf(name.trim())
        } catch (_: IllegalArgumentException) {
            DiscipleStatus.IDLE
        }
    }

    // ==================== 代理：平铺的 Disciple Protobuf 编码 ====================

    @Serializable
    private data class DiscipleSurrogate(
        // ===== 直接字段 =====
        @ProtoNumber(1) val id: String = "",
        @ProtoNumber(2) val name: String = "",
        @ProtoNumber(100) val surname: String = "",
        @ProtoNumber(3) val realm: Int = 9,
        @ProtoNumber(4) val realmLayer: Int = 1,
        @ProtoNumber(5) val cultivation: Double = 0.0,
        @ProtoNumber(101) val cultivationCheckpoint: Long = 0L,        // 域模型为 Double，序列化为 Long
        @ProtoNumber(91) val cultivationCheckpointGameMonth: Int = 0,
        @ProtoNumber(6) val spiritRootType: String = "metal",
        @ProtoNumber(7) val age: Int = 16,
        @ProtoNumber(8) val lifespan: Int = 80,
        @ProtoNumber(9) val isAlive: Boolean = true,
        @ProtoNumber(10) val gender: String = "male",
        @ProtoNumber(90) val portraitRes: String = "",
        @ProtoNumber(21) val manualIds: List<String> = emptyList(),
        @ProtoNumber(22) val talentIds: List<String> = emptyList(),
        @ProtoNumber(23) val manualMasteries: Map<String, Int> = emptyMap(),
        @ProtoNumber(32) val status: String = "IDLE",
        @ProtoNumber(33) val statusData: Map<String, String> = emptyMap(),
        @ProtoNumber(34) val cultivationSpeedBonus: Double = 0.0,
        @ProtoNumber(35) val cultivationSpeedDuration: Int = 0,
        @ProtoNumber(74) val discipleType: String = "outer",
        @ProtoNumber(92) val autoLearnFromWarehouse: Boolean = false,
        @ProtoNumber(29) val soulPower: Int = 0,
        @ProtoNumber(94) val cultivationCompletionMonth: Int = 0,
        @ProtoNumber(95) val cultivationCompletionPhase: Int = 1,
        @ProtoNumber(96) val manualCompletionMonth: Int = 0,
        @ProtoNumber(97) val manualCompletionPhase: Int = 1,
        @ProtoNumber(98) val equipmentNurturingCompletionMonth: Int = 0,
        @ProtoNumber(99) val equipmentNurturingCompletionPhase: Int = 1,

        // ===== CombatAttributes @Embedded =====
        @ProtoNumber(67) val baseHp: Int = 120,
        @ProtoNumber(68) val baseMp: Int = 60,
        @ProtoNumber(69) val basePhysicalAttack: Int = 12,
        @ProtoNumber(70) val baseMagicAttack: Int = 12,
        @ProtoNumber(71) val basePhysicalDefense: Int = 10,
        @ProtoNumber(72) val baseMagicDefense: Int = 8,
        @ProtoNumber(73) val baseSpeed: Int = 15,
        @ProtoNumber(60) val hpVariance: Int = 0,
        @ProtoNumber(61) val mpVariance: Int = 0,
        @ProtoNumber(62) val physicalAttackVariance: Int = 0,
        @ProtoNumber(63) val magicAttackVariance: Int = 0,
        @ProtoNumber(64) val physicalDefenseVariance: Int = 0,
        @ProtoNumber(65) val magicDefenseVariance: Int = 0,
        @ProtoNumber(66) val speedVariance: Int = 0,
        @ProtoNumber(81) val totalCultivation: Long = 0,
        @ProtoNumber(82) val breakthroughCount: Int = 0,
        @ProtoNumber(83) val breakthroughFailCount: Int = 0,
        @ProtoNumber(79) val currentHp: Int = -1,
        @ProtoNumber(80) val currentMp: Int = -1,

        // ===== PillEffects @Embedded =====
        @ProtoNumber(36) val pillPhysicalAttackBonus: Int = 0,
        @ProtoNumber(37) val pillMagicAttackBonus: Int = 0,
        @ProtoNumber(38) val pillPhysicalDefenseBonus: Int = 0,
        @ProtoNumber(39) val pillMagicDefenseBonus: Int = 0,
        @ProtoNumber(40) val pillHpBonus: Int = 0,
        @ProtoNumber(41) val pillMpBonus: Int = 0,
        @ProtoNumber(42) val pillSpeedBonus: Int = 0,
        @ProtoNumber(43) val pillCritRateBonus: Double = 0.0,
        @ProtoNumber(44) val pillCritEffectBonus: Double = 0.0,
        @ProtoNumber(45) val pillCultivationSpeedBonus: Double = 0.0,
        @ProtoNumber(46) val pillSkillExpSpeedBonus: Double = 0.0,
        @ProtoNumber(47) val pillNurtureSpeedBonus: Double = 0.0,
        @ProtoNumber(48) val pillEffectDuration: Int = 0,
        @ProtoNumber(49) val activePillCategory: String = "",
        @ProtoNumber(89) val activePillTypes: List<String> = emptyList(),

        // ===== EquipmentSet @Embedded =====
        @ProtoNumber(17) val weaponId: String = "",
        @ProtoNumber(18) val armorId: String = "",
        @ProtoNumber(19) val bootsId: String = "",
        @ProtoNumber(20) val accessoryId: String = "",
        @ProtoNumber(24) val weaponNurture: EquipmentNurtureData = EquipmentNurtureData("", 0),
        @ProtoNumber(25) val armorNurture: EquipmentNurtureData = EquipmentNurtureData("", 0),
        @ProtoNumber(26) val bootsNurture: EquipmentNurtureData = EquipmentNurtureData("", 0),
        @ProtoNumber(27) val accessoryNurture: EquipmentNurtureData = EquipmentNurtureData("", 0),
        @ProtoNumber(103) val autoEquipFromWarehouse: Boolean = false,
        @ProtoNumber(30) val storageBagItems: List<StorageBagItem> = emptyList(),
        @ProtoNumber(31) val storageBagSpiritStones: Long = 0,
        @ProtoNumber(28) val spiritStones: Int = 0,

        // ===== SocialData @Embedded =====
        @ProtoNumber(11) val partnerId: String = "",
        @ProtoNumber(12) val partnerSectId: String = "",
        @ProtoNumber(13) val parentId1: String = "",
        @ProtoNumber(14) val parentId2: String = "",
        @ProtoNumber(15) val lastChildYear: Int = 0,
        @ProtoNumber(102) val childBirthMonth: Int = 0,
        @ProtoNumber(16) val griefEndYear: Int = NULL_INT_SENTINEL,
        @ProtoNumber(93) val masterId: String = "",

        // ===== SkillStats @Embedded =====
        @ProtoNumber(84) val intelligence: Int = 50,
        @ProtoNumber(85) val charm: Int = 50,
        @ProtoNumber(50) val loyalty: Int = 50,
        @ProtoNumber(51) val comprehension: Int = 50,
        @ProtoNumber(52) val artifactRefining: Int = 50,
        @ProtoNumber(53) val pillRefining: Int = 50,
        @ProtoNumber(54) val spiritPlanting: Int = 50,
        @ProtoNumber(86) val mining: Int = 50,
        @ProtoNumber(55) val teaching: Int = 50,
        @ProtoNumber(56) val morality: Int = 50,
        @ProtoNumber(57) val salaryPaidCount: Int = 0,
        @ProtoNumber(58) val salaryMissedCount: Int = 0,

        // ===== UsageTracking @Embedded =====
        @ProtoNumber(75) val usedFunctionalPillTypes: List<String> = emptyList(),
        @ProtoNumber(76) val usedExtendLifePillIds: List<String> = emptyList(),
        @ProtoNumber(87) val usedPermanentPillKeys: List<String> = emptyList(),
        @ProtoNumber(88) val usedExtendLifePillTypes: List<String> = emptyList(),
        @ProtoNumber(59) val recruitedMonth: Int = 0,
        @ProtoNumber(77) val hasReviveEffect: Boolean = false,
        @ProtoNumber(78) val hasClearAllEffect: Boolean = false,
    )
}
