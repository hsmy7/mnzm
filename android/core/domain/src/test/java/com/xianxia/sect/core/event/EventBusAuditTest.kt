package com.xianxia.sect.core.event

import org.junit.Assert.*
import org.junit.Test

class EventBusAuditTest {

    // ==================== ConsumerRecord ====================

    @Test
    fun consumerRecord_construction() {
        val record = EventBusAudit.ConsumerRecord(
            consumerClass = "TestClass",
            eventType = "death",
            threading = "applicationScope.launch",
            backpressure = "无背压控制",
            errorHandling = "try-catch隔离",
            riskLevel = "MEDIUM",
            notes = "测试备注"
        )
        assertEquals("TestClass", record.consumerClass)
        assertEquals("death", record.eventType)
        assertEquals("applicationScope.launch", record.threading)
        assertEquals("无背压控制", record.backpressure)
        assertEquals("try-catch隔离", record.errorHandling)
        assertEquals("MEDIUM", record.riskLevel)
        assertEquals("测试备注", record.notes)
    }

    @Test
    fun consumerRecord_defaultNotes() {
        val record = EventBusAudit.ConsumerRecord(
            consumerClass = "C",
            eventType = "e",
            threading = "t",
            backpressure = "b",
            errorHandling = "eh",
            riskLevel = "LOW"
        )
        assertEquals("", record.notes)
    }

    @Test
    fun consumerRecord_equality() {
        val a = EventBusAudit.ConsumerRecord(
            consumerClass = "C", eventType = "e", threading = "t",
            backpressure = "b", errorHandling = "eh", riskLevel = "LOW", notes = "n"
        )
        val b = EventBusAudit.ConsumerRecord(
            consumerClass = "C", eventType = "e", threading = "t",
            backpressure = "b", errorHandling = "eh", riskLevel = "LOW", notes = "n"
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun consumerRecord_inequality() {
        val a = EventBusAudit.ConsumerRecord(
            consumerClass = "C1", eventType = "e", threading = "t",
            backpressure = "b", errorHandling = "eh", riskLevel = "LOW"
        )
        val b = EventBusAudit.ConsumerRecord(
            consumerClass = "C2", eventType = "e", threading = "t",
            backpressure = "b", errorHandling = "eh", riskLevel = "LOW"
        )
        assertNotEquals(a, b)
    }

    @Test
    fun consumerRecord_copy() {
        val original = EventBusAudit.ConsumerRecord(
            consumerClass = "C", eventType = "e", threading = "t",
            backpressure = "b", errorHandling = "eh", riskLevel = "MEDIUM"
        )
        val modified = original.copy(riskLevel = "HIGH")
        assertEquals("HIGH", modified.riskLevel)
        assertEquals("C", modified.consumerClass)
    }

    // ==================== EventBusAudit.consumers ====================

    @Test
    fun audit_consumersNotEmpty() {
        assertTrue(EventBusAudit.consumers.isNotEmpty())
    }

    @Test
    fun audit_consumersHaveValidRiskLevels() {
        val validRiskLevels = setOf("HIGH", "MEDIUM", "LOW")
        for (consumer in EventBusAudit.consumers) {
            assertTrue(
                "Consumer ${consumer.consumerClass} has invalid risk level: ${consumer.riskLevel}",
                consumer.riskLevel in validRiskLevels
            )
        }
    }

    @Test
    fun audit_consumersHaveRequiredFields() {
        for (consumer in EventBusAudit.consumers) {
            assertTrue("consumerClass should not be blank", consumer.consumerClass.isNotBlank())
            assertTrue("eventType should not be blank", consumer.eventType.isNotBlank())
            assertTrue("threading should not be blank", consumer.threading.isNotBlank())
            assertTrue("backpressure should not be blank", consumer.backpressure.isNotBlank())
            assertTrue("errorHandling should not be blank", consumer.errorHandling.isNotBlank())
        }
    }

    @Test
    fun audit_hasProducerEntries() {
        val producers = EventBusAudit.consumers.filter { it.consumerClass.startsWith("[Producer]") }
        assertTrue("Should have at least one producer entry", producers.isNotEmpty())
    }

    @Test
    fun audit_hasConsumerEntries() {
        val consumers = EventBusAudit.consumers.filter { !it.consumerClass.startsWith("[Producer]") }
        assertTrue("Should have at least one consumer entry", consumers.isNotEmpty())
    }

    @Test
    fun audit_producerEntriesHaveMediumRisk() {
        // Producers use emitSync which drops events when channel is full
        val producers = EventBusAudit.consumers.filter { it.consumerClass.startsWith("[Producer]") }
        for (producer in producers) {
            assertTrue(
                "Producer ${producer.consumerClass} should be MEDIUM risk, got ${producer.riskLevel}",
                producer.riskLevel == "MEDIUM"
            )
        }
    }

    // ==================== EventBusAudit.summary ====================

    @Test
    fun audit_summaryIsNotBlank() {
        assertTrue(EventBusAudit.summary.isNotBlank())
    }

    @Test
    fun audit_summaryContainsKeySections() {
        val summary = EventBusAudit.summary
        assertTrue("Summary should mention consumer count", summary.contains("消费者数量"))
        assertTrue("Summary should mention producer count", summary.contains("生产者数量"))
        assertTrue("Summary should mention risk distribution", summary.contains("风险分布"))
    }

    @Test
    fun audit_summaryRiskCountsMatchConsumers() {
        val total = EventBusAudit.consumers.size
        val producers = EventBusAudit.consumers.count { it.consumerClass.startsWith("[Producer]") }
        val consumersOnly = total - producers
        val highRisk = EventBusAudit.consumers.count { it.riskLevel == "HIGH" }
        val mediumRisk = EventBusAudit.consumers.count { it.riskLevel == "MEDIUM" }
        val lowRisk = EventBusAudit.consumers.count { it.riskLevel == "LOW" }

        val summary = EventBusAudit.summary
        assertTrue(summary.contains("消费者数量: $consumersOnly"))
        assertTrue(summary.contains("生产者数量: $producers"))
        assertTrue(summary.contains("HIGH=$highRisk"))
        assertTrue(summary.contains("MEDIUM=$mediumRisk"))
        assertTrue(summary.contains("LOW=$lowRisk"))
    }

    @Test
    fun audit_summaryContainsKeyFindings() {
        val summary = EventBusAudit.summary
        assertTrue("Should mention Channel capacity", summary.contains("Channel capacity"))
        assertTrue("Should mention trySend", summary.contains("trySend"))
        assertTrue("Should mention emitSync", summary.contains("emitSync"))
    }

    // ==================== DomainEventHistory ====================

    @Test
    fun domainEventHistory_addAndGetAll() {
        val history = DomainEventHistory(maxSize = 5)
        val event = CultivationEvent("d1", "张三", 9, 10, 1000L)
        history.add(event)
        assertEquals(1, history.getAll().size)
    }

    @Test
    fun domainEventHistory_maxSizeLimit() {
        val history = DomainEventHistory(maxSize = 3)
        for (i in 1..5) {
            history.add(CultivationEvent("d$i", "弟子$i", 9, 10, 1000L))
        }
        assertEquals(3, history.size())
    }

    @Test
    fun domainEventHistory_getByType() {
        val history = DomainEventHistory(maxSize = 10)
        history.add(CultivationEvent("d1", "张三", 9, 10, 1000L))
        history.add(DeathEvent("e1", "妖兽", "战斗阵亡"))
        history.add(CultivationEvent("d2", "李四", 10, 11, 2000L))

        val cultivationEvents = history.getByType("cultivation")
        assertEquals(2, cultivationEvents.size)
        assertEquals(1, history.getByType("death").size)
        assertEquals(0, history.getByType("nonexistent").size)
    }

    @Test
    fun domainEventHistory_getSince() {
        val history = DomainEventHistory(maxSize = 10)
        val before = System.currentTimeMillis() - 1000
        history.add(CultivationEvent("d1", "张三", 9, 10, 1000L))
        val after = System.currentTimeMillis() + 1000

        // The event was just added, so its timestamp should be >= before
        assertTrue(history.getSince(before).isNotEmpty())
        // No events should have timestamp >= after (future)
        assertTrue(history.getSince(after).isEmpty())
    }

    @Test
    fun domainEventHistory_clear() {
        val history = DomainEventHistory(maxSize = 10)
        history.add(CultivationEvent("d1", "张三", 9, 10, 1000L))
        assertEquals(1, history.size())

        history.clear()
        assertEquals(0, history.size())
        assertTrue(history.getAll().isEmpty())
    }

    @Test
    fun domainEventHistory_addOrderIsNewestFirst() {
        val history = DomainEventHistory(maxSize = 10)
        history.add(CultivationEvent("d1", "第一个", 9, 10, 1000L))
        history.add(CultivationEvent("d2", "第二个", 10, 11, 2000L))

        val all = history.getAll()
        assertEquals("第二个", (all[0] as CultivationEvent).discipleName)
        assertEquals("第一个", (all[1] as CultivationEvent).discipleName)
    }

    @Test
    fun domainEventHistory_defaultMaxSize() {
        val history = DomainEventHistory()
        for (i in 1..150) {
            history.add(CultivationEvent("d$i", "弟子$i", 9, 10, 1000L))
        }
        assertEquals(100, history.size())
    }
}
