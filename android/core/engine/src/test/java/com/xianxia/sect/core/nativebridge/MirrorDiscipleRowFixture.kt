package com.xianxia.sect.core.nativebridge

import com.google.protobuf.ByteString
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentNurtureData
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.proto.gameview.DiscipleRow
import com.xianxia.sect.proto.gameview.EquipmentNurtureDataView
import com.xianxia.sect.proto.gameview.StringIntEntry
import com.xianxia.sect.proto.gameview.StringStringEntry
import kotlinx.serialization.builtins.ListSerializer

/**
 * MirrorDiscipleRowFixture — 弟子 → `DiscipleRow` typed 行编码夹具
 * （[MirrorProtoFeedEquivalenceTest] 的新臂载荷构造）。
 *
 * 与 [MirrorProtoFeedFixture] 同事实源：两臂都从同一个 Disciple 实例派生，
 * 本对象只负责"域模型 → proto typed 行"这一半映射，口径与 C++ 编码器
 * `gameview_encode.h` 的 `kDiscipleRowFields` 表逐项对应。
 */
internal object MirrorDiscipleRowFixture {

    private const val GRIEF_NULL_SENTINEL = -1

    private val json = MirrorProtoFeedFixture.json

    /**
     * 弟子 → `DiscipleRow` typed 行（109 协议字段逐字段）。
     *
     * **恒设全部字段**（与 C++ 编码器 emit-always 同规）：漏设任一字段即
     * presence=false → 解码侧按域默认补位 → 两臂弟子不再全等，本守卫即红——
     * 故本函数同时是 proto 契约字段覆盖度的钉。
     */
    fun toGameViewRow(d: Disciple): DiscipleRow {
        val b = DiscipleRow.newBuilder()
        fillDirectRowFields(b, d)
        fillCombatPillRowFields(b, d)
        fillEquipmentSocialRowFields(b, d)
        fillSkillUsageRowFields(b, d)
        return b.build()
    }

    /** 新弟子行（与 [MirrorProtoFeedFixture.discipleUpsertsJson] 的 `fresh` 片段同值：仅 id/name/isAlive）。 */
    fun newDiscipleRow(): DiscipleRow = DiscipleRow.newBuilder()
        .setId(MirrorProtoFeedFixture.NEW_DISCIPLE_ID)
        .setName("新弟子")
        .setIsAlive(true)
        .build()

    private fun fillDirectRowFields(b: DiscipleRow.Builder, d: Disciple) {
        b.id = d.id
        b.name = d.name
        b.surname = d.surname
        b.realm = d.realm
        b.realmLayer = d.realmLayer
        b.cultivation = d.cultivation
        // 协议按 Long 承载（与 C++ to_json / DiscipleSurrogate 同截断口径）
        b.cultivationCheckpoint = d.cultivationCheckpoint.toLong()
        b.cultivationCheckpointGameMonth = d.cultivationCheckpointGameMonth
        b.spiritRootType = d.spiritRootType
        b.age = d.age
        b.lifespan = d.lifespan
        b.isAlive = d.isAlive
        // deathYear：协议随行字段，Kotlin 域模型无对应（两臂同语义丢弃）
        b.deathYear = 0
        b.gender = d.gender
        b.portraitRes = d.portraitRes
        b.addAllManualIds(d.manualIds)
        b.addAllTalentIds(d.talentIds)
        b.addAllPhysiqueIds(d.physiqueIds)
        b.addAllAffixIds(d.affixIds)
        b.addAllManualMasteries(
            d.manualMasteries.map { StringIntEntry.newBuilder().setKey(it.key).setValue(it.value).build() }
        )
        b.status = d.status.name
        b.addAllStatusData(
            d.statusData.map { StringStringEntry.newBuilder().setKey(it.key).setValue(it.value).build() }
        )
        b.cultivationSpeedBonus = d.cultivationSpeedBonus
        b.cultivationSpeedDuration = d.cultivationSpeedDuration
        b.discipleType = d.discipleType
        b.soulPower = d.soulPower
        b.cultivationCompletionMonth = d.cultivationCompletionMonth
        b.cultivationCompletionPhase = d.cultivationCompletionPhase
        b.manualCompletionMonth = d.manualCompletionMonth
        b.manualCompletionPhase = d.manualCompletionPhase
        b.equipmentNurturingCompletionMonth = d.equipmentNurturingCompletionMonth
        b.equipmentNurturingCompletionPhase = d.equipmentNurturingCompletionPhase
    }

    private fun fillCombatPillRowFields(b: DiscipleRow.Builder, d: Disciple) {
        b.baseHp = d.combat.baseHp
        b.baseMp = d.combat.baseMp
        b.basePhysicalAttack = d.combat.basePhysicalAttack
        b.baseMagicAttack = d.combat.baseMagicAttack
        b.basePhysicalDefense = d.combat.basePhysicalDefense
        b.baseMagicDefense = d.combat.baseMagicDefense
        b.baseSpeed = d.combat.baseSpeed
        b.hpVariance = d.combat.hpVariance
        b.mpVariance = d.combat.mpVariance
        b.physicalAttackVariance = d.combat.physicalAttackVariance
        b.magicAttackVariance = d.combat.magicAttackVariance
        b.physicalDefenseVariance = d.combat.physicalDefenseVariance
        b.magicDefenseVariance = d.combat.magicDefenseVariance
        b.speedVariance = d.combat.speedVariance
        b.totalCultivation = d.combat.totalCultivation
        b.breakthroughCount = d.combat.breakthroughCount
        b.breakthroughFailCount = d.combat.breakthroughFailCount
        b.currentHp = d.combat.currentHp
        b.currentMp = d.combat.currentMp
        b.pillPhysicalAttackBonus = d.pillEffects.pillPhysicalAttackBonus
        b.pillMagicAttackBonus = d.pillEffects.pillMagicAttackBonus
        b.pillPhysicalDefenseBonus = d.pillEffects.pillPhysicalDefenseBonus
        b.pillMagicDefenseBonus = d.pillEffects.pillMagicDefenseBonus
        b.pillHpBonus = d.pillEffects.pillHpBonus
        b.pillMpBonus = d.pillEffects.pillMpBonus
        b.pillSpeedBonus = d.pillEffects.pillSpeedBonus
        b.pillCritRateBonus = d.pillEffects.pillCritRateBonus
        b.pillCritEffectBonus = d.pillEffects.pillCritEffectBonus
        b.pillCultivationSpeedBonus = d.pillEffects.pillCultivationSpeedBonus
        b.pillSkillExpSpeedBonus = d.pillEffects.pillSkillExpSpeedBonus
        b.pillNurtureSpeedBonus = d.pillEffects.pillNurtureSpeedBonus
        b.pillEffectDuration = d.pillEffects.pillEffectDuration
        b.addAllActivePillTypes(d.pillEffects.activePillTypes.toList())
        b.activePillCategory = d.pillEffects.activePillCategory
    }

    private fun fillEquipmentSocialRowFields(b: DiscipleRow.Builder, d: Disciple) {
        b.weaponId = d.equipment.weaponId
        b.armorId = d.equipment.armorId
        b.bootsId = d.equipment.bootsId
        b.accessoryId = d.equipment.accessoryId
        b.weaponNurture = d.equipment.weaponNurture.toView()
        b.armorNurture = d.equipment.armorNurture.toView()
        b.bootsNurture = d.equipment.bootsNurture.toView()
        b.accessoryNurture = d.equipment.accessoryNurture.toView()
        // storageBagItems v1 过渡编码：JSON 原文（与旧协议同值）
        b.storageBagItemsJson = ByteString.copyFromUtf8(
            json.encodeToString(
                ListSerializer(StorageBagItem.serializer()), d.equipment.storageBagItems
            )
        )
        b.storageBagSpiritStones = d.equipment.storageBagSpiritStones
        b.spiritStones = d.equipment.spiritStones
        // 社交可空字段的线路哨兵（"" / 0 / -1 = null）与 DiscipleSerializer 同口径
        b.partnerId = d.social.partnerId ?: ""
        b.partnerSectId = d.social.partnerSectId ?: ""
        b.parentId1 = d.social.parentId1 ?: ""
        b.parentId2 = d.social.parentId2 ?: ""
        b.lastChildYear = d.social.lastChildYear
        b.childBirthMonth = d.social.childBirthMonth ?: 0
        b.griefEndYear = d.social.griefEndYear ?: GRIEF_NULL_SENTINEL
        b.masterId = d.social.masterId ?: ""
    }

    private fun fillSkillUsageRowFields(b: DiscipleRow.Builder, d: Disciple) {
        b.intelligence = d.skills.intelligence
        b.charm = d.skills.charm
        b.loyalty = d.skills.loyalty
        b.comprehension = d.skills.comprehension
        b.artifactRefining = d.skills.artifactRefining
        b.pillRefining = d.skills.pillRefining
        b.spiritPlanting = d.skills.spiritPlanting
        b.mining = d.skills.mining
        b.teaching = d.skills.teaching
        b.morality = d.skills.morality
        b.aptitude = d.skills.aptitude
        b.salaryPaidCount = d.skills.salaryPaidCount
        b.salaryMissedCount = d.skills.salaryMissedCount
        b.alchemyLevel = d.skills.alchemyLevel
        b.alchemyPromotionCount = d.skills.alchemyPromotionCount
        b.forgeLevel = d.skills.forgeLevel
        b.forgePromotionCount = d.skills.forgePromotionCount
        b.addAllUsedPermanentPillKeys(d.usage.usedPermanentPillKeys.toList())
        b.addAllUsedExtendLifePillTypes(d.usage.usedExtendLifePillTypes.toList())
        b.addAllUsedFunctionalPillTypes(d.usage.usedFunctionalPillTypes)
        b.addAllUsedExtendLifePillIds(d.usage.usedExtendLifePillIds)
        b.recruitedMonth = d.usage.recruitedMonth
        b.hasReviveEffect = d.usage.hasReviveEffect
        b.hasClearAllEffect = d.usage.hasClearAllEffect
    }

    private fun EquipmentNurtureData.toView(): EquipmentNurtureDataView =
        EquipmentNurtureDataView.newBuilder()
            .setEquipmentId(equipmentId)
            .setRarity(rarity)
            .setNurtureLevel(nurtureLevel)
            .setNurtureProgress(nurtureProgress)
            .build()
}
