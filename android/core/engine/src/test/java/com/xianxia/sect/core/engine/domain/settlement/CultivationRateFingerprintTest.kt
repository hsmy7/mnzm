package com.xianxia.sect.core.engine.domain.settlement

import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 CultivationRateFingerprint 能检测影响修炼速率的所有变化维度：
 * - 弟子增删 (aliveDiscipleIdsHash)
 * - 境界变化 (realmHash)
 * - 丹药/丧亲/功法 (perDiscipleHash)
 */
class CultivationRateFingerprintTest {

    @Test
    fun `fingerprints with different aliveDiscipleIdsHash are not equal`() {
        val fp1 = fp(aliveDiscipleIdsHash = hashOf(1, 2, 3))
        val fp2 = fp(aliveDiscipleIdsHash = hashOf(1, 2, 3, 4))
        assertNotEquals("新增弟子后指纹必须不同", fp1, fp2)
    }

    @Test
    fun `fingerprints with same fields are equal`() {
        val fp1 = fp()
        val fp2 = fp()
        assertEquals("相同指纹必须相等", fp1, fp2)
    }

    @Test
    fun `fingerprints differ when disciple list changes but structure stays same`() {
        val before = fp(aliveDiscipleIdsHash = hashOf(1, 2, 3))
        val after = fp(aliveDiscipleIdsHash = hashOf(1, 2, 3, 4))
        assertNotEquals("仅弟子列表变化时指纹必须不同", before, after)
    }

    @Test
    fun `fingerprints differ when disciple removed`() {
        val before = fp(aliveDiscipleIdsHash = hashOf(1, 2, 3))
        val after = fp(aliveDiscipleIdsHash = hashOf(1, 3))
        assertNotEquals("弟子移除后指纹必须不同", before, after)
    }

    @Test
    fun `fingerprints differ when realm changes`() {
        val before = fp(realmHash = hashOf(0, 0, 1))
        val after = fp(realmHash = hashOf(0, 1, 1)) // 弟子2突破
        assertNotEquals("弟子突破后境界分布变化，指纹必须不同", before, after)
    }

    @Test
    fun `fingerprints differ when pill effect or grief state changes`() {
        // 丹药效果开始/结束/丧亲状态变化 → perDiscipleHash 不同
        val before = fp(perDiscipleHash = 100)
        val after = fp(perDiscipleHash = 200)
        assertNotEquals("丹药/丧亲/功法状态变化时指纹必须不同", before, after)
    }

    @Test
    fun `fingerprints differ when any dimension changes`() {
        // 任一维度变化都应导致指纹不同
        val base = fp(residenceLayout = 100)
        assertNotEquals("住所布局变化", base, fp(residenceLayout = 200))
        assertNotEquals("长老分配变化", base, fp(elderAssignments = 999))
        assertNotEquals("政策变化", base, fp(policyFlags = 777))
        assertNotEquals("境界变化", base, fp(realmHash = 555))
        assertNotEquals("逐弟子状态变化", base, fp(perDiscipleHash = 333))
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private fun fp(
        residenceLayout: Int = 0,
        elderAssignments: Int = 0,
        preachingAssignments: Int = 0,
        policyFlags: Int = 0,
        aliveDiscipleIdsHash: Int = 0,
        realmHash: Int = 0,
        perDiscipleHash: Int = 0
    ) = CultivationRateFingerprint(
        residenceLayout = residenceLayout,
        elderAssignments = elderAssignments,
        preachingAssignments = preachingAssignments,
        policyFlags = policyFlags,
        aliveDiscipleIdsHash = aliveDiscipleIdsHash,
        realmHash = realmHash,
        perDiscipleHash = perDiscipleHash
    )

    private fun hashOf(vararg ids: Int): Int = ids.toList().hashCode()
}
