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
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.proto.gameview.DiscipleRow
import com.xianxia.sect.proto.gameview.EquipmentNurtureDataView
import com.xianxia.sect.proto.gameview.StringIntEntry
import com.xianxia.sect.proto.gameview.StringStringEntry
import com.xianxia.sect.proto.gameview.TypedField
import com.xianxia.sect.proto.gameview.TypedRow
import com.xianxia.sect.proto.gameview.TypedValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

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
 *
 * 逐段构造函数（combat/pillEffects/equipment/social/skills/usage）是域模型
 * 分段形状的样板拆分，函数数即协议段数。
 */
@Suppress("TooManyFunctions")
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
        return withEmbeddedSegments(disciple, row, json)
    }

    /** 各 @Embedded 段填充（与 DiscipleSerializer buildDisciple 的分段构造同形） */
    private fun withEmbeddedSegments(disciple: Disciple, row: DiscipleRow, json: Json): Disciple =
        disciple.copy(
            combat = combatOf(row),
            pillEffects = pillEffectsOf(row),
            equipment = equipmentOf(row, json),
            social = socialOf(row),
            skills = skillsOf(row),
            usage = usageOf(row)
        )

    private fun combatOf(row: DiscipleRow) = CombatAttributes(
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
        )

    private fun pillEffectsOf(row: DiscipleRow) = PillEffects(
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
        )

    private fun equipmentOf(row: DiscipleRow, json: Json) = EquipmentSet(
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
        )

    private fun socialOf(row: DiscipleRow) = SocialData(
                partnerId = row.partnerId.ifEmpty { null },
                partnerSectId = row.partnerSectId.ifEmpty { null },
                parentId1 = row.parentId1.ifEmpty { null },
                parentId2 = row.parentId2.ifEmpty { null },
                lastChildYear = row.lastChildYear,
                childBirthMonth = row.childBirthMonth.takeIf { it != 0 },
                griefEndYear = row.griefEndYear.takeIf { it != NULL_INT_SENTINEL },
                masterId = row.masterId.ifEmpty { null }
        )

    private fun skillsOf(row: DiscipleRow) = SkillStats(
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
        )

    private fun usageOf(row: DiscipleRow) = UsageTracking(
                usedFunctionalPillTypes = row.usedFunctionalPillTypesList,
                usedExtendLifePillIds = row.usedExtendLifePillIdsList,
                usedPermanentPillKeys = row.usedPermanentPillKeysList.toSet(),
                usedExtendLifePillTypes = row.usedExtendLifePillTypesList.toSet(),
                recruitedMonth = row.recruitedMonth,
                hasReviveEffect = row.hasReviveEffect,
                hasClearAllEffect = row.hasClearAllEffect
        )

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

    /**
     * 储物袋条目（B18-P1-A2）：`storageBagItemsTyped` 优先（typed 行重建
     * JsonArray → 既有域反序列化器零变更），旧 75 号 JSON 原文 fallback
     * ——旧格式 golden 夹具仍可解码（对照面保留，非删断言）。
     *
     * 零条目 = 空袋（列携带语义由 `storageBagItemsPresent` 承载，仅列级合并
     * 需要——解码侧零条目与缺省同义，均取空表）。
     */
    private fun DiscipleRow.storageBagItems(
        serializer: KSerializer<List<StorageBagItem>>,
        json: Json
    ): List<StorageBagItem> =
        when {
            storageBagItemsTypedCount > 0 -> json.decodeFromJsonElement(
                serializer,
                JsonArray(storageBagItemsTypedList.map { it.toJsonObject() })
            )
            storageBagItemsJson.isEmpty -> emptyList()
            else -> json.decodeFromString(serializer, storageBagItemsJson.toStringUtf8())
        }

    private const val NULL_INT_SENTINEL = -1

    // ============================================================
    // R2.4/B09：列级导出的部分行合并面
    // ============================================================

    /**
     * 列级信封的弟子行补丁（proto `DiscipleRow` 的**不透明包装**）。
     *
     * 列级模式下 C++ 行内只携带脏列（presence = 已变化），消费侧需以既有
     * 行为基线合并后落表。包装的目的 = 让 [com.xianxia.sect.core.nativebridge.
     * StateSyncService] 携带 proto 载荷而不 import proto 类型（镜像符号
     * 单一入口守卫：`proto.gameview` import 仅允许 codec 与本文件）。
     */
    class DiscipleRowPatch internal constructor(internal val row: DiscipleRow) {
        override fun toString(): String = "DiscipleRowPatch(id=${row.id})"
    }

    /**
     * 部分行合并：以既有行为基线、补丁只覆盖 presence 字段，产出**全行**
     * 后走与全量臂完全相同的 [toDisciple] 投影（含 requiredScalarFields
     * fail-fast——合并后的行恒全字段，缺字段只会出现在"新行未携全字段"
     * 的 C++ 不变量破坏场景，照旧抛错降级全量兜底）。
     *
     * 合并语义 = proto `Builder.mergeFrom`（标量/消息 presence 覆盖）+
     * **repeated 先清后并**（proto repeated mergeFrom 是追加，而列级协议
     * 携带的是整列新值——脏 repeated 列必须整体替换，故先按补丁 presence
     * 清空基线同名字段再合并）。
     *
     * **repeated 列 presence 的两种表达**：条目数 > 0 即携带；**零条目**的
     * "列脏且清空"在 wire 层与"列缺省"不可区分，须由专用列携带位补回
     * （B18-P1-A2 `DiscipleRow.storageBagItemsPresent` —— 由标量换轨到
     * repeated 的列必须补该位，否则清空语义丢失）。
     *
     * @param base 既有行（store 组装）；null = 新行——列级协议下新增行走
     *        DiscipleStore 结构原语（整行标脏）恒携全字段，补丁稀疏即协议
     *        漂移，抛错（与全量臂 fail-fast 同语义）
     */
    fun mergeToDisciple(base: Disciple?, patch: DiscipleRowPatch, json: Json): Disciple {
        val partial = patch.row
        if (base == null) {
            val missing = missingRequiredFields(partial)
            require(missing.isEmpty()) {
                "列级信封新增弟子行缺必在字段（C++ append 恒全行标脏，稀疏新增 = 协议漂移）：$missing"
            }
            return toDisciple(partial, json)
        }
        val b = toRow(base, json).toBuilder()
        for (clearer in REPEATED_FIELD_CLEARERS) {
            if (clearer.present(partial)) clearer.clear(b)
        }
        return toDisciple(b.mergeFrom(partial).build(), json)
    }

    /** repeated 字段的"presence 判定 + 基线侧清空"对（合并前按补丁 presence 执行） */
    private class RepeatedClearer(
        val name: String,
        val present: (DiscipleRow) -> Boolean,
        val clear: (DiscipleRow.Builder) -> DiscipleRow.Builder,
    )

    private val REPEATED_FIELD_CLEARERS: List<RepeatedClearer> = listOf(
        RepeatedClearer("manualIds", { it.manualIdsCount > 0 }) { it.clearManualIds() },
        RepeatedClearer("talentIds", { it.talentIdsCount > 0 }) { it.clearTalentIds() },
        RepeatedClearer("physiqueIds", { it.physiqueIdsCount > 0 }) { it.clearPhysiqueIds() },
        RepeatedClearer("affixIds", { it.affixIdsCount > 0 }) { it.clearAffixIds() },
        RepeatedClearer("manualMasteries", { it.manualMasteriesCount > 0 }) { it.clearManualMasteries() },
        RepeatedClearer("statusData", { it.statusDataCount > 0 }) { it.clearStatusData() },
        RepeatedClearer("activePillTypes", { it.activePillTypesCount > 0 }) { it.clearActivePillTypes() },
        RepeatedClearer("weaponNurture", { it.hasWeaponNurture() }) { it.clearWeaponNurture() },
        RepeatedClearer("armorNurture", { it.hasArmorNurture() }) { it.clearArmorNurture() },
        RepeatedClearer("bootsNurture", { it.hasBootsNurture() }) { it.clearBootsNurture() },
        RepeatedClearer("accessoryNurture", { it.hasAccessoryNurture() }) {
            it.clearAccessoryNurture()
        },
        RepeatedClearer("usedPermanentPillKeys", { it.usedPermanentPillKeysCount > 0 }) {
            it.clearUsedPermanentPillKeys()
        },
        RepeatedClearer("usedExtendLifePillTypes", { it.usedExtendLifePillTypesCount > 0 }) {
            it.clearUsedExtendLifePillTypes()
        },
        RepeatedClearer("usedFunctionalPillTypes", { it.usedFunctionalPillTypesCount > 0 }) {
            it.clearUsedFunctionalPillTypes()
        },
        RepeatedClearer("usedExtendLifePillIds", { it.usedExtendLifePillIdsCount > 0 }) {
            it.clearUsedExtendLifePillIds()
        },
        // B18-P1-A2：storageBagItems 由 75 号**标量**（presence 天然可表达"空袋"）
        // 换轨到 110 号 repeated —— 列级合并须整体替换（repeated mergeFrom 是追加），
        // 且"列脏且清空"必须靠列携带位表达：零条目 + present=true 仍须清空基线，
        // 否则"袋被扣空/清袋"在列级合并路径上静默丢失（B18-P1-A2 实测可达：
        // auto_gear.h:936 / month_settlement.h:1698 均可在扣空后标脏该列）。
        RepeatedClearer("storageBagItemsTyped", {
            it.storageBagItemsTypedCount > 0 || it.storageBagItemsPresent
        }) { it.clearStorageBagItemsTyped() },
    )

    // ============================================================
    // B20a：列级补丁「presence 列直写」（列级收窄）
    // ============================================================

    /**
     * 列级补丁直写（B20a 列级收窄）：补丁行 presence 列**原位写**
     * [DiscipleTables]，跳过全行合并臂「基线组装 + 全行合并 + 全组列写」的
     * 三次全行遍历——列级信封稳态只携 3~5 脏列，全行臂对每行付 ~300 次
     * 列访问，直写只付 ~2×脏列（presence 位测试 + 实写）。
     *
     * ## 逐列等价口径（对照 [mergeToDisciple] + [DiscipleTables.upsertMirrorRow]
     * 全行臂，守卫 = `GameViewDiscipleColumnApplyEquivalenceTest` +
     * `MirrorSegmentProjectionBenchTest` 两臂落库全等断言）
     * - presence 标量列 → 映射列直写（协议 ↔ 域的既有口径原样保留：
     *   `cultivationCheckpoint` Long→Double、社交空串/0/-1 线路哨兵 → null、
     *   `status` 宽松枚举回退、usage 布尔 → 0/1）；
     * - presence repeated 列 → 整列替换（[REPEATED_FIELD_CLEARERS] 同清单：
     *   列级协议携带整列新值，先清后并 == 直写整列）；
     * - presence 嵌套消息列（装备孕养）→ **整值替换**（全行臂 clearer 对消息列
     *   同 repeated 家族先清后写——列级协议携带整列新值；absent 子字段按域默认，
     *   [toNurture] 同源），非字段级 overlay；
     * - storageBagItems 三表达（110 typed / 75 旧 JSON 原文 / present 零条目）
     *   → 整列替换，解码分派与 [toDisciple] 同源；
     * - **协议外瞬态列净效果显式复刻**：全行臂对每行恒写
     *   `lifeEvents = 空`（协议外瞬态显示列，每旬镜像重投后由投影事务重写）
     *   与 `slotIds = 0`（[toDisciple] 恒 0 回写）——直写同净效果；
     * - `deathYear` 两臂同语义丢弃（域模型无对应列）；
     * - 未 presence 列 → 表内既有值零触碰（全行臂对其为恒等回写）。
     *
     * @return true = 已按列直写应用（存在行）；false = 行不存在（新行/幽灵行），
     *         调用方回退全行臂（[mergeToDisciple] base=null + [DiscipleTables.
     *         upsertMirrorRow]：C++ append 恒整行标脏，稀疏新增照旧 fail-fast）
     */
    fun applyPatchInPlace(tables: DiscipleTables, patch: DiscipleRowPatch, json: Json): Boolean {
        val row = patch.row
        val id = row.id.toIntOrNull() ?: return false
        return tables.patchExistingMirrorRow(id) {
            // 协议外瞬态列净效果（全行臂恒写：lifeEvents 空 / slotIds 0）
            lifeEvents[id] = emptyList()
            slotIds[id] = 0
            applyBasicPatchColumns(id, row)
            applyCombatPatchColumns(id, row)
            applyPillPatchColumns(id, row)
            applyEquipmentPatchColumns(id, row, json)
            applySocialPatchColumns(id, row)
            applySkillPatchColumns(id, row)
            applyUsagePatchColumns(id, row)
        }
    }

    /** 基础段 presence 列直写（映射表 = [toDisciple] 基础段 ↔ `writeAllFields` 基本面）。 */
    // 列级补丁 presence 直写映射表：函数数=协议列数，每行一列 presence 位测试
    // + 直写（与 C++ serializeDiscipleColumn switch 同形样板），拆分即机械切半
    @Suppress("CyclomaticComplexMethod")
    private fun DiscipleTables.applyBasicPatchColumns(id: Int, row: DiscipleRow) {
        if (row.hasName()) names[id] = row.name
        if (row.hasSurname()) surnames[id] = row.surname
        if (row.hasRealm()) realms[id] = row.realm
        if (row.hasRealmLayer()) realmLayers[id] = row.realmLayer
        if (row.hasCultivation()) cultivations[id] = row.cultivation
        if (row.hasCultivationCheckpoint()) {
            cultivationCheckpoints[id] = row.cultivationCheckpoint.toDouble()
        }
        if (row.hasCultivationCheckpointGameMonth()) {
            cultivationCheckpointGameMonths[id] = row.cultivationCheckpointGameMonth
        }
        if (row.hasSpiritRootType()) spiritRootTypes[id] = row.spiritRootType
        if (row.hasAge()) ages[id] = row.age
        if (row.hasLifespan()) lifespans[id] = row.lifespan
        if (row.hasIsAlive()) isAlive[id] = if (row.isAlive) 1 else 0
        // deathYear：协议随行字段，域模型无对应列——两臂同语义丢弃
        if (row.hasGender()) genders[id] = row.gender
        if (row.hasPortraitRes()) portraitRes[id] = row.portraitRes
        if (row.manualIdsCount > 0) manualIds[id] = row.manualIdsList
        if (row.talentIdsCount > 0) talentIds[id] = row.talentIdsList
        if (row.physiqueIdsCount > 0) physiqueIds[id] = row.physiqueIdsList
        if (row.affixIdsCount > 0) affixIds[id] = row.affixIdsList
        if (row.manualMasteriesCount > 0) {
            manualMasteries[id] = row.manualMasteriesList.associate { it.key to it.value }
        }
        if (row.hasStatus()) statuses[id] = safeStatus(row.status)
        if (row.statusDataCount > 0) {
            statusData[id] = row.statusDataList.associate { it.key to it.value }
        }
        if (row.hasCultivationSpeedBonus()) cultivationSpeedBonuses[id] = row.cultivationSpeedBonus
        if (row.hasCultivationSpeedDuration()) {
            cultivationSpeedDurations[id] = row.cultivationSpeedDuration
        }
        if (row.hasDiscipleType()) discipleTypes[id] = row.discipleType
        if (row.hasSoulPower()) soulPowers[id] = row.soulPower
        if (row.hasCultivationCompletionMonth()) {
            cultivationCompletionMonths[id] = row.cultivationCompletionMonth
        }
        if (row.hasCultivationCompletionPhase()) {
            cultivationCompletionPhases[id] = row.cultivationCompletionPhase
        }
        if (row.hasManualCompletionMonth()) manualCompletionMonths[id] = row.manualCompletionMonth
        if (row.hasManualCompletionPhase()) manualCompletionPhases[id] = row.manualCompletionPhase
        if (row.hasEquipmentNurturingCompletionMonth()) {
            equipmentNurturingCompletionMonths[id] = row.equipmentNurturingCompletionMonth
        }
        if (row.hasEquipmentNurturingCompletionPhase()) {
            equipmentNurturingCompletionPhases[id] = row.equipmentNurturingCompletionPhase
        }
    }

    /** 战斗段 presence 列直写（映射表 = [combatOf] ↔ `writeAllFields` 战斗面）。 */
    // 列级补丁 presence 直写映射表：函数数=协议列数，每行一列 presence 位测试
    // + 直写（与 C++ serializeDiscipleColumn switch 同形样板），拆分即机械切半
    @Suppress("CyclomaticComplexMethod")
    private fun DiscipleTables.applyCombatPatchColumns(id: Int, row: DiscipleRow) {
        if (row.hasBaseHp()) baseHps[id] = row.baseHp
        if (row.hasBaseMp()) baseMps[id] = row.baseMp
        if (row.hasBasePhysicalAttack()) basePhysicalAttacks[id] = row.basePhysicalAttack
        if (row.hasBaseMagicAttack()) baseMagicAttacks[id] = row.baseMagicAttack
        if (row.hasBasePhysicalDefense()) basePhysicalDefenses[id] = row.basePhysicalDefense
        if (row.hasBaseMagicDefense()) baseMagicDefenses[id] = row.baseMagicDefense
        if (row.hasBaseSpeed()) baseSpeeds[id] = row.baseSpeed
        if (row.hasHpVariance()) hpVariances[id] = row.hpVariance
        if (row.hasMpVariance()) mpVariances[id] = row.mpVariance
        if (row.hasPhysicalAttackVariance()) {
            physicalAttackVariances[id] = row.physicalAttackVariance
        }
        if (row.hasMagicAttackVariance()) magicAttackVariances[id] = row.magicAttackVariance
        if (row.hasPhysicalDefenseVariance()) {
            physicalDefenseVariances[id] = row.physicalDefenseVariance
        }
        if (row.hasMagicDefenseVariance()) magicDefenseVariances[id] = row.magicDefenseVariance
        if (row.hasSpeedVariance()) speedVariances[id] = row.speedVariance
        if (row.hasTotalCultivation()) totalCultivations[id] = row.totalCultivation
        if (row.hasBreakthroughCount()) breakthroughCounts[id] = row.breakthroughCount
        if (row.hasBreakthroughFailCount()) breakthroughFailCounts[id] = row.breakthroughFailCount
        if (row.hasCurrentHp()) currentHps[id] = row.currentHp
        if (row.hasCurrentMp()) currentMps[id] = row.currentMp
    }

    /** 丹药段 presence 列直写（映射表 = [pillEffectsOf] ↔ `writeAllFields` 丹药面）。 */
    // 列级补丁 presence 直写映射表：函数数=协议列数，每行一列 presence 位测试
    // + 直写（与 C++ serializeDiscipleColumn switch 同形样板），拆分即机械切半
    @Suppress("CyclomaticComplexMethod")
    private fun DiscipleTables.applyPillPatchColumns(id: Int, row: DiscipleRow) {
        if (row.hasPillPhysicalAttackBonus()) {
            pillPhysicalAttackBonuses[id] = row.pillPhysicalAttackBonus
        }
        if (row.hasPillMagicAttackBonus()) pillMagicAttackBonuses[id] = row.pillMagicAttackBonus
        if (row.hasPillPhysicalDefenseBonus()) {
            pillPhysicalDefenseBonuses[id] = row.pillPhysicalDefenseBonus
        }
        if (row.hasPillMagicDefenseBonus()) pillMagicDefenseBonuses[id] = row.pillMagicDefenseBonus
        if (row.hasPillHpBonus()) pillHpBonuses[id] = row.pillHpBonus
        if (row.hasPillMpBonus()) pillMpBonuses[id] = row.pillMpBonus
        if (row.hasPillSpeedBonus()) pillSpeedBonuses[id] = row.pillSpeedBonus
        if (row.hasPillEffectDuration()) pillEffectDurations[id] = row.pillEffectDuration
        if (row.hasPillCritRateBonus()) pillCritRateBonuses[id] = row.pillCritRateBonus
        if (row.hasPillCritEffectBonus()) pillCritEffectBonuses[id] = row.pillCritEffectBonus
        if (row.hasPillCultivationSpeedBonus()) {
            pillCultivationSpeedBonuses[id] = row.pillCultivationSpeedBonus
        }
        if (row.hasPillSkillExpSpeedBonus()) {
            pillSkillExpSpeedBonuses[id] = row.pillSkillExpSpeedBonus
        }
        if (row.hasPillNurtureSpeedBonus()) {
            pillNurtureSpeedBonuses[id] = row.pillNurtureSpeedBonus
        }
        if (row.hasActivePillCategory()) activePillCategories[id] = row.activePillCategory
        if (row.activePillTypesCount > 0) activePillTypes[id] = row.activePillTypesList.toSet()
    }

    /** 装备段 presence 列直写（映射表 = [equipmentOf] ↔ 装备列写入面）。 */
    // 列级补丁 presence 直写映射表：函数数=协议列数，每行一列 presence 位测试
    // + 直写（与 C++ serializeDiscipleColumn switch 同形样板），拆分即机械切半
    @Suppress("CyclomaticComplexMethod")
    private fun DiscipleTables.applyEquipmentPatchColumns(
        id: Int,
        row: DiscipleRow,
        json: Json,
    ) {
        if (row.hasWeaponId()) weaponIds[id] = row.weaponId
        if (row.hasArmorId()) armorIds[id] = row.armorId
        if (row.hasBootsId()) bootsIds[id] = row.bootsId
        if (row.hasAccessoryId()) accessoryIds[id] = row.accessoryId
        // 孕养嵌套消息：全行臂对消息列同 repeated 家族——clearer 先清、mergeFrom
        // 整值写入（列级协议携带整列新值），absent 子字段按域默认（toNurture 同源）
        if (row.hasWeaponNurture()) weaponNurtures[id] = row.weaponNurture.toNurture()
        if (row.hasArmorNurture()) armorNurtures[id] = row.armorNurture.toNurture()
        if (row.hasBootsNurture()) bootsNurtures[id] = row.bootsNurture.toNurture()
        if (row.hasAccessoryNurture()) accessoryNurtures[id] = row.accessoryNurture.toNurture()
        // 储物袋：净效果 = 全行臂 merged 行的解码分派（typed 优先，基线 typed
        // 经 toRow 进入 merged 行）——故补丁携带位（typed/present）在位 ⇒ 整列
        // 替换（typed → 75 → 空）；**75-only 且基线袋为空** ⇒ 75 解码（merged
        // 行 typed 计数 0 时 75 才可见）；75-only 且基线袋非空 ⇒ typed 优先
        // 吞掉 75 ⇒ 基线保留。三判定与 mergeToDisciple+toDisciple 逐输入对齐。
        val bagCarried = row.storageBagItemsTypedCount > 0 || row.storageBagItemsPresent
        when {
            bagCarried -> storageBagItems[id] = row.bagItemsOrEmpty(json)
            !row.storageBagItemsJson.isEmpty &&
                storageBagItems.getOrDefault(id, emptyList()).isEmpty() -> {
                storageBagItems[id] = row.bagItemsOrEmpty(json)
            }
        }
        if (row.hasStorageBagSpiritStones()) {
            storageBagSpiritStones[id] = row.storageBagSpiritStones
        }
        if (row.hasSpiritStones()) discipleSpiritStones[id] = row.spiritStones
    }

    /** 社交段 presence 列直写（映射表 = [socialOf] ↔ `writeAllFields` 社交面，线路哨兵同口径）。 */
    private fun DiscipleTables.applySocialPatchColumns(id: Int, row: DiscipleRow) {
        if (row.hasPartnerId()) partnerIds[id] = row.partnerId.ifEmpty { null }
        if (row.hasPartnerSectId()) partnerSectIds[id] = row.partnerSectId.ifEmpty { null }
        if (row.hasParentId1()) parentId1s[id] = row.parentId1.ifEmpty { null }
        if (row.hasParentId2()) parentId2s[id] = row.parentId2.ifEmpty { null }
        if (row.hasLastChildYear()) lastChildYears[id] = row.lastChildYear
        if (row.hasChildBirthMonth()) {
            childBirthMonths[id] = row.childBirthMonth.takeIf { it != 0 }
        }
        // -1 = 域 null 的线路哨兵：merged 臂 -1 → null → 回写哨兵，直写透传同值
        if (row.hasGriefEndYear()) griefEndYears[id] = row.griefEndYear
        if (row.hasMasterId()) masterIds[id] = row.masterId.ifEmpty { null }
    }

    /** 技能段 presence 列直写（映射表 = [skillsOf] ↔ `writeAllFields` 技能面）。 */
    // 列级补丁 presence 直写映射表：函数数=协议列数，每行一列 presence 位测试
    // + 直写（与 C++ serializeDiscipleColumn switch 同形样板），拆分即机械切半
    @Suppress("CyclomaticComplexMethod")
    private fun DiscipleTables.applySkillPatchColumns(id: Int, row: DiscipleRow) {
        if (row.hasIntelligence()) intelligences[id] = row.intelligence
        if (row.hasCharm()) charms[id] = row.charm
        if (row.hasLoyalty()) loyalties[id] = row.loyalty
        if (row.hasComprehension()) comprehensions[id] = row.comprehension
        if (row.hasArtifactRefining()) artifactRefinings[id] = row.artifactRefining
        if (row.hasPillRefining()) pillRefinings[id] = row.pillRefining
        if (row.hasSpiritPlanting()) spiritPlantings[id] = row.spiritPlanting
        if (row.hasMining()) minings[id] = row.mining
        if (row.hasTeaching()) teachings[id] = row.teaching
        if (row.hasMorality()) moralities[id] = row.morality
        if (row.hasAptitude()) aptitudes[id] = row.aptitude
        if (row.hasSalaryPaidCount()) salaryPaidCounts[id] = row.salaryPaidCount
        if (row.hasSalaryMissedCount()) salaryMissedCounts[id] = row.salaryMissedCount
        if (row.hasAlchemyLevel()) alchemyLevels[id] = row.alchemyLevel
        if (row.hasAlchemyPromotionCount()) alchemyPromotionCounts[id] = row.alchemyPromotionCount
        if (row.hasForgeLevel()) forgeLevels[id] = row.forgeLevel
        if (row.hasForgePromotionCount()) forgePromotionCounts[id] = row.forgePromotionCount
    }

    /** 使用追踪段 presence 列直写（映射表 = [usageOf] ↔ `writeAllFields` 使用面）。 */
    private fun DiscipleTables.applyUsagePatchColumns(id: Int, row: DiscipleRow) {
        if (row.usedFunctionalPillTypesCount > 0) {
            usedFunctionalPillTypes[id] = row.usedFunctionalPillTypesList
        }
        if (row.usedExtendLifePillIdsCount > 0) {
            usedExtendLifePillIds[id] = row.usedExtendLifePillIdsList
        }
        if (row.usedPermanentPillKeysCount > 0) {
            usedPermanentPillKeys[id] = row.usedPermanentPillKeysList.toSet()
        }
        if (row.usedExtendLifePillTypesCount > 0) {
            usedExtendLifePillTypes[id] = row.usedExtendLifePillTypesList.toSet()
        }
        if (row.hasRecruitedMonth()) recruitedMonths[id] = row.recruitedMonth
        if (row.hasHasReviveEffect()) hasReviveEffects[id] = if (row.hasReviveEffect) 1 else 0
        if (row.hasHasClearAllEffect()) hasClearAllEffects[id] = if (row.hasClearAllEffect) 1 else 0
    }

    /**
     * 补丁行储物袋列值（presence 判定与 [mergeToDisciple] 的 clearer + [toDisciple]
     * 的解码分派同口径）：typed 优先 → 旧 75 号 JSON 原文 fallback → 零条目空袋。
     */
    private fun DiscipleRow.bagItemsOrEmpty(json: Json): List<StorageBagItem> = when {
        storageBagItemsTypedCount > 0 -> json.decodeFromJsonElement(
            bagItemSerializer,
            JsonArray(storageBagItemsTypedList.map { it.toJsonObject() })
        )
        !storageBagItemsJson.isEmpty -> json.decodeFromString(
            bagItemSerializer,
            storageBagItemsJson.toStringUtf8()
        )
        else -> emptyList()
    }

    /**
     * 弟子域模型 → `DiscipleRow` 全字段行编码（生产面；与 C++ 编码器
     * `kDiscipleRowFields` 表逐项对应，emit-always 恒设全部字段）。
     * 列级合并的基线行来源（[mergeToDisciple]），亦供测试夹具复用。
     */    fun toRow(d: Disciple, json: Json): DiscipleRow {
        val b = DiscipleRow.newBuilder()
        fillDirectRowFields(b, d)
        fillCombatPillRowFields(b, d)
        fillEquipmentSocialRowFields(b, d, json)
        fillSkillUsageRowFields(b, d)
        return b.build()
    }

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

    private fun fillEquipmentSocialRowFields(
        b: DiscipleRow.Builder,
        d: Disciple,
        json: Json,
    ) {
        b.weaponId = d.equipment.weaponId
        b.armorId = d.equipment.armorId
        b.bootsId = d.equipment.bootsId
        b.accessoryId = d.equipment.accessoryId
        b.weaponNurture = d.equipment.weaponNurture.toRowView()
        b.armorNurture = d.equipment.armorNurture.toRowView()
        b.bootsNurture = d.equipment.bootsNurture.toRowView()
        b.accessoryNurture = d.equipment.accessoryNurture.toRowView()
        // storageBagItems（B18-P1-A2）：typed 行承载（旧 75 号 JSON 原文停写保留）；
        // 列携带位恒置 true（全量行 = emit-always；列级补丁侧由 C++ 按"该列脏"置位）
        // ——repeated 零条目无法区分"列缺省"与"列脏且清空"，清空语义全靠本位置
        d.equipment.storageBagItems.forEach { item ->
            b.addStorageBagItemsTyped(
                json.encodeToJsonElement(StorageBagItem.serializer(), item).jsonObject.toTypedRow()
            )
        }
        b.storageBagItemsPresent = true
        b.storageBagSpiritStones = d.equipment.storageBagSpiritStones
        b.spiritStones = d.equipment.spiritStones
        // 社交可空字段的线路哨兵（"" / 0 / -1 = null）与 DiscipleSerializer 同口径
        b.partnerId = d.social.partnerId ?: ""
        b.partnerSectId = d.social.partnerSectId ?: ""
        b.parentId1 = d.social.parentId1 ?: ""
        b.parentId2 = d.social.parentId2 ?: ""
        b.lastChildYear = d.social.lastChildYear
        b.childBirthMonth = d.social.childBirthMonth ?: 0
        b.griefEndYear = d.social.griefEndYear ?: NULL_INT_SENTINEL
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

    private fun EquipmentNurtureData.toRowView(): EquipmentNurtureDataView =
        EquipmentNurtureDataView.newBuilder()
            .setEquipmentId(equipmentId)
            .setRarity(rarity)
            .setNurtureLevel(nurtureLevel)
            .setNurtureProgress(nurtureProgress)
            .build()
}

// ============================================================
// B18-P1：通用 typed 值承载（proto `TypedValue`/`TypedRow`）→ JSON 元素树
// 重建基建。upsertsJson/valueJson/storageBagItemsJson 三字段 typed 化后，
// 解码侧以本单源重建 JsonElement（Kotlin 内部交换格式仍是 JsonElement 树，
// 下游 applier 零变更）；挂本文件是因 proto.gameview import 守卫白名单仅
// codec 与本文件，且 TypedRow 与 DiscipleRow 同为行契约类型。
// ============================================================

/**
 * proto typed 值 → JSON 元素树（递归）。
 *
 * 等价口径（§1.6 数字格式红线）：`vInt` 经 [JsonPrimitive] 重建的 content
 * 与旧 JSON 整数文本逐字符串相等；`vDouble` 同（生产树恒已过
 * normalizeIntegralFloats——整值浮点已转 int64，此分支只见非整值浮点；
 * 人间尺度数值的 Java 最短表示与 nlohmann dump 文本一致。极端量级浮点的
 * content 文本可能表示形式不同而数值恒等——下游域解码按值消费零影响，
 * 双路等价守卫为仲裁，红则按方案口径改 content 直构）。
 *
 * 空容器判别（b02 发现 7 wire 事实）：`[]` 与 `{}` 在 wire 层同为零字节——
 * C++ 编码器对空数组恒写 `vEmptyArray=true` 判别位，零字节缺省 = `{}`。
 */
internal fun TypedValue.toJsonElement(): JsonElement = when {
    hasVString() -> JsonPrimitive(vString)
    hasVInt() -> JsonPrimitive(vInt)
    hasVDouble() -> JsonPrimitive(vDouble)
    hasVBool() -> JsonPrimitive(vBool)
    hasVNull() -> JsonNull
    vArrayCount > 0 -> JsonArray(vArrayList.map { it.toJsonElement() })
    vObjectCount > 0 -> JsonObject(vObjectList.associate { it.toEntry() })
    hasVEmptyArray() -> JsonArray(emptyList())
    else -> JsonObject(emptyMap())
}

/**
 * proto typed 实体行 → JSON 对象（键序 = wire 序 = C++ nlohmann 键字典序，
 * 与旧 JSON 文本解析产物同形同值）。
 */
internal fun TypedRow.toJsonObject(): JsonObject = JsonObject(fieldsList.associate { it.toEntry() })

private fun TypedField.toEntry(): Pair<String, JsonElement> =
    key to (if (hasValue()) value.toJsonElement() else JsonNull)

// ── 反向（B18-P1-A2）：JSON 元素树 → proto typed 承载 ────────────────
// 生产消费点 = [toRow]（弟子域模型 → DiscipleRow 全字段行的 storageBagItems 列，
// 兼作列级合并的基线行与测试夹具编码侧）。分派口径与 C++ 编码器同序
// （字符串 → 数值 → 布尔 → 数组 → 对象 → null）。

/** JSON 元素 → proto typed 值（递归；空数组写 `vEmptyArray` 判别位）。 */
internal fun JsonElement.toTypedValue(): TypedValue {
    val b = TypedValue.newBuilder()
    when (this) {
        is JsonNull -> b.setVNull(true)
        is JsonObject -> forEach { (k, v) ->
            b.addVObject(TypedField.newBuilder().setKey(k).setValue(v.toTypedValue()))
        }
        is JsonArray ->
            if (isEmpty()) b.setVEmptyArray(true) else forEach { b.addVArray(it.toTypedValue()) }
        is JsonPrimitive -> when {
            isString -> b.setVString(content)
            booleanOrNull != null -> b.setVBool(booleanOrNull!!)
            longOrNull != null -> b.setVInt(longOrNull!!)
            else -> b.setVDouble(requireNotNull(doubleOrNull) { "非数值字面量：$this" })
        }
    }
    return b.build()
}

/** JSON 对象 → proto typed 行（键序 = JsonObject 插入序 = C++ nlohmann 键字典序）。 */
internal fun JsonObject.toTypedRow(): TypedRow = TypedRow.newBuilder().apply {
    forEach { (k, v) -> addFields(TypedField.newBuilder().setKey(k).setValue(v.toTypedValue())) }
}.build()
