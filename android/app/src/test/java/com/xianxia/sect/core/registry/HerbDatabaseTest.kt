package com.xianxia.sect.core.registry

import org.junit.Assert.*
import org.junit.Test

class HerbDatabaseTest {

    @Test
    fun `all herbs have non-blank id`() {
        HerbDatabase.getAllHerbs().forEach { herb ->
            assertTrue("herb id should not be blank", herb.id.isNotBlank())
        }
    }

    @Test
    fun `all herbs have non-blank name`() {
        HerbDatabase.getAllHerbs().forEach { herb ->
            assertTrue("herb name should not be blank", herb.name.isNotBlank())
        }
    }

    @Test
    fun `all herbs have valid rarity 1 to 6`() {
        HerbDatabase.getAllHerbs().forEach { herb ->
            assertTrue("herb ${herb.id} rarity ${herb.rarity} not in 1-6", herb.rarity in 1..6)
        }
    }

    @Test
    fun `all herbs have valid tier 1 to 6`() {
        HerbDatabase.getAllHerbs().forEach { herb ->
            assertTrue("herb ${herb.id} tier ${herb.tier} not in 1-6", herb.tier in 1..6)
        }
    }

    @Test
    fun `herb ids are unique`() {
        val herbs = HerbDatabase.getAllHerbs()
        val ids = herbs.map { it.id }
        val uniqueIds = ids.toSet()
        assertEquals("herb ids should be unique", uniqueIds.size, ids.size)
    }

    @Test
    fun `all seeds have non-blank id`() {
        HerbDatabase.getAllSeeds().forEach { seed ->
            assertTrue("seed id should not be blank", seed.id.isNotBlank())
        }
    }

    @Test
    fun `all seeds have non-blank name`() {
        HerbDatabase.getAllSeeds().forEach { seed ->
            assertTrue("seed name should not be blank", seed.name.isNotBlank())
        }
    }

    @Test
    fun `all seeds have valid rarity 1 to 6`() {
        HerbDatabase.getAllSeeds().forEach { seed ->
            assertTrue("seed ${seed.id} rarity ${seed.rarity} not in 1-6", seed.rarity in 1..6)
        }
    }

    @Test
    fun `all seeds have positive growTime`() {
        HerbDatabase.getAllSeeds().forEach { seed ->
            assertTrue("seed ${seed.id} growTime ${seed.growTime} should be positive", seed.growTime > 0)
        }
    }

    @Test
    fun `all seeds have positive yield`() {
        HerbDatabase.getAllSeeds().forEach { seed ->
            assertTrue("seed ${seed.id} yield ${seed.yield} should be positive", seed.yield > 0)
        }
    }

    @Test
    fun `seed ids are unique`() {
        val seeds = HerbDatabase.getAllSeeds()
        val ids = seeds.map { it.id }
        val uniqueIds = ids.toSet()
        assertEquals("seed ids should be unique", uniqueIds.size, ids.size)
    }

    @Test
    fun `getHerbById returns herb for known id`() {
        val first = HerbDatabase.getAllHerbs().first()
        val result = HerbDatabase.getHerbById(first.id)
        assertNotNull(result)
        assertEquals(first.id, result!!.id)
    }

    @Test
    fun `getHerbById returns null for unknown id`() {
        assertNull(HerbDatabase.getHerbById("nonexistent_herb_id"))
    }

    @Test
    fun `getHerbByName returns herb for known name`() {
        val first = HerbDatabase.getAllHerbs().first()
        val result = HerbDatabase.getHerbByName(first.name)
        assertNotNull(result)
        assertEquals(first.name, result!!.name)
    }

    @Test
    fun `getHerbByName returns null for unknown name`() {
        assertNull(HerbDatabase.getHerbByName("nonexistent_herb"))
    }

    @Test
    fun `getSeedById returns seed for known id`() {
        val first = HerbDatabase.getAllSeeds().first()
        val result = HerbDatabase.getSeedById(first.id)
        assertNotNull(result)
        assertEquals(first.id, result!!.id)
    }

    @Test
    fun `getSeedById returns null for unknown id`() {
        assertNull(HerbDatabase.getSeedById("nonexistent_seed_id"))
    }

    @Test
    fun `getSeedByName returns seed for known name`() {
        val first = HerbDatabase.getAllSeeds().first()
        val result = HerbDatabase.getSeedByName(first.name)
        assertNotNull(result)
        assertEquals(first.name, result!!.name)
    }

    @Test
    fun `getSeedByName returns null for unknown name`() {
        assertNull(HerbDatabase.getSeedByName("nonexistent_seed"))
    }

    @Test
    fun `getHerbFromSeed returns herb for known seed id`() {
        val firstSeed = HerbDatabase.getAllSeeds().first()
        val herb = HerbDatabase.getHerbFromSeed(firstSeed.id)
        assertNotNull(herb)
    }

    @Test
    fun `getHerbFromSeed returns null for unknown seed id`() {
        assertNull(HerbDatabase.getHerbFromSeed("nonexistent_seed_id"))
    }

    @Test
    fun `getHerbsByTier returns herbs for each tier 1 to 6`() {
        for (tier in 1..6) {
            val herbs = HerbDatabase.getHerbsByTier(tier)
            assertTrue("tier $tier should have herbs", herbs.isNotEmpty())
            herbs.forEach { herb ->
                assertEquals(tier, herb.tier)
            }
        }
    }

    @Test
    fun `getHerbsByTier returns empty for invalid tier`() {
        assertTrue(HerbDatabase.getHerbsByTier(0).isEmpty())
    }

    @Test
    fun `getSeedsByTier returns seeds for each tier 1 to 6`() {
        for (tier in 1..6) {
            val seeds = HerbDatabase.getSeedsByTier(tier)
            assertTrue("tier $tier should have seeds", seeds.isNotEmpty())
            seeds.forEach { seed ->
                assertEquals(tier, seed.tier)
            }
        }
    }

    @Test
    fun `getSeedsByTier returns empty for invalid tier`() {
        assertTrue(HerbDatabase.getSeedsByTier(99).isEmpty())
    }

    @Test
    fun `getByRarity returns herbs for each rarity 1 to 6`() {
        for (rarity in 1..6) {
            val herbs = HerbDatabase.getByRarity(rarity)
            assertTrue("rarity $rarity should have herbs", herbs.isNotEmpty())
            herbs.forEach { herb ->
                assertEquals(rarity, herb.rarity)
            }
        }
    }

    @Test
    fun `getByRarity returns empty for invalid rarity`() {
        assertTrue(HerbDatabase.getByRarity(0).isEmpty())
    }

    @Test
    fun `getSeedsByRarity returns seeds for each rarity 1 to 6`() {
        for (rarity in 1..6) {
            val seeds = HerbDatabase.getSeedsByRarity(rarity)
            assertTrue("rarity $rarity should have seeds", seeds.isNotEmpty())
            seeds.forEach { seed ->
                assertEquals(rarity, seed.rarity)
            }
        }
    }

    @Test
    fun `getSeedsByRarity returns empty for invalid rarity`() {
        assertTrue(HerbDatabase.getSeedsByRarity(99).isEmpty())
    }

    @Test
    fun `every seed can find its corresponding herb`() {
        HerbDatabase.getAllSeeds().forEach { seed ->
            val herb = HerbDatabase.getHerbFromSeed(seed.id)
            assertNotNull("seed ${seed.id} should find herb", herb)
        }
    }

    @Test
    fun `seed and herb tier consistency`() {
        HerbDatabase.getAllSeeds().forEach { seed ->
            val herb = HerbDatabase.getHerbFromSeed(seed.id)
            assertNotNull(herb)
            assertEquals(seed.tier, herb!!.tier)
        }
    }

    @Test
    fun `seed and herb rarity consistency`() {
        HerbDatabase.getAllSeeds().forEach { seed ->
            val herb = HerbDatabase.getHerbFromSeed(seed.id)
            assertNotNull(herb)
            assertEquals(seed.rarity, herb!!.rarity)
        }
    }

    @Test
    fun `getHerbNameFromSeedName converts seed suffix`() {
        val result = HerbDatabase.getHerbNameFromSeedName("test_seed")
        assertNotNull(result)
    }
}
