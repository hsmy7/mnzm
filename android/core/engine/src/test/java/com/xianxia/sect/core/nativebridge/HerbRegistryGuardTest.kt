package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.registry.HerbDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * HerbRegistryGuardTest — 灵草/种子静态数据守卫（补齐预存缺口）。
 *
 * 守护目标：中性源快照（herb_db_sample.json，由 scripts/gen-templates.mjs 从
 * scripts/data/herb_db_sample.json 生成）与 Kotlin HerbDatabase **实时数据**
 * 一致（逐字段：id/name/tier/rarity/category/description + 种子 growTime/yield）。
 *
 * 背景：architecture.md 原文称"双端守卫已覆盖 6 类"，实际灵草/种子
 * 双端均无守卫（Kotlin 侧无 HerbRegistryGuardTest、C++ 侧无 herb_db_test.cpp）
 * ——本测试补齐 Kotlin 侧；C++ 侧 herb_db_test.cpp 同步新增。
 *
 * 修复指引：修改 HerbDatabase 后运行 `node scripts/gen-templates.mjs`。
 */
class HerbRegistryGuardTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadRoot(): JsonObject? {
        val resource = javaClass.getResourceAsStream("/templates/herb_db_sample.json")
        assumeTrue("快照不存在（先运行 node scripts/gen-templates.mjs）", resource != null)
        resource ?: return null
        return json.parseToJsonElement(resource.readBytes().decodeToString()).jsonObject
    }

    @Test
    fun `herb snapshot matches kotlin registry`() {
        val root = loadRoot() ?: return
        val herbs = root.getValue("herbs").jsonArray

        val kotlinAll = HerbDatabase.getAllHerbs()
        assertEquals("灵草数量与 Kotlin Registry 不一致", kotlinAll.size, herbs.size)

        herbs.forEach { e ->
            val obj = e.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            val herb = HerbDatabase.getHerbById(id)
            assertNotNull("灵草 $id 不存在于 Kotlin Registry", herb)
            herb ?: return@forEach

            assertEquals("$id.name", obj.getValue("name").jsonPrimitive.content, herb.name)
            assertEquals("$id.tier", obj.getValue("tier").jsonPrimitive.content.toInt(), herb.tier)
            assertEquals("$id.rarity", obj.getValue("rarity").jsonPrimitive.content.toInt(), herb.rarity)
            assertEquals("$id.category", obj.getValue("category").jsonPrimitive.content, herb.category)
            assertEquals("$id.description", obj.getValue("description").jsonPrimitive.content, herb.description)
        }
    }

    @Test
    fun `seed snapshot matches kotlin registry`() {
        val root = loadRoot() ?: return
        val seeds = root.getValue("seeds").jsonArray

        val kotlinAll = HerbDatabase.getAllSeeds()
        assertEquals("种子数量与 Kotlin Registry 不一致", kotlinAll.size, seeds.size)

        seeds.forEach { e ->
            val obj = e.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            val seed = HerbDatabase.getSeedById(id)
            assertNotNull("种子 $id 不存在于 Kotlin Registry", seed)
            seed ?: return@forEach

            assertEquals("$id.name", obj.getValue("name").jsonPrimitive.content, seed.name)
            assertEquals("$id.tier", obj.getValue("tier").jsonPrimitive.content.toInt(), seed.tier)
            assertEquals("$id.rarity", obj.getValue("rarity").jsonPrimitive.content.toInt(), seed.rarity)
            assertEquals("$id.growTime", obj.getValue("growTime").jsonPrimitive.content.toInt(), seed.growTime)
            assertEquals("$id.yield", obj.getValue("yield").jsonPrimitive.content.toInt(), seed.yield)
            assertEquals("$id.description", obj.getValue("description").jsonPrimitive.content, seed.description)
        }
    }

    @Test
    fun `seed to herb mapping matches kotlin`() {
        val root = loadRoot() ?: return
        val seeds = root.getValue("seeds").jsonArray
        seeds.forEach { e ->
            val id = e.jsonObject.getValue("id").jsonPrimitive.content
            val kotlinHerbId = HerbDatabase.getHerbIdFromSeedId(id)
            // 种子 id 去 "Seed" 后缀 = 灵草 id（与 C++ herbIdFromSeedId 同规则）
            if (id.endsWith("Seed")) {
                val expected = id.removeSuffix("Seed")
                assertEquals("种子 $id → 灵草映射", expected, kotlinHerbId)
                assertNotNull("种子 $id 对应灵草 $expected 应存在", HerbDatabase.getHerbById(expected))
            }
        }
    }
}
