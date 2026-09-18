package com.xianxia.sect.core.nativebridge

import com.google.protobuf.ByteString
import com.xianxia.sect.proto.gameview.DiscipleRow
import com.xianxia.sect.proto.gameview.EquipmentNurtureDataView
import com.xianxia.sect.proto.gameview.GameView
import com.xianxia.sect.proto.gameview.StringIntEntry
import com.xianxia.sect.proto.gameview.StringStringEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * GameViewMirrorCodec — GameView protobuf 信封 → 变更集树解码（重构方案 R2.2）。
 *
 * 把 C++ [GameView] 二进制信封还原为与旧 JSON 变更集协议**同形**的
 * `{changed, removed}`（键：`gameData.<field>` / 集合名；值：实体数组 JSON /
 * 字段值 JSON）——[StateSyncService] 随后复用既有 JSON 应用逻辑（同一 applier），
 * 因此 protobuf 传输与 JSON 传输在相同 state 输入下逐值等价（等价性由
 * DiffDirtyEnvelopeEquivalenceTest 守卫锁定）。
 *
 * 本批红线：UI 消费面不动（R2.3 才瘦身），镜像仍全量、仅换传输编码——故本解码
 * 面只做**编码换轨**（protobuf → 同一棵变更集树），不改应用语义。
 *
 * DiscipleRow → JSON 逐字段重建与 C++ 编码器 `kDiscipleRowFields` 表一一对应：
 * 只重建 protobuf 中 present 的字段（C++ 编码器 emit-always + 逐键 presence 判定
 * 与本解码器 `hasXxx()`/count 判定同源），storageBagItems 走 JSON 原文回填（v1 过渡编码）。
 */
@Suppress("TooManyFunctions")  // 逐字段类别构造函数（str/i32/...）为编码表样板， cohesive 解码职责
internal object GameViewMirrorCodec {

    private val json = Json { ignoreUnknownKeys = true }

    /** 解码产物：与 [StateSyncService] 的 DirtyEnvelope 同语义三元组。 */
    data class Decoded(
        val version: Long,
        val changed: JsonObject,
        val removed: JsonObject,
    )

    /**
     * GameView 信封字节 → 变更集树。非法字节抛 InvalidProtocolBufferException
     * （调用方 runCatching 降级，不触碰状态）。
     */
    fun decode(bytes: ByteArray): Decoded {
        val gv = GameView.parseFrom(bytes)
        val changed = LinkedHashMap<String, JsonElement>()
        val removed = LinkedHashMap<String, JsonElement>()

        // 块 1：resourcesHeader.spiritStones → gameData.spiritStones
        if (gv.hasResourcesHeader() && gv.resourcesHeader.hasSpiritStones()) {
            changed["gameData.spiritStones"] = JsonPrimitive(gv.resourcesHeader.spiritStones)
        }

        // 扩展区 gameDataChange：gameData.<name> = valueJson（原始值形态，标量/容器）
        for (jc in gv.gameDataChangeList) {
            if (jc.valueJson.isEmpty) continue
            changed["gameData.${jc.name}"] = json.parseToJsonElement(jc.valueJson.toStringUtf8())
        }

        // 扩展区 collectionChange：非弟子实体集合（upsertsJson 数组 + removedIds）
        for (cc in gv.collectionChangeList) {
            if (!cc.upsertsJson.isEmpty) {
                changed[cc.name] = json.parseToJsonElement(cc.upsertsJson.toStringUtf8())
            }
            if (cc.removedIdsCount > 0) removed[cc.name] = cc.removedIdsList.toJsonIdArray()
        }

        // 块 2：discipleListDelta（typed 行重建 + removedIds）
        if (gv.hasDiscipleListDelta()) {
            val delta = gv.discipleListDelta
            if (delta.upsertsCount > 0) {
                changed["disciples"] = JsonArray(delta.upsertsList.map { it.toJsonObject() })
            }
            if (delta.removedIdsCount > 0) removed["disciples"] = delta.removedIdsList.toJsonIdArray()
        }

        return Decoded(gv.version, JsonObject(changed), JsonObject(removed))
    }

    private fun List<String>.toJsonIdArray(): JsonArray = JsonArray(map { JsonPrimitive(it) })

    private fun EquipmentNurtureDataView.nurtureToJson(): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        if (hasEquipmentId()) m["equipmentId"] = JsonPrimitive(equipmentId)
        if (hasRarity()) m["rarity"] = JsonPrimitive(rarity.toLong())
        if (hasNurtureLevel()) m["nurtureLevel"] = JsonPrimitive(nurtureLevel.toLong())
        if (hasNurtureProgress()) m["nurtureProgress"] = JsonPrimitive(nurtureProgress)
        return JsonObject(m)
    }

    @Suppress("UNCHECKED_CAST")
    private fun DiscipleRow.toJsonObject(): JsonObject {
        val map = LinkedHashMap<String, JsonElement>()
        for (spec in ROW_SPECS) {
            if (!spec.present(this)) continue
            map[spec.key] = when (spec.kind) {
                Kind.STR -> JsonPrimitive(spec.value(this) as String)
                Kind.INT, Kind.LONG -> JsonPrimitive((spec.value(this) as Number).toLong())
                Kind.BOOL -> JsonPrimitive(spec.value(this) as Boolean)
                Kind.DOUBLE -> JsonPrimitive(spec.value(this) as Double)
                Kind.STR_LIST ->
                    JsonArray((spec.value(this) as List<String>).map { JsonPrimitive(it) })
                Kind.INT_MAP -> JsonObject(
                    (spec.value(this) as List<StringIntEntry>).associate {
                        it.key to JsonPrimitive(it.value.toLong())
                    }
                )
                Kind.STR_MAP -> JsonObject(
                    (spec.value(this) as List<StringStringEntry>).associate {
                        it.key to JsonPrimitive(it.value)
                    }
                )
                Kind.NURTURE -> (spec.value(this) as EquipmentNurtureDataView).nurtureToJson()
                Kind.BYTES_JSON ->
                    json.parseToJsonElement((spec.value(this) as ByteString).toStringUtf8())
            }
        }
        return JsonObject(map)
    }

    /** 行字段 wire 类别（与 game_view.proto DiscipleRow / C++ kDiscipleRowFields 对应）。 */
    private enum class Kind { STR, INT, LONG, BOOL, DOUBLE, STR_LIST, INT_MAP, STR_MAP, NURTURE, BYTES_JSON }

    private class Spec(
        val key: String,
        val kind: Kind,
        val present: (DiscipleRow) -> Boolean,
        val value: (DiscipleRow) -> Any,
    )

    private fun str(k: String, has: (DiscipleRow) -> Boolean, get: (DiscipleRow) -> String) =
        Spec(k, Kind.STR, has, get)

    private fun i32(k: String, has: (DiscipleRow) -> Boolean, get: (DiscipleRow) -> Int) =
        Spec(k, Kind.INT, has, get)

    private fun i64(k: String, has: (DiscipleRow) -> Boolean, get: (DiscipleRow) -> Long) =
        Spec(k, Kind.LONG, has, get)

    private fun bl(k: String, has: (DiscipleRow) -> Boolean, get: (DiscipleRow) -> Boolean) =
        Spec(k, Kind.BOOL, has, get)

    private fun dbl(k: String, has: (DiscipleRow) -> Boolean, get: (DiscipleRow) -> Double) =
        Spec(k, Kind.DOUBLE, has, get)

    private fun sl(k: String, get: (DiscipleRow) -> List<String>) =
        Spec(k, Kind.STR_LIST, { get(it).isNotEmpty() }, get)

    private fun im(k: String, get: (DiscipleRow) -> List<StringIntEntry>) =
        Spec(k, Kind.INT_MAP, { get(it).isNotEmpty() }, get)

    private fun sm(k: String, get: (DiscipleRow) -> List<StringStringEntry>) =
        Spec(k, Kind.STR_MAP, { get(it).isNotEmpty() }, get)

    private fun nu(
        k: String,
        has: (DiscipleRow) -> Boolean,
        get: (DiscipleRow) -> EquipmentNurtureDataView,
    ) = Spec(k, Kind.NURTURE, has, get)

    private fun bj(k: String, get: (DiscipleRow) -> ByteString) =
        Spec(k, Kind.BYTES_JSON, { get(it).isEmpty.not() }, get)

    // 表序 = proto 字段号升序，键 = C++ to_json 协议键（与 kDiscipleRowFields 逐项对齐）。
    // present 判定与 C++ 编码器逐字段 presence 判定同源（emit-always ⇒ 稳态恒 present）；
    // schema 演进（老 native 不产出新字段）时 hasXxx()=false → 不重建，Disciple 默认值补位。
    @Suppress("LargeClass", "MaxLineLength")
    private val ROW_SPECS: List<Spec> = listOf(
        str("id", { it.hasId() }, { it.id }),
        str("name", { it.hasName() }, { it.name }),
        str("surname", { it.hasSurname() }, { it.surname }),
        i32("realm", { it.hasRealm() }, { it.realm }),
        i32("realmLayer", { it.hasRealmLayer() }, { it.realmLayer }),
        dbl("cultivation", { it.hasCultivation() }, { it.cultivation }),
        i64("cultivationCheckpoint", { it.hasCultivationCheckpoint() }, { it.cultivationCheckpoint }),
        i32(
            "cultivationCheckpointGameMonth", { it.hasCultivationCheckpointGameMonth() },
            { it.cultivationCheckpointGameMonth },
        ),
        str("spiritRootType", { it.hasSpiritRootType() }, { it.spiritRootType }),
        i32("age", { it.hasAge() }, { it.age }),
        i32("lifespan", { it.hasLifespan() }, { it.lifespan }),
        bl("isAlive", { it.hasIsAlive() }, { it.isAlive }),
        i32("deathYear", { it.hasDeathYear() }, { it.deathYear }),
        str("gender", { it.hasGender() }, { it.gender }),
        str("portraitRes", { it.hasPortraitRes() }, { it.portraitRes }),
        sl("manualIds", { it.manualIdsList }),
        sl("talentIds", { it.talentIdsList }),
        sl("physiqueIds", { it.physiqueIdsList }),
        sl("affixIds", { it.affixIdsList }),
        im("manualMasteries", { it.manualMasteriesList }),
        str("status", { it.hasStatus() }, { it.status }),
        sm("statusData", { it.statusDataList }),
        dbl("cultivationSpeedBonus", { it.hasCultivationSpeedBonus() }, { it.cultivationSpeedBonus }),
        i32("cultivationSpeedDuration", { it.hasCultivationSpeedDuration() }, { it.cultivationSpeedDuration }),
        str("discipleType", { it.hasDiscipleType() }, { it.discipleType }),
        i32("soulPower", { it.hasSoulPower() }, { it.soulPower }),
        i32(
            "cultivationCompletionMonth", { it.hasCultivationCompletionMonth() },
            { it.cultivationCompletionMonth },
        ),
        i32(
            "cultivationCompletionPhase", { it.hasCultivationCompletionPhase() },
            { it.cultivationCompletionPhase },
        ),
        i32("manualCompletionMonth", { it.hasManualCompletionMonth() }, { it.manualCompletionMonth }),
        i32("manualCompletionPhase", { it.hasManualCompletionPhase() }, { it.manualCompletionPhase }),
        i32(
            "equipmentNurturingCompletionMonth", { it.hasEquipmentNurturingCompletionMonth() },
            { it.equipmentNurturingCompletionMonth },
        ),
        i32(
            "equipmentNurturingCompletionPhase", { it.hasEquipmentNurturingCompletionPhase() },
            { it.equipmentNurturingCompletionPhase },
        ),
        i32("baseHp", { it.hasBaseHp() }, { it.baseHp }),
        i32("baseMp", { it.hasBaseMp() }, { it.baseMp }),
        i32("basePhysicalAttack", { it.hasBasePhysicalAttack() }, { it.basePhysicalAttack }),
        i32("baseMagicAttack", { it.hasBaseMagicAttack() }, { it.baseMagicAttack }),
        i32("basePhysicalDefense", { it.hasBasePhysicalDefense() }, { it.basePhysicalDefense }),
        i32("baseMagicDefense", { it.hasBaseMagicDefense() }, { it.baseMagicDefense }),
        i32("baseSpeed", { it.hasBaseSpeed() }, { it.baseSpeed }),
        i32("hpVariance", { it.hasHpVariance() }, { it.hpVariance }),
        i32("mpVariance", { it.hasMpVariance() }, { it.mpVariance }),
        i32("physicalAttackVariance", { it.hasPhysicalAttackVariance() }, { it.physicalAttackVariance }),
        i32("magicAttackVariance", { it.hasMagicAttackVariance() }, { it.magicAttackVariance }),
        i32(
            "physicalDefenseVariance", { it.hasPhysicalDefenseVariance() },
            { it.physicalDefenseVariance },
        ),
        i32("magicDefenseVariance", { it.hasMagicDefenseVariance() }, { it.magicDefenseVariance }),
        i32("speedVariance", { it.hasSpeedVariance() }, { it.speedVariance }),
        i64("totalCultivation", { it.hasTotalCultivation() }, { it.totalCultivation }),
        i32("breakthroughCount", { it.hasBreakthroughCount() }, { it.breakthroughCount }),
        i32("breakthroughFailCount", { it.hasBreakthroughFailCount() }, { it.breakthroughFailCount }),
        i32("currentHp", { it.hasCurrentHp() }, { it.currentHp }),
        i32("currentMp", { it.hasCurrentMp() }, { it.currentMp }),
        i32("pillPhysicalAttackBonus", { it.hasPillPhysicalAttackBonus() }, { it.pillPhysicalAttackBonus }),
        i32("pillMagicAttackBonus", { it.hasPillMagicAttackBonus() }, { it.pillMagicAttackBonus }),
        i32(
            "pillPhysicalDefenseBonus", { it.hasPillPhysicalDefenseBonus() },
            { it.pillPhysicalDefenseBonus },
        ),
        i32("pillMagicDefenseBonus", { it.hasPillMagicDefenseBonus() }, { it.pillMagicDefenseBonus }),
        i32("pillHpBonus", { it.hasPillHpBonus() }, { it.pillHpBonus }),
        i32("pillMpBonus", { it.hasPillMpBonus() }, { it.pillMpBonus }),
        i32("pillSpeedBonus", { it.hasPillSpeedBonus() }, { it.pillSpeedBonus }),
        dbl("pillCritRateBonus", { it.hasPillCritRateBonus() }, { it.pillCritRateBonus }),
        dbl("pillCritEffectBonus", { it.hasPillCritEffectBonus() }, { it.pillCritEffectBonus }),
        dbl(
            "pillCultivationSpeedBonus", { it.hasPillCultivationSpeedBonus() },
            { it.pillCultivationSpeedBonus },
        ),
        dbl("pillSkillExpSpeedBonus", { it.hasPillSkillExpSpeedBonus() }, { it.pillSkillExpSpeedBonus }),
        dbl("pillNurtureSpeedBonus", { it.hasPillNurtureSpeedBonus() }, { it.pillNurtureSpeedBonus }),
        i32("pillEffectDuration", { it.hasPillEffectDuration() }, { it.pillEffectDuration }),
        sl("activePillTypes", { it.activePillTypesList }),
        str("activePillCategory", { it.hasActivePillCategory() }, { it.activePillCategory }),
        str("weaponId", { it.hasWeaponId() }, { it.weaponId }),
        str("armorId", { it.hasArmorId() }, { it.armorId }),
        str("bootsId", { it.hasBootsId() }, { it.bootsId }),
        str("accessoryId", { it.hasAccessoryId() }, { it.accessoryId }),
        nu("weaponNurture", { it.hasWeaponNurture() }, { it.weaponNurture }),
        nu("armorNurture", { it.hasArmorNurture() }, { it.armorNurture }),
        nu("bootsNurture", { it.hasBootsNurture() }, { it.bootsNurture }),
        nu("accessoryNurture", { it.hasAccessoryNurture() }, { it.accessoryNurture }),
        bj("storageBagItems", { it.storageBagItemsJson }),
        i64("storageBagSpiritStones", { it.hasStorageBagSpiritStones() }, { it.storageBagSpiritStones }),
        i32("spiritStones", { it.hasSpiritStones() }, { it.spiritStones }),
        str("partnerId", { it.hasPartnerId() }, { it.partnerId }),
        str("partnerSectId", { it.hasPartnerSectId() }, { it.partnerSectId }),
        str("parentId1", { it.hasParentId1() }, { it.parentId1 }),
        str("parentId2", { it.hasParentId2() }, { it.parentId2 }),
        i32("lastChildYear", { it.hasLastChildYear() }, { it.lastChildYear }),
        i32("childBirthMonth", { it.hasChildBirthMonth() }, { it.childBirthMonth }),
        i32("griefEndYear", { it.hasGriefEndYear() }, { it.griefEndYear }),
        str("masterId", { it.hasMasterId() }, { it.masterId }),
        i32("intelligence", { it.hasIntelligence() }, { it.intelligence }),
        i32("charm", { it.hasCharm() }, { it.charm }),
        i32("loyalty", { it.hasLoyalty() }, { it.loyalty }),
        i32("comprehension", { it.hasComprehension() }, { it.comprehension }),
        i32("artifactRefining", { it.hasArtifactRefining() }, { it.artifactRefining }),
        i32("pillRefining", { it.hasPillRefining() }, { it.pillRefining }),
        i32("spiritPlanting", { it.hasSpiritPlanting() }, { it.spiritPlanting }),
        i32("mining", { it.hasMining() }, { it.mining }),
        i32("teaching", { it.hasTeaching() }, { it.teaching }),
        i32("morality", { it.hasMorality() }, { it.morality }),
        i32("aptitude", { it.hasAptitude() }, { it.aptitude }),
        i32("salaryPaidCount", { it.hasSalaryPaidCount() }, { it.salaryPaidCount }),
        i32("salaryMissedCount", { it.hasSalaryMissedCount() }, { it.salaryMissedCount }),
        i32("alchemyLevel", { it.hasAlchemyLevel() }, { it.alchemyLevel }),
        i32(
            "alchemyPromotionCount", { it.hasAlchemyPromotionCount() },
            { it.alchemyPromotionCount },
        ),
        i32("forgeLevel", { it.hasForgeLevel() }, { it.forgeLevel }),
        i32("forgePromotionCount", { it.hasForgePromotionCount() }, { it.forgePromotionCount }),
        sl("usedPermanentPillKeys", { it.usedPermanentPillKeysList }),
        sl("usedExtendLifePillTypes", { it.usedExtendLifePillTypesList }),
        sl("usedFunctionalPillTypes", { it.usedFunctionalPillTypesList }),
        sl("usedExtendLifePillIds", { it.usedExtendLifePillIdsList }),
        i32("recruitedMonth", { it.hasRecruitedMonth() }, { it.recruitedMonth }),
        bl("hasReviveEffect", { it.hasHasReviveEffect() }, { it.hasReviveEffect }),
        bl("hasClearAllEffect", { it.hasHasClearAllEffect() }, { it.hasClearAllEffect }),
    )
}
