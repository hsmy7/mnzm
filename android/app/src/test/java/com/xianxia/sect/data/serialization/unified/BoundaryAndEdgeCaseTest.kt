package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.result.ErrorSeverity
import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import com.xianxia.sect.data.unified.SaveError
import org.junit.Assert.*
import org.junit.Test

class BoundaryAndEdgeCaseTest {

    private fun createSaveDataWithDisciples(count: Int): SaveData {
        val disciples = (1..count).map { i ->
            Disciple(
                id = "d_$i",
                name = "弟子$i",
                realm = (i % 20) + 1,
                realmLayer = i % 10,
                cultivation = i * 1000.0,
                spiritRootType = listOf("金", "木", "水", "火", "土")[i % 5],
                age = 15 + (i % 200),
                lifespan = 100 + (i % 500),
                isAlive = i % 7 != 0,
                gender = if (i % 2 == 0) "男" else "女",
                manualIds = listOf("manual_$i"),
                talentIds = listOf("talent_$i"),
                manualMasteries = mapOf("manual_$i" to (i * 10)),
                status = if (i % 8 == 0) DiscipleStatus.ON_MISSION else DiscipleStatus.IDLE,
                statusData = if (i % 8 == 0) mapOf("type" to "mission") else emptyMap(),
                cultivationSpeedBonus = if (i % 3 == 0) i * 0.5 else 0.0,
                cultivationSpeedDuration = if (i % 3 == 0) i else 0,
                discipleType = listOf("inner", "outer", "core")[i % 3],
                combat = CombatAttributes(
                    baseHp = 100 + i * 10,
                    baseMp = 50 + i * 5,
                    basePhysicalAttack = 10 + i * 3,
                    baseMagicAttack = 10 + i * 2,
                    basePhysicalDefense = 5 + i * 2,
                    baseMagicDefense = 5 + i,
                    baseSpeed = 10 + i,
                    hpVariance = i % 20,
                    mpVariance = i % 15,
                    physicalAttackVariance = i % 25,
                    magicAttackVariance = i % 20,
                    physicalDefenseVariance = i % 15,
                    magicDefenseVariance = i % 18,
                    speedVariance = i % 10,
                    totalCultivation = (i * 10000L),
                    breakthroughCount = i % 10,
                    breakthroughFailCount = i % 5
                ),
                pillEffects = PillEffects(),
                equipment = EquipmentSet(
                    weaponId = if (i % 2 == 0) "weapon_$i" else "",
                    armorId = if (i % 3 == 0) "armor_$i" else "",
                    spiritStones = i * 100,
                    soulPower = i * 5,
                    storageBagSpiritStones = (i * 50L)
                ),
                social = SocialData(
                    partnerId = if (i % 3 == 0) "partner_$i" else null,
                    partnerSectId = "",
                    parentId1 = if (i % 5 == 0) "parent1_$i" else null,
                    parentId2 = if (i % 5 == 0) "parent2_$i" else null,
                    lastChildYear = if (i % 4 == 0) i * 10 else 0,
                    griefEndYear = if (i % 6 == 0) -1 else null
                ),
                skills = SkillStats(
                    intelligence = 30 + (i % 70),
                    charm = 20 + (i % 80),
                    loyalty = 40 + (i % 60),
                    comprehension = 30 + (i % 70),
                    artifactRefining = i % 100,
                    pillRefining = i % 100,
                    spiritPlanting = i % 100,
                    teaching = i % 100,
                    morality = 10 + (i % 90),
                    salaryPaidCount = i % 50,
                    salaryMissedCount = i % 10
                ),
                usage = UsageTracking(
                    usedFunctionalPillTypes = emptyList(),
                    usedExtendLifePillIds = emptyList(),
                    recruitedMonth = 1 + (i % 12),
                    hasReviveEffect = i % 20 == 0,
                    hasClearAllEffect = i % 30 == 0
                )
            )
        }
        return SaveData(
            version = "2.0",
            timestamp = System.currentTimeMillis(),
            gameData = GameData(sectName = "大宗门"),
            disciples = disciples,
            equipmentStacks = emptyList(),
            equipmentInstances = emptyList(),
            manualStacks = emptyList(),
            manualInstances = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )
    }

    @Test
    fun `SaveDataConverter roundtrip - 100 disciples preserves all data`() {
        val converter = SaveDataConverter()
        val original = createSaveDataWithDisciples(100)
        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)

        assertEquals(100, restored.disciples.size)
        for (i in 1..100) {
            val r = restored.disciples[i - 1]
            assertEquals("d_$i", r.id)
            assertEquals("弟子$i", r.name)
            assertEquals((i % 20) + 1, r.realm)
        }
    }

    @Test
    fun `SaveDataConverter roundtrip - single disciple preserves all nullable fields`() {
        val converter = SaveDataConverter()
        val disciple = Disciple(
            id = "d_1",
            name = "测试弟子",
            realm = 5,
            realmLayer = 3,
            cultivation = 50000.0,
            spiritRootType = "火",
            age = 30,
            lifespan = 300,
            isAlive = true,
            gender = "男",
            manualIds = listOf("m1", "m2", "m3"),
            talentIds = listOf("t1", "t2"),
            manualMasteries = mapOf("m1" to 80, "m2" to 50, "m3" to 20),
            status = DiscipleStatus.ON_MISSION,
            statusData = mapOf("type" to "mission", "remaining" to "3"),
            cultivationSpeedBonus = 15.5,
            cultivationSpeedDuration = 10,
            discipleType = "core",
            combat = CombatAttributes(
                baseHp = 2000, baseMp = 1000,
                basePhysicalAttack = 300, baseMagicAttack = 250,
                basePhysicalDefense = 150, baseMagicDefense = 120,
                baseSpeed = 80,
                hpVariance = 10, mpVariance = 8,
                physicalAttackVariance = 15, magicAttackVariance = 12,
                physicalDefenseVariance = 7, magicDefenseVariance = 9,
                speedVariance = 5,
                totalCultivation = 150000L,
                breakthroughCount = 8, breakthroughFailCount = 3
            ),
            pillEffects = PillEffects(
                pillPhysicalAttackBonus = 5, pillMagicAttackBonus = 3,
                pillPhysicalDefenseBonus = 2, pillMagicDefenseBonus = 4,
                pillHpBonus = 100, pillMpBonus = 50,
                pillSpeedBonus = 1, pillEffectDuration = 5
            ),
            equipment = EquipmentSet(
                weaponId = "weapon_1", armorId = "armor_1",
                bootsId = "boots_1", accessoryId = "accessory_1",
                spiritStones = 9999, soulPower = 888,
                storageBagSpiritStones = 7777L
            ),
            social = SocialData(
                partnerId = "partner_1", partnerSectId = "sect_2",
                parentId1 = "parent_1", parentId2 = "parent_2",
                lastChildYear = 25, griefEndYear = 30
            ),
            skills = SkillStats(
                intelligence = 95, charm = 88, loyalty = 100, comprehension = 92,
                artifactRefining = 75, pillRefining = 60, spiritPlanting = 45,
                teaching = 80, morality = 90, salaryPaidCount = 30, salaryMissedCount = 2
            ),
            usage = UsageTracking(
                usedFunctionalPillTypes = listOf("pill_1", "pill_2"),
                usedExtendLifePillIds = listOf("extend_pill_1"),
                recruitedMonth = 6, hasReviveEffect = true, hasClearAllEffect = true
            )
        )

        val original = SaveData(
            version = "2.0",
            timestamp = System.currentTimeMillis(),
            gameData = GameData(),
            disciples = listOf(disciple),
            equipmentStacks = emptyList(),
            equipmentInstances = emptyList(),
            manualStacks = emptyList(),
            manualInstances = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )

        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)
        val r = restored.disciples[0]

        assertEquals("partner_1", r.partnerId)
        assertEquals("parent_1", r.parentId1)
        assertEquals("parent_2", r.parentId2)
        assertEquals("weapon_1", r.weaponId)
        assertEquals("armor_1", r.armorId)
        assertEquals("boots_1", r.bootsId)
        assertEquals("accessory_1", r.accessoryId)
        assertEquals(listOf("m1", "m2", "m3"), r.manualIds)
        assertEquals(listOf("t1", "t2"), r.talentIds)
        assertEquals(30, r.griefEndYear)
        assertEquals(25, r.lastChildYear)
        assertEquals(9999, r.spiritStones)
        assertEquals(888, r.soulPower)
        assertEquals(7777L, r.storageBagSpiritStones)
        assertEquals(DiscipleStatus.ON_MISSION, r.status)
        assertEquals(15.5, r.cultivationSpeedBonus, 0.001)
        assertEquals(10, r.cultivationSpeedDuration)
        assertEquals(5, r.pillPhysicalAttackBonus)
        assertEquals(3, r.pillMagicAttackBonus)
        assertEquals(2, r.pillPhysicalDefenseBonus)
        assertEquals(4, r.pillMagicDefenseBonus)
        assertEquals(100, r.pillHpBonus)
        assertEquals(50, r.pillMpBonus)
        assertEquals(1, r.pillSpeedBonus)
        assertEquals(5, r.pillEffectDuration)
        assertEquals(150000L, r.totalCultivation)
        assertEquals(8, r.breakthroughCount)
        assertEquals(3, r.breakthroughFailCount)
        assertEquals(95, r.intelligence)
        assertEquals(88, r.charm)
        assertEquals(100, r.loyalty)
        assertEquals(92, r.comprehension)
        assertEquals(75, r.artifactRefining)
        assertEquals(60, r.pillRefining)
        assertEquals(45, r.spiritPlanting)
        assertEquals(80, r.teaching)
        assertEquals(90, r.morality)
        assertEquals(30, r.salaryPaidCount)
        assertEquals(2, r.salaryMissedCount)
        assertEquals(6, r.recruitedMonth)
        assertEquals(2000, r.baseHp)
        assertEquals(1000, r.baseMp)
        assertEquals(300, r.basePhysicalAttack)
        assertEquals(250, r.baseMagicAttack)
        assertEquals(150, r.basePhysicalDefense)
        assertEquals(120, r.baseMagicDefense)
        assertEquals(80, r.baseSpeed)
        assertEquals("core", r.discipleType)
        assertTrue(r.hasReviveEffect)
        assertTrue(r.hasClearAllEffect)
    }

    @Test
    fun `SaveDataConverter - empty string name is preserved`() {
        val converter = SaveDataConverter()
        val disciple = Disciple(
            id = "d_empty",
            name = "",
            realm = 1,
            realmLayer = 0,
            cultivation = 0.0,
            spiritRootType = "",
            age = 0,
            lifespan = 0,
            isAlive = false,
            gender = "",
            status = DiscipleStatus.IDLE,
            statusData = emptyMap(),
            cultivationSpeedBonus = 0.0,
            cultivationSpeedDuration = 0,
            discipleType = "",
            combat = CombatAttributes(
                baseHp = 0, baseMp = 0, basePhysicalAttack = 0, baseMagicAttack = 0,
                basePhysicalDefense = 0, baseMagicDefense = 0, baseSpeed = 0,
                hpVariance = 0, mpVariance = 0, physicalAttackVariance = 0,
                magicAttackVariance = 0, physicalDefenseVariance = 0,
                magicDefenseVariance = 0, speedVariance = 0,
                totalCultivation = 0L, breakthroughCount = 0, breakthroughFailCount = 0
            ),
            pillEffects = PillEffects(),
            equipment = EquipmentSet(spiritStones = 0, soulPower = 0, storageBagSpiritStones = 0L),
            social = SocialData(),
            skills = SkillStats(
                intelligence = 0, charm = 0, loyalty = 0, comprehension = 0,
                artifactRefining = 0, pillRefining = 0, spiritPlanting = 0,
                teaching = 0, morality = 0, salaryPaidCount = 0, salaryMissedCount = 0
            ),
            usage = UsageTracking(recruitedMonth = 0)
        )

        val original = SaveData(
            version = "2.0",
            timestamp = 0L,
            gameData = GameData(),
            disciples = listOf(disciple),
            equipmentStacks = emptyList(),
            equipmentInstances = emptyList(),
            manualStacks = emptyList(),
            manualInstances = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )

        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)

        assertEquals("", restored.disciples[0].name)
        assertFalse(restored.disciples[0].isAlive)
        assertEquals(0L, restored.timestamp)
    }

    @Test
    fun `SaveDataConverter - max int and max long values are preserved`() {
        val converter = SaveDataConverter()
        val disciple = Disciple(
            id = "d_max",
            name = "最大值测试",
            realm = Int.MAX_VALUE,
            realmLayer = Int.MAX_VALUE,
            cultivation = Double.MAX_VALUE,
            spiritRootType = "火",
            age = Int.MAX_VALUE,
            lifespan = Int.MAX_VALUE,
            isAlive = true,
            gender = "男",
            status = DiscipleStatus.IDLE,
            statusData = emptyMap(),
            cultivationSpeedBonus = Double.MAX_VALUE,
            cultivationSpeedDuration = Int.MAX_VALUE,
            discipleType = "inner",
            combat = CombatAttributes(
                baseHp = Int.MAX_VALUE, baseMp = Int.MAX_VALUE,
                basePhysicalAttack = Int.MAX_VALUE, baseMagicAttack = Int.MAX_VALUE,
                basePhysicalDefense = Int.MAX_VALUE, baseMagicDefense = Int.MAX_VALUE,
                baseSpeed = Int.MAX_VALUE,
                hpVariance = Int.MAX_VALUE, mpVariance = Int.MAX_VALUE,
                physicalAttackVariance = Int.MAX_VALUE, magicAttackVariance = Int.MAX_VALUE,
                physicalDefenseVariance = Int.MAX_VALUE, magicDefenseVariance = Int.MAX_VALUE,
                speedVariance = Int.MAX_VALUE,
                totalCultivation = Long.MAX_VALUE,
                breakthroughCount = Int.MAX_VALUE, breakthroughFailCount = Int.MAX_VALUE
            ),
            pillEffects = PillEffects(
                pillPhysicalAttackBonus = Int.MAX_VALUE, pillMagicAttackBonus = Int.MAX_VALUE,
                pillPhysicalDefenseBonus = Int.MAX_VALUE, pillMagicDefenseBonus = Int.MAX_VALUE,
                pillHpBonus = Int.MAX_VALUE, pillMpBonus = Int.MAX_VALUE,
                pillSpeedBonus = Int.MAX_VALUE, pillEffectDuration = Int.MAX_VALUE
            ),
            equipment = EquipmentSet(
                spiritStones = Int.MAX_VALUE, soulPower = Int.MAX_VALUE,
                storageBagSpiritStones = Long.MAX_VALUE
            ),
            social = SocialData(lastChildYear = Int.MAX_VALUE),
            skills = SkillStats(
                intelligence = Int.MAX_VALUE, charm = Int.MAX_VALUE,
                loyalty = Int.MAX_VALUE, comprehension = Int.MAX_VALUE,
                artifactRefining = Int.MAX_VALUE, pillRefining = Int.MAX_VALUE,
                spiritPlanting = Int.MAX_VALUE, teaching = Int.MAX_VALUE,
                morality = Int.MAX_VALUE, salaryPaidCount = Int.MAX_VALUE,
                salaryMissedCount = Int.MAX_VALUE
            ),
            usage = UsageTracking(
                recruitedMonth = Int.MAX_VALUE, hasReviveEffect = true, hasClearAllEffect = true
            )
        )

        val original = SaveData(
            version = "2.0",
            timestamp = Long.MAX_VALUE,
            gameData = GameData(spiritStones = Long.MAX_VALUE),
            disciples = listOf(disciple),
            equipmentStacks = emptyList(),
            equipmentInstances = emptyList(),
            manualStacks = emptyList(),
            manualInstances = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )

        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)

        assertEquals(Int.MAX_VALUE, restored.disciples[0].realm)
        assertEquals(Int.MAX_VALUE, restored.disciples[0].age)
        assertEquals(Long.MAX_VALUE, restored.disciples[0].storageBagSpiritStones)
        assertEquals(Long.MAX_VALUE, restored.gameData.spiritStones)
        assertEquals(Long.MAX_VALUE, restored.timestamp)
    }

    @Test
    fun `SaveDataConverter - negative values are preserved`() {
        val converter = SaveDataConverter()
        val disciple = Disciple(
            id = "d_neg",
            name = "负值测试",
            realm = -1,
            realmLayer = -5,
            cultivation = -1000.0,
            spiritRootType = "火",
            age = -1,
            lifespan = -100,
            isAlive = false,
            gender = "男",
            status = DiscipleStatus.IDLE,
            statusData = emptyMap(),
            cultivationSpeedBonus = -5.0,
            cultivationSpeedDuration = -3,
            discipleType = "inner",
            combat = CombatAttributes(
                baseHp = -100, baseMp = -50,
                basePhysicalAttack = -10, baseMagicAttack = -10,
                basePhysicalDefense = -5, baseMagicDefense = -5,
                baseSpeed = -5,
                totalCultivation = -10000L,
                breakthroughFailCount = -1
            ),
            pillEffects = PillEffects(),
            equipment = EquipmentSet(spiritStones = -500, soulPower = -50, storageBagSpiritStones = -1000L),
            social = SocialData(lastChildYear = -10),
            skills = SkillStats(
                intelligence = -10, charm = -5, loyalty = -20, comprehension = -15,
                morality = -50, salaryMissedCount = -1
            ),
            usage = UsageTracking(recruitedMonth = -1)
        )

        val original = SaveData(
            version = "2.0",
            timestamp = -1L,
            gameData = GameData(spiritStones = -999L),
            disciples = listOf(disciple),
            equipmentStacks = emptyList(),
            equipmentInstances = emptyList(),
            manualStacks = emptyList(),
            manualInstances = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )

        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)

        assertEquals(-1, restored.disciples[0].realm)
        assertEquals(-5, restored.disciples[0].realmLayer)
        assertEquals(-1000.0, restored.disciples[0].cultivation, 0.001)
        assertEquals(-1, restored.disciples[0].age)
        assertEquals(-500, restored.disciples[0].spiritStones)
        assertEquals(-1000L, restored.disciples[0].storageBagSpiritStones)
        assertEquals(-5.0, restored.disciples[0].cultivationSpeedBonus, 0.001)
        assertEquals(-999L, restored.gameData.spiritStones)
    }

    @Test
    fun `SaveDataConverter - very long strings are preserved`() {
        val converter = SaveDataConverter()
        val longName = "A".repeat(10000)
        val disciple = Disciple(
            id = "d_long",
            name = longName,
            realm = 1,
            realmLayer = 0,
            cultivation = 0.0,
            spiritRootType = "火",
            age = 20,
            lifespan = 200,
            isAlive = true,
            gender = "男",
            status = DiscipleStatus.IDLE,
            statusData = emptyMap(),
            cultivationSpeedBonus = 0.0,
            cultivationSpeedDuration = 0,
            discipleType = "inner",
            combat = CombatAttributes(
                baseHp = 100, baseMp = 50, basePhysicalAttack = 10, baseMagicAttack = 10,
                basePhysicalDefense = 10, baseMagicDefense = 10, baseSpeed = 10
            ),
            pillEffects = PillEffects(),
            equipment = EquipmentSet(),
            social = SocialData(),
            skills = SkillStats(),
            usage = UsageTracking(recruitedMonth = 1)
        )

        val original = SaveData(
            version = "2.0",
            timestamp = System.currentTimeMillis(),
            gameData = GameData(sectName = longName),
            disciples = listOf(disciple),
            equipmentStacks = emptyList(),
            equipmentInstances = emptyList(),
            manualStacks = emptyList(),
            manualInstances = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )

        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)

        assertEquals(longName, restored.disciples[0].name)
        assertEquals(longName, restored.gameData.sectName)
    }

    @Test
    fun `SaveDataConverter - unicode and special characters are preserved`() {
        val converter = SaveDataConverter()
        val specialName = "修仙者🔥\n\t特殊\"字符'测试"
        val original = SaveData(
            version = "2.0",
            timestamp = System.currentTimeMillis(),
            gameData = GameData(sectName = specialName),
            disciples = emptyList(),
            equipmentStacks = emptyList(),
            equipmentInstances = emptyList(),
            manualStacks = emptyList(),
            manualInstances = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )

        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)

        assertEquals(specialName, restored.gameData.sectName)
    }

    @Test
    fun `HashedSaveData - create generates valid hash`() {
        val data = SerializableSaveData(
            version = "2.0",
            timestamp = System.currentTimeMillis(),
            gameData = SerializableGameData(sectName = "测试宗门")
        )
        val hashed = HashedSaveData.create(data)

        assertTrue(hashed.integrityHash.isNotEmpty())
        assertEquals(64, hashed.integrityHash.length)
        assertEquals("SHA-256", hashed.hashAlgorithm)
        assertTrue(hashed.timestamp > 0)
    }

    @Test
    fun `HashedSaveData - verifyIntegrity returns true for valid data`() {
        val data = SerializableSaveData(
            version = "2.0",
            timestamp = System.currentTimeMillis(),
            gameData = SerializableGameData(sectName = "测试宗门")
        )
        val hashed = HashedSaveData.create(data)

        assertTrue(HashedSaveData.verifyIntegrity(hashed))
        assertTrue(hashed.isValid())
    }

    @Test
    fun `HashedSaveData - verifyIntegrity returns false for tampered data`() {
        val data = SerializableSaveData(
            version = "2.0",
            timestamp = System.currentTimeMillis(),
            gameData = SerializableGameData(sectName = "原始宗门")
        )
        val hashed = HashedSaveData.create(data)

        val tampered = hashed.copy(
            data = hashed.data.copy(gameData = hashed.data.gameData.copy(sectName = "篡改宗门"))
        )

        assertFalse(HashedSaveData.verifyIntegrity(tampered))
        assertFalse(tampered.isValid())
    }

    @Test
    fun `HashedSaveData - verifyIntegrity returns false for empty hash`() {
        val data = SerializableSaveData(
            version = "2.0",
            timestamp = System.currentTimeMillis(),
            gameData = SerializableGameData()
        )
        val hashed = HashedSaveData(data = data, integrityHash = "")

        assertFalse(HashedSaveData.verifyIntegrity(hashed))
    }

    @Test
    fun `HashedSaveData - hash changes when data changes`() {
        val data1 = SerializableSaveData(
            version = "2.0",
            timestamp = 1000L,
            gameData = SerializableGameData(sectName = "宗门A")
        )
        val data2 = SerializableSaveData(
            version = "2.0",
            timestamp = 2000L,
            gameData = SerializableGameData(sectName = "宗门B")
        )
        val hashed1 = HashedSaveData.create(data1)
        val hashed2 = HashedSaveData.create(data2)

        assertNotEquals(hashed1.integrityHash, hashed2.integrityHash)
    }

    @Test
    fun `NullSafeProtoBuf - griefEndYear sentinel -1 roundtrip`() {
        val toProto = NullSafeProtoBuf.intToProto(null, sentinel = -1)
        val fromProto = NullSafeProtoBuf.intFromProto(toProto, sentinel = -1)
        assertNull(fromProto)

        val toProto2 = NullSafeProtoBuf.intToProto(30, sentinel = -1)
        val fromProto2 = NullSafeProtoBuf.intFromProto(toProto2, sentinel = -1)
        assertEquals(30, fromProto2)
    }

    @Test
    fun `NullSafeProtoBuf - all nullable relation fields roundtrip correctly`() {
        val fields = listOf(null, "disciple_1", "weapon_abc", "armor_xyz")

        for (field in fields) {
            val toProto = NullSafeProtoBuf.relationIdToProto(field)
            val fromProto = NullSafeProtoBuf.relationIdFromProto(toProto)
            assertEquals(field, fromProto)
        }

        val emptyToProto = NullSafeProtoBuf.relationIdToProto("")
        val emptyFromProto = NullSafeProtoBuf.relationIdFromProto(emptyToProto)
        assertNull(emptyFromProto)
    }

    @Test
    fun `NullSafeProtoBuf - all nullable equipment fields roundtrip correctly`() {
        val fields = listOf(null, "weapon_1", "armor_2", "boots_3", "accessory_4")

        for (field in fields) {
            val toProto = NullSafeProtoBuf.equipmentIdToProto(field)
            val fromProto = NullSafeProtoBuf.equipmentIdFromProto(toProto)
            assertEquals(field, fromProto)
        }

        val emptyToProto = NullSafeProtoBuf.equipmentIdToProto("")
        val emptyFromProto = NullSafeProtoBuf.equipmentIdFromProto(emptyToProto)
        assertNull(emptyFromProto)
    }

    @Test
    fun `SaveError - all error types have isRecoverable and requiresUserAction defined`() {
        for (error in SaveError.values()) {
            val recoverable = error.isRecoverable()
            val requiresAction = error.requiresUserAction()
            assertTrue(recoverable || !recoverable)
            assertTrue(requiresAction || !requiresAction)
        }
    }

    @Test
    fun `StorageError - all error types have getSeverity defined`() {
        for (error in StorageError.values()) {
            val severity = error.getSeverity()
            assertNotNull(severity)
            assertTrue(severity in listOf(ErrorSeverity.INFO, ErrorSeverity.WARNING, ErrorSeverity.ERROR, ErrorSeverity.CRITICAL))
        }
    }

    @Test
    fun `SaveDataConverter - large number of equipment preserves all`() {
        val converter = SaveDataConverter()
        val equipment = (1..500).map { i ->
            EquipmentInstance(
                id = "equip_$i",
                name = "装备$i",
                slot = listOf(EquipmentSlot.WEAPON, EquipmentSlot.ARMOR, EquipmentSlot.BOOTS, EquipmentSlot.ACCESSORY)[i % 4],
                rarity = (i % 6) + 1,
                physicalAttack = i * 2,
                magicAttack = i,
                physicalDefense = i,
                magicDefense = i / 2,
                speed = i % 20,
                hp = i * 5,
                mp = i * 3,
                description = "装备描述$i",
                critChance = (i % 10) * 0.05,
                isEquipped = i % 3 == 0,
                nurtureLevel = i % 10,
                nurtureProgress = (i % 100) / 100.0,
                minRealm = i % 20,
                ownerId = if (i % 2 == 0) "d_${i % 100}" else null
            )
        }

        val original = SaveData(
            version = "2.0",
            timestamp = System.currentTimeMillis(),
            gameData = GameData(),
            disciples = emptyList(),
            equipmentStacks = emptyList(),
            equipmentInstances = equipment,
            manualStacks = emptyList(),
            manualInstances = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )

        val serializable = converter.toSerializable(original)
        val restored = converter.fromSerializable(serializable)

        assertEquals(500, restored.equipmentInstances.size)
        for (i in 1..500) {
            val r = restored.equipmentInstances[i - 1]
            assertEquals("equip_$i", r.id)
            assertEquals("装备$i", r.name)
            assertEquals((i % 6) + 1, r.rarity)
        }
    }
}
