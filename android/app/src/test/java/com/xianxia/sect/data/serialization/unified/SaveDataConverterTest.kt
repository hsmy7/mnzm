package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SaveDataConverterTest {

    private lateinit var converter: SaveDataConverter

    @Before
    fun setUp() {
        converter = SaveDataConverter()
    }

    private fun createMinimalSaveData(): SaveData {
        return SaveData(
            version = "2.0",
            timestamp = System.currentTimeMillis(),
            gameData = GameData(),
            disciples = emptyList(),
            equipment = emptyList(),
            manuals = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )
    }

    private fun createRichSaveData(): SaveData {
        val disciple = Disciple(
            id = "disciple_1",
            name = "张三",
            realm = 3,
            realmLayer = 2,
            cultivation = 15000.0,
            spiritRootType = "火",
            age = 25,
            lifespan = 200,
            isAlive = true,
            gender = "男",
            manualIds = listOf("manual_1"),
            talentIds = listOf("talent_1"),
            manualMasteries = mapOf("manual_1" to 50),
            status = DiscipleStatus.IDLE,
            statusData = emptyMap(),
            cultivationSpeedBonus = 0.0,
            cultivationSpeedDuration = 0,
            discipleType = "inner",
            combat = CombatAttributes(
                baseHp = 1000,
                baseMp = 500,
                basePhysicalAttack = 200,
                baseMagicAttack = 150,
                basePhysicalDefense = 100,
                baseMagicDefense = 80,
                baseSpeed = 50,
                hpVariance = 5,
                mpVariance = 3,
                physicalAttackVariance = 8,
                magicAttackVariance = 6,
                physicalDefenseVariance = 4,
                magicDefenseVariance = 7,
                speedVariance = 2,
                totalCultivation = 50000L,
                breakthroughCount = 3,
                breakthroughFailCount = 1
            ),
            pillEffects = PillEffects(
                pillPhysicalAttackBonus = 0.0,
                pillMagicAttackBonus = 0.0,
                pillPhysicalDefenseBonus = 0.0,
                pillMagicDefenseBonus = 0.0,
                pillHpBonus = 0.0,
                pillMpBonus = 0.0,
                pillSpeedBonus = 0.0,
                pillEffectDuration = 0
            ),
            equipment = EquipmentSet(
                weaponId = "weapon_1",
                armorId = "armor_1",
                bootsId = "",
                accessoryId = "",
                spiritStones = 1000,
                soulPower = 50,
                storageBagItems = emptyList(),
                storageBagSpiritStones = 500L
            ),
            social = SocialData(
                partnerId = "disciple_2",
                partnerSectId = "",
                parentId1 = "",
                parentId2 = "",
                lastChildYear = 0,
                griefEndYear = -1
            ),
            skills = SkillStats(
                intelligence = 80,
                charm = 70,
                loyalty = 90,
                comprehension = 85,
                artifactRefining = 30,
                pillRefining = 40,
                spiritPlanting = 20,
                teaching = 50,
                morality = 75,
                salaryPaidCount = 10,
                salaryMissedCount = 0
            ),
            usage = UsageTracking(
                monthlyUsedPillIds = emptyList(),
                usedExtendLifePillIds = emptyList(),
                recruitedMonth = 3,
                hasReviveEffect = false,
                hasClearAllEffect = false
            )
        )

        val equipment = Equipment(
            id = "weapon_1",
            name = "青锋剑",
            slot = EquipmentSlot.WEAPON,
            rarity = 3,
            physicalAttack = 50,
            speed = 10,
            description = "一把锋利的长剑",
            critChance = 0.1,
            isEquipped = true,
            ownerId = "disciple_1",
            nurtureLevel = 2,
            nurtureProgress = 0.5,
            minRealm = 0,
            quantity = 1
        )

        val manual = Manual(
            id = "manual_1",
            name = "烈火诀",
            type = ManualType.MIND,
            rarity = 3,
            stats = mapOf("cultivationSpeed" to 20),
            description = "火属性修炼功法"
        )

        val pill = Pill(
            id = "pill_1",
            name = "筑基丹",
            category = PillCategory.BREAKTHROUGH,
            rarity = 3,
            breakthroughChance = 30.0,
            description = "辅助突破的丹药",
            quantity = 5
        )

        val material = Material(
            id = "material_1",
            name = "灵铁",
            category = MaterialCategory.BEAST_HIDE,
            rarity = 2,
            quantity = 10,
            description = "锻造材料"
        )

        val herb = Herb(
            id = "herb_1",
            name = "百年灵芝",
            rarity = 3,
            quantity = 3,
            description = "珍贵药材"
        )

        val seed = Seed(
            id = "seed_1",
            name = "火灵花种子",
            rarity = 2,
            growTime = 6,
            yield = 3,
            quantity = 5,
            description = "火属性灵花种子"
        )

        val team = ExplorationTeam(
            id = "team_1",
            name = "探索队一",
            memberIds = listOf("disciple_1"),
            status = ExplorationStatus.COMPLETED,
            duration = 0,
            progress = 0
        )

        val event = GameEvent(
            id = "event_1",
            type = EventType.SUCCESS,
            message = "张三突破成功",
            timestamp = System.currentTimeMillis(),
            year = 5,
            month = 3
        )

        val battleLog = BattleLog(
            id = "battle_1",
            timestamp = System.currentTimeMillis(),
            year = 5,
            month = 2,
            type = BattleType.PVE,
            attackerName = "青云宗",
            defenderName = "魔教",
            result = BattleResult.WIN,
            details = "战斗胜利",
            drops = listOf("spiritStones:500")
        )

        val alliance = Alliance(
            id = "alliance_1",
            sectIds = listOf("sect_player", "sect_ally"),
            startYear = 3,
            initiatorId = "sect_player",
            envoyDiscipleId = "disciple_1"
        )

        return SaveData(
            version = "2.0",
            timestamp = System.currentTimeMillis(),
            gameData = GameData(
                sectName = "青云宗",
                currentSlot = 1,
                gameYear = 5,
                gameMonth = 3,
                gameDay = 15,
                spiritStones = 50000L,
                spiritHerbs = 100,
                autoSaveIntervalMonths = 3,
                monthlySalary = mapOf(1 to 100, 2 to 200),
                monthlySalaryEnabled = mapOf(1 to true, 2 to true),
                worldMapSects = emptyList(),
                exploredSects = emptyMap(),
                scoutInfo = emptyMap(),
                manualProficiencies = emptyMap(),
                travelingMerchantItems = emptyList(),
                merchantLastRefreshYear = 5,
                merchantRefreshCount = 3,
                playerListedItems = emptyList(),
                recruitList = emptyList(),
                lastRecruitYear = 4,
                cultivatorCaves = emptyList(),
                caveExplorationTeams = emptyList(),
                aiCaveTeams = emptyList(),
                unlockedDungeons = listOf("dungeon_1"),
                unlockedRecipes = listOf("recipe_1"),
                unlockedManuals = listOf("manual_1"),
                lastSaveTime = System.currentTimeMillis(),
                alliances = listOf(alliance),
                sectRelations = emptyList(),
                playerAllianceSlots = 3,
                usedRedeemCodes = listOf("CODE123"),
                playerProtectionEnabled = true,
                playerProtectionStartYear = 1,
                playerHasAttackedAI = false
            ),
            disciples = listOf(disciple),
            equipment = listOf(equipment),
            manuals = listOf(manual),
            pills = listOf(pill),
            materials = listOf(material),
            herbs = listOf(herb),
            seeds = listOf(seed),
            teams = listOf(team),
            events = listOf(event),
            battleLogs = listOf(battleLog),
            alliances = listOf(alliance)
        )
    }

    @Test
    fun `toSerializable - minimal SaveData preserves version and timestamp`() {
        val original = createMinimalSaveData()
        val serializable = converter.toSerializable(original)

        assertEquals(original.version, serializable.version)
        assertEquals(original.timestamp, serializable.timestamp)
    }

    @Test
    fun `toSerializable - minimal SaveData preserves empty collections`() {
        val original = createMinimalSaveData()
        val serializable = converter.toSerializable(original)

        assertTrue(serializable.disciples.isEmpty())
        assertTrue(serializable.equipment.isEmpty())
        assertTrue(serializable.manuals.isEmpty())
        assertTrue(serializable.pills.isEmpty())
        assertTrue(serializable.materials.isEmpty())
        assertTrue(serializable.herbs.isEmpty())
        assertTrue(serializable.seeds.isEmpty())
        assertTrue(serializable.teams.isEmpty())
        assertTrue(serializable.events.isEmpty())
        assertTrue(serializable.battleLogs.isEmpty())
        assertTrue(serializable.alliances.isEmpty())
    }

    @Test
    fun `toSerializable - rich SaveData preserves all entity counts`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)

        assertEquals(1, serializable.disciples.size)
        assertEquals(1, serializable.equipment.size)
        assertEquals(1, serializable.manuals.size)
        assertEquals(1, serializable.pills.size)
        assertEquals(1, serializable.materials.size)
        assertEquals(1, serializable.herbs.size)
        assertEquals(1, serializable.seeds.size)
        assertEquals(1, serializable.teams.size)
        assertEquals(1, serializable.events.size)
        assertEquals(1, serializable.battleLogs.size)
        assertEquals(1, serializable.alliances.size)
    }

    @Test
    fun `toSerializable - disciple fields are correctly mapped`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val sDisciple = serializable.disciples[0]
        val oDisciple = original.disciples[0]

        assertEquals(oDisciple.id, sDisciple.id)
        assertEquals(oDisciple.name, sDisciple.name)
        assertEquals(oDisciple.realm, sDisciple.realm)
        assertEquals(oDisciple.realmLayer, sDisciple.realmLayer)
        assertEquals(oDisciple.cultivation, sDisciple.cultivation, 0.001)
        assertEquals(oDisciple.spiritRootType, sDisciple.spiritRootType)
        assertEquals(oDisciple.age, sDisciple.age)
        assertEquals(oDisciple.lifespan, sDisciple.lifespan)
        assertEquals(oDisciple.isAlive, sDisciple.isAlive)
        assertEquals(oDisciple.gender, sDisciple.gender)
    }

    @Test
    fun `toSerializable - disciple nullable relation fields use NullSafeProtoBuf`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val sDisciple = serializable.disciples[0]
        val oDisciple = original.disciples[0]

        assertEquals(NullSafeProtoBuf.relationIdToProto(oDisciple.partnerId), sDisciple.partnerId)
        assertEquals(NullSafeProtoBuf.relationIdToProto(oDisciple.parentId1), sDisciple.parentId1)
        assertEquals(NullSafeProtoBuf.relationIdToProto(oDisciple.parentId2), sDisciple.parentId2)
        assertEquals(NullSafeProtoBuf.equipmentIdToProto(oDisciple.weaponId), sDisciple.weaponId)
        assertEquals(NullSafeProtoBuf.equipmentIdToProto(oDisciple.armorId), sDisciple.armorId)
    }

    @Test
    fun `toSerializable - equipment fields are correctly mapped`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val sEquip = serializable.equipment[0]
        val oEquip = original.equipment[0]

        assertEquals(oEquip.id, sEquip.id)
        assertEquals(oEquip.name, sEquip.name)
        assertEquals(oEquip.slot.name, sEquip.type)
        assertEquals(oEquip.rarity, sEquip.rarity)
        assertEquals(oEquip.nurtureLevel, sEquip.nurtureLevel)
        assertEquals(oEquip.nurtureProgress, sEquip.nurtureProgress, 0.001)
        assertEquals(oEquip.isEquipped, sEquip.isEquipped)
    }

    @Test
    fun `toSerializable - gameData core fields are correctly mapped`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val sGame = serializable.gameData
        val oGame = original.gameData

        assertEquals(oGame.sectName, sGame.sectName)
        assertEquals(oGame.gameYear, sGame.gameYear)
        assertEquals(oGame.gameMonth, sGame.gameMonth)
        assertEquals(oGame.gameDay, sGame.gameDay)
        assertEquals(oGame.spiritStones, sGame.spiritStones)
        assertEquals(oGame.spiritHerbs, sGame.spiritHerbs)
        assertEquals(oGame.autoSaveIntervalMonths, sGame.autoSaveIntervalMonths)
    }

    @Test
    fun `fromSerializable - minimal data roundtrip preserves version`() {
        val original = createMinimalSaveData()
        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)

        assertEquals(original.version, restored.version)
    }

    @Test
    fun `fromSerializable - minimal data roundtrip preserves empty collections`() {
        val original = createMinimalSaveData()
        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)

        assertTrue(restored.disciples.isEmpty())
        assertTrue(restored.equipment.isEmpty())
        assertTrue(restored.manuals.isEmpty())
        assertTrue(restored.pills.isEmpty())
        assertTrue(restored.materials.isEmpty())
        assertTrue(restored.herbs.isEmpty())
        assertTrue(restored.seeds.isEmpty())
        assertTrue(restored.teams.isEmpty())
        assertTrue(restored.events.isEmpty())
    }

    @Test
    fun `fromSerializable - rich data roundtrip preserves entity counts`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)

        assertEquals(original.disciples.size, restored.disciples.size)
        assertEquals(original.equipment.size, restored.equipment.size)
        assertEquals(original.manuals.size, restored.manuals.size)
        assertEquals(original.pills.size, restored.pills.size)
        assertEquals(original.materials.size, restored.materials.size)
        assertEquals(original.herbs.size, restored.herbs.size)
        assertEquals(original.seeds.size, restored.seeds.size)
        assertEquals(original.teams.size, restored.teams.size)
        assertEquals(original.events.size, restored.events.size)
        assertEquals(original.battleLogs.size, restored.battleLogs.size)
        assertEquals(original.alliances.size, restored.alliances.size)
    }

    @Test
    fun `fromSerializable - disciple core fields roundtrip correctly`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)
        val oDisciple = original.disciples[0]
        val rDisciple = restored.disciples[0]

        assertEquals(oDisciple.id, rDisciple.id)
        assertEquals(oDisciple.name, rDisciple.name)
        assertEquals(oDisciple.realm, rDisciple.realm)
        assertEquals(oDisciple.realmLayer, rDisciple.realmLayer)
        assertEquals(oDisciple.cultivation, rDisciple.cultivation, 0.001)
        assertEquals(oDisciple.spiritRootType, rDisciple.spiritRootType)
        assertEquals(oDisciple.age, rDisciple.age)
        assertEquals(oDisciple.lifespan, rDisciple.lifespan)
        assertEquals(oDisciple.isAlive, rDisciple.isAlive)
        assertEquals(oDisciple.gender, rDisciple.gender)
    }

    @Test
    fun `fromSerializable - disciple relation fields roundtrip correctly`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)
        val oDisciple = original.disciples[0]
        val rDisciple = restored.disciples[0]

        assertEquals(oDisciple.partnerId, rDisciple.partnerId)
        assertEquals(oDisciple.weaponId, rDisciple.weaponId)
        assertEquals(oDisciple.armorId, rDisciple.armorId)
    }

    @Test
    fun `fromSerializable - disciple empty relation fields become null`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)
        val rDisciple = restored.disciples[0]

        assertEquals("", rDisciple.bootsId)
        assertEquals("", rDisciple.accessoryId)
        assertNull(rDisciple.parentId1)
        assertNull(rDisciple.parentId2)
    }

    @Test
    fun `fromSerializable - gameData core fields roundtrip correctly`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)

        assertEquals(original.gameData.sectName, restored.gameData.sectName)
        assertEquals(original.gameData.gameYear, restored.gameData.gameYear)
        assertEquals(original.gameData.gameMonth, restored.gameData.gameMonth)
        assertEquals(original.gameData.gameDay, restored.gameData.gameDay)
        assertEquals(original.gameData.spiritStones, restored.gameData.spiritStones)
        assertEquals(original.gameData.spiritHerbs, restored.gameData.spiritHerbs)
    }

    @Test
    fun `fromSerializable - equipment roundtrip preserves all fields`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)
        val oEquip = original.equipment[0]
        val rEquip = restored.equipment[0]

        assertEquals(oEquip.id, rEquip.id)
        assertEquals(oEquip.name, rEquip.name)
        assertEquals(oEquip.slot, rEquip.slot)
        assertEquals(oEquip.rarity, rEquip.rarity)
        assertEquals(oEquip.physicalAttack, rEquip.physicalAttack)
        assertEquals(oEquip.speed, rEquip.speed)
        assertEquals(oEquip.description, rEquip.description)
        assertEquals(oEquip.critChance, rEquip.critChance, 0.001)
        assertEquals(oEquip.minRealm, rEquip.minRealm)
        assertEquals(oEquip.quantity, rEquip.quantity)
        assertEquals(oEquip.nurtureLevel, rEquip.nurtureLevel)
        assertEquals(oEquip.nurtureProgress, rEquip.nurtureProgress, 0.001)
        assertEquals(oEquip.isEquipped, rEquip.isEquipped)
        assertEquals(oEquip.ownerId, rEquip.ownerId)
    }

    @Test
    fun `fromSerializable - battleLog roundtrip preserves core fields`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)
        val oLog = original.battleLogs[0]
        val rLog = restored.battleLogs[0]

        assertEquals(oLog.id, rLog.id)
        assertEquals(oLog.attackerName, rLog.attackerName)
        assertEquals(oLog.defenderName, rLog.defenderName)
        assertEquals(oLog.result, rLog.result)
        assertEquals(oLog.drops, rLog.drops)
    }

    @Test
    fun `fromSerializable - alliance roundtrip preserves all fields`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)
        val oAlliance = original.alliances[0]
        val rAlliance = restored.alliances[0]

        assertEquals(oAlliance.id, rAlliance.id)
        assertEquals(oAlliance.sectIds, rAlliance.sectIds)
        assertEquals(oAlliance.startYear, rAlliance.startYear)
        assertEquals(oAlliance.initiatorId, rAlliance.initiatorId)
        assertEquals(oAlliance.envoyDiscipleId, rAlliance.envoyDiscipleId)
    }

    @Test
    fun `full roundtrip - toSerializable then fromSerializable preserves data integrity`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)

        assertEquals(original.version, restored.version)
        assertEquals(original.gameData.sectName, restored.gameData.sectName)
        assertEquals(original.gameData.spiritStones, restored.gameData.spiritStones)
        assertEquals(original.disciples.size, restored.disciples.size)
        assertEquals(original.equipment.size, restored.equipment.size)
        assertEquals(original.manuals.size, restored.manuals.size)
        assertEquals(original.pills.size, restored.pills.size)
        assertEquals(original.materials.size, restored.materials.size)
        assertEquals(original.herbs.size, restored.herbs.size)
        assertEquals(original.seeds.size, restored.seeds.size)
    }

    @Test
    fun `toSerializable - null gameData produces default SerializableGameData`() {
        val saveData = SaveData(
            version = "2.0",
            timestamp = System.currentTimeMillis(),
            gameData = GameData(),
            disciples = emptyList(),
            equipment = emptyList(),
            manuals = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )
        val serializable = converter.toSerializable(saveData)

        assertEquals("青云宗", serializable.gameData.sectName)
        assertEquals(1, serializable.gameData.currentSlot)
    }

    @Test
    fun `toSerializable - multiple disciples preserve order`() {
        val disciples = (1..5).map { i ->
            Disciple(
                id = "disciple_$i",
                name = "弟子$i",
                realm = i,
                realmLayer = 0,
                cultivation = i * 1000.0,
                spiritRootType = "火",
                age = 20 + i,
                lifespan = 200,
                isAlive = true,
                gender = "男",
                status = DiscipleStatus.IDLE,
                statusData = emptyMap(),
                cultivationSpeedBonus = 0.0,
                cultivationSpeedDuration = 0,
                discipleType = "inner",
                combat = CombatAttributes(
                    baseHp = 100,
                    baseMp = 50,
                    basePhysicalAttack = 10,
                    baseMagicAttack = 10,
                    basePhysicalDefense = 10,
                    baseMagicDefense = 10,
                    baseSpeed = 10,
                    hpVariance = 0,
                    mpVariance = 0,
                    physicalAttackVariance = 0,
                    magicAttackVariance = 0,
                    physicalDefenseVariance = 0,
                    magicDefenseVariance = 0,
                    speedVariance = 0,
                    totalCultivation = 0L,
                    breakthroughCount = 0,
                    breakthroughFailCount = 0
                ),
                pillEffects = PillEffects(
                    pillPhysicalAttackBonus = 0.0,
                    pillMagicAttackBonus = 0.0,
                    pillPhysicalDefenseBonus = 0.0,
                    pillMagicDefenseBonus = 0.0,
                    pillHpBonus = 0.0,
                    pillMpBonus = 0.0,
                    pillSpeedBonus = 0.0,
                    pillEffectDuration = 0
                ),
                equipment = EquipmentSet(
                    spiritStones = 0,
                    soulPower = 0,
                    storageBagItems = emptyList(),
                    storageBagSpiritStones = 0L
                ),
                social = SocialData(
                    partnerId = null,
                    partnerSectId = null,
                    parentId1 = null,
                    parentId2 = null,
                    lastChildYear = 0
                ),
                skills = SkillStats(
                    intelligence = 50,
                    charm = 50,
                    loyalty = 50,
                    comprehension = 50,
                    artifactRefining = 0,
                    pillRefining = 0,
                    spiritPlanting = 0,
                    teaching = 0,
                    morality = 50,
                    salaryPaidCount = 0,
                    salaryMissedCount = 0
                ),
                usage = UsageTracking(
                    monthlyUsedPillIds = emptyList(),
                    usedExtendLifePillIds = emptyList(),
                    recruitedMonth = 1,
                    hasReviveEffect = false,
                    hasClearAllEffect = false
                )
            )
        }
        val saveData = createMinimalSaveData().copy(disciples = disciples)
        val serializable = converter.toSerializable(saveData)
        val restored = converter.fromSerializable(serializable)

        assertEquals(5, restored.disciples.size)
        for (i in 1..5) {
            assertEquals("disciple_$i", restored.disciples[i - 1].id)
            assertEquals(i, restored.disciples[i - 1].realm)
        }
    }
}
