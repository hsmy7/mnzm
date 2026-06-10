package com.xianxia.sect.core.config

import org.junit.Assert.*
import org.junit.Test

class DiplomaticEventConfigTest {

    @Test
    fun monthlyTriggerChance_is001() {
        assertEquals(0.01, DiplomaticEventConfig.MONTHLY_TRIGGER_CHANCE, 0.0001)
    }

    @Test
    fun allEvents_has16Events() {
        assertEquals(16, DiplomaticEventConfig.Events.ALL_EVENTS.size)
    }

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
        for (e in DiplomaticEventConfig.Events.ALL_EVENTS) {
            assertTrue("Event ${e.id} has blank id", e.id.isNotBlank())
            assertTrue("Event ${e.id} has blank name", e.name.isNotBlank())
            assertTrue("Event ${e.id} has blank description", e.description.isNotBlank())
        }
    }

    @Test
    fun negativeEvents_haveIsPositiveFalse_positiveEventsHaveIsPositiveTrue() {
        for (e in DiplomaticEventConfig.Events.ALL_EVENTS) {
            if (e.favorChange < 0) {
                assertFalse("Event ${e.id} has negative favorChange but isPositive=true", e.isPositive)
            } else {
                assertTrue("Event ${e.id} has non-negative favorChange but isPositive=false", e.isPositive)
            }
        }
    }

    @Test
    fun allEventIds_areUnique() {
        val ids = DiplomaticEventConfig.Events.ALL_EVENTS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
