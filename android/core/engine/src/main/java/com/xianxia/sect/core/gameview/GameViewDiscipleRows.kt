package com.xianxia.sect.core.gameview

import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentNurtureData
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.PillEffects
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.SocialData
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.UsageTracking
import com.xianxia.sect.proto.gameview.DiscipleRow
import com.xianxia.sect.proto.gameview.EquipmentNurtureDataView
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * GameViewDiscipleRows —— `DiscipleRow` → [Disciple] 的 **typed 直读投影**
 * （重构方案 R2.3 第二波，弟子列表块）。
 *
 * ## 退场的形状
 * 第一波（B06/B07）弟子行的消费链是
 * `DiscipleRow →（逐字段）JsonObject → kotlinx JSON 解码 → Disciple`：
 * 每行先按 109 个协议字段搭一棵 JSON 元素树（JsonPrimitive/JsonArray/JsonObject
 * 节点各一），再被 kotlinx 逐键解析回同一份域对象——同一批数据在 Kotlin 侧
 * 被"造出来又拆掉"。每旬全脏（5000 弟子修炼推进）时这就是 5000 × 109 个
 * JsonElement 分配 + 5000 次 JSON 结构解码，即 mirror 段"每旬级全量重建"的
 * 弟子侧形状。本对象按 proto typed getter 直读，节点数 0。
 *
 * ## 逐值等价（守卫锁定）
 * 映射表以 [Disciple] 的 JSON 协议面（C++ `kDiscipleRowFields` ↔
 * `DiscipleSerializer` 平铺代理）为唯一口径，含四处协议↔域模型的既有口径：
 * `cultivationCheckpoint` 按 Long 承载回填 Double、`griefEndYear` 的 -1 空值
 * 哨兵、`status` 字符串的宽松枚举回退 IDLE、Set 型字段以 List 承载。
 * `DiscipleRowTypedProjectionTest` 对同一行做两路对照（typed vs JSON 树重建）
 * 逐字段全等，并在真实 C++ 结算信封上核对字段覆盖。
 *
 * ## fail-fast 红线（方案 §5「投影缺失字段 fail-fast 而非静默空」）
 * C++ 编码器对行内标量恒为 emit-always（含 0/false），因此
 * [requiredScalarFields] 中任一字段 `hasXxx() == false` 即为协议漂移
 * （老 native + 新 Kotlin 的 schema 错位）——**抛错，不返回默认值掩盖**；
 * 抛出经 `applyDirtyFromNative` 的降级契约转为"本封增量失败 → 全量兜底"，
 * 不会污染镜像。repeated 字段（列表/映射）与嵌套消息按 proto3
 * "absent = empty"语义取值，不属于"缺失"。
 */
internal object GameViewDiscipleRows {

    /** 储物袋条目 v1 仍为 JSON 原文承载（R2.1 过渡编码，typed 化时只增不改追加号） */
    private val bagItemSerializer: KSerializer<List<StorageBagItem>> = ListSerializer(StorageBagItem.serializer())

    /** 行内必须恒在的标量字段（C++ emit-always 面）——投影契约，缺任一即 fail-fast */
    val requiredScalarFields: List<Pair<String, (DiscipleRow) -> Boolean>> = listOf(
        "id" to DiscipleRow::hasId,
        "name" to DiscipleRow::hasName,
        "surname" to DiscipleRow::hasSurname,
        "realm" to DiscipleRow::hasRealm,
        "realmLayer" to DiscipleRow::hasRealmLayer,
        "cultivation" to DiscipleRow::hasCultivation,
        "cultivationCheckpoint" to DiscipleRow::hasCultivationCheckpoint,
        "cultivationCheckpointGameMonth" to DiscipleRow::hasCultivationCheckpointGameMonth,
        "spiritRootType" to DiscipleRow::hasSpiritRootType,
        "age" to DiscipleRow::hasAge,
        "lifespan" to DiscipleRow::hasLifespan,
        "isAlive" to DiscipleRow::hasIsAlive,
        "deathYear" to DiscipleRow::hasDeathYear,
        "gender" to DiscipleRow::hasGender,
        "portraitRes" to DiscipleRow::hasPortraitRes,
        "status" to DiscipleRow::hasStatus,
        "cultivationSpeedBonus" to DiscipleRow::hasCultivationSpeedBonus,
        "cultivationSpeedDuration" to DiscipleRow::hasCultivationSpeedDuration,
        "discipleType" to DiscipleRow::hasDiscipleType,
        "soulPower" to DiscipleRow::hasSoulPower,
        "cultivationCompletionMonth" to DiscipleRow::hasCultivationCompletionMonth,
        "cultivationCompletionPhase" to DiscipleRow::hasCultivationCompletionPhase,
        "manualCompletionMonth" to DiscipleRow::hasManualCompletionMonth,
        "manualCompletionPhase" to DiscipleRow::hasManualCompletionPhase,
        "equipmentNurturingCompletionMonth" to DiscipleRow::hasEquipmentNurturingCompletionMonth,
        "equipmentNurturingCompletionPhase" to DiscipleRow::hasEquipmentNurturingCompletionPhase,
        "baseHp" to DiscipleRow::hasBaseHp,
        "baseMp" to DiscipleRow::hasBaseMp,
        "basePhysicalAttack" to DiscipleRow::hasBasePhysicalAttack,
        "baseMagicAttack" to DiscipleRow::hasBaseMagicAttack,
        "basePhysicalDefense" to DiscipleRow::hasBasePhysicalDefense,
        "baseMagicDefense" to DiscipleRow::hasBaseMagicDefense,
        "baseSpeed" to DiscipleRow::hasBaseSpeed,
        "hpVariance" to DiscipleRow::hasHpVariance,
        "mpVariance" to DiscipleRow::hasMpVariance,
        "physicalAttackVariance" to DiscipleRow::hasPhysicalAttackVariance,
        "magicAttackVariance" to DiscipleRow::hasMagicAttackVariance,
        "physicalDefenseVariance" to DiscipleRow::hasPhysicalDefenseVariance,
        "magicDefenseVariance" to DiscipleRow::hasMagicDefenseVariance,
        "speedVariance" to DiscipleRow::hasSpeedVariance,
        "totalCultivation" to DiscipleRow::hasTotalCultivation,
        "breakthroughCount" to DiscipleRow::hasBreakthroughCount,
        "breakthroughFailCount" to DiscipleRow::hasBreakthroughFailCount,
        "currentHp" to DiscipleRow::hasCurrentHp,
        "currentMp" to DiscipleRow::hasCurrentMp,
        "pillPhysicalAttackBonus" to DiscipleRow::hasPillPhysicalAttackBonus,
        "pillMagicAttackBonus" to DiscipleRow::hasPillMagicAttackBonus,
        "pillPhysicalDefenseBonus" to DiscipleRow::hasPillPhysicalDefenseBonus,
        "pillMagicDefenseBonus" to DiscipleRow::hasPillMagicDefenseBonus,
        "pillHpBonus" to DiscipleRow::hasPillHpBonus,
        "pillMpBonus" to DiscipleRow::hasPillMpBonus,
        "pillSpeedBonus" to DiscipleRow::hasPillSpeedBonus,
        "pillCritRateBonus" to DiscipleRow::hasPillCritRateBonus,
        "pillCritEffectBonus" to DiscipleRow::hasPillCritEffectBonus,
        "pillCultivationSpeedBonus" to DiscipleRow::hasPillCultivationSpeedBonus,
        "pillSkillExpSpeedBonus" to DiscipleRow::hasPillSkillExpSpeedBonus,
        "pillNurtureSpeedBonus" to DiscipleRow::hasPillNurtureSpeedBonus,
        "pillEffectDuration" to DiscipleRow::hasPillEffectDuration,
        "activePillCategory" to DiscipleRow::hasActivePillCategory,
        "weaponId" to DiscipleRow::hasWeaponId,
        "armorId" to DiscipleRow::hasArmorId,
        "bootsId" to DiscipleRow::hasBootsId,
        "accessoryId" to DiscipleRow::hasAccessoryId,
        "storageBagSpiritStones" to DiscipleRow::hasStorageBagSpiritStones,
        "spiritStones" to DiscipleRow::hasSpiritStones,
        "partnerId" to DiscipleRow::hasPartnerId,
        "partnerSectId" to DiscipleRow::hasPartnerSectId,
        "parentId1" to DiscipleRow::hasParentId1,
        "parentId2" to DiscipleRow::hasParentId2,
        "lastChildYear" to DiscipleRow::hasLastChildYear,
        "childBirthMonth" to DiscipleRow::hasChildBirthMonth,
        "griefEndYear" to DiscipleRow::hasGriefEndYear,
        "masterId" to DiscipleRow::hasMasterId,
        "intelligence" to DiscipleRow::hasIntelligence,
        "charm" to DiscipleRow::hasCharm,
        "loyalty" to DiscipleRow::hasLoyalty,
        "comprehension" to DiscipleRow::hasComprehension,
        "artifactRefining" to DiscipleRow::hasArtifactRefining,
        "pillRefining" to DiscipleRow::hasPillRefining,
        "spiritPlanting" to DiscipleRow::hasSpiritPlanting,
        "mining" to DiscipleRow::hasMining,
        "teaching" to DiscipleRow::hasTeaching,
        "morality" to DiscipleRow::hasMorality,
        "aptitude" to DiscipleRow::hasAptitude,
        "salaryPaidCount" to DiscipleRow::hasSalaryPaidCount,
        "salaryMissedCount" to DiscipleRow::hasSalaryMissedCount,
        "alchemyLevel" to DiscipleRow::hasAlchemyLevel,
        "alchemyPromotionCount" to DiscipleRow::hasAlchemyPromotionCount,
        "forgeLevel" to DiscipleRow::hasForgeLevel,
        "forgePromotionCount" to DiscipleRow::hasForgePromotionCount,
        "recruitedMonth" to DiscipleRow::hasRecruitedMonth,
        "hasReviveEffect" to DiscipleRow::hasHasReviveEffect,
        "hasClearAllEffect" to DiscipleRow::hasHasClearAllEffect
    )

    /** 本行缺失的必在标量字段名（守卫用；空集 = 投影契约成立） */
    fun missingRequiredFields(row: DiscipleRow): List<String> =
        requiredScalarFields.filterNot { (_, present) -> present(row) }.map { it.first }

    /**
     * 弟子行 typed 直读投影。
     *
     * @throws IllegalArgumentException 必在标量字段缺失（协议漂移，见类 KDoc fail-fast）
     */
    fun toDisciple(row: DiscipleRow, json: Json): Disciple {
        val missing = missingRequiredFields(row)
        require(missing.isEmpty()) {
            "DiscipleRow 投影缺失必在字段（C++ emit-always 面漂移，禁止以默认值掩盖）：" +
                missing.joinToString(",")
        }
        val disciple = Disciple(
            id = row.id,
            slotId = 0, // 与 JSON 解码臂同口径：slotId 由 StorageEngine 落盘时赋值
            name = row.name,
            surname = row.surname,
            realm = row.realm,
            realmLayer = row.realmLayer,
            cultivation = row.cultivation,
            cultivationCheckpoint = row.cultivationCheckpoint.toDouble(),
            cultivationCheckpointGameMonth = row.cultivationCheckpointGameMonth,
            spiritRootType = row.spiritRootType,
            age = row.age,
            lifespan = row.lifespan,
            isAlive = row.isAlive,
            gender = row.gender,
            portraitRes = row.portraitRes,
            manualIds = row.manualIdsList,
            talentIds = row.talentIdsList,
            physiqueIds = row.physiqueIdsList,
            affixIds = row.affixIdsList,
            manualMasteries = row.manualMasteriesList.associate { it.key to it.value },
            status = safeStatus(row.status),
            statusData = row.statusDataList.associate { it.key to it.value },
            cultivationSpeedBonus = row.cultivationSpeedBonus,
            cultivationSpeedDuration = row.cultivationSpeedDuration,
            discipleType = row.discipleType,
            soulPower = row.soulPower,
            cultivationCompletionMonth = row.cultivationCompletionMonth,
            cultivationCompletionPhase = row.cultivationCompletionPhase,
            manualCompletionMonth = row.manualCompletionMonth,
            manualCompletionPhase = row.manualCompletionPhase,
            equipmentNurturingCompletionMonth = row.equipmentNurturingCompletionMonth,
            equipmentNurturingCompletionPhase = row.equipmentNurturingCompletionPhase
        )
        return disciple.copy(
            combat = CombatAttributes(
                baseHp = row.baseHp,
                baseMp = row.baseMp,
                basePhysicalAttack = row.basePhysicalAttack,
                baseMagicAttack = row.baseMagicAttack,
                basePhysicalDefense = row.basePhysicalDefense,
                baseMagicDefense = row.baseMagicDefense,
                baseSpeed = row.baseSpeed,
                hpVariance = row.hpVariance,
                mpVariance = row.mpVariance,
                physicalAttackVariance = row.physicalAttackVariance,
                magicAttackVariance = row.magicAttackVariance,
                physicalDefenseVariance = row.physicalDefenseVariance,
                magicDefenseVariance = row.magicDefenseVariance,
                speedVariance = row.speedVariance,
                totalCultivation = row.totalCultivation,
                breakthroughCount = row.breakthroughCount,
                breakthroughFailCount = row.breakthroughFailCount,
                currentHp = row.currentHp,
                currentMp = row.currentMp
            ),
            pillEffects = PillEffects(
                pillPhysicalAttackBonus = row.pillPhysicalAttackBonus,
                pillMagicAttackBonus = row.pillMagicAttackBonus,
                pillPhysicalDefenseBonus = row.pillPhysicalDefenseBonus,
                pillMagicDefenseBonus = row.pillMagicDefenseBonus,
                pillHpBonus = row.pillHpBonus,
                pillMpBonus = row.pillMpBonus,
                pillSpeedBonus = row.pillSpeedBonus,
                pillCritRateBonus = row.pillCritRateBonus,
                pillCritEffectBonus = row.pillCritEffectBonus,
                pillCultivationSpeedBonus = row.pillCultivationSpeedBonus,
                pillSkillExpSpeedBonus = row.pillSkillExpSpeedBonus,
                pillNurtureSpeedBonus = row.pillNurtureSpeedBonus,
                pillEffectDuration = row.pillEffectDuration,
                activePillCategory = row.activePillCategory,
                activePillTypes = row.activePillTypesList.toSet()
            ),
            equipment = EquipmentSet(
                weaponId = row.weaponId,
                armorId = row.armorId,
                bootsId = row.bootsId,
                accessoryId = row.accessoryId,
                weaponNurture = row.weaponNurture.toNurture(),
                armorNurture = row.armorNurture.toNurture(),
                bootsNurture = row.bootsNurture.toNurture(),
                accessoryNurture = row.accessoryNurture.toNurture(),
                storageBagItems = row.storageBagItems(bagItemSerializer, json),
                storageBagSpiritStones = row.storageBagSpiritStones,
                spiritStones = row.spiritStones
            ),
            social = SocialData(
                partnerId = row.partnerId.ifEmpty { null },
                partnerSectId = row.partnerSectId.ifEmpty { null },
                parentId1 = row.parentId1.ifEmpty { null },
                parentId2 = row.parentId2.ifEmpty { null },
                lastChildYear = row.lastChildYear,
                childBirthMonth = row.childBirthMonth.takeIf { it != 0 },
                griefEndYear = row.griefEndYear.takeIf { it != NULL_INT_SENTINEL },
                masterId = row.masterId.ifEmpty { null }
            ),
            skills = SkillStats(
                intelligence = row.intelligence,
                charm = row.charm,
                loyalty = row.loyalty,
                comprehension = row.comprehension,
                artifactRefining = row.artifactRefining,
                pillRefining = row.pillRefining,
                spiritPlanting = row.spiritPlanting,
                mining = row.mining,
                teaching = row.teaching,
                morality = row.morality,
                aptitude = row.aptitude,
                salaryPaidCount = row.salaryPaidCount,
                salaryMissedCount = row.salaryMissedCount,
                alchemyLevel = row.alchemyLevel,
                alchemyPromotionCount = row.alchemyPromotionCount,
                forgeLevel = row.forgeLevel,
                forgePromotionCount = row.forgePromotionCount
            ),
            usage = UsageTracking(
                usedFunctionalPillTypes = row.usedFunctionalPillTypesList,
                usedExtendLifePillIds = row.usedExtendLifePillIdsList,
                usedPermanentPillKeys = row.usedPermanentPillKeysList.toSet(),
                usedExtendLifePillTypes = row.usedExtendLifePillTypesList.toSet(),
                recruitedMonth = row.recruitedMonth,
                hasReviveEffect = row.hasReviveEffect,
                hasClearAllEffect = row.hasClearAllEffect
            )
        )
    }

    /** 与 `DiscipleSerializer.safeDiscipleStatus` 同口径：未知状态宽松回退 IDLE */
    private fun safeStatus(name: String): DiscipleStatus =
        runCatching { DiscipleStatus.valueOf(name.trim()) }.getOrDefault(DiscipleStatus.IDLE)

    /** 孕养段：嵌套消息 absent = 空孕养（与 JSON 臂"键缺失取域默认"同语义） */
    private fun EquipmentNurtureDataView.toNurture(): EquipmentNurtureData = EquipmentNurtureData(
        equipmentId = if (hasEquipmentId()) equipmentId else "",
        rarity = if (hasRarity()) rarity else 0,
        nurtureLevel = if (hasNurtureLevel()) nurtureLevel else 0,
        nurtureProgress = if (hasNurtureProgress()) nurtureProgress else 0.0
    )

    private fun DiscipleRow.storageBagItems(
        serializer: KSerializer<List<StorageBagItem>>,
        json: Json
    ): List<StorageBagItem> =
        if (storageBagItemsJson.isEmpty) emptyList()
        else json.decodeFromString(serializer, storageBagItemsJson.toStringUtf8())

    private const val NULL_INT_SENTINEL = -1
}
