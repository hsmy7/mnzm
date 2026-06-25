package com.xianxia.sect.ui.game

import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleAttributes
import com.xianxia.sect.core.model.DiscipleCore
import com.xianxia.sect.core.model.DiscipleExtended
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscipleFilterUtilsTest {

    // -- 测试夹具 ----------------------------------------------------------

    private fun createAggregate(
        id: String = "d1",
        realm: Int = 9,
        realmLayer: Int = 1,
        isFollowed: Boolean = false,
        spiritRootType: String = "metal",
        comprehension: Int = 50,
        intelligence: Int = 50,
        pillRefining: Int = 50,
        mining: Int = 50,
        morality: Int = 50
    ): DiscipleAggregate {
        return DiscipleAggregate(
            core = DiscipleCore(
                id = id,
                realm = realm,
                realmLayer = realmLayer,
                spiritRootType = spiritRootType
            ),
            combatStats = null,
            equipment = null,
            extended = if (isFollowed) {
                DiscipleExtended(
                    discipleId = id,
                    statusData = mapOf("followed" to "true")
                )
            } else {
                DiscipleExtended(discipleId = id)
            },
            attributes = DiscipleAttributes(
                discipleId = id,
                comprehension = comprehension,
                intelligence = intelligence,
                pillRefining = pillRefining,
                mining = mining,
                morality = morality
            )
        )
    }

    // -- 排序：无任何筛选（已关注优先 → 境界 → 境界层）--------------------

    @Test
    fun noFilters_noDefaultSort_followedFirstThenRealmAsc() {
        val followed = createAggregate(
            id = "f", realm = 9, isFollowed = true
        )
        val unfollowed = createAggregate(id = "u", realm = 5)
        val result = listOf(unfollowed, followed)
            .applyFilters(emptySet(), emptySet(), null)
        assertEquals("f", result[0].id)
        assertEquals("u", result[1].id)
    }

    @Test
    fun noFilters_noDefaultSort_lowerRealmFirst() {
        val realm9 = createAggregate(id = "r9", realm = 9)
        val realm5 = createAggregate(id = "r5", realm = 5)
        val result = listOf(realm9, realm5)
            .applyFilters(emptySet(), emptySet(), null)
        assertEquals("r5", result[0].id)
        assertEquals("r9", result[1].id)
    }

    @Test
    fun noFilters_noDefaultSort_sameRealm_higherLayerFirst() {
        val layer1 = createAggregate(
            id = "l1", realm = 9, realmLayer = 1
        )
        val layer9 = createAggregate(
            id = "l9", realm = 9, realmLayer = 9
        )
        val result = listOf(layer1, layer9)
            .applyFilters(emptySet(), emptySet(), null)
        assertEquals("l9", result[0].id)
        assertEquals("l1", result[1].id)
    }

    // -- 排序：无筛选 + 推荐属性（已关注 → 推荐属性 → 境界）----------------

    @Test
    fun noFilters_withDefaultSort_followedFirstThenAttributeThenRealm() {
        val followedLowAttr = createAggregate(
            id = "fLow", realm = 9, isFollowed = true, pillRefining = 10
        )
        val unfollowedHighAttr = createAggregate(
            id = "uHigh", realm = 5, pillRefining = 99
        )
        val followedHighAttr = createAggregate(
            id = "fHigh", realm = 9, isFollowed = true, pillRefining = 80
        )
        val result = listOf(unfollowedHighAttr, followedLowAttr, followedHighAttr)
            .applyFilters(emptySet(), emptySet(), null, "pillRefining")
        assertEquals("fHigh", result[0].id)
        assertEquals("fLow", result[1].id)
        assertEquals("uHigh", result[2].id)
    }

    // -- 排序：属性排序模式（属性↓ → 境界↑ → 境界层↓ → 灵根数↑）--------

    @Test
    fun attributeSort_descendingAttribute_thenRealm_thenLayer_thenSpiritRootCount() {
        val single = createAggregate(
            id = "s", realm = 5, pillRefining = 90,
            spiritRootType = "metal"
        )
        val dual = createAggregate(
            id = "d", realm = 5, pillRefining = 90,
            spiritRootType = "metal,water"
        )
        val sameAttrHigherRealm = createAggregate(
            id = "h", realm = 9, pillRefining = 90
        )
        val result = listOf(sameAttrHigherRealm, single, dual)
            .applyFilters(emptySet(), emptySet(), "pillRefining")
        assertEquals("s", result[0].id)
        assertEquals("d", result[1].id)
        assertEquals("h", result[2].id)
    }

    @Test
    fun attributeSort_sameRealmSameAttr_higherLayerFirst() {
        val layer1 = createAggregate(
            id = "l1", realm = 5, realmLayer = 1, pillRefining = 80
        )
        val layer9 = createAggregate(
            id = "l9", realm = 5, realmLayer = 9, pillRefining = 80
        )
        val higherRealm = createAggregate(
            id = "hr", realm = 3, pillRefining = 80
        )
        val result = listOf(layer1, layer9, higherRealm)
            .applyFilters(emptySet(), emptySet(), "pillRefining")
        assertEquals("hr", result[0].id)
        assertEquals("l9", result[1].id)
        assertEquals("l1", result[2].id)
    }

    // -- 排序：有筛选无属性排序（境界↑ → 境界层↓ → 灵根数↑）--------------

    @Test
    fun realmFilter_sortsByRealmThenLayerThenSpiritRoot() {
        val realm9Layer1 = createAggregate(
            id = "r9l1", realm = 9, realmLayer = 1
        )
        val realm9Layer9 = createAggregate(
            id = "r9l9", realm = 9, realmLayer = 9
        )
        val realm9Layer9Dual = createAggregate(
            id = "r9l9d", realm = 9, realmLayer = 9,
            spiritRootType = "metal,water"
        )
        val realm5Followed = createAggregate(
            id = "r5f", realm = 5, isFollowed = true
        )
        val realm3 = createAggregate(id = "r3", realm = 3)
        val result = listOf(
            realm9Layer1, realm3, realm9Layer9, realm5Followed, realm9Layer9Dual
        ).applyFilters(setOf(9, 5, 3), emptySet(), null)
        assertEquals("r3", result[0].id)
        assertEquals("r5f", result[1].id)
        assertEquals("r9l9", result[2].id)
        assertEquals("r9l9d", result[3].id)
        assertEquals("r9l1", result[4].id)
    }

    @Test
    fun spiritRootFilter_sortsByRealmThenLayerThenSpiritRootCount() {
        val singleHighRealm = createAggregate(
            id = "sh", realm = 3, spiritRootType = "metal"
        )
        val dualLowRealm = createAggregate(
            id = "dl", realm = 9, spiritRootType = "metal,water"
        )
        val result = listOf(dualLowRealm, singleHighRealm)
            .applyFilters(emptySet(), setOf(1, 2), null)
        assertEquals("sh", result[0].id)
        assertEquals("dl", result[1].id)
    }

    @Test
    fun bothRealmAndSpiritRootFilter_noAttributeSort_sortsByRealm() {
        val realm3Single = createAggregate(
            id = "r3s", realm = 3, spiritRootType = "metal"
        )
        val realm3Dual = createAggregate(
            id = "r3d", realm = 3, spiritRootType = "metal,water"
        )
        val realm9Single = createAggregate(
            id = "r9s", realm = 9, spiritRootType = "metal"
        )
        val result = listOf(realm9Single, realm3Dual, realm3Single)
            .applyFilters(setOf(3, 9), setOf(1, 2), null)
        assertEquals(3, result.size)
        assertEquals("r3s", result[0].id)
        assertEquals("r3d", result[1].id)
        assertEquals("r9s", result[2].id)
    }

    // -- 过滤：排除不匹配的弟子 --------------------------------------------

    @Test
    fun realmFilter_filtersOutNonMatching() {
        val realm5 = createAggregate(id = "r5", realm = 5)
        val realm9 = createAggregate(id = "r9", realm = 9)
        val result = listOf(realm5, realm9)
            .applyFilters(setOf(5), emptySet(), null)
        assertEquals(1, result.size)
        assertEquals("r5", result[0].id)
    }

    @Test
    fun spiritRootFilter_filtersOutNonMatching() {
        val single = createAggregate(
            id = "s", spiritRootType = "metal"
        )
        val dual = createAggregate(
            id = "d", spiritRootType = "metal,water"
        )
        val triple = createAggregate(
            id = "t", spiritRootType = "metal,water,fire"
        )
        val result = listOf(single, dual, triple)
            .applyFilters(emptySet(), setOf(1), null)
        assertEquals(1, result.size)
        assertEquals("s", result[0].id)
    }

    // -- 组合：属性排序 + 过滤 --------------------------------------------

    @Test
    fun realmWithAttributeSort_attributePriority() {
        val lowAttr = createAggregate(
            id = "low", realm = 3, comprehension = 10
        )
        val highAttr = createAggregate(
            id = "high", realm = 9, comprehension = 90
        )
        val result = listOf(lowAttr, highAttr)
            .applyFilters(setOf(3, 9), emptySet(), "comprehension")
        assertEquals("high", result[0].id)
        assertEquals("low", result[1].id)
    }

    @Test
    fun allThreeFilters_attributePriority_thenFilterAll() {
        val target = createAggregate(
            id = "t", realm = 5, comprehension = 90,
            spiritRootType = "metal"
        )
        val wrongRealm = createAggregate(
            id = "wr", realm = 9, comprehension = 80,
            spiritRootType = "metal"
        )
        val lowAttr = createAggregate(
            id = "la", realm = 5, comprehension = 70,
            spiritRootType = "metal"
        )
        val result = listOf(target, wrongRealm, lowAttr)
            .applyFilters(setOf(5), setOf(1), "comprehension")
        assertEquals(2, result.size)
        assertEquals("t", result[0].id)
        assertEquals("la", result[1].id)
    }

    // -- 边界条件 ----------------------------------------------------------

    @Test
    fun emptyList_noFilters_returnsEmpty() {
        val result = emptyList<DiscipleAggregate>()
            .applyFilters(emptySet(), emptySet(), null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun emptyList_withFilters_returnsEmpty() {
        val result = emptyList<DiscipleAggregate>()
            .applyFilters(setOf(5), setOf(1), "comprehension")
        assertTrue(result.isEmpty())
    }

    @Test
    fun defaultSortAttribute_ignoredWhenRealmFilterActive() {
        val followedHighAttr = createAggregate(
            id = "fHigh", realm = 9, isFollowed = true, pillRefining = 99
        )
        val unfollowedLowAttr = createAggregate(
            id = "uLow", realm = 3, pillRefining = 10
        )
        val result = listOf(followedHighAttr, unfollowedLowAttr)
            .applyFilters(setOf(3, 9), emptySet(), null, "pillRefining")
        assertEquals("uLow", result[0].id)
        assertEquals("fHigh", result[1].id)
    }

    // -- 辅助函数 ----------------------------------------------------------

    @Test
    fun getAttributeValue_unknownKey_returnsZero() {
        val d = createAggregate(id = "d1", comprehension = 80)
        assertEquals(0, d.getAttributeValue("nonexistent"))
    }
}
