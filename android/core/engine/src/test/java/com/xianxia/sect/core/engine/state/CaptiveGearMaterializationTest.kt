package com.xianxia.sect.core.engine.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentNurtureData
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.materializeCaptiveGear
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 俘虏装备落库测试 — 覆盖 [materializeCaptiveGear]。
 *
 * 需要 Robolectric：DiscipleTables 的 ComponentTable 底层是 android.util.SparseArray，
 * 纯 JVM 环境下写入会静默丢失。
 */
@RunWith(RobolectricTestRunner::class)
class CaptiveGearMaterializationTest {

    @After
    fun tearDown() {
        // 恢复全局单例，避免注入的测试功法库污染其他测试类
        ManualDatabase.resetForTest()
    }

    @Before
    fun setUp() {
        ManualDatabase.initializeWithManuals(mapOf(
            "testAtk1" to ManualDatabase.ManualTemplate(
                id = "testAtk1", name = "烈阳剑诀", type = ManualType.ATTACK,
                rarity = 4, description = "测试功法",
                stats = mapOf("cultivationSpeedPercent" to 40, "hp" to 30)
            ),
            "testDef1" to ManualDatabase.ManualTemplate(
                id = "testDef1", name = "玄龟甲功", type = ManualType.DEFENSE,
                rarity = 4, description = "测试功法",
                stats = mapOf("cultivationSpeedPercent" to 40, "mp" to 20)
            ),
            "testMind1" to ManualDatabase.ManualTemplate(
                id = "testMind1", name = "静心诀", type = ManualType.MIND,
                rarity = 4, description = "测试功法",
                stats = mapOf("cultivationSpeedPercent" to 40)
            )
        ))
    }

    private fun createState(): MutableGameState {
        val tables = DiscipleTables()
        tables.writeAllowed = true
        return MutableGameState(
            gameData = GameData(),
            discipleTables = tables,
            equipmentStacks = EntityStore(),
            equipmentInstances = EntityStore(),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(),
            pills = EntityStore(),
            materials = EntityStore(),
            herbs = EntityStore(),
            seeds = EntityStore(),
            storageBags = EntityStore(),
            teams = emptyList(),
            battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )
    }

    /** 构造带装备/功法的俘虏（AI 侧持久化语义：模板 id + manualMasteries） */
    private fun makeCaptive(): Disciple = Disciple(
        id = "captive_1",
        name = "俘虏弟子",
        realm = 5,
        realmLayer = 2,
        spiritRootType = "金",
        manualIds = listOf("testAtk1", "testDef1", "testMind1"),
        manualMasteries = mapOf(
            "testAtk1" to 5000,
            "testDef1" to 15000,
            "testMind1" to 0
        ),
        equipment = EquipmentSet(
            weaponId = "ironSword",
            armorId = "leatherArmor",
            weaponNurture = EquipmentNurtureData(
                equipmentId = "ironSword", rarity = 1,
                nurtureLevel = 3, nurtureProgress = 0.5
            )
        )
    )

    @Test
    fun `materializeCaptiveGear - 装备功法落库为玩家实例`() {
        val state = createState()
        val captive = makeCaptive()
        val newId = state.discipleTables.allocateAndInsert(captive)
        val intId = newId.toIntOrNull()!!

        state.materializeCaptiveGear(captive, newId)

        // 1. 装备实例：2 件（ironSword/leatherArmor），UUID id、ownerId、isEquipped
        assertEquals("应创建 2 件装备实例", 2, state.equipmentInstances.size)
        val weaponInstance = state.equipmentInstances
            .firstOrNull { it.name == "精铁剑" }!!
        assertNotEquals("实例 id 应为 UUID 而非模板 id", "ironSword", weaponInstance.id)
        assertEquals("ownerId 应为新弟子 id", newId, weaponInstance.ownerId)
        assertTrue("应标记为已装备", weaponInstance.isEquipped)
        assertEquals("孕养等级应继承", 3, weaponInstance.nurtureLevel)
        assertEquals("孕养进度应继承", 0.5, weaponInstance.nurtureProgress, 0.001)
        // 2. 槽位列回写实例 id
        assertEquals("weaponIds 列应回写实例 id", weaponInstance.id, state.discipleTables.weaponIds[intId])
        assertNotEquals("armorIds 列应回写实例 id", "leatherArmor", state.discipleTables.armorIds[intId])
        assertTrue("armorIds 列应非空", state.discipleTables.armorIds[intId].isNotEmpty())
        // 3. 功法实例：3 本，isLearned
        assertEquals("应创建 3 本功法实例", 3, state.manualInstances.size)
        val atkInstance = state.manualInstances
            .firstOrNull { it.name == "烈阳剑诀" }!!
        assertTrue("应标记为已学习", atkInstance.isLearned)
        // 4. manualIds 列全量回写为实例 id（无模板 id 残留）
        val manualIdList = state.discipleTables.manualIds[intId]
        assertEquals(3, manualIdList.size)
        assertEquals("manualIds 应全为实例 id", manualIdList.toSet(),
            state.manualInstances.toList().map { it.id }.toSet())
        // 5. 熟练度注册进 gameData.manualProficiencies（按新弟子 id）
        val proficiencies = state.gameData.manualProficiencies[newId]
        assertNotNull("应注册 manualProficiencies", proficiencies)
        assertEquals(3, proficiencies!!.size)
        val atkProf = proficiencies.first { it.manualId == atkInstance.id }
        assertEquals("熟练度值应继承", 5000.0, atkProf.proficiency, 0.001)
    }

    @Test
    fun `materializeCaptiveGear - HP和MP增量`() {
        val state = createState()
        val captive = makeCaptive()
        val newId = state.discipleTables.allocateAndInsert(captive)
        val intId = newId.toIntOrNull()!!
        state.discipleTables.currentHps[intId] = 500
        state.discipleTables.currentMps[intId] = 400

        state.materializeCaptiveGear(captive, newId)

        // 烈阳剑诀 +30 HP，玄龟甲功 +20 MP
        assertEquals("HP 应增加功法增量", 530, state.discipleTables.currentHps[intId])
        assertEquals("MP 应增加功法增量", 420, state.discipleTables.currentMps[intId])
    }

    @Test
    fun `materializeCaptiveGear - 幂等`() {
        val state = createState()
        val captive = makeCaptive()
        val newId = state.discipleTables.allocateAndInsert(captive)

        state.materializeCaptiveGear(captive, newId)
        state.materializeCaptiveGear(captive, newId)

        assertEquals("重复调用不应重复创建装备实例", 2, state.equipmentInstances.size)
        assertEquals("重复调用不应重复创建功法实例", 3, state.manualInstances.size)
    }

    @Test
    fun `materializeCaptiveGear - 无装备功法的普通弟子跳过`() {
        val state = createState()
        val plain = Disciple(id = "plain_1", name = "普通弟子", realm = 9, spiritRootType = "金")
        val newId = state.discipleTables.allocateAndInsert(plain)

        state.materializeCaptiveGear(plain, newId)

        assertEquals("普通弟子不应创建装备实例", 0, state.equipmentInstances.size)
        assertEquals("普通弟子不应创建功法实例", 0, state.manualInstances.size)
        assertTrue("不应注册 manualProficiencies",
            state.gameData.manualProficiencies[newId].isNullOrEmpty())
    }
}
