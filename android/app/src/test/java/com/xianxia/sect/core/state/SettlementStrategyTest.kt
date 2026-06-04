package com.xianxia.sect.core.state

import org.junit.Assert.*
import org.junit.Test
import kotlin.reflect.full.findAnnotation

class SettlementStrategyTest {

    // --- Strategy enum 值数量 ---

    @Test
    fun strategyEnum_hasFiveValues() {
        assertEquals(5, Strategy.values().size)
    }

    // --- Strategy 各枚举值存在且名称正确 ---

    @Test
    fun strategyPreserveOld_name() {
        assertEquals("PRESERVE_OLD", Strategy.PRESERVE_OLD.name)
    }

    @Test
    fun strategyUseShadow_name() {
        assertEquals("USE_SHADOW", Strategy.USE_SHADOW.name)
    }

    @Test
    fun strategyDelta_name() {
        assertEquals("DELTA", Strategy.DELTA.name)
    }

    @Test
    fun strategyThreeWayId_name() {
        assertEquals("THREE_WAY_ID", Strategy.THREE_WAY_ID.name)
    }

    @Test
    fun strategyCustom_name() {
        assertEquals("CUSTOM", Strategy.CUSTOM.name)
    }

    // --- SettlementStrategy 注解属性 ---

    @Test
    fun settlementStrategy_annotationTargetsProperty() {
        val targets = SettlementStrategy::class.annotations
            .filterIsInstance<kotlin.reflect.KClass<out kotlin.Annotation>>()
        // 直接检查注解的 @Target 元注解
        val targetAnnotation = SettlementStrategy::class.java
            .getAnnotation(kotlin.annotation.Target::class.java)
        assertNotNull(targetAnnotation)
        assertTrue(
            targetAnnotation!!.allowedTargets.contains(AnnotationTarget.PROPERTY)
        )
    }

    @Test
    fun settlementStrategy_hasRuntimeRetention() {
        val retention = SettlementStrategy::class.java
            .getAnnotation(kotlin.annotation.Retention::class.java)
        assertNotNull(retention)
        assertEquals(AnnotationRetention.RUNTIME, retention!!.value)
    }

    @Test
    fun settlementStrategy_hasValueParameterOfTypeStrategy() {
        val params = SettlementStrategy::class.java.declaredMethods
        assertTrue(params.any { it.name == "value" && it.returnType == Strategy::class.java })
    }

    // --- 注解使用 ---

    @Test
    fun canAnnotatePropertyWithSettlementStrategy() {
        // Verify annotation can be applied to a property via Kotlin reflection
        val prop = AnnotatedHolder::deltaField
        val annotation = prop.findAnnotation<SettlementStrategy>()
        assertNotNull(annotation)
        assertEquals(Strategy.DELTA, annotation!!.value)
    }

    companion object {
        class AnnotatedHolder {
            @SettlementStrategy(Strategy.DELTA)
            val deltaField: Int = 0
        }
    }
}
