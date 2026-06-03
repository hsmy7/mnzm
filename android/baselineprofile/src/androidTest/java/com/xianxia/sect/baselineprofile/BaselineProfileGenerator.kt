package com.xianxia.sect.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() {
        baselineProfileRule.collect(
            packageName = "com.xianxia.sect",
            includeInStartupProfile = true
        ) {
            pressHome()
            startActivityAndWait()
            waitForIdleSync()
        }
    }

    @Test
    fun gamePlayScenario() = baselineProfileRule.collect(
        packageName = "com.xianxia.sect",
        includeInStartupProfile = false
    ) {
        pressHome()
        startActivityAndWait()
        waitForIdleSync()

        // Let the game loop run for several ticks to warm up JIT for:
        // - GameEngineCore.tick() + SystemManager pipeline
        // - Compose rendering (Canvas, LazyColumn, StateFlow collection)
        // - StateFlow .map{} chains and collectAsStateWithLifecycle subscribers
        repeat(6) {
            Thread.sleep(1200)  // slightly longer than 1000ms tick interval
            waitForIdleSync()
        }
    }
}
