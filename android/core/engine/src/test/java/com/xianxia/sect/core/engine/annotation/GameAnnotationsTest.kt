package com.xianxia.sect.core.engine.annotation

import org.junit.Assert.*
import org.junit.Test

class GameAnnotationsTest {

    // ---- GameService annotation ----

    @Test
    fun gameService_hasClassTarget() {
        val targets = GameService::class.annotations
            .filterIsInstance<Target>()
            .firstOrNull()
        assertNotNull("GameService should have @Target annotation", targets)
        assertTrue(
            "GameService target should include CLASS",
            targets!!.allowedTargets.contains(AnnotationTarget.CLASS)
        )
    }

    @Test
    fun gameService_hasRuntimeRetention() {
        val retention = GameService::class.annotations
            .filterIsInstance<Retention>()
            .firstOrNull()
        assertNotNull("GameService should have @Retention annotation", retention)
        assertEquals(
            "GameService retention should be RUNTIME",
            AnnotationRetention.RUNTIME,
            retention!!.value
        )
    }

    @Test
    fun gameService_hasNameParameter() {
        val params = GameService::class.members.map { it.name }
        assertTrue("GameService should have 'name' parameter", params.contains("name"))
    }

    @Test
    fun gameService_nameParameterReturnsValue() {
        @GameService(name = "TestService")
        class TestClass

        val annotation = TestClass::class.annotations.filterIsInstance<GameService>().firstOrNull()
        assertNotNull("TestClass should have GameService annotation", annotation)
        assertEquals("TestService", annotation!!.name)
    }

    // ---- AutoTickSystem annotation ----

    @Test
    fun autoTickSystem_hasClassTarget() {
        val targets = AutoTickSystem::class.annotations
            .filterIsInstance<Target>()
            .firstOrNull()
        assertNotNull("AutoTickSystem should have @Target annotation", targets)
        assertTrue(
            "AutoTickSystem target should include CLASS",
            targets!!.allowedTargets.contains(AnnotationTarget.CLASS)
        )
    }

    @Test
    fun autoTickSystem_hasSourceRetention() {
        val retention = AutoTickSystem::class.annotations
            .filterIsInstance<Retention>()
            .firstOrNull()
        assertNotNull("AutoTickSystem should have @Retention annotation", retention)
        assertEquals(
            "AutoTickSystem retention should be SOURCE",
            AnnotationRetention.SOURCE,
            retention!!.value
        )
    }

    @Test
    fun autoTickSystem_hasNameParameter() {
        val params = AutoTickSystem::class.members.map { it.name }
        assertTrue("AutoTickSystem should have 'name' parameter", params.contains("name"))
    }

    @Test
    fun autoTickSystem_nameParameterReturnsValue() {
        @AutoTickSystem(name = "TestSystem")
        class TestClass

        val annotation = TestClass::class.annotations.filterIsInstance<AutoTickSystem>().firstOrNull()
        // SOURCE retention means annotation is not available at runtime, so it should be null
        assertNull("AutoTickSystem with SOURCE retention should not be visible at runtime", annotation)
    }

    // ---- Retention comparison ----

    @Test
    fun gameServiceAndAutoTickSystem_haveDifferentRetention() {
        val serviceRetention = GameService::class.annotations
            .filterIsInstance<Retention>()
            .firstOrNull()?.value
        val systemRetention = AutoTickSystem::class.annotations
            .filterIsInstance<Retention>()
            .firstOrNull()?.value
        assertNotNull(serviceRetention)
        assertNotNull(systemRetention)
        assertNotEquals("GameService and AutoTickSystem should have different retention", serviceRetention, systemRetention)
    }
}
