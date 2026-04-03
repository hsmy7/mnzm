package com.xianxia.sect.core.data

import com.xianxia.sect.core.model.Dungeon

object DungeonDatabase {
    private val dungeons = mutableMapOf<String, Dungeon>()

    fun addDungeon(dungeon: Dungeon) {
        dungeons[dungeon.id] = dungeon
    }

    fun getDungeon(dungeonId: String): Dungeon? {
        return dungeons[dungeonId]
    }

    fun getAllDungeons(): List<Dungeon> {
        return dungeons.values.toList()
    }

    fun getUnlockedDungeons(): List<Dungeon> {
        return dungeons.values.filter { it.isUnlocked }
    }

    fun clear() {
        dungeons.clear()
    }
}
