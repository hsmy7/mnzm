package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.ExplorationTeam
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameEvent
import com.xianxia.sect.core.model.BattleLog

data class DiffResult<T>(
    val added: List<T> = emptyList(),
    val removed: List<T> = emptyList(),
    val modified: List<T> = emptyList(),
    val hasChanges: Boolean = added.isNotEmpty() || removed.isNotEmpty() || modified.isNotEmpty()
)

object DiffUpdateSystem {
    
    private const val CULTIVATION_THRESHOLD = 0.01
    private const val QUANTITY_THRESHOLD = 0
    
    fun <T> diffLists(
        oldList: List<T>,
        newList: List<T>,
        idSelector: (T) -> String,
        contentComparator: (T, T) -> Boolean = { a, b -> a == b }
    ): DiffResult<T> {
        val oldMap = oldList.associateBy(idSelector)
        val newMap = newList.associateBy(idSelector)
        
        val added = mutableListOf<T>()
        val removed = mutableListOf<T>()
        val modified = mutableListOf<T>()
        
        newMap.forEach { (id, newItem) ->
            val oldItem = oldMap[id]
            when {
                oldItem == null -> added.add(newItem)
                !contentComparator(oldItem, newItem) -> modified.add(newItem)
            }
        }
        
        oldMap.forEach { (id, oldItem) ->
            if (id !in newMap) {
                removed.add(oldItem)
            }
        }
        
        return DiffResult(
            added = added,
            removed = removed,
            modified = modified,
            hasChanges = added.isNotEmpty() || removed.isNotEmpty() || modified.isNotEmpty()
        )
    }
    
    fun diffDisciples(
        oldList: List<Disciple>,
        newList: List<Disciple>
    ): DiffResult<Disciple> {
        return diffLists(
            oldList = oldList,
            newList = newList,
            idSelector = { it.id },
            contentComparator = { old, new ->
                old.id == new.id &&
                old.name == new.name &&
                old.realm == new.realm &&
                old.cultivation == new.cultivation &&
                old.isAlive == new.isAlive &&
                old.status == new.status &&
                kotlin.math.abs(old.cultivation - new.cultivation) < CULTIVATION_THRESHOLD
            }
        )
    }
    
    fun diffEquipmentStacks(
        oldList: List<EquipmentStack>,
        newList: List<EquipmentStack>
    ): DiffResult<EquipmentStack> {
        return diffLists(
            oldList = oldList,
            newList = newList,
            idSelector = { it.id },
            contentComparator = { old, new ->
                old.id == new.id &&
                old.quantity == new.quantity
            }
        )
    }

    fun diffEquipmentInstances(
        oldList: List<EquipmentInstance>,
        newList: List<EquipmentInstance>
    ): DiffResult<EquipmentInstance> {
        return diffLists(
            oldList = oldList,
            newList = newList,
            idSelector = { it.id },
            contentComparator = { old, new ->
                old.id == new.id &&
                old.nurtureLevel == new.nurtureLevel &&
                old.nurtureProgress == new.nurtureProgress &&
                old.ownerId == new.ownerId &&
                old.isEquipped == new.isEquipped
            }
        )
    }

    fun diffManualStacks(
        oldList: List<ManualStack>,
        newList: List<ManualStack>
    ): DiffResult<ManualStack> {
        return diffLists(
            oldList = oldList,
            newList = newList,
            idSelector = { it.id },
            contentComparator = { old, new ->
                old.id == new.id &&
                old.quantity == new.quantity
            }
        )
    }

    fun diffManualInstances(
        oldList: List<ManualInstance>,
        newList: List<ManualInstance>
    ): DiffResult<ManualInstance> {
        return diffLists(
            oldList = oldList,
            newList = newList,
            idSelector = { it.id },
            contentComparator = { old, new ->
                old.id == new.id &&
                old.ownerId == new.ownerId
            }
        )
    }
    
    fun diffPills(
        oldList: List<Pill>,
        newList: List<Pill>
    ): DiffResult<Pill> {
        return diffLists(
            oldList = oldList,
            newList = newList,
            idSelector = { it.id },
            contentComparator = { old, new ->
                old.id == new.id &&
                old.quantity == new.quantity
            }
        )
    }
    
    fun diffMaterials(
        oldList: List<Material>,
        newList: List<Material>
    ): DiffResult<Material> {
        return diffLists(
            oldList = oldList,
            newList = newList,
            idSelector = { it.id },
            contentComparator = { old, new ->
                old.id == new.id &&
                old.quantity == new.quantity
            }
        )
    }
    
    fun diffHerbs(
        oldList: List<Herb>,
        newList: List<Herb>
    ): DiffResult<Herb> {
        return diffLists(
            oldList = oldList,
            newList = newList,
            idSelector = { it.id },
            contentComparator = { old, new ->
                old.id == new.id &&
                old.quantity == new.quantity
            }
        )
    }
    
    fun diffSeeds(
        oldList: List<Seed>,
        newList: List<Seed>
    ): DiffResult<Seed> {
        return diffLists(
            oldList = oldList,
            newList = newList,
            idSelector = { it.id },
            contentComparator = { old, new ->
                old.id == new.id &&
                old.quantity == new.quantity
            }
        )
    }
    
    fun diffTeams(
        oldList: List<ExplorationTeam>,
        newList: List<ExplorationTeam>
    ): DiffResult<ExplorationTeam> {
        return diffLists(
            oldList = oldList,
            newList = newList,
            idSelector = { it.id },
            contentComparator = { old, new ->
                old.id == new.id &&
                old.status == new.status &&
                old.progress == new.progress
            }
        )
    }
    
    fun diffBuildingSlots(
        oldList: List<BuildingSlot>,
        newList: List<BuildingSlot>
    ): DiffResult<BuildingSlot> {
        return diffLists(
            oldList = oldList,
            newList = newList,
            idSelector = { it.id },
            contentComparator = { old, new ->
                old.id == new.id &&
                old.status == new.status &&
                old.discipleId == new.discipleId &&
                old.recipeId == new.recipeId
            }
        )
    }
    
    fun diffGameData(
        oldData: GameData,
        newData: GameData
    ): Boolean {
        return oldData.gameYear != newData.gameYear ||
               oldData.gameMonth != newData.gameMonth ||
               oldData.spiritStones != newData.spiritStones ||
               oldData.spiritHerbs != newData.spiritHerbs ||
               oldData.librarySlots != newData.librarySlots ||
               oldData.elderSlots != newData.elderSlots ||
               oldData.manualProficiencies != newData.manualProficiencies
    }
    
    fun <T> applyDiff(
        currentList: List<T>,
        diff: DiffResult<T>,
        idSelector: (T) -> String
    ): List<T> {
        if (!diff.hasChanges) return currentList
        
        val result = currentList.toMutableList()
        
        diff.removed.forEach { removedItem ->
            val id = idSelector(removedItem)
            result.removeAll { idSelector(it) == id }
        }
        
        diff.modified.forEach { modifiedItem ->
            val id = idSelector(modifiedItem)
            val index = result.indexOfFirst { idSelector(it) == id }
            if (index >= 0) {
                result[index] = modifiedItem
            }
        }
        
        result.addAll(diff.added)
        
        return result
    }
}

data class IncrementalUpdate(
    val disciples: DiffResult<Disciple>? = null,
    val equipmentStacks: DiffResult<EquipmentStack>? = null,
    val equipmentInstances: DiffResult<EquipmentInstance>? = null,
    val manualStacks: DiffResult<ManualStack>? = null,
    val manualInstances: DiffResult<ManualInstance>? = null,
    val pills: DiffResult<Pill>? = null,
    val materials: DiffResult<Material>? = null,
    val herbs: DiffResult<Herb>? = null,
    val seeds: DiffResult<Seed>? = null,
    val teams: DiffResult<ExplorationTeam>? = null,
    val gameDataChanged: Boolean = false
) {
    val hasAnyChanges: Boolean
        get() = (disciples?.hasChanges == true) ||
                (equipmentStacks?.hasChanges == true) ||
                (equipmentInstances?.hasChanges == true) ||
                (manualStacks?.hasChanges == true) ||
                (manualInstances?.hasChanges == true) ||
                (pills?.hasChanges == true) ||
                (materials?.hasChanges == true) ||
                (herbs?.hasChanges == true) ||
                (seeds?.hasChanges == true) ||
                (teams?.hasChanges == true) ||
                gameDataChanged
}
