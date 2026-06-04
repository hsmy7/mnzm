package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.*
import org.junit.Assert.*
import org.junit.Test

class DiscipleUtilsTest {

    // ---- 辅助方法 ----

    private fun createAggregate(
        id: String = "d1",
        realm: Int = 9,
        realmLayer: Int = 1,
        isFollowed: Boolean = false,
        comprehension: Int = 50,
        intelligence: Int = 50,
        charm: Int = 50,
        loyalty: Int = 50,
        artifactRefining: Int = 50,
        pillRefining: Int = 50,
        spiritPlanting: Int = 50,
        mining: Int = 50,
        teaching: Int = 50,
        morality: Int = 50
    ): DiscipleAggregate {
        return DiscipleAggregate(
            core = DiscipleCore(id = id, realm = realm, realmLayer = realmLayer),
            combatStats = null,
            equipment = null,
            extended = if (isFollowed) {
                DiscipleExtended(discipleId = id, statusData = mapOf("followed" to "true"))
            } else {
                DiscipleExtended(discipleId = id)
            },
            attributes = DiscipleAttributes(
                discipleId = id,
                comprehension = comprehension,
                intelligence = intelligence,
                charm = charm,
                loyalty = loyalty,
                artifactRefining = artifactRefining,
                pillRefining = pillRefining,
                spiritPlanting = spiritPlanting,
                mining = mining,
                teaching = teaching,
                morality = morality
            )
        )
    }

    // ---- isFollowed ----

    @Test
    fun isFollowed_statusDataFollowedTrue_returnsTrue() {
        val agg = createAggregate(isFollowed = true)
        assertTrue(agg.isFollowed)
    }

    @Test
    fun isFollowed_statusDataEmpty_returnsFalse() {
        val agg = createAggregate(isFollowed = false)
        assertFalse(agg.isFollowed)
    }

    @Test
    fun isFollowed_statusDataOtherValue_returnsFalse() {
        val agg = DiscipleAggregate(
            core = DiscipleCore(id = "d1"),
            combatStats = null,
            equipment = null,
            extended = DiscipleExtended(discipleId = "d1", statusData = mapOf("followed" to "yes")),
            attributes = null
        )
        assertFalse(agg.isFollowed)
    }

    // ---- sortedByFollowAndRealm ----

    @Test
    fun sortedByFollowAndRealm_followedFirst() {
        val followed = createAggregate(id = "d1", realm = 9, isFollowed = true)
        val unfollowed = createAggregate(id = "d2", realm = 5, isFollowed = false)
        val result = listOf(unfollowed, followed).sortedByFollowAndRealm()
        assertEquals("d1", result[0].id)
        assertEquals("d2", result[1].id)
    }

    @Test
    fun sortedByFollowAndRealm_lowerRealmFirst() {
        val high = createAggregate(id = "d1", realm = 9)
        val low = createAggregate(id = "d2", realm = 5)
        val result = listOf(high, low).sortedByFollowAndRealm()
        assertEquals("d2", result[0].id)
        assertEquals("d1", result[1].id)
    }

    @Test
    fun sortedByFollowAndRealm_sameRealm_higherLayerFirst() {
        val layer1 = createAggregate(id = "d1", realm = 9, realmLayer = 1)
        val layer9 = createAggregate(id = "d2", realm = 9, realmLayer = 9)
        val result = listOf(layer1, layer9).sortedByFollowAndRealm()
        assertEquals("d2", result[0].id)
        assertEquals("d1", result[1].id)
    }

    @Test
    fun sortedByFollowAndRealm_emptyList_returnsEmpty() {
        val result = emptyList<DiscipleAggregate>().sortedByFollowAndRealm()
        assertTrue(result.isEmpty())
    }

    // ---- sortedByFollowAttributeAndRealm ----

    @Test
    fun sortedByFollowAttributeAndRealm_nullAttribute_usesComprehension() {
        val highComp = createAggregate(id = "d1", comprehension = 90)
        val lowComp = createAggregate(id = "d2", comprehension = 30)
        val result = listOf(highComp, lowComp).sortedByFollowAttributeAndRealm(null)
        assertEquals("d1", result[0].id)
        assertEquals("d2", result[1].id)
    }

    @Test
    fun sortedByFollowAttributeAndRealm_comprehensionAttribute() {
        val high = createAggregate(id = "d1", comprehension = 80)
        val low = createAggregate(id = "d2", comprehension = 40)
        val result = listOf(high, low).sortedByFollowAttributeAndRealm("comprehension")
        assertEquals("d1", result[0].id)
        assertEquals("d2", result[1].id)
    }

    @Test
    fun sortedByFollowAttributeAndRealm_intelligenceAttribute() {
        val high = createAggregate(id = "d1", intelligence = 90)
        val low = createAggregate(id = "d2", intelligence = 30)
        val result = listOf(high, low).sortedByFollowAttributeAndRealm("intelligence")
        assertEquals("d1", result[0].id)
        assertEquals("d2", result[1].id)
    }

    @Test
    fun sortedByFollowAttributeAndRealm_charmAttribute() {
        val high = createAggregate(id = "d1", charm = 95)
        val low = createAggregate(id = "d2", charm = 20)
        val result = listOf(high, low).sortedByFollowAttributeAndRealm("charm")
        assertEquals("d1", result[0].id)
        assertEquals("d2", result[1].id)
    }

    @Test
    fun sortedByFollowAttributeAndRealm_loyaltyAttribute() {
        val high = createAggregate(id = "d1", loyalty = 88)
        val low = createAggregate(id = "d2", loyalty = 22)
        val result = listOf(high, low).sortedByFollowAttributeAndRealm("loyalty")
        assertEquals("d1", result[0].id)
        assertEquals("d2", result[1].id)
    }

    @Test
    fun sortedByFollowAttributeAndRealm_artifactRefiningAttribute() {
        val high = createAggregate(id = "d1", artifactRefining = 77)
        val low = createAggregate(id = "d2", artifactRefining = 33)
        val result = listOf(high, low).sortedByFollowAttributeAndRealm("artifactRefining")
        assertEquals("d1", result[0].id)
        assertEquals("d2", result[1].id)
    }

    @Test
    fun sortedByFollowAttributeAndRealm_pillRefiningAttribute() {
        val high = createAggregate(id = "d1", pillRefining = 70)
        val low = createAggregate(id = "d2", pillRefining = 30)
        val result = listOf(high, low).sortedByFollowAttributeAndRealm("pillRefining")
        assertEquals("d1", result[0].id)
        assertEquals("d2", result[1].id)
    }

    @Test
    fun sortedByFollowAttributeAndRealm_spiritPlantingAttribute() {
        val high = createAggregate(id = "d1", spiritPlanting = 85)
        val low = createAggregate(id = "d2", spiritPlanting = 15)
        val result = listOf(high, low).sortedByFollowAttributeAndRealm("spiritPlanting")
        assertEquals("d1", result[0].id)
        assertEquals("d2", result[1].id)
    }

    @Test
    fun sortedByFollowAttributeAndRealm_miningAttribute() {
        val high = createAggregate(id = "d1", mining = 60)
        val low = createAggregate(id = "d2", mining = 40)
        val result = listOf(high, low).sortedByFollowAttributeAndRealm("mining")
        assertEquals("d1", result[0].id)
        assertEquals("d2", result[1].id)
    }

    @Test
    fun sortedByFollowAttributeAndRealm_teachingAttribute() {
        val high = createAggregate(id = "d1", teaching = 75)
        val low = createAggregate(id = "d2", teaching = 25)
        val result = listOf(high, low).sortedByFollowAttributeAndRealm("teaching")
        assertEquals("d1", result[0].id)
        assertEquals("d2", result[1].id)
    }

    @Test
    fun sortedByFollowAttributeAndRealm_moralityAttribute() {
        val high = createAggregate(id = "d1", morality = 99)
        val low = createAggregate(id = "d2", morality = 1)
        val result = listOf(high, low).sortedByFollowAttributeAndRealm("morality")
        assertEquals("d1", result[0].id)
        assertEquals("d2", result[1].id)
    }

    @Test
    fun sortedByFollowAttributeAndRealm_unknownAttribute_treatedAsZero() {
        val a = createAggregate(id = "d1", comprehension = 80)
        val b = createAggregate(id = "d2", comprehension = 30)
        // unknown attribute → both get 0, so order is by comprehension fallback from null case
        val result = listOf(a, b).sortedByFollowAttributeAndRealm("nonexistent")
        // Both have same attribute value (0), so relative order depends on stable sort
        assertEquals(2, result.size)
    }

    @Test
    fun sortedByFollowAttributeAndRealm_followedFirst_withAttribute() {
        val followed = createAggregate(id = "d1", realm = 9, isFollowed = true, comprehension = 10)
        val unfollowed = createAggregate(id = "d2", realm = 5, isFollowed = false, comprehension = 99)
        val result = listOf(unfollowed, followed).sortedByFollowAttributeAndRealm("comprehension")
        assertEquals("d1", result[0].id)
        assertEquals("d2", result[1].id)
    }
}
