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
            equipmentStacks = emptyList(),
            equipmentInstances = emptyList(),
            manualStacks = emptyList(),
            manualInstances = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList()
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
            soulPower = 50,
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
                pillPhysicalAttackBonus = 0,
                pillMagicAttackBonus = 0,
                pillPhysicalDefenseBonus = 0,
                pillMagicDefenseBonus = 0,
                pillHpBonus = 0,
                pillMpBonus = 0,
                pillSpeedBonus = 0,
                pillEffectDuration = 0,
                activePillTypes = setOf(
                    "cultivation_speed_boost",
                    "temp_attack_boost"
                )
            ),
            equipment = EquipmentSet(
                weaponId = "weapon_1",
                armorId = "armor_1",
                bootsId = "",
                accessoryId = "",
                spiritStones = 1000,
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
                usedPermanentPillKeys = setOf("3#intelligence", "1#charm"),
                usedExtendLifePillTypes = setOf("extend_life_low"),
                usedFunctionalPillTypes = emptyList(),
                usedExtendLifePillIds = emptyList(),
                recruitedMonth = 3,
                hasReviveEffect = false,
                hasClearAllEffect = false
            )
        )

        val equipment = EquipmentInstance(
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
            minRealm = 0
        )

        val manual = ManualInstance(
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
            category = PillCategory.FUNCTIONAL,
            rarity = 3,
            effects = PillEffect(breakthroughChance = 30.0),
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
                gamePhase = 1,  // 中旬 (旧 gameDay=15 → phase=1)
                spiritStones = 50000L,
                spiritHerbs = 100,
                yearlySalary = mapOf(1 to 1200, 2 to 2400),
                yearlySalaryEnabled = mapOf(1 to true, 2 to true),
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
            equipmentStacks = emptyList(),
            equipmentInstances = listOf(equipment),
            manualStacks = emptyList(),
            manualInstances = listOf(manual),
            pills = listOf(pill),
            materials = listOf(material),
            herbs = listOf(herb),
            seeds = listOf(seed),
            teams = listOf(team),
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
        val oEquip = original.equipmentInstances[0]

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
        assertEquals(oGame.gamePhase, sGame.gamePhase)
        assertEquals(oGame.spiritStones, sGame.spiritStones)
        assertEquals(oGame.spiritHerbs, sGame.spiritHerbs)
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
        assertTrue(restored.equipmentInstances.isEmpty())
        assertTrue(restored.manualInstances.isEmpty())
        assertTrue(restored.pills.isEmpty())
        assertTrue(restored.materials.isEmpty())
        assertTrue(restored.herbs.isEmpty())
        assertTrue(restored.seeds.isEmpty())
        assertTrue(restored.teams.isEmpty())
    }

    @Test
    fun `fromSerializable - rich data roundtrip preserves entity counts`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)

        assertEquals(original.disciples.size, restored.disciples.size)
        assertEquals(original.equipmentInstances.size, restored.equipmentInstances.size)
        assertEquals(original.manualInstances.size, restored.manualInstances.size)
        assertEquals(original.pills.size, restored.pills.size)
        assertEquals(original.materials.size, restored.materials.size)
        assertEquals(original.herbs.size, restored.herbs.size)
        assertEquals(original.seeds.size, restored.seeds.size)
        assertEquals(original.teams.size, restored.teams.size)
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
        assertEquals(original.gameData.gamePhase, restored.gameData.gamePhase)
        assertEquals(original.gameData.spiritStones, restored.gameData.spiritStones)
        assertEquals(original.gameData.spiritHerbs, restored.gameData.spiritHerbs)
    }

    @Test
    fun `fromSerializable - equipment roundtrip preserves all fields`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)
        val oEquip = original.equipmentInstances[0]
        val rEquip = restored.equipmentInstances[0]

        assertEquals(oEquip.id, rEquip.id)
        assertEquals(oEquip.name, rEquip.name)
        assertEquals(oEquip.slot, rEquip.slot)
        assertEquals(oEquip.rarity, rEquip.rarity)
        assertEquals(oEquip.physicalAttack, rEquip.physicalAttack)
        assertEquals(oEquip.speed, rEquip.speed)
        assertEquals(oEquip.description, rEquip.description)
        assertEquals(oEquip.critChance, rEquip.critChance, 0.001)
        assertEquals(oEquip.minRealm, rEquip.minRealm)
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
        assertEquals(original.equipmentInstances.size, restored.equipmentInstances.size)
        assertEquals(original.manualInstances.size, restored.manualInstances.size)
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
            equipmentStacks = emptyList(),
            equipmentInstances = emptyList(),
            manualStacks = emptyList(),
            manualInstances = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList()
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
                    pillPhysicalAttackBonus = 0,
                    pillMagicAttackBonus = 0,
                    pillEffectDuration = 0
                ),
                equipment = EquipmentSet(
                    spiritStones = 0,
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
                    usedFunctionalPillTypes = emptyList(),
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

    @Test
    fun `roundtrip preserves activePillTypes`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)
        val oTypes = original.disciples[0].pillEffects.activePillTypes
        val rTypes = restored.disciples[0].pillEffects.activePillTypes
        assertEquals(oTypes, rTypes)
        assertTrue(rTypes.contains("cultivation_speed_boost"))
        assertTrue(rTypes.contains("temp_attack_boost"))
    }

    @Test
    fun `roundtrip preserves usedPermanentPillKeys`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)
        val oKeys = original.disciples[0].usage.usedPermanentPillKeys
        val rKeys = restored.disciples[0].usage.usedPermanentPillKeys
        assertEquals(oKeys, rKeys)
        assertTrue(rKeys.contains("3#intelligence"))
        assertTrue(rKeys.contains("1#charm"))
    }

    @Test
    fun `roundtrip preserves usedExtendLifePillTypes`() {
        val original = createRichSaveData()
        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)
        val oTypes = original.disciples[0].usage.usedExtendLifePillTypes
        val rTypes = restored.disciples[0].usage.usedExtendLifePillTypes
        assertEquals(oTypes, rTypes)
        assertTrue(rTypes.contains("extend_life_low"))
    }

    @Test
    fun `old save compatibility - missing pill tracking fields default to empty`() {
        // 模拟旧存档：不传新字段，依赖 data class 默认值 emptyList()
        val oldData = createRichSaveData()
        val full = converter.toSerializable(oldData)
        // 构造一个缺少新字段的 SerializableDisciple
        val oldStyleDisciple = full.disciples[0].copy(
            usedPermanentPillKeys = emptyList(),
            usedExtendLifePillTypes = emptyList(),
            activePillTypes = emptyList()
        )
        val oldStyleSave = full.copy(
            gameData = full.gameData,
            disciples = listOf(oldStyleDisciple),
            equipment = full.equipment,
            manuals = full.manuals,
            pills = full.pills,
            materials = full.materials,
            herbs = full.herbs,
            seeds = full.seeds,
            teams = full.teams,
            battleLogs = full.battleLogs,
            alliances = full.alliances
        )
        val restored = converter.fromSerializable(oldStyleSave)
        val rDisciple = restored.disciples[0]
        assertTrue(
            "旧存档缺失 activePillTypes 应默认为空 Set",
            rDisciple.pillEffects.activePillTypes.isEmpty()
        )
        assertTrue(
            "旧存档缺失 usedPermanentPillKeys 应默认为空 Set",
            rDisciple.usage.usedPermanentPillKeys.isEmpty()
        )
        assertTrue(
            "旧存档缺失 usedExtendLifePillTypes 应默认为空 Set",
            rDisciple.usage.usedExtendLifePillTypes.isEmpty()
        )
    }

    // ==================== 云存档新增字段测试 ====================

    @Test
    fun `roundtrip preserves disciple portraitRes`() {
        val disciple = createRichSaveData().disciples[0].copy(portraitRes = "male_disciple_5")
        val saveData = createMinimalSaveData().copy(disciples = listOf(disciple))
        val serializable = converter.toSerializable(saveData)
        val restored = converter.fromSerializable(serializable)

        assertEquals("male_disciple_5", restored.disciples[0].portraitRes)
    }

    @Test
    fun `roundtrip preserves placedBuildings`() {
        val buildings = listOf(
            GridBuildingData(buildingId = "alchemy", displayName = "炼丹炉", gridX = 5, gridY = 3, width = 2, height = 2, instanceId = "inst_1"),
            GridBuildingData(buildingId = "spirit_mine", displayName = "灵矿场", gridX = 0, gridY = 0, width = 3, height = 3, instanceId = "inst_2")
        )
        val gameData = GameData().copy(placedBuildings = buildings)
        val saveData = createMinimalSaveData().copy(gameData = gameData)
        val serializable = converter.toSerializable(saveData)
        val restored = converter.fromSerializable(serializable)

        assertEquals(2, restored.gameData.placedBuildings.size)
        assertEquals("alchemy", restored.gameData.placedBuildings[0].buildingId)
        assertEquals("炼丹炉", restored.gameData.placedBuildings[0].displayName)
        assertEquals(5, restored.gameData.placedBuildings[0].gridX)
        assertEquals(3, restored.gameData.placedBuildings[0].gridY)
        assertEquals("inst_2", restored.gameData.placedBuildings[1].instanceId)
    }

    @Test
    fun `roundtrip preserves midGrade and highGrade spirit stones`() {
        val gameData = GameData().copy(midGradeSpiritStones = 500L, highGradeSpiritStones = 200L)
        val saveData = createMinimalSaveData().copy(gameData = gameData)
        val serializable = converter.toSerializable(saveData)
        val restored = converter.fromSerializable(serializable)

        assertEquals(500L, restored.gameData.midGradeSpiritStones)
        assertEquals(200L, restored.gameData.highGradeSpiritStones)
    }

    @Test
    fun `roundtrip preserves rngStates`() {
        val rngStates = mapOf(1 to 12345L, 2 to 67890L)
        val gameData = GameData().copy(rngStates = rngStates)
        val saveData = createMinimalSaveData().copy(gameData = gameData)
        val serializable = converter.toSerializable(saveData)
        val restored = converter.fromSerializable(serializable)

        assertEquals(rngStates, restored.gameData.rngStates)
    }

    @Test
    fun `roundtrip preserves worldLevels`() {
        val worldLevels = listOf(
            WorldLevel(id = "wl_1", type = LevelType.BEAST, realm = 5, beastName = "烈焰虎", x = 10f, y = 20f, defeated = false),
            WorldLevel(id = "wl_2", type = LevelType.CAVE, realm = 3, caveName = "幽冥洞", x = 30f, y = 40f, defeated = true)
        )
        val gameData = GameData().copy(worldLevels = worldLevels)
        val saveData = createMinimalSaveData().copy(gameData = gameData)
        val serializable = converter.toSerializable(saveData)
        val restored = converter.fromSerializable(serializable)

        assertEquals(2, restored.gameData.worldLevels.size)
        assertEquals("wl_1", restored.gameData.worldLevels[0].id)
        assertEquals(LevelType.BEAST, restored.gameData.worldLevels[0].type)
        assertEquals("烈焰虎", restored.gameData.worldLevels[0].beastName)
        assertFalse(restored.gameData.worldLevels[0].defeated)
        assertTrue(restored.gameData.worldLevels[1].defeated)
    }

    @Test
    fun `roundtrip preserves vassalContracts`() {
        val contracts = listOf(
            VassalContract(vassalSectId = "sect_2", establishedYear = 5, lastTributeYear = 8)
        )
        val gameData = GameData().copy(vassalContracts = contracts)
        val saveData = createMinimalSaveData().copy(gameData = gameData)
        val serializable = converter.toSerializable(saveData)
        val restored = converter.fromSerializable(serializable)

        assertEquals(1, restored.gameData.vassalContracts.size)
        assertEquals("sect_2", restored.gameData.vassalContracts[0].vassalSectId)
        assertEquals(5, restored.gameData.vassalContracts[0].establishedYear)
    }

    @Test
    fun `roundtrip preserves mapSeed`() {
        val gameData = GameData().copy(mapSeed = 42)
        val saveData = createMinimalSaveData().copy(gameData = gameData)
        val serializable = converter.toSerializable(saveData)
        val restored = converter.fromSerializable(serializable)

        assertEquals(42, restored.gameData.mapSeed)
    }

    @Test
    fun `roundtrip preserves bloodRefinements`() {
        val bloodRefs = mapOf("disciple_1" to listOf("mat_1", "mat_2"))
        val gameData = GameData().copy(bloodRefinements = bloodRefs)
        val saveData = createMinimalSaveData().copy(gameData = gameData)
        val serializable = converter.toSerializable(saveData)
        val restored = converter.fromSerializable(serializable)

        assertEquals(bloodRefs, restored.gameData.bloodRefinements)
    }

    @Test
    fun `old save compatibility - new SerializableGameData fields default to zero`() {
        // 模拟旧存档：SerializableGameData 不包含新字段，反序列化后应取默认值
        val oldStyleSerializable = SerializableGameData(sectName = "旧存档")
        val restored = converter.fromSerializable(
            SerializableSaveData(version = "2.0", timestamp = 0L, gameData = oldStyleSerializable)
        )

        val gd = restored.gameData
        assertEquals("旧存档", gd.sectName)
        assertEquals(0L, gd.midGradeSpiritStones)
        assertEquals(0L, gd.highGradeSpiritStones)
        assertTrue(gd.placedBuildings.isEmpty())
        assertTrue(gd.worldLevels.isEmpty())
        assertEquals(0, gd.mapSeed)
        assertFalse(gd.isGameOver)
        assertEquals(0, gd.saveVersion)
    }

    @Test
    fun `roundtrip preserves patrolSlots and patrolConfig`() {
        val patrolSlots = listOf(PatrolSlot(index = 0, discipleId = "d1", discipleName = "弟子一"))
        val patrolConfig = PatrolConfig(targetRealms = setOf(5, 6), maxBeastCount = 2)
        val gameData = GameData().copy(
            patrolSlots = patrolSlots,
            patrolConfig = patrolConfig
        )
        val saveData = createMinimalSaveData().copy(gameData = gameData)
        val serializable = converter.toSerializable(saveData)
        val restored = converter.fromSerializable(serializable)

        assertEquals(1, restored.gameData.patrolSlots.size)
        assertEquals("d1", restored.gameData.patrolSlots[0].discipleId)
        assertEquals("弟子一", restored.gameData.patrolSlots[0].discipleName)
        assertEquals(setOf(5, 6), restored.gameData.patrolConfig.targetRealms)
        assertEquals(2, restored.gameData.patrolConfig.maxBeastCount)
    }

    @Test
    fun `roundtrip preserves numeric string and boolean settings fields`() {
        val gd = GameData().copy(
            sectCultivation = 8500.0,
            worldLevelLastRefreshMonth = 120,
            activeSectId = "sub_sect_1",
            saveVersion = 42,
            suzerainSectId = "master_sect",
            lastYearSpiritStoneIncome = 500000L,
            isGameOver = true,
            spiritMineLastSettledMonth = 60,
            shownWarningStageIds = listOf("warn_1", "warn_2"),
            sectAttackCooldowns = mapOf("sect_a" to 12, "sect_b" to 24),
            guideCounters = mapOf("build_count" to 5L, "recruit_count" to 3L)
        )
        val saveData = createMinimalSaveData().copy(gameData = gd)
        val restored = converter.fromSerializable(converter.toSerializable(saveData)).gameData

        assertEquals(8500.0, restored.sectCultivation, 0.001)
        assertEquals(120, restored.worldLevelLastRefreshMonth)
        assertEquals("sub_sect_1", restored.activeSectId)
        assertEquals(42, restored.saveVersion)
        assertEquals("master_sect", restored.suzerainSectId)
        assertEquals(500000L, restored.lastYearSpiritStoneIncome)
        assertTrue(restored.isGameOver)
        assertEquals(60, restored.spiritMineLastSettledMonth)
        assertEquals(listOf("warn_1", "warn_2"), restored.shownWarningStageIds)
        assertEquals(mapOf("sect_a" to 12, "sect_b" to 24), restored.sectAttackCooldowns)
        assertEquals(mapOf("build_count" to 5L, "recruit_count" to 3L), restored.guideCounters)
    }

    @Test
    fun `roundtrip preserves Set fields as Set via List conversion`() {
        val gd = GameData().copy(
            autoRecruitSpiritRootFilter = setOf(1, 3, 5),
            daoCompanionBannedRootCounts = setOf(4),
            breakthroughAutoPillRootCounts = setOf(1, 2),
            autoEquipFromWarehouseRootCounts = setOf(3),
            autoLearnFromWarehouseRootCounts = setOf(1, 5),
            guideClaimedRewardIds = setOf(10, 20, 30)
        )
        val saveData = createMinimalSaveData().copy(gameData = gd)
        val restored = converter.fromSerializable(converter.toSerializable(saveData)).gameData

        assertEquals(setOf(1, 3, 5), restored.autoRecruitSpiritRootFilter)
        assertEquals(setOf(4), restored.daoCompanionBannedRootCounts)
        assertEquals(setOf(1, 2), restored.breakthroughAutoPillRootCounts)
        assertEquals(setOf(3), restored.autoEquipFromWarehouseRootCounts)
        assertEquals(setOf(1, 5), restored.autoLearnFromWarehouseRootCounts)
        assertEquals(setOf(10, 20, 30), restored.guideClaimedRewardIds)
    }

    @Test
    fun `roundtrip preserves boolean settings fields`() {
        val gd = GameData().copy(
            daoCompanionConsentRequired = true,
            patrolBattleResultPopup = true,
            autoSellMidGradeForPurchase = true,
            autoSellHighGradeForPurchase = false,
            showAllAvailableDisciples = true,
            breakthroughAutoPillFocused = false,
            autoEquipFromWarehouseFocused = true,
            autoLearnFromWarehouseFocused = false
        )
        val saveData = createMinimalSaveData().copy(gameData = gd)
        val restored = converter.fromSerializable(converter.toSerializable(saveData)).gameData

        assertTrue(restored.daoCompanionConsentRequired)
        assertTrue(restored.patrolBattleResultPopup)
        assertTrue(restored.autoSellMidGradeForPurchase)
        assertFalse(restored.autoSellHighGradeForPurchase)
        assertTrue(restored.showAllAvailableDisciples)
        assertFalse(restored.breakthroughAutoPillFocused)
        assertTrue(restored.autoEquipFromWarehouseFocused)
        assertFalse(restored.autoLearnFromWarehouseFocused)
    }

    @Test
    fun `roundtrip preserves heavenlyTrialState and signInState`() {
        val htState = HeavenlyTrialSaveData(
            highestClearedLevel = 3,
            levelClearCounts = listOf(2, 1, 3, 0, 0, 0, 0, 0),
            phase1ClearedLevels = listOf(0, 1, 2, 3),
            phase2ClearedLevels = listOf(0, 1),
            claimedRewardLevels = listOf(0)
        )
        val siState = SignInState(
            claimedDays = listOf(1, 3, 5, 10),
            currentMonth = 7,
            currentYear = 5,
            claimedMilestones = listOf(7, 30)
        )
        val gd = GameData().copy(heavenlyTrialState = htState, signInState = siState)
        val saveData = createMinimalSaveData().copy(gameData = gd)
        val restored = converter.fromSerializable(converter.toSerializable(saveData)).gameData

        assertEquals(3, restored.heavenlyTrialState.highestClearedLevel)
        assertEquals(listOf(2, 1, 3, 0, 0, 0, 0, 0), restored.heavenlyTrialState.levelClearCounts)
        assertEquals(listOf(0, 1, 2, 3), restored.heavenlyTrialState.phase1ClearedLevels)
        assertEquals(listOf(0, 1), restored.heavenlyTrialState.phase2ClearedLevels)
        assertEquals(listOf(0), restored.heavenlyTrialState.claimedRewardLevels)
        assertEquals(listOf(1, 3, 5, 10), restored.signInState.claimedDays)
        assertEquals(5, restored.signInState.currentYear)
        assertEquals(listOf(7, 30), restored.signInState.claimedMilestones)
    }

    @Test
    fun `roundtrip preserves aiSectPersonalities map`() {
        val personalities = mapOf(
            "sect_a" to AISectPersonality.AGGRESSIVE,
            "sect_b" to AISectPersonality.CONSERVATIVE
        )
        val gd = GameData().copy(aiSectPersonalities = personalities)
        val saveData = createMinimalSaveData().copy(gameData = gd)
        val restored = converter.fromSerializable(converter.toSerializable(saveData)).gameData

        assertEquals(2, restored.aiSectPersonalities.size)
        assertEquals(AISectPersonality.AGGRESSIVE, restored.aiSectPersonalities["sect_a"])
        assertEquals(AISectPersonality.CONSERVATIVE, restored.aiSectPersonalities["sect_b"])
    }

    @Test
    fun `roundtrip preserves activeAttackWarnings and sectBattleRecords`() {
        val warnings = listOf(
            AttackWarning("w_1", "sect_a", "魔教", WarningStage.DENUNCIATION, attackMonth = 72, createdAtMonth = 66)
        )
        val battles = listOf(
            SectBattleRecord(year = 5, type = SectBattleType.CONQUEST),
            SectBattleRecord(year = 6, type = SectBattleType.BATTLE_LOSS)
        )
        val gd = GameData().copy(activeAttackWarnings = warnings, sectBattleRecords = battles)
        val saveData = createMinimalSaveData().copy(gameData = gd)
        val restored = converter.fromSerializable(converter.toSerializable(saveData)).gameData

        assertEquals(1, restored.activeAttackWarnings.size)
        assertEquals("sect_a", restored.activeAttackWarnings[0].attackerSectId)
        assertEquals(WarningStage.DENUNCIATION, restored.activeAttackWarnings[0].stage)
        assertEquals(2, restored.sectBattleRecords.size)
        assertEquals(SectBattleType.CONQUEST, restored.sectBattleRecords[0].type)
        assertEquals(6, restored.sectBattleRecords[1].year)
    }

    @Test
    fun `roundtrip preserves yearlyReports`() {
        val reports = listOf(
            YearlyReport(year = 3, totalIncome = 100000L, totalExpenditure = 50000L,
                forgeCompleted = 5, newDisciples = 3)
        )
        val gd = GameData().copy(yearlyReports = reports)
        val saveData = createMinimalSaveData().copy(gameData = gd)
        val restored = converter.fromSerializable(converter.toSerializable(saveData)).gameData

        assertEquals(1, restored.yearlyReports.size)
        assertEquals(3, restored.yearlyReports[0].year)
        assertEquals(100000L, restored.yearlyReports[0].totalIncome)
        assertEquals(5, restored.yearlyReports[0].forgeCompleted)
        assertEquals(3, restored.yearlyReports[0].newDisciples)
    }

    @Test
    fun `roundtrip preserves annual tracking fields`() {
        val gd = GameData().copy(
            annualIncomeBySource = mapOf("tribute" to 5000L, "tax" to 3000L),
            annualExpenditureByReason = mapOf("salary" to 4000L),
            annualTotalIncome = 8000L,
            annualTotalExpenditure = 4000L,
            annualAlchemyCount = 12,
            annualForgeCount = 8,
            annualHerbCount = 20,
            annualNewDisciples = 5,
            annualDeceasedDisciples = 2,
            annualDesertedDisciples = 1,
            annualTheftCount = 3,
            theftJudgementsThisMonth = 1,
            annualEquipmentBySource = mapOf("forge:3" to 2),
            annualPillBySource = mapOf("alchemy:HIGH" to 5),
            annualHerbBySource = mapOf("spirit_field" to 15)
        )
        val saveData = createMinimalSaveData().copy(gameData = gd)
        val restored = converter.fromSerializable(converter.toSerializable(saveData)).gameData

        assertEquals(mapOf("tribute" to 5000L, "tax" to 3000L), restored.annualIncomeBySource)
        assertEquals(mapOf("salary" to 4000L), restored.annualExpenditureByReason)
        assertEquals(8000L, restored.annualTotalIncome)
        assertEquals(4000L, restored.annualTotalExpenditure)
        assertEquals(12, restored.annualAlchemyCount)
        assertEquals(8, restored.annualForgeCount)
        assertEquals(20, restored.annualHerbCount)
        assertEquals(5, restored.annualNewDisciples)
        assertEquals(2, restored.annualDeceasedDisciples)
        assertEquals(1, restored.annualDesertedDisciples)
        assertEquals(3, restored.annualTheftCount)
        assertEquals(1, restored.theftJudgementsThisMonth)
        assertEquals(mapOf("forge:3" to 2), restored.annualEquipmentBySource)
        assertEquals(mapOf("alchemy:HIGH" to 5), restored.annualPillBySource)
        assertEquals(mapOf("spirit_field" to 15), restored.annualHerbBySource)
    }

    @Test
    fun `roundtrip preserves mailRecords and sectLevelClaimRecords`() {
        val mails = listOf(MailClaimRecord(mailId = "mail_1", claimedAt = 1000L, source = "builtin"))
        val claims = listOf(SectLevelClaimRecord(level = 3, claimedAtEpochMs = 2000L))
        val gd = GameData().copy(mailRecords = mails, sectLevelClaimRecords = claims)
        val saveData = createMinimalSaveData().copy(gameData = gd)
        val restored = converter.fromSerializable(converter.toSerializable(saveData)).gameData

        assertEquals(1, restored.mailRecords.size)
        assertEquals("mail_1", restored.mailRecords[0].mailId)
        assertEquals(1000L, restored.mailRecords[0].claimedAt)
        assertEquals(1, restored.sectLevelClaimRecords.size)
        assertEquals(3, restored.sectLevelClaimRecords[0].level)
    }

    @Test
    fun `roundtrip preserves autoBuyList`() {
        val entries = listOf(
            AutoBuyEntry(itemName = "筑基丹", itemType = "pill", rarity = 3),
            AutoBuyEntry(itemName = "灵石", itemType = "spiritStone", rarity = 1)
        )
        val gd = GameData().copy(autoBuyList = entries)
        val saveData = createMinimalSaveData().copy(gameData = gd)
        val restored = converter.fromSerializable(converter.toSerializable(saveData)).gameData

        assertEquals(2, restored.autoBuyList.size)
        assertEquals("筑基丹", restored.autoBuyList[0].itemName)
        assertEquals("pill", restored.autoBuyList[0].itemType)
    }

    @Test
    fun `roundtrip preserves bloodRefinementMaps`() {
        val progress = mapOf("bld_1" to BloodRefinementProgress(
            discipleId = "d1", materialId = "mat_1", startYear = 5, durationMonths = 12, bonusPercent = 0.15
        ))
        val bonusTotals = mapOf("d1" to BloodRefinementBonusTotal(hpBonus = 100, physicalAttackBonus = 20))
        val pctTotals = mapOf("d1" to BloodRefinementPctTotal(hpBonusPct = 5.0, physicalAttackBonusPct = 2.0))
        val gd = GameData().copy(
            activeBloodRefinements = progress,
            bloodRefinementBonusTotals = bonusTotals,
            bloodRefinementPctTotals = pctTotals
        )
        val saveData = createMinimalSaveData().copy(gameData = gd)
        val restored = converter.fromSerializable(converter.toSerializable(saveData)).gameData

        assertEquals("d1", restored.activeBloodRefinements["bld_1"]?.discipleId ?: "")
        assertEquals(0.15, restored.activeBloodRefinements["bld_1"]?.bonusPercent ?: 0.0, 0.001)
        assertEquals(100, restored.bloodRefinementBonusTotals["d1"]?.hpBonus ?: 0)
        assertEquals(2.0, restored.bloodRefinementPctTotals["d1"]?.physicalAttackBonusPct ?: 0.0, 0.001)
    }

    @Test
    fun `roundtrip preserves spiritFieldPlants and warehouseGarrisons`() {
        val plants = listOf(
            SpiritFieldPlant(buildingInstanceId = "field_1", seedId = "seed_1", seedName = "火灵花",
                growTime = 6, expectedYield = 10, plantYear = 5, plantMonth = 3)
        )
        val garrisons = listOf(
            WarehouseGarrisonSlot(buildingInstanceId = "wh_1", discipleId = "d1", discipleName = "弟子一")
        )
        val gd = GameData().copy(spiritFieldPlants = plants, warehouseGarrisons = garrisons)
        val saveData = createMinimalSaveData().copy(gameData = gd)
        val restored = converter.fromSerializable(converter.toSerializable(saveData)).gameData

        assertEquals(1, restored.spiritFieldPlants.size)
        assertEquals("field_1", restored.spiritFieldPlants[0].buildingInstanceId)
        assertEquals("火灵花", restored.spiritFieldPlants[0].seedName)
        assertEquals(1, restored.warehouseGarrisons.size)
        assertEquals("wh_1", restored.warehouseGarrisons[0].buildingInstanceId)
    }
}
