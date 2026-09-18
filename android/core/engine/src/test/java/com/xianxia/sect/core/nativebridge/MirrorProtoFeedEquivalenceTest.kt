package com.xianxia.sect.core.nativebridge

import com.google.protobuf.ByteString
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentNurtureData
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillEffects
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.SocialData
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.UsageTracking
import com.xianxia.sect.proto.gameview.CollectionChange
import com.xianxia.sect.proto.gameview.DiscipleListDelta
import com.xianxia.sect.proto.gameview.DiscipleRow
import com.xianxia.sect.proto.gameview.EquipmentNurtureDataView
import com.xianxia.sect.proto.gameview.GameView
import com.xianxia.sect.proto.gameview.JsonFieldChange
import com.xianxia.sect.proto.gameview.ResourcesHeader
import com.xianxia.sect.proto.gameview.StringIntEntry
import com.xianxia.sect.proto.gameview.StringStringEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MirrorProtoFeedEquivalenceTest — R2.3 第一波验收门 4「全链路守卫」：
 * **proto 信封 → 解码 → GameStateStore 馈送**逐字段与旧 JSON 镜像路径同形同值。
 *
 * ## 与 B06 守卫的分工（为什么还需要本类）
 * `DiffDirtyEnvelopeEquivalenceTest`（R2.2）锁的是**变更集树层**等价（C++ 编码器
 * 产出 ↔ [GameViewMirrorCodec] 还原 ↔ JSON 树 deep-equal）；本类往下再锁一段：
 * 解码产物经 [StateSyncService] 的 applier 落到 GameStateStore 之后，**UI 可见的
 * 数据形状与值**仍与旧路径逐字段一致（弟子行级列存储、实体集合 upsert/remove、
 * gameData 标量与嵌套容器、单事务原子性、计数契约）——即"只换传输、UI 无感"
 * 这条红线的运行期证明。
 *
 * ## 为什么不经 native
 * 弟子表底层为 `android.util.SparseArray`（普通 JVM stub 静默 no-op）——行级馈送
 * 断言必须在 Robolectric 环境跑，而桌面 JNI 桥与 Robolectric 沙箱 ClassLoader
 * 冲突（既有约束，同 [DiffDirtyDisciplesTest]）。故本类用 javalite builder 按
 * `game_view.proto` 契约手工产出信封，两臂输入由同一个 [Disciple] 实例派生：
 * 旧臂 = [com.xianxia.sect.core.model.DiscipleSerializer] 平铺协议文本，
 * 新臂 = typed 行（哨兵/截断口径与 C++ to_json 一致：cultivationCheckpoint 按
 * Long 承载、null 社交字段以 ""/0/-1 承载、storageBagItems 走 JSON 原文过渡编码）。
 * "C++ 实际产出 == 本类手工信封"由 R2.2 编码面对拍守卫负责，两段合起来覆盖
 * `native 编码 → 传输 → 解码 → GameStateStore 馈送` 全链。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class MirrorProtoFeedEquivalenceTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `proto envelope feeds store identically to legacy json mirror`() {
        val changed = richDisciple()
        val viaJson = FakeGameStateStore().also { seed(it) }
        val viaProto = FakeGameStateStore().also { seed(it) }

        val jsonResult = StateSyncService(viaJson).applyDirty(legacyJsonDirty(changed))
        val protoResult = StateSyncService(viaProto).applyDirtyProto(protoDirty(changed))

        // ── 应用契约同值（版本号 / gameData 字段数 / upsert / removed 计数） ──
        assertEquals("DirtyApplyResult 逐字段等价", jsonResult, protoResult)
        assertEquals(DirtyApplyResult(VERSION, 3, 3, 3), jsonResult)
        assertEquals("两臂同为单事务原子", 1, viaJson.updateCallCount)
        assertEquals(1, viaProto.updateCallCount)

        // ── UI 可见馈送面逐块同形同值 ──
        assertEquals("gameData 全字段一致", viaJson.gameDataValue, viaProto.gameDataValue)
        assertEquals("弟子行集一致", viaJson.disciplesValue, viaProto.disciplesValue)
        assertEquals("pills 一致", viaJson.pillsValue, viaProto.pillsValue)
        assertEquals("herbs 一致", viaJson.herbsValue, viaProto.herbsValue)

        // ── 防"两臂同错"：断言镜像确实落到期望值（非仅彼此相等） ──
        assertEquals(expectedGameData(), viaProto.gameDataValue)
        assertEquals(listOf("101", NEW_DISCIPLE_ID), viaProto.disciplesValue.map { it.id })
        assertRichDiscipleFed(viaProto.disciplesValue.first { it.id == changed.id })
        assertEquals(
            listOf("p-keep", "p-new"), viaProto.pillsValue.map { it.id }.sorted()
        )
        assertTrue("herbs 整删", viaProto.herbsValue.isEmpty())
    }

    // ── 两臂共同的初态与载荷 ─────────────────────────────────────

    /** 初态：弟子 101（将被整行覆盖）+ 102（将被删）、pills 两枚、herbs 一枚。 */
    private fun seed(store: FakeGameStateStore) {
        store.gameDataValue = GameData().apply {
            spiritStones = 111
            gameYear = 1
            gameMonth = 5
            gamePhase = 2
            sectName = "镜像宗"
            unlockedManuals = emptyList()
        }
        store.disciplesValue = listOf(
            Disciple().apply { id = "101"; name = "更新前"; realm = 1 },
            Disciple().apply { id = REMOVED_DISCIPLE_ID; name = "待删" },
        )
        store.pillsValue = listOf(
            Pill(id = "p-old", name = "旧丹", rarity = 1, quantity = 1),
            Pill(id = "p-keep", name = "保留丹", rarity = 2, quantity = 2),
        )
        store.herbsValue = listOf(
            Herb(id = "h-1", name = "灵草", rarity = 1, quantity = 2, category = "普通"),
        )
    }

    private fun expectedGameData(): GameData = GameData().apply {
        spiritStones = SPIRIT_STONES
        gameYear = GAME_YEAR
        gameMonth = 5
        gamePhase = 2
        sectName = "镜像宗"
        unlockedManuals = listOf("man-a", "man-b")
    }

    /**
     * 弟子 upsert 载荷（两臂共用同一文本派生：旧臂直接进 JSON 树，
     * 新臂经 [discipleToRow] 逐字段 typed 化——两臂输入同源，杜绝夹具漂移）。
     */
    private fun discipleUpsertsJson(changed: Disciple): String {
        val rich = json.encodeToString(Disciple.serializer(), changed)
        val fresh = """{"id":"$NEW_DISCIPLE_ID","name":"新弟子","isAlive":true}"""
        return "[$rich,$fresh]"
    }

    /** 旧 JSON 镜像协议文本（R2.2 换轨前镜像通道的唯一编码形态）。 */
    private fun legacyJsonDirty(changed: Disciple): String {
        return buildJsonObject {
            put("version", VERSION)
            put(
                "changed", buildJsonObject {
                    put("gameData.spiritStones", SPIRIT_STONES)
                    put("gameData.gameYear", GAME_YEAR)
                    put("gameData.unlockedManuals", element("""["man-a","man-b"]"""))
                    put("disciples", element(discipleUpsertsJson(changed)))
                    put("pills", element(PILLS_UPSERT_JSON))
                }
            )
            put(
                "removed", buildJsonObject {
                    put("disciples", element("""["$REMOVED_DISCIPLE_ID"]"""))
                    put("pills", element("""["p-old"]"""))
                    put("herbs", element("""["h-1"]"""))
                }
            )
        }.toString()
    }

    /**
     * GameView protobuf 信封（同一变更集的二进制传输形态）——块划分即
     * `game_view.proto` 的生产者分发规则：resourcesHeader（spiritStones 直拷）
     * + gameDataChange（其余 gameData 字段）+ discipleListDelta（typed 行 +
     * removed）+ collectionChange（pills 增删、herbs 删）。
     */
    private fun protoDirty(changed: Disciple): ByteArray =
        GameView.newBuilder()
            .setVersion(VERSION)
            .setResourcesHeader(ResourcesHeader.newBuilder().setSpiritStones(SPIRIT_STONES))
            .addGameDataChange(
                JsonFieldChange.newBuilder().setName("gameYear")
                    .setValueJson(ByteString.copyFromUtf8(GAME_YEAR.toString()))
            )
            .addGameDataChange(
                JsonFieldChange.newBuilder().setName("unlockedManuals")
                    .setValueJson(ByteString.copyFromUtf8("""["man-a","man-b"]"""))
            )
            .setDiscipleListDelta(
                DiscipleListDelta.newBuilder()
                    .addUpserts(discipleToRow(changed))
                    .addUpserts(
                        DiscipleRow.newBuilder().setId(NEW_DISCIPLE_ID).setName("新弟子")
                            .setIsAlive(true)
                    )
                    .addRemovedIds(REMOVED_DISCIPLE_ID)
            )
            .addCollectionChange(
                CollectionChange.newBuilder().setName("pills")
                    .setUpsertsJson(ByteString.copyFromUtf8(PILLS_UPSERT_JSON))
                    .addRemovedIds("p-old")
            )
            .addCollectionChange(
                CollectionChange.newBuilder().setName("herbs").addRemovedIds("h-1")
            )
            .build()
            .toByteArray()

    private fun element(text: String) = json.parseToJsonElement(text).jsonArray

    // ── 弟子 typed 行（109 协议字段逐字段，与平铺 JSON 协议同源） ──

    private fun discipleToRow(d: Disciple): DiscipleRow {
        val b = DiscipleRow.newBuilder()
        fillDirectRowFields(b, d)
        fillCombatPillRowFields(b, d)
        fillEquipmentSocialRowFields(b, d)
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

    /** 馈送后弟子行的逐字段核对（覆盖每个 wire 类别，证明"同形"而非"同为空"）。 */
    private fun assertRichDiscipleFed(actual: Disciple) {
        assertEquals("玄真", actual.name)
        assertEquals("李", actual.surname)
        assertEquals(5, actual.realm)
        assertEquals(128.5, actual.cultivation, 0.0)
        assertEquals(99L, actual.cultivationCheckpoint.toLong())
        assertEquals(DiscipleStatus.ALCHEMY, actual.status)
        assertEquals(mapOf("m1" to 3, "m2" to 7), actual.manualMasteries)
        assertEquals(mapOf("task" to "alchemy", "slot" to "42"), actual.statusData)
        assertEquals(listOf("m1", "m2"), actual.manualIds)
        assertEquals(setOf("dan1"), actual.pillEffects.activePillTypes)
        assertEquals(500, actual.combat.baseHp)
        assertEquals(9999999L, actual.combat.totalCultivation)
        assertEquals(-1, actual.combat.currentHp)
        assertEquals("w1", actual.equipment.weaponId)
        assertEquals(4, actual.equipment.weaponNurture.nurtureLevel)
        assertEquals(0.25, actual.equipment.weaponNurture.nurtureProgress, 0.0)
        assertEquals(1, actual.equipment.storageBagItems.size)
        assertEquals("s1", actual.equipment.storageBagItems.first().itemId)
        assertEquals(55L, actual.equipment.storageBagSpiritStones)
        assertEquals("p2", actual.social.partnerId)
        assertEquals(20, actual.social.griefEndYear)
        assertNull(actual.social.parentId1)
        assertEquals(setOf("3#hpAdd"), actual.usage.usedPermanentPillKeys)
        assertTrue(actual.usage.hasClearAllEffect)
    }

    companion object {
        private const val VERSION = 77L
        private const val SPIRIT_STONES = 4321L
        private const val GAME_YEAR = 9
        private const val REMOVED_DISCIPLE_ID = "102"
        private const val NEW_DISCIPLE_ID = "205"
        private const val GRIEF_NULL_SENTINEL = -1
        private const val PILLS_UPSERT_JSON =
            """[{"id":"p-new","name":"凝气丹","rarity":3,"quantity":7}]"""

        /** 全字段弟子（每个 wire 类别取非默认值，逐字段与旧平铺协议同源）。 */
        private fun richDisciple(): Disciple = Disciple(
            id = "101",
            name = "玄真",
            surname = "李",
            realm = 5,
            realmLayer = 3,
            cultivation = 128.5,
            cultivationCheckpoint = 99.0,
            cultivationCheckpointGameMonth = 42,
            spiritRootType = "metal,wood",
            age = 21,
            lifespan = 200,
            isAlive = true,
            gender = "male",
            portraitRes = "d101",
            manualIds = listOf("m1", "m2"),
            talentIds = listOf("t1"),
            affixIds = listOf("af1"),
            manualMasteries = mapOf("m1" to 3, "m2" to 7),
            status = DiscipleStatus.ALCHEMY,
            statusData = mapOf("task" to "alchemy", "slot" to "42"),
            cultivationSpeedBonus = 1.25,
            cultivationSpeedDuration = 6,
            discipleType = "inner",
            soulPower = 17,
            cultivationCompletionMonth = 3,
            cultivationCompletionPhase = 2,
            manualCompletionMonth = 4,
            manualCompletionPhase = 1,
            equipmentNurturingCompletionMonth = 5,
            equipmentNurturingCompletionPhase = 3,
        ).apply {
            combat = CombatAttributes(
                baseHp = 500, baseMp = 300, basePhysicalAttack = 61, baseMagicAttack = 57,
                basePhysicalDefense = 48, baseMagicDefense = 44, baseSpeed = 39,
                hpVariance = 12, mpVariance = 9, physicalAttackVariance = 7,
                magicAttackVariance = 5, physicalDefenseVariance = 4,
                magicDefenseVariance = 3, speedVariance = 2,
                totalCultivation = 9999999L, breakthroughCount = 2, breakthroughFailCount = 1,
                currentHp = -1, currentMp = 120,
            )
            pillEffects = PillEffects(
                pillPhysicalAttackBonus = 11, pillMagicAttackBonus = 12,
                pillPhysicalDefenseBonus = 13, pillMagicDefenseBonus = 14,
                pillHpBonus = 15, pillMpBonus = 16, pillSpeedBonus = 17,
                pillCritRateBonus = 0.05, pillCritEffectBonus = 0.06,
                pillCultivationSpeedBonus = 0.07, pillSkillExpSpeedBonus = 0.08,
                pillNurtureSpeedBonus = 0.09, pillEffectDuration = 3,
                activePillTypes = setOf("dan1"), activePillCategory = "cultivation",
            )
            equipment = EquipmentSet(
                weaponId = "w1", armorId = "a1", bootsId = "b1", accessoryId = "c1",
                weaponNurture = EquipmentNurtureData("w1", 2, 4, 0.25),
                armorNurture = EquipmentNurtureData("a1", 1, 1, 0.5),
                bootsNurture = EquipmentNurtureData("b1", 3, 2, 0.75),
                accessoryNurture = EquipmentNurtureData("c1", 4, 3, 1.0),
                storageBagItems = listOf(
                    StorageBagItem(
                        itemId = "s1", itemType = "material", name = "兽皮", rarity = 1,
                        quantity = 9, obtainedYear = 3, obtainedMonth = 2,
                    ),
                ),
                storageBagSpiritStones = 55L, spiritStones = 88,
            )
            social = SocialData(
                partnerId = "p2", partnerSectId = null, parentId1 = null, parentId2 = "pp2",
                lastChildYear = 12, childBirthMonth = 7, griefEndYear = 20, masterId = "ms1",
            )
            skills = SkillStats(
                intelligence = 61, charm = 62, loyalty = 63, comprehension = 64,
                artifactRefining = 65, pillRefining = 66, spiritPlanting = 67, mining = 68,
                teaching = 69, morality = 70, aptitude = 71, salaryPaidCount = 3,
                salaryMissedCount = 1, alchemyLevel = 2, alchemyPromotionCount = 5,
                forgeLevel = 1, forgePromotionCount = 2,
            )
            usage = UsageTracking(
                usedPermanentPillKeys = setOf("3#hpAdd"),
                usedExtendLifePillTypes = setOf("life1"),
                usedFunctionalPillTypes = listOf("k1"),
                usedExtendLifePillIds = listOf("e1"),
                recruitedMonth = 120, hasReviveEffect = false, hasClearAllEffect = true,
            )
        }
    }
}
