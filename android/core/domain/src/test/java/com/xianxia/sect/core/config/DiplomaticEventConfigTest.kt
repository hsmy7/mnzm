package com.xianxia.sect.core.config

import org.junit.Assert.*
import org.junit.Test

class DiplomaticEventConfigTest {

    @Test
    fun borderDispute_fields() {
        val e = DiplomaticEventConfig.Events.BORDER_DISPUTE
        assertEquals("border_dispute", e.id)
        assertEquals(-5, e.favorChange)
        assertFalse(e.isPositive)
    }

    @Test
    fun resourceConflict_fields() {
        val e = DiplomaticEventConfig.Events.RESOURCE_CONFLICT
        assertEquals(-8, e.favorChange)
        assertFalse(e.isPositive)
    }

    @Test
    fun discipleClash_fields() {
        val e = DiplomaticEventConfig.Events.DISCIPLE_CLASH
        assertEquals(-3, e.favorChange)
        assertFalse(e.isPositive)
    }

    @Test
    fun culturalExchange_fields() {
        val e = DiplomaticEventConfig.Events.CULTURAL_EXCHANGE
        assertEquals(3, e.favorChange)
        assertTrue(e.isPositive)
    }

    @Test
    fun jointExpedition_fields() {
        val e = DiplomaticEventConfig.Events.JOINT_EXPEDITION
        assertEquals(5, e.favorChange)
        assertTrue(e.isPositive)
    }

    @Test
    fun mutualAid_fields() {
        val e = DiplomaticEventConfig.Events.MUTUAL_AID
        assertEquals(8, e.favorChange)
        assertTrue(e.isPositive)
    }

    @Test
    fun allianceCooperation_fields() {
        val e = DiplomaticEventConfig.Events.ALLIANCE_COOPERATION
        assertEquals(2, e.favorChange)
        assertTrue(e.requiresAlliance)
    }

    @Test
    fun tradeBoom_fields() {
        val e = DiplomaticEventConfig.Events.TRADE_BOOM
        assertEquals(4, e.favorChange)
        assertTrue(e.isPositive)
    }

    @Test
    fun territorialEncroachment_fields() {
        val e = DiplomaticEventConfig.Events.TERRITORIAL_ENCROACHMENT
        assertEquals(-12, e.favorChange)
        assertFalse(e.isPositive)
    }

    @Test
    fun spyDiscovered_fields() {
        val e = DiplomaticEventConfig.Events.SPY_DISCOVERED
        assertEquals(-15, e.favorChange)
        assertFalse(e.isPositive)
    }

    @Test
    fun marriageAlliance_fields() {
        val e = DiplomaticEventConfig.Events.MARRIAGE_ALLIANCE
        assertEquals(15, e.favorChange)
        assertTrue(e.isPositive)
    }

    @Test
    fun sameAlignmentBond_fields() {
        val e = DiplomaticEventConfig.Events.SAME_ALIGNMENT_BOND
        assertTrue(e.requiresSameAlignment)
    }

    @Test
    fun opposingAlignmentClash_fields() {
        val e = DiplomaticEventConfig.Events.OPPOSING_ALIGNMENT_CLASH
        assertTrue(e.requiresOpposingAlignment)
    }

    @Test
    fun playerDiscipleEncounter_fields() {
        val e = DiplomaticEventConfig.Events.PLAYER_DISCIPLE_ENCOUNTER
        assertTrue(e.requiresPlayer)
    }

    @Test
    fun playerEscortMission_fields() {
        val e = DiplomaticEventConfig.Events.PLAYER_ESCORT_MISSION
        assertTrue(e.requiresPlayer)
    }

    @Test
    fun playerInsultIncident_fields() {
        val e = DiplomaticEventConfig.Events.PLAYER_INSULT_INCIDENT
        assertTrue(e.requiresPlayer)
        assertFalse(e.isPositive)
    }

    @Test
    fun allEvents_haveNonBlankIdNameDescription() {
        val allEvents = listOf(
            DiplomaticEventConfig.Events.BORDER_DISPUTE,
            DiplomaticEventConfig.Events.RESOURCE_CONFLICT,
            DiplomaticEventConfig.Events.DISCIPLE_CLASH,
            DiplomaticEventConfig.Events.CULTURAL_EXCHANGE,
            DiplomaticEventConfig.Events.JOINT_EXPEDITION,
            DiplomaticEventConfig.Events.MUTUAL_AID,
            DiplomaticEventConfig.Events.ALLIANCE_COOPERATION,
            DiplomaticEventConfig.Events.TRADE_BOOM,
            DiplomaticEventConfig.Events.TERRITORIAL_ENCROACHMENT,
            DiplomaticEventConfig.Events.SPY_DISCOVERED,
            DiplomaticEventConfig.Events.MARRIAGE_ALLIANCE,
            DiplomaticEventConfig.Events.SAME_ALIGNMENT_BOND,
            DiplomaticEventConfig.Events.OPPOSING_ALIGNMENT_CLASH,
            DiplomaticEventConfig.Events.PLAYER_DISCIPLE_ENCOUNTER,
            DiplomaticEventConfig.Events.PLAYER_ESCORT_MISSION,
            DiplomaticEventConfig.Events.PLAYER_INSULT_INCIDENT
        )
        for (e in allEvents) {
            assertTrue("Event ${e.id} has blank id", e.id.isNotBlank())
            assertTrue("Event ${e.id} has blank name", e.name.isNotBlank())
            assertTrue("Event ${e.id} has blank description", e.description.isNotBlank())
        }
    }

    @Test
    fun negativeEvents_haveIsPositiveFalse_positiveEventsHaveIsPositiveTrue() {
        val allEvents = listOf(
            DiplomaticEventConfig.Events.BORDER_DISPUTE,
            DiplomaticEventConfig.Events.RESOURCE_CONFLICT,
            DiplomaticEventConfig.Events.DISCIPLE_CLASH,
            DiplomaticEventConfig.Events.CULTURAL_EXCHANGE,
            DiplomaticEventConfig.Events.JOINT_EXPEDITION,
            DiplomaticEventConfig.Events.MUTUAL_AID,
            DiplomaticEventConfig.Events.ALLIANCE_COOPERATION,
            DiplomaticEventConfig.Events.TRADE_BOOM,
            DiplomaticEventConfig.Events.TERRITORIAL_ENCROACHMENT,
            DiplomaticEventConfig.Events.SPY_DISCOVERED,
            DiplomaticEventConfig.Events.MARRIAGE_ALLIANCE,
            DiplomaticEventConfig.Events.SAME_ALIGNMENT_BOND,
            DiplomaticEventConfig.Events.OPPOSING_ALIGNMENT_CLASH,
            DiplomaticEventConfig.Events.PLAYER_DISCIPLE_ENCOUNTER,
            DiplomaticEventConfig.Events.PLAYER_ESCORT_MISSION,
            DiplomaticEventConfig.Events.PLAYER_INSULT_INCIDENT
        )
        for (e in allEvents) {
            if (e.favorChange < 0) {
                assertFalse("Event ${e.id} has negative favorChange but isPositive=true", e.isPositive)
            } else {
                assertTrue("Event ${e.id} has non-negative favorChange but isPositive=false", e.isPositive)
            }
        }
    }

    @Test
    fun allEventIds_areUnique() {
        val allEvents = listOf(
            DiplomaticEventConfig.Events.BORDER_DISPUTE,
            DiplomaticEventConfig.Events.RESOURCE_CONFLICT,
            DiplomaticEventConfig.Events.DISCIPLE_CLASH,
            DiplomaticEventConfig.Events.CULTURAL_EXCHANGE,
            DiplomaticEventConfig.Events.JOINT_EXPEDITION,
            DiplomaticEventConfig.Events.MUTUAL_AID,
            DiplomaticEventConfig.Events.ALLIANCE_COOPERATION,
            DiplomaticEventConfig.Events.TRADE_BOOM,
            DiplomaticEventConfig.Events.TERRITORIAL_ENCROACHMENT,
            DiplomaticEventConfig.Events.SPY_DISCOVERED,
            DiplomaticEventConfig.Events.MARRIAGE_ALLIANCE,
            DiplomaticEventConfig.Events.SAME_ALIGNMENT_BOND,
            DiplomaticEventConfig.Events.OPPOSING_ALIGNMENT_CLASH,
            DiplomaticEventConfig.Events.PLAYER_DISCIPLE_ENCOUNTER,
            DiplomaticEventConfig.Events.PLAYER_ESCORT_MISSION,
            DiplomaticEventConfig.Events.PLAYER_INSULT_INCIDENT
        )
        val ids = allEvents.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
