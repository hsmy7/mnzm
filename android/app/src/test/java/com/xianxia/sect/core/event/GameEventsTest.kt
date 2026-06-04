package com.xianxia.sect.core.event

import org.junit.Assert.*
import org.junit.Test

class GameEventsTest {

    // ==================== NotificationSeverity enum ====================

    @Test
    fun notificationSeverity_values() {
        assertEquals(4, NotificationSeverity.values().size)
        assertArrayEquals(
            arrayOf(NotificationSeverity.INFO, NotificationSeverity.WARNING, NotificationSeverity.ERROR, NotificationSeverity.SUCCESS),
            NotificationSeverity.values()
        )
    }

    // ==================== DomainEvent type constants ====================

    @Test
    fun cultivationEvent_type() {
        val event = CultivationEvent(
            discipleId = "d1",
            discipleName = "张三",
            oldRealm = 9,
            newRealm = 10,
            cultivation = 1000L
        )
        assertEquals("cultivation", event.type)
    }

    @Test
    fun breakthroughEvent_type() {
        val event = BreakthroughEvent(
            discipleId = "d1",
            discipleName = "张三",
            realm = 10,
            success = true,
            newLayer = 3
        )
        assertEquals("breakthrough", event.type)
    }

    @Test
    fun combatEvent_type() {
        val event = CombatEvent(
            attackerId = "a1",
            defenderId = "d1",
            damage = 100,
            isCritical = true,
            skillName = "剑气"
        )
        assertEquals("combat", event.type)
    }

    @Test
    fun deathEvent_type() {
        val event = DeathEvent(
            entityId = "e1",
            entityName = "妖兽",
            cause = "战斗阵亡"
        )
        assertEquals("death", event.type)
    }

    @Test
    fun itemEvent_type() {
        val event = ItemEvent(
            itemId = "i1",
            itemName = "丹药",
            action = "use",
            quantity = 1,
            targetId = null
        )
        assertEquals("item", event.type)
    }

    @Test
    fun sectEvent_type() {
        val event = SectEvent(
            sectId = "s1",
            sectName = "天剑宗",
            action = "alliance"
        )
        assertEquals("sect", event.type)
    }

    @Test
    fun timeEvent_type() {
        val event = TimeEvent(year = 5, month = 3, day = 1, action = "month_end")
        assertEquals("time", event.type)
    }

    @Test
    fun saveEvent_type() {
        val event = SaveEvent(slot = 0, success = true, message = "ok")
        assertEquals("save", event.type)
    }

    @Test
    fun errorEvent_type() {
        val event = ErrorEvent(errorCode = "E001", message = "error")
        assertEquals("error", event.type)
    }

    @Test
    fun notificationEvent_type() {
        val event = NotificationEvent(title = "通知", message = "内容")
        assertEquals("notification", event.type)
    }

    @Test
    fun discipleUpdatedEvent_type() {
        val event = DiscipleUpdatedEvent(discipleId = "d1", changes = emptyMap())
        assertEquals("disciple_updated", event.type)
    }

    @Test
    fun cultivationProgressEvent_type() {
        val event = CultivationProgressEvent(discipleId = "d1", progress = 0.5)
        assertEquals("cultivation_progress", event.type)
    }

    @Test
    fun itemCraftedEvent_type() {
        val event = ItemCraftedEvent(itemId = "i1", itemType = "pill")
        assertEquals("item_crafted", event.type)
    }

    @Test
    fun battleCompletedEvent_type() {
        val event = BattleCompletedEvent(
            battleId = "b1",
            result = BattleResultInfo(victory = true)
        )
        assertEquals("battle_completed", event.type)
    }

    @Test
    fun battleStartedEvent_type() {
        val event = BattleStartedEvent(attackerId = "a1", defenderId = "d1")
        assertEquals("battle_started", event.type)
    }

    @Test
    fun buildingCompletedEvent_type() {
        val event = BuildingCompletedEvent(buildingId = "b1")
        assertEquals("building_completed", event.type)
    }

    @Test
    fun spiritStonesChangedEvent_type() {
        val event = SpiritStonesChangedEvent(delta = 100L, newTotal = 1000L)
        assertEquals("spirit_stones_changed", event.type)
    }

    @Test
    fun sectRelationChangedEvent_type() {
        val event = SectRelationChangedEvent(sectId = "s1")
        assertEquals("sect_relation_changed", event.type)
    }

    @Test
    fun discipleRecruitedEvent_type() {
        val event = DiscipleRecruitedEvent(discipleId = "d1")
        assertEquals("disciple_recruited", event.type)
    }

    @Test
    fun discipleExpelledEvent_type() {
        val event = DiscipleExpelledEvent(discipleId = "d1")
        assertEquals("disciple_expelled", event.type)
    }

    @Test
    fun productionCompletedEvent_type() {
        val event = ProductionCompletedEvent(buildingType = "ALCHEMY", slotIndex = 0)
        assertEquals("production_completed", event.type)
    }

    @Test
    fun allianceFormedEvent_type() {
        val event = AllianceFormedEvent(sectId = "s1")
        assertEquals("alliance_formed", event.type)
    }

    @Test
    fun allianceDissolvedEvent_type() {
        val event = AllianceDissolvedEvent(sectId = "s1")
        assertEquals("alliance_dissolved", event.type)
    }

    @Test
    fun explorationCompletedEvent_type() {
        val event = ExplorationCompletedEvent(teamId = "t1", success = true)
        assertEquals("exploration_completed", event.type)
    }

    // ==================== Data class equality ====================

    @Test
    fun cultivationEvent_equality() {
        val a = CultivationEvent("d1", "张三", 9, 10, 1000L)
        val b = CultivationEvent("d1", "张三", 9, 10, 1000L)
        assertEquals(a, b)
    }

    @Test
    fun breakthroughEvent_equality() {
        val a = BreakthroughEvent("d1", "张三", 10, true, 3)
        val b = BreakthroughEvent("d1", "张三", 10, true, 3)
        assertEquals(a, b)
    }

    @Test
    fun combatEvent_equality() {
        val a = CombatEvent("a1", "d1", 100, true, "剑气")
        val b = CombatEvent("a1", "d1", 100, true, "剑气")
        assertEquals(a, b)
    }

    @Test
    fun deathEvent_equality() {
        val a = DeathEvent("e1", "妖兽", "战斗阵亡")
        val b = DeathEvent("e1", "妖兽", "战斗阵亡")
        assertEquals(a, b)
    }

    @Test
    fun notificationEvent_defaultSeverity() {
        val event = NotificationEvent(title = "通知", message = "内容")
        assertEquals(NotificationSeverity.INFO, event.severity)
    }

    @Test
    fun notificationEvent_customSeverity() {
        val event = NotificationEvent(title = "警告", message = "内容", severity = NotificationSeverity.WARNING)
        assertEquals(NotificationSeverity.WARNING, event.severity)
    }

    // ==================== BattleResultInfo ====================

    @Test
    fun battleResultInfo_defaultValues() {
        val info = BattleResultInfo(victory = true)
        assertTrue(info.victory)
        assertEquals(0, info.playerLosses)
        assertEquals(0, info.enemyLosses)
        assertTrue(info.rewards.isEmpty())
    }

    @Test
    fun battleResultInfo_withRewards() {
        val reward = RewardItemInfo(itemId = "r1", itemName = "丹药", quantity = 2, rarity = 3)
        val info = BattleResultInfo(victory = true, rewards = listOf(reward))
        assertEquals(1, info.rewards.size)
        assertEquals("丹药", info.rewards[0].itemName)
    }

    @Test
    fun battleResultInfo_equality() {
        val a = BattleResultInfo(victory = true, playerLosses = 1, enemyLosses = 5)
        val b = BattleResultInfo(victory = true, playerLosses = 1, enemyLosses = 5)
        assertEquals(a, b)
    }

    // ==================== RewardItemInfo ====================

    @Test
    fun rewardItemInfo_equality() {
        val a = RewardItemInfo("r1", "丹药", 2, 3)
        val b = RewardItemInfo("r1", "丹药", 2, 3)
        assertEquals(a, b)
    }

    // ==================== DomainEvent timestamp ====================

    @Test
    fun domainEvent_timestampIsAccessible() {
        val event = CultivationEvent("d1", "张三", 9, 10, 1000L)
        // timestamp has a default getter from DomainEvent interface
        assertTrue(event.timestamp > 0)
    }

    // ==================== Event default values ====================

    @Test
    fun battleStartedEvent_defaultNames() {
        val event = BattleStartedEvent(attackerId = "a1", defenderId = "d1")
        assertEquals("", event.attackerName)
        assertEquals("", event.defenderName)
    }

    @Test
    fun buildingCompletedEvent_defaults() {
        val event = BuildingCompletedEvent(buildingId = "b1")
        assertEquals("", event.buildingName)
        assertEquals(0, event.gridX)
        assertEquals(0, event.gridY)
    }

    @Test
    fun spiritStonesChangedEvent_defaults() {
        val event = SpiritStonesChangedEvent(delta = 100L, newTotal = 1000L)
        assertEquals("", event.reason)
    }

    @Test
    fun discipleExpelledEvent_defaults() {
        val event = DiscipleExpelledEvent(discipleId = "d1")
        assertEquals("", event.discipleName)
        assertEquals("", event.reason)
    }

    @Test
    fun allianceDissolvedEvent_defaults() {
        val event = AllianceDissolvedEvent(sectId = "s1")
        assertEquals("", event.sectName)
        assertEquals("", event.reason)
    }

    @Test
    fun explorationCompletedEvent_defaults() {
        val event = ExplorationCompletedEvent(teamId = "t1", success = true)
        assertEquals(0, event.survivorCount)
    }

    @Test
    fun sectEvent_defaultDetails() {
        val event = SectEvent(sectId = "s1", sectName = "天剑宗", action = "attack")
        assertTrue(event.details.isEmpty())
    }

    @Test
    fun errorEvent_defaultDetails() {
        val event = ErrorEvent(errorCode = "E001", message = "error")
        assertTrue(event.details.isEmpty())
    }

    // ==================== Copy ====================

    @Test
    fun combatEvent_copy() {
        val original = CombatEvent("a1", "d1", 100, false, null)
        val modified = original.copy(damage = 200, isCritical = true)
        assertEquals(200, modified.damage)
        assertTrue(modified.isCritical)
        assertEquals("a1", modified.attackerId)
    }

    @Test
    fun notificationEvent_copy() {
        val original = NotificationEvent(title = "通知", message = "内容", severity = NotificationSeverity.INFO)
        val modified = original.copy(severity = NotificationSeverity.ERROR)
        assertEquals(NotificationSeverity.ERROR, modified.severity)
    }
}
