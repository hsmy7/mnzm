package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentNurtureData
import com.xianxia.sect.core.model.StorageBagItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P-3 子对象级 patch 组装等价性测试（2026-08-02）。
 *
 * 守卫：assembleAllPatched 与 assembleAll 在任何写列组合下逐字段等价——
 * 包括 per-phase 典型写列（cultivation/HP/MP/熟练度）、混合组写列、全组脏、
 * 未知列退化、幽灵/移除边界。patch 误用列→组映射（漏判脏组）会导致静默
 * 复用旧子对象，本测试以全字段 data class equals 捕获。
 *
 * 注意：Disciple.lifeEvents 是 class body var（非 data 字段），equals 不覆盖，
 * 需单独断言。
 */
@RunWith(RobolectricTestRunner::class)
class AssemblePatchEquivalenceTest {

    private lateinit var tables: DiscipleTables

    @Before
    fun setUp() {
        tables = DiscipleTables()
        tables.writeAllowed = true
        // 三表齐全避免幽灵防御干扰（isAlive + names + realms）
        for (i in 1..60) {
            tables.insert(Disciple(id = i.toString(), name = "弟子$i", realm = 5, realmLayer = 1))
            // 子对象数据填充（覆盖全部组装组）
            tables.comprehensions[i] = 60
            tables.currentHps[i] = 500
            tables.weaponIds[i] = "w$i"
            tables.weaponNurtures[i] = EquipmentNurtureData(equipmentId = "w$i", rarity = 3)
            tables.partnerIds[i] = "p$i"
            tables.usedPermanentPillKeys[i] = setOf("pk$i")
            tables.storageBagItems[i] = listOf(
                StorageBagItem(itemId = "bag$i", itemType = "pill", name = "丹", rarity = 2)
            )
        }
    }

    /** 断言 patch 与全量逐字段等价（data class equals 覆盖全部构造参数 + lifeEvents） */
    private fun assertPatchEquivalent(patch: List<Disciple>, full: List<Disciple>, label: String) {
        assertEquals("[$label] 弟子数量不一致", full.size, patch.size)
        assertEquals("[$label] id 顺序不一致", full.map { it.id }, patch.map { it.id })
        for (i in full.indices) {
            val f = full[i]
            val p = patch[i]
            assertEquals("[$label] id=${f.id} 对象不一致", f, p)
            assertEquals("[$label] id=${f.id} lifeEvents 不一致", f.lifeEvents, p.lifeEvents)
        }
    }

    private fun dirtyIndices(vararg columnNames: String): Set<Int> =
        columnNames.map { tables.columnIndexOf(it) }.toSet()

    @Test
    fun `per-phase 典型写列 - patch 与全量等价`() {
        tables.insert(Disciple(id = "101", name = "新弟子", realm = 5, realmLayer = 1))
        val prev = tables.assembleAll()

        // 每旬典型：修为累积 + HP/MP 恢复 + 熟练度 + 忠诚等高频列
        for (i in 1..60) {
            tables.cultivations[i] = tables.cultivations.getOrDefault(i, 0.0) + 100.0
            tables.currentHps[i] = tables.currentHps.getOrDefault(i, 500) - 50
            tables.comprehensions[i] = 70
        }
        tables.cultivations[101] = 50.0

        val changed = tables.changedIdTracker.consumeChangedIds()
        val dirty = dirtyIndices("cultivations", "currentHps", "comprehensions")
        val patch = tables.assembleAllPatched(prev, changed, dirty)
        val full = tables.assembleAll()
        assertPatchEquivalent(patch, full, "per-phase 典型写列")
    }

    @Test
    fun `混合组写列 - patch 与全量等价`() {
        val prev = tables.assembleAll()

        // 六个子对象组各写一列 + 本体列
        tables.intelligences[1] = 88
        tables.pillHpBonuses[2] = 100
        tables.armorNurtures[3] = EquipmentNurtureData(equipmentId = "a3", rarity = 5)
        tables.griefEndYears[4] = 1
        tables.salaryMissedCounts[5] = 3
        tables.hasReviveEffects[6] = 1
        tables.lifeEvents[7] = listOf("事件")
        tables.names[8] = "改名"

        val changed = tables.changedIdTracker.consumeChangedIds()
        val dirty = dirtyIndices(
            "intelligences", "pillHpBonuses", "armorNurtures", "griefEndYears",
            "salaryMissedCounts", "hasReviveEffects", "lifeEvents", "names"
        )
        val patch = tables.assembleAllPatched(prev, changed, dirty)
        val full = tables.assembleAll()
        assertPatchEquivalent(patch, full, "混合组写列")
    }

    @Test
    fun `全组脏 - patch 与全量等价`() {
        val prev = tables.assembleAll()

        // 全部 7 组都写（含本体）
        for (i in 1..3) {
            tables.currentHps[i] = 1
            tables.pillHpBonuses[i] = 2
            tables.weaponNurtures[i] = EquipmentNurtureData(equipmentId = "w$i", rarity = 4)
            tables.partnerIds[i] = "x$i"
            tables.loyalties[i] = 50
            tables.recruitedMonths[i] = 3
            tables.lifeEvents[i] = listOf("e$i")
            tables.cultivations[i] = 999.0
        }

        val changed = tables.changedIdTracker.consumeChangedIds()
        val dirty = dirtyIndices(
            "currentHps", "pillHpBonuses", "weaponNurtures", "partnerIds",
            "loyalties", "recruitedMonths", "lifeEvents", "cultivations"
        )
        val patch = tables.assembleAllPatched(prev, changed, dirty)
        val full = tables.assembleAll()
        assertPatchEquivalent(patch, full, "全组脏")
    }

    @Test
    fun `未知列退化全量 - 结果与全量等价`() {
        val prev = tables.assembleAll()
        tables.cultivations[1] = 123.0

        val changed = tables.changedIdTracker.consumeChangedIds()
        // 伪造越界列索引（未注册映射 → 整体退化）
        val patch = tables.assembleAllPatched(prev, changed, setOf(99999))
        val full = tables.assembleAll()
        assertPatchEquivalent(patch, full, "未知列退化")
    }

    @Test
    fun `空 changedIds 返回原列表`() {
        val prev = tables.assembleAll()
        val patch = tables.assembleAllPatched(prev, emptySet(), emptySet())
        assertTrue("空 changedIds 应返回原列表（引用相等）", patch === prev)
    }

    @Test
    fun `移除弟子 - patch 剔除陈尸`() {
        val prev = tables.assembleAll()
        tables.remove(1)
        tables.remove(2)

        val changed = tables.changedIdTracker.consumeChangedIds()
        val dirty = dirtyIndices("names")  // 任意脏列
        val patch = tables.assembleAllPatched(prev, changed, dirty)
        val full = tables.assembleAll()
        assertPatchEquivalent(patch, full, "移除弟子")
        assertTrue("移除后不应包含 id=1", patch.none { it.id == "1" })
        assertTrue("移除后不应包含 id=2", patch.none { it.id == "2" })
    }

    @Test
    fun `未变子对象引用复用 - 每旬典型场景`() {
        val prev = tables.assembleAll()
        // 每旬典型：仅 cultivation 变化（HP/MP 满血无写入）
        for (i in 1..60) {
            tables.cultivations[i] = tables.cultivations.getOrDefault(i, 0.0) + 50.0
        }

        val changed = tables.changedIdTracker.consumeChangedIds()
        val dirty = dirtyIndices("cultivations")
        val patch = tables.assembleAllPatched(prev, changed, dirty)
        val full = tables.assembleAll()
        assertPatchEquivalent(patch, full, "引用复用")

        // 子对象引用必须复用 prev（未脏组不重装）
        val prevById = prev.associateBy { it.id }
        val patchById = patch.associateBy { it.id }
        for (i in 1..60) {
            val pid = i.toString()
            val prevD = prevById[pid] ?: error("prev 缺 id=$pid")
            val patchD = patchById[pid] ?: error("patch 缺 id=$pid")
            assertTrue("id=$pid combat 未脏应复用引用", patchD.combat === prevD.combat)
            assertTrue("id=$pid pillEffects 未脏应复用引用", patchD.pillEffects === prevD.pillEffects)
            assertTrue("id=$pid equipment 未脏应复用引用", patchD.equipment === prevD.equipment)
            assertTrue("id=$pid social 未脏应复用引用", patchD.social === prevD.social)
            assertTrue("id=$pid skills 未脏应复用引用", patchD.skills === prevD.skills)
            assertTrue("id=$pid usage 未脏应复用引用", patchD.usage === prevD.usage)
            assertTrue("id=$pid lifeEvents 未脏应复用引用", patchD.lifeEvents === prevD.lifeEvents)
        }
    }
}
