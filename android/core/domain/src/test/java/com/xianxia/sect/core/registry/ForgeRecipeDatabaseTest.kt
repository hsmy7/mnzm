package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipmentSlot
import org.junit.Assert.*
import org.junit.Test

class ForgeRecipeDatabaseTest {

    // 1. All forge recipes have valid data
    @Test
    fun allRecipes_haveValidData() {
        val recipes = ForgeRecipeDatabase.getAllRecipes()
        assertTrue("Recipe list is empty", recipes.isNotEmpty())

        for (recipe in recipes) {
            assertTrue(
                "Recipe ${recipe.id} has blank id",
                recipe.id.isNotBlank()
            )
            assertTrue(
                "Recipe ${recipe.id} has blank name",
                recipe.name.isNotBlank()
            )
            assertTrue(
                "Recipe ${recipe.id} has invalid tier: ${recipe.tier}",
                recipe.tier in 1..6
            )
            assertTrue(
                "Recipe ${recipe.id} has invalid rarity: ${recipe.rarity}",
                recipe.rarity in 1..6
            )
            assertTrue(
                "Recipe ${recipe.id} has blank description",
                recipe.description.isNotBlank()
            )
            assertTrue(
                "Recipe ${recipe.id} has invalid duration: ${recipe.duration}",
                recipe.duration > 0
            )
            assertTrue(
                "Recipe ${recipe.id} has invalid successRate: ${recipe.successRate}",
                recipe.successRate > 0.0 && recipe.successRate <= 1.0
            )
        }
    }

    // 2. Recipe IDs are unique
    @Test
    fun allRecipes_haveUniqueIds() {
        val recipes = ForgeRecipeDatabase.getAllRecipes()
        val ids = recipes.map { it.id }
        val distinctIds = ids.toSet()
        assertEquals(
            "Duplicate recipe IDs found",
            ids.size,
            distinctIds.size
        )
    }

    // 3. Each recipe has valid input materials
    @Test
    fun allRecipes_haveValidInputMaterials() {
        val recipes = ForgeRecipeDatabase.getAllRecipes()
        for (recipe in recipes) {
            assertTrue(
                "Recipe ${recipe.id} has no materials",
                recipe.materials.isNotEmpty()
            )
            for ((materialId, quantity) in recipe.materials) {
                assertTrue(
                    "Recipe ${recipe.id} has blank material id",
                    materialId.isNotBlank()
                )
                assertTrue(
                    "Recipe ${recipe.id} has non-positive quantity for material $materialId: $quantity",
                    quantity > 0
                )
            }
        }
    }

    // 4. Each recipe has valid output equipment (id exists in EquipmentDatabase)
    @Test
    fun allRecipes_haveValidOutputEquipment() {
        val recipes = ForgeRecipeDatabase.getAllRecipes()
        for (recipe in recipes) {
            val equipment = EquipmentDatabase.getById(recipe.id)
            assertNotNull(
                "Recipe ${recipe.id} has no matching equipment in EquipmentDatabase",
                equipment
            )
            if (equipment != null) {
                assertEquals(
                    "Recipe ${recipe.id} rarity does not match equipment rarity",
                    equipment.rarity,
                    recipe.rarity
                )
                assertEquals(
                    "Recipe ${recipe.id} type does not match equipment slot",
                    equipment.slot,
                    recipe.type
                )
            }
        }
    }

    // 5. getByRarity works
    @Test
    fun getRecipesByTier_returnsRecipesOfSpecificTier() {
        for (tier in 1..6) {
            val recipes = ForgeRecipeDatabase.getRecipesByTier(tier)
            assertTrue(
                "getRecipesByTier($tier) returned empty list",
                recipes.isNotEmpty()
            )
            for (recipe in recipes) {
                assertEquals(
                    "Recipe ${recipe.id} has wrong tier",
                    tier,
                    recipe.tier
                )
            }
        }
    }

    @Test
    fun getRecipesByTier_returnsEmptyListForInvalidTier() {
        assertTrue(ForgeRecipeDatabase.getRecipesByTier(0).isEmpty())
        assertTrue(ForgeRecipeDatabase.getRecipesByTier(7).isEmpty())
    }

    // 6. getById works
    @Test
    fun getRecipeById_returnsRecipeForKnownId() {
        val knownId = ForgeRecipeDatabase.getAllRecipes().first().id
        val result = ForgeRecipeDatabase.getRecipeById(knownId)
        assertNotNull("getRecipeById returned null for known id: $knownId", result)
        assertEquals(knownId, result!!.id)
    }

    @Test
    fun getRecipeById_returnsNullForUnknownId() {
        val result = ForgeRecipeDatabase.getRecipeById("nonExistentRecipeId12345")
        assertNull("getRecipeById should return null for unknown id", result)
    }
}
