package com.xianxia.sect.core.engine.domain.settlement

import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 CultivationRateFingerprint 的 aliveDiscipleIdsHash 字段能正确区
 * 分不同弟子列表，确保招募新弟子后缓存指纹变化、触发缓存重建。
 *
 * 关联 Bug：跨月复用旧缓存导致新招募弟子永无月度修炼结算。
 * 根因：原指纹仅含宗门结构（住所、长老、政策），不包含弟子增删信息。
 * 修复：新增 aliveDiscipleIdsHash 字段，弟子增删时指纹必然不同。
 */
class CultivationRateFingerprintTest {

    @Test
    fun `fingerprints with different aliveDiscipleIdsHash are not equal`() {
        val fp1 = CultivationRateFingerprint(
            residenceLayout = 100,
            elderAssignments = 200,
            preachingAssignments = 300,
            policyFlags = 400,
            aliveDiscipleIdsHash = hashOf(1, 2, 3)
        )
        val fp2 = CultivationRateFingerprint(
            residenceLayout = 100,
            elderAssignments = 200,
            preachingAssignments = 300,
            policyFlags = 400,
            aliveDiscipleIdsHash = hashOf(1, 2, 3, 4) // 多一个弟子
        )

        assertNotEquals(
            "新增弟子后指纹必须不同，否则缓存不会重建",
            fp1, fp2
        )
        assertNotEquals(
            "hashCode 必须不同",
            fp1.hashCode(), fp2.hashCode()
        )
    }

    @Test
    fun `fingerprints with same fields are equal`() {
        val fp1 = CultivationRateFingerprint(
            residenceLayout = 100,
            elderAssignments = 200,
            preachingAssignments = 300,
            policyFlags = 400,
            aliveDiscipleIdsHash = hashOf(1, 2, 3)
        )
        val fp2 = CultivationRateFingerprint(
            residenceLayout = 100,
            elderAssignments = 200,
            preachingAssignments = 300,
            policyFlags = 400,
            aliveDiscipleIdsHash = hashOf(1, 2, 3) // 相同
        )

        assertEquals("相同弟子列表指纹必须相等", fp1, fp2)
        assertEquals("hashCode 必须相等", fp1.hashCode(), fp2.hashCode())
    }

    @Test
    fun `fingerprints differ when disciple list changes but structure stays same`() {
        // 模拟：宗门结构不变（住所、长老、政策完全相同），但弟子列表变化
        val beforeRecruit = CultivationRateFingerprint(
            residenceLayout = 555,
            elderAssignments = 666,
            preachingAssignments = 777,
            policyFlags = 888,
            aliveDiscipleIdsHash = hashOf(1, 2, 3)
        )
        val afterRecruit = CultivationRateFingerprint(
            residenceLayout = 555,    // 相同
            elderAssignments = 666,   // 相同
            preachingAssignments = 777, // 相同
            policyFlags = 888,        // 相同
            aliveDiscipleIdsHash = hashOf(1, 2, 3, 4) // 不同：新弟子加入
        )

        assertNotEquals(
            "仅弟子列表变化时指纹必须不同，触发缓存重建",
            beforeRecruit, afterRecruit
        )
    }

    @Test
    fun `fingerprints differ when disciple removed`() {
        val before = CultivationRateFingerprint(
            residenceLayout = 0,
            elderAssignments = 0,
            preachingAssignments = 0,
            policyFlags = 0,
            aliveDiscipleIdsHash = hashOf(1, 2, 3)
        )
        val after = CultivationRateFingerprint(
            residenceLayout = 0,
            elderAssignments = 0,
            preachingAssignments = 0,
            policyFlags = 0,
            aliveDiscipleIdsHash = hashOf(1, 3) // 弟子2被移除
        )

        assertNotEquals(
            "弟子移除后指纹必须不同",
            before, after
        )
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    /** 模拟 computeFingerprint 中使用的 hashCode() 计算 */
    private fun hashOf(vararg ids: Int): Int = ids.toList().hashCode()
}
