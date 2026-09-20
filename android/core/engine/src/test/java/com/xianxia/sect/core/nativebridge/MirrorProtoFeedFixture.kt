package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.gameview.GameDataFieldPatch
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
import kotlinx.serialization.json.Json

/**
 * MirrorProtoFeedFixture — [MirrorProtoFeedEquivalenceTest] 的两臂共用夹具。
 *
 * 单一事实源：旧 JSON 臂的弟子数组文本与 GameView 臂的 typed 行**都由同一个
 * [richDisciple] 实例派生**，杜绝两臂夹具各写一份而漂移（那样等价性就成了
 * 两份手写文本的自证）。放在独立文件而非测试类内联，是为让测试类保持单一职责
 * （对账逻辑）且函数数不触 detekt 文件上限（同 `DiffMonthSettlementFixture.kt`
 * 的拆法）。
 *
 * 线路口径与 C++ `to_json` / [com.xianxia.sect.core.model.DiscipleSerializer]
 * 一致：`cultivationCheckpoint` 按 Long 承载（截断）、可空社交字段以 ""/0/-1
 * 哨兵承载、`Set` 域按 `toList()` 序承载、`storageBagItems` 走 JSON 原文过渡编码。
 */
internal object MirrorProtoFeedFixture {

    const val VERSION = 77L
    const val SPIRIT_STONES = 4321L
    const val GAME_YEAR = 9
    const val REMOVED_DISCIPLE_ID = "102"
    const val NEW_DISCIPLE_ID = "205"
    const val PILLS_UPSERT_JSON = """[{"id":"p-new","name":"凝气丹","rarity":3,"quantity":7}]"""
    const val UNLOCKED_MANUALS_JSON = """["man-a","man-b"]"""
    val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * 镜像载荷测试可用 gameData 字段名单源卡点（b02 发现 10）：字段名不在
     * [GameDataFieldPatch.coveredFields]（= 权威在册清单）即红——防"选错名
     * 被应用器宽松忽略 ⇒ 两臂同绿空转"的假绿（比红更贵）。
     */
    fun mirrorGameDataField(name: String): String = name.also {
        require(it in GameDataFieldPatch.coveredFields) {
            "镜像载荷字段名 '$it' 不在 GameDataFieldPatch.coveredFields——" +
                "名字写错会被应用器宽松忽略，等价守卫将绿着空转（b02 发现 10）"
        }
    }

    /** 弟子 upsert 载荷：旧臂直接进 JSON 树，新臂经 [MirrorDiscipleRowFixture.toGameViewRow] 逐字段 typed 化。 */
    fun discipleUpsertsJson(d: Disciple): String {
        val rich = json.encodeToString(Disciple.serializer(), d)
        // 新增弟子也按**全字段**载荷表达（与 C++ 编码器 emit-always 同规）——
        // 三键稀疏行是只有测试才存在的形状，第二波行投影对缺字段 fail-fast（红线），
        // 故夹具随契约收敛：两臂都消费同一份全字段 Disciple 的编码。
        val fresh = json.encodeToString(Disciple.serializer(), newDisciple())
        return "[$rich,$fresh]"
    }

    /** 新弟子（id/名字外全取域默认值；两臂共同事实源） */
    fun newDisciple(): Disciple = Disciple(id = NEW_DISCIPLE_ID, name = "新弟子")

    /** 全字段弟子（每个 wire 类别取非默认值，逐字段与旧平铺协议同源）。 */
    fun richDisciple(): Disciple = Disciple(
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
        combat = richCombat()
        pillEffects = richPillEffects()
        equipment = richEquipment()
        social = richSocial()
        skills = richSkills()
        usage = richUsage()
    }

    private fun richCombat(): CombatAttributes = CombatAttributes(
        baseHp = 500, baseMp = 300, basePhysicalAttack = 61, baseMagicAttack = 57,
        basePhysicalDefense = 48, baseMagicDefense = 44, baseSpeed = 39,
        hpVariance = 12, mpVariance = 9, physicalAttackVariance = 7,
        magicAttackVariance = 5, physicalDefenseVariance = 4,
        magicDefenseVariance = 3, speedVariance = 2,
        totalCultivation = 9999999L, breakthroughCount = 2, breakthroughFailCount = 1,
        currentHp = -1, currentMp = 120,
    )

    private fun richPillEffects(): PillEffects = PillEffects(
        pillPhysicalAttackBonus = 11, pillMagicAttackBonus = 12,
        pillPhysicalDefenseBonus = 13, pillMagicDefenseBonus = 14,
        pillHpBonus = 15, pillMpBonus = 16, pillSpeedBonus = 17,
        pillCritRateBonus = 0.05, pillCritEffectBonus = 0.06,
        pillCultivationSpeedBonus = 0.07, pillSkillExpSpeedBonus = 0.08,
        pillNurtureSpeedBonus = 0.09, pillEffectDuration = 3,
        activePillTypes = setOf("dan1"), activePillCategory = "cultivation",
    )

    private fun richEquipment(): EquipmentSet = EquipmentSet(
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

    private fun richSocial(): SocialData = SocialData(
        partnerId = "p2", partnerSectId = null, parentId1 = null, parentId2 = "pp2",
        lastChildYear = 12, childBirthMonth = 7, griefEndYear = 20, masterId = "ms1",
    )

    private fun richSkills(): SkillStats = SkillStats(
        intelligence = 61, charm = 62, loyalty = 63, comprehension = 64,
        artifactRefining = 65, pillRefining = 66, spiritPlanting = 67, mining = 68,
        teaching = 69, morality = 70, aptitude = 71, salaryPaidCount = 3,
        salaryMissedCount = 1, alchemyLevel = 2, alchemyPromotionCount = 5,
        forgeLevel = 1, forgePromotionCount = 2,
    )

    private fun richUsage(): UsageTracking = UsageTracking(
        usedPermanentPillKeys = setOf("3#hpAdd"),
        usedExtendLifePillTypes = setOf("life1"),
        usedFunctionalPillTypes = listOf("k1"),
        usedExtendLifePillIds = listOf("e1"),
        recruitedMonth = 120, hasReviveEffect = false, hasClearAllEffect = true,
    )
}
