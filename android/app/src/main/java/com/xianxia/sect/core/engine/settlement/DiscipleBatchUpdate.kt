package com.xianxia.sect.core.engine.settlement

import com.xianxia.sect.core.model.EquipmentInstance

data class RealmChange(
    val newRealm: Int,
    val newRealmLayer: Int,
    val newCultivation: Double,
    val lifespanGain: Int,
    val hpAfter: Int,
    val mpAfter: Int,
    val pillsConsumed: List<String> = emptyList()
)

data class SalaryChange(
    val amount: Int,
    val paidCountDelta: Int = 0,
    val missedCountDelta: Int = 0,
    val loyaltyDelta: Int = 0,
    val isPaid: Boolean
)

data class DiscipleBatchUpdate(
    var id: String = "",
    var cultivationDelta: Double = 0.0,
    var realmChange: RealmChange? = null,
    var salaryChange: SalaryChange? = null,
    var loyaltyDelta: Int = 0,
    var nurtureUpdates: Map<String, Double> = emptyMap(),
    var proficiencyUpdates: Map<String, Double> = emptyMap(),
    var ageDelta: Int = 0,
    var isDead: Boolean = false
) {
    companion object {
        private const val POOL_CAPACITY = 200
        private val pool = ArrayDeque<DiscipleBatchUpdate>(POOL_CAPACITY)

        fun obtain(): DiscipleBatchUpdate {
            val pooled = pool.removeFirstOrNull()
            return pooled?.apply { reset() } ?: DiscipleBatchUpdate()
        }

        fun recycle(update: DiscipleBatchUpdate) {
            if (pool.size < POOL_CAPACITY) {
                update.reset()
                pool.addLast(update)
            }
        }

        fun recycleAll(updates: List<DiscipleBatchUpdate>) {
            for (update in updates) {
                if (pool.size < POOL_CAPACITY) {
                    update.reset()
                    pool.addLast(update)
                }
            }
        }
    }

    fun reset() {
        id = ""
        cultivationDelta = 0.0
        realmChange = null
        salaryChange = null
        loyaltyDelta = 0
        nurtureUpdates = emptyMap()
        proficiencyUpdates = emptyMap()
        ageDelta = 0
        isDead = false
    }
}

data class GlobalBatchUpdate(
    var spiritStoneDelta: Long = 0,
    var equipmentInstanceUpdates: Map<String, EquipmentInstance> = emptyMap(),
    var manualProficiencyUpdates: Map<String, List<com.xianxia.sect.core.model.ManualProficiencyData>> = emptyMap(),
    var pillRemovals: Set<String> = emptySet(),
    var deadDiscipleIds: Set<String> = emptySet()
) {
    fun reset() {
        spiritStoneDelta = 0
        equipmentInstanceUpdates = emptyMap()
        manualProficiencyUpdates = emptyMap()
        pillRemovals = emptySet()
        deadDiscipleIds = emptySet()
    }

    companion object {
        private const val POOL_CAPACITY = 10
        private val pool = ArrayDeque<GlobalBatchUpdate>(POOL_CAPACITY)

        fun obtain(): GlobalBatchUpdate {
            val pooled = pool.removeFirstOrNull()
            return pooled?.apply { reset() } ?: GlobalBatchUpdate()
        }

        fun recycle(update: GlobalBatchUpdate) {
            if (pool.size < POOL_CAPACITY) {
                update.reset()
                pool.addLast(update)
            }
        }
    }
}
