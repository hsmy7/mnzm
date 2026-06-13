package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.*

data class MutableGameState(
    var gameData: GameData,
    var discipleTables: DiscipleTables,        // 替代 List<Disciple>，组件表存储
    var equipmentStacks: EntityStore<EquipmentStack>,
    var equipmentInstances: EntityStore<EquipmentInstance>,
    var manualStacks: EntityStore<ManualStack>,
    var manualInstances: EntityStore<ManualInstance>,
    var pills: EntityStore<Pill>,
    var materials: EntityStore<Material>,
    var herbs: EntityStore<Herb>,
    var seeds: EntityStore<Seed>,
    var storageBags: EntityStore<StorageBag>,
    var teams: List<ExplorationTeam>,
    var battleLogs: List<BattleLog>,
    var isPaused: Boolean,
    var isLoading: Boolean,
    var isSaving: Boolean,
    var pendingNotification: GameNotification? = null,
    var isSettlementShadow: Boolean = false
)
