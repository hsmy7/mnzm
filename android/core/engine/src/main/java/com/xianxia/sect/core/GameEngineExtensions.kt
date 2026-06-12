package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.*

/**
 * Convenience extension functions for GameEngine.
 * These provide simplified access patterns for common operations.
 */

/** Get current game year from snapshot */
val GameEngine.currentGameYear: Int get() = gameDataSnapshot.gameYear

/** Get current game month from snapshot */
val GameEngine.currentGameMonth: Int get() = gameDataSnapshot.gameMonth

/** Get current spirit stones from snapshot */
val GameEngine.currentSpiritStones: Long get() = gameDataSnapshot.spiritStones

/** Get current sect name from snapshot */
val GameEngine.currentSectName: String get() = gameDataSnapshot.sectName

/** Check if a disciple is alive by ID */
fun GameEngine.isDiscipleAlive(discipleId: String): Boolean =
    getDiscipleById(discipleId)?.isAlive == true

/** Get all alive disciples */
fun GameEngine.getAliveDisciples(): List<Disciple> =
    disciples.value.filter { it.isAlive }

/** Get current world map sects */
val GameEngine.currentWorldMapSects: List<WorldSect> get() = gameDataSnapshot.worldMapSects

/** Get current placed buildings */
val GameEngine.currentPlacedBuildings: List<GridBuildingData> get() = gameDataSnapshot.placedBuildings

/** Get current player allies */
val GameEngine.playerAllies: List<String> get() = getPlayerAllies()

/** Check if game has been started */
val GameEngine.gameStarted: Boolean get() = isGameStarted()
