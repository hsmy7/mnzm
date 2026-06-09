package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class InventoryDelegate(
    private val gameEngine: GameEngine,
    private val scope: CoroutineScope
) {

    fun toggleItemLock(itemId: String, itemType: String) {
        scope.launch {
            gameEngine.toggleItemLock(itemId, itemType)
        }
    }

    fun sellToMerchant(itemId: String, quantity: Int) {
        scope.launch { gameEngine.sellToMerchant(itemId, quantity) }
    }

    fun sellItem(itemId: String, itemType: String, quantity: Int) {
        scope.launch {
            try {
                when (itemType) {
                    "equipment" -> gameEngine.sellEquipment(itemId, quantity)
                    "manual" -> gameEngine.sellManual(itemId, quantity)
                    "pill" -> gameEngine.sellPill(itemId, quantity)
                    "material" -> gameEngine.sellMaterial(itemId, quantity)
                    "herb" -> gameEngine.sellHerb(itemId, quantity)
                    "seed" -> gameEngine.sellSeed(itemId, quantity)
                }
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun buyFromMerchant(itemId: String, quantity: Int = 1) {
        scope.launch {
            try {
                gameEngine.buyMerchantItem(itemId, quantity)
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun listItemsToMerchant(items: List<Pair<String, Int>>) {
        scope.launch {
            try {
                gameEngine.listItemsToMerchant(items)
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun removePlayerListedItem(itemId: String) {
        scope.launch {
            try {
                gameEngine.removePlayerListedItem(itemId)
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun getEquipmentById(id: String): EquipmentInstance? {
        return gameEngine.equipmentInstances.value.find { it.id == id }
    }

    fun getEquipmentInstanceById(id: String): EquipmentInstance? {
        return gameEngine.equipmentInstances.value.find { it.id == id }
    }

    @Suppress("DEPRECATION")
    fun getManualById(id: String): ManualInstance? {
        return gameEngine.manualInstances.value.find { it.id == id }
    }

    fun getManualInstanceById(id: String): ManualInstance? {
        return gameEngine.manualInstances.value.find { it.id == id }
    }

    fun getPillById(id: String): Pill? {
        return gameEngine.pills.value.find { it.id == id }
    }

    fun getMaterialById(id: String): Material? {
        return gameEngine.materials.value.find { it.id == id }
    }

    fun getHerbById(id: String): Herb? {
        return gameEngine.herbs.value.find { it.id == id }
    }

    fun getSeedById(id: String): Seed? {
        return gameEngine.seeds.value.find { it.id == id }
    }
}
