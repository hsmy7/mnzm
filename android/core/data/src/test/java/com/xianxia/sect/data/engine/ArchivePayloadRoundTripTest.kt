package com.xianxia.sect.data.engine

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentNurtureData
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.UsageTracking
import com.xianxia.sect.data.local.ProtobufConverters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 归档载荷可还原性守卫（审计 §12-F）。
 *
 * 背景：`DataArchiveScheduler` 每 600s 把已故弟子/溢出战斗日志搬出主表，
 * 归档行 `dataBlob` 此前固定写 `""`（只剩 id/名/境界）且归档表零查询调用者
 * ⇒ 单向数据销毁。修复后 `dataBlob` = 全量序列化 Base64 载荷，本测试锁定：
 * ① 已故弟子载荷往返逐字段等价；② 战斗日志载荷往返逐字段等价；
 * ③ 载荷非空（防回归到 `""`）且不同实体载荷可区分。
 *
 * 覆盖面（最小但非平凡夹具）：弟子含装备（weaponId + 孕育数据）、功法
 * （manualIds/manualMasteries）、状态（status/statusData）、战斗与技能段；
 * 战斗日志含成员/敌人/回合/动作/drops。
 *
 * 已知非保真项（`DiscipleSerializer` 决定，非本刀引入）：`slotId` 不入载荷
 * （还原为 0）、`lifeEvents`（@Ignore）不序列化、`cultivationCheckpoint`
 * Double↔Long 取整。夹具避开这些损失点（checkpoint 取整、slotId=0）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchivePayloadRoundTripTest {

    private fun richDisciple(): Disciple = Disciple(
        id = "disciple-dead-1",
        slotId = 0,
        name = "林寒",
        surname = "林",
        realm = 5,
        realmLayer = 3,
        cultivation = 12345.5,
        cultivationCheckpoint = 6789.0,
        cultivationCheckpointGameMonth = 240,
        spiritRootType = "ice",
        age = 210,
        lifespan = 300,
        isAlive = false,
        gender = "female",
        portraitRes = "portrait_42",
        manualIds = listOf("manual_qingfeng"),
        talentIds = listOf("talent_sword"),
        physiqueIds = listOf("physique_ice"),
        affixIds = listOf("affix_a1"),
        manualMasteries = mapOf("manual_qingfeng" to 7),
        status = DiscipleStatus.DEAD,
        statusData = mapOf("cause" to "battle", "killer" to "enemy_9"),
        cultivationSpeedBonus = 0.25,
        cultivationSpeedDuration = 12,
        discipleType = "inner",
        soulPower = 33
    ).withRichExtras()

    /** 装备/技能/战斗/使用记录的富化块（与 [richDisciple] 拆开以满足 detekt 单函数 60 行阈值） */
    private fun Disciple.withRichExtras(): Disciple = copy(
        equipment = EquipmentSet(
            weaponId = "weapon_1",
            armorId = "armor_1",
            bootsId = "boots_1",
            accessoryId = "accessory_1",
            weaponNurture = EquipmentNurtureData("weapon_1", 2, nurtureLevel = 5, nurtureProgress = 0.5),
            storageBagSpiritStones = 4321L,
            spiritStones = 999
        ),
        skills = SkillStats(
            intelligence = 66,
            charm = 55,
            loyalty = 88,
            comprehension = 71,
            aptitude = 92,
            alchemyLevel = 4,
            alchemyPromotionCount = 9,
            forgeLevel = 2
        ),
        combat = CombatAttributes(
            baseHp = 5000,
            baseMp = 1200,
            basePhysicalAttack = 320,
            baseSpeed = 44,
            totalCultivation = 987654321L,
            breakthroughCount = 6,
            breakthroughFailCount = 1,
            currentHp = 0,
            currentMp = 300
        ),
        usage = UsageTracking(
            recruitedMonth = 120,
            usedFunctionalPillTypes = listOf("pill_atk"),
            usedExtendLifePillIds = listOf("pill_life_1"),
            hasReviveEffect = true
        )
    )

    private fun richBattleLog(): BattleLog = BattleLog(
        id = "battle-log-1",
        slotId = 0,
        timestamp = 1_700_000_000_000L,
        year = 12,
        month = 7,
        type = BattleType.SECT_WAR,
        attackerName = "青云宗",
        defenderName = "血煞门",
        result = BattleResult.WIN,
        details = "宗门战：击溃守军",
        drops = listOf("spirit_stone_x100", "manual_fire"),
        dungeonName = "血煞秘境",
        teamMembers = listOf(
            BattleLogMember(id = "d1", name = "林寒", realm = 5, realmName = "元婴", hp = 0, maxHp = 5000, isAlive = false)
        ),
        enemies = listOf(
            BattleLogEnemy(id = "e1", name = "血煞长老", realm = 4, hp = 0, maxHp = 4200, isAlive = false)
        ),
        rounds = listOf(
            BattleLogRound(
                roundNumber = 1,
                actions = listOf(
                    BattleLogAction(
                        type = "attack", attacker = "林寒", attackerType = "disciple",
                        target = "血煞长老", damage = 1234, damageType = "physical",
                        isCrit = true, isKill = false, message = "林寒一剑斩出"
                    )
                )
            )
        ),
        turns = 8,
        teamCasualties = 1,
        beastsDefeated = 0
    )

    @Test
    fun `已故弟子归档载荷往返逐字段等价`() {
        val original = richDisciple()
        val blob = encodeArchivedDiscipleBlob(original)
        assertTrue("弟子归档载荷不得为空（防回归到 \"\"）", blob.isNotEmpty())

        val decoded = ProtobufConverters.decodeFromBase64(Disciple.serializer(), blob) {
            error("弟子归档载荷解码失败")
        }
        // slotId 不入载荷（DiscipleSerializer 还原为 0）；其余字段逐字段等价
        assertEquals(original.copy(slotId = 0), decoded)
        // 非平凡字段抽查（防止整体 equals 因未来改动静默放宽）
        assertEquals("林寒", decoded.name)
        assertEquals(DiscipleStatus.DEAD, decoded.status)
        assertEquals("weapon_1", decoded.equipment.weaponId)
        assertEquals(5, decoded.equipment.weaponNurture.nurtureLevel)
        assertEquals(listOf("manual_qingfeng"), decoded.manualIds)
        assertEquals(92, decoded.skills.aptitude)
        assertEquals(5000, decoded.combat.baseHp)
    }

    @Test
    fun `战斗日志归档载荷往返逐字段等价`() {
        val original = richBattleLog()
        val blob = encodeArchivedBattleLogBlob(original)
        assertTrue("战斗日志归档载荷不得为空（防回归到 \"\"）", blob.isNotEmpty())

        val decoded = ProtobufConverters.decodeFromBase64(BattleLog.serializer(), blob) {
            error("战斗日志归档载荷解码失败")
        }
        assertEquals(original, decoded)
        // 非平凡字段抽查
        assertEquals(BattleType.SECT_WAR, decoded.type)
        assertEquals(BattleResult.WIN, decoded.result)
        assertEquals(1, decoded.teamMembers.size)
        assertEquals(1, decoded.enemies.size)
        assertEquals(1, decoded.rounds.first().actions.size)
        assertEquals(1234, decoded.rounds.first().actions.first().damage)
        assertEquals(listOf("spirit_stone_x100", "manual_fire"), decoded.drops)
    }

    @Test
    fun `归档载荷非空且不同实体可区分`() {
        val first = richDisciple()
        val second = first.copy(id = "disciple-dead-2", name = "另一人", realm = 3)

        val blobFirst = encodeArchivedDiscipleBlob(first)
        val blobSecond = encodeArchivedDiscipleBlob(second)

        assertTrue(blobFirst.isNotEmpty())
        assertTrue(blobSecond.isNotEmpty())
        assertNotEquals("不同弟子应产出不同载荷", blobFirst, blobSecond)

        val decodedFirst = ProtobufConverters.decodeFromBase64(Disciple.serializer(), blobFirst) {
            error("decode first failed")
        }
        val decodedSecond = ProtobufConverters.decodeFromBase64(Disciple.serializer(), blobSecond) {
            error("decode second failed")
        }
        assertEquals("disciple-dead-1", decodedFirst.id)
        assertEquals("disciple-dead-2", decodedSecond.id)
        assertEquals(3, decodedSecond.realm)
    }
}
