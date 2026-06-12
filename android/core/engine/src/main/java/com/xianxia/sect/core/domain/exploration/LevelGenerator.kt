package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import kotlin.math.sqrt
import kotlin.random.Random

object LevelGenerator {

    private val MAP_WIDTH get() = GameConfig.WorldMap.MAP_WIDTH
    private val MAP_HEIGHT get() = GameConfig.WorldMap.MAP_HEIGHT
    private val BORDER_PADDING get() = GameConfig.WorldMap.BORDER_PADDING

    private val guardianPrefixes = listOf(
        "碧眼", "赤焰", "九幽", "玄冰", "紫电", "青冥", "金翅", "银鬃", "黑水", "白虹",
        "幽冥", "焚天", "冰魄", "龙吟", "凤鸣", "裂空", "噬魂", "镇岳", "吞天", "撼地"
    )

    private val guardianSuffixes = listOf(
        "金蟾", "玄龟", "魔蛟", "灵蟒", "妖鹏", "神猿", "古蜥", "鬼蝠", "仙鹤", "石犀",
        "铜虎", "银狼", "铁熊", "血鹰", "幻狐", "雷龙", "寒蛇"
    )

    private val caveNamePrefixes = listOf(
        "玄天", "紫霄", "太虚", "青云", "幽冥", "焚天", "冰魄", "龙吟", "凤鸣"
    )

    private val caveNameSuffixes = listOf(
        "洞府", "秘洞", "灵窟", "仙窟", "古洞"
    )

    fun generateWorldLevels(
        existingSects: List<WorldSect>,
        connectionEdges: List<MSTEdge>,
        currentYear: Int,
        currentMonth: Int,
        existingLevels: List<WorldLevel>,
        maxNewLevels: Int = 6
    ): List<WorldLevel> {
        val newLevels = mutableListOf<WorldLevel>()

        val usedPositions = mutableSetOf<Pair<Int, Int>>()
        existingSects.forEach { sect ->
            usedPositions.add(Pair(sect.x.toInt(), sect.y.toInt()))
        }
        existingLevels.forEach { level ->
            usedPositions.add(Pair(level.x.toInt(), level.y.toInt()))
        }

        val newLevelCount = if (maxNewLevels <= 0) 0 else Random.nextInt(1, maxNewLevels + 1)

        var attempts = 0
        while (newLevels.size < newLevelCount && attempts < 5000) {
            attempts++

            val x = Random.nextInt(BORDER_PADDING, MAP_WIDTH - BORDER_PADDING)
            val y = Random.nextInt(BORDER_PADDING, MAP_HEIGHT - BORDER_PADDING)

            val allLevels = existingLevels + newLevels
            if (!isValidPosition(x, y, usedPositions, existingSects, connectionEdges, allLevels)) {
                continue
            }

            // 随机选择类型：妖兽 80/85，洞府 5/85
            val isCave = Random.nextDouble() < (5.0 / 85.0)

            val level = if (isCave) {
                generateCaveLevel(currentYear, currentMonth, x, y)
            } else {
                generateBeastLevel(currentYear, currentMonth, x, y)
            }

            newLevels.add(level)
            usedPositions.add(Pair(x, y))
        }

        return newLevels
    }

    private fun generateBeastLevel(
        currentYear: Int, currentMonth: Int, x: Int, y: Int
    ): WorldLevel {
        val beastTypeIndex = Random.nextInt(0, 8)
        val beastConfig = GameConfig.Beast.getType(beastTypeIndex)
        val realm = Random.nextInt(0, 10)
        val realmLayer = Random.nextInt(1, 10)
        val count = Random.nextInt(1, 14)
        val realmName = when (realm) {
            0 -> "仙人"; 1 -> "渡劫"; 2 -> "大乘"; 3 -> "合体"; 4 -> "炼虚"
            5 -> "化神"; 6 -> "元婴"; 7 -> "金丹"; 8 -> "筑基"; 9 -> "炼气"
            else -> "炼气"
        }

        // 持续4个月
        val beastNewMonth = currentMonth + 4
        val beastExpiryYear = currentYear + (beastNewMonth - 1) / 12
        val beastExpiryMonth = (beastNewMonth - 1) % 12 + 1

        return WorldLevel(
            type = LevelType.BEAST,
            beastType = beastTypeIndex,
            realm = realm,
            realmLayer = realmLayer,
            beastName = "${beastConfig.prefix}${beastConfig.name}",
            x = x.toFloat(),
            y = y.toFloat(),
            spawnYear = currentYear,
            spawnMonth = currentMonth,
            expiryYear = beastExpiryYear,
            expiryMonth = beastExpiryMonth,
            count = count
        )
    }

    private fun generateCaveLevel(
        currentYear: Int, currentMonth: Int, x: Int, y: Int
    ): WorldLevel {
        val caveRealm = when (Random.nextInt(5)) {
            0 -> 5  // 化神
            1 -> 4  // 炼虚
            2 -> 3  // 合体
            3 -> 2  // 大乘
            4 -> 1  // 渡劫
            else -> 5
        }
        val realmLayer = Random.nextInt(1, 10)
        val caveImageIndex = Random.nextInt(0, 3)
        val guardianName = "${guardianPrefixes.random()}${guardianSuffixes.random()}"
        val realmName = GameConfig.Realm.getName(caveRealm)
        val caveName = "${caveNamePrefixes.random()}$realmName${caveNameSuffixes.random()}"

        // 持续4个月
        val caveNewMonth = currentMonth + 4
        val caveExpiryYear = currentYear + (caveNewMonth - 1) / 12
        val caveExpiryMonth = (caveNewMonth - 1) % 12 + 1

        return WorldLevel(
            type = LevelType.CAVE,
            realm = caveRealm,
            realmLayer = realmLayer,
            guardianName = guardianName,
            caveName = caveName,
            x = x.toFloat(),
            y = y.toFloat(),
            spawnYear = currentYear,
            spawnMonth = currentMonth,
            expiryYear = caveExpiryYear,
            expiryMonth = caveExpiryMonth,
            count = 2,
            caveImageIndex = caveImageIndex
        )
    }

    private fun isValidPosition(
        x: Int, y: Int,
        usedPositions: Set<Pair<Int, Int>>,
        sects: List<WorldSect>,
        edges: List<MSTEdge>,
        existingLevels: List<WorldLevel>
    ): Boolean {
        if (Pair(x, y) in usedPositions) return false

        val minSectDist = GameConfig.WorldMap.CAVE_MIN_SECT_DISTANCE
        for (sect in sects) {
            val dist = sqrt(
                (x - sect.x).toDouble() * (x - sect.x).toDouble() +
                (y - sect.y).toDouble() * (y - sect.y).toDouble()
            )
            if (dist < minSectDist) return false
        }

        val minPathDist = GameConfig.WorldMap.CAVE_MIN_PATH_DISTANCE
        for (edge in edges) {
            if (GeometryUtils.isPointNearCurvedPath(x, y, edge, minPathDist)) return false
        }

        val minLevelDist = GameConfig.WorldMap.LEVEL_MIN_DISTANCE
        for (level in existingLevels) {
            val dist = sqrt(
                (x - level.x).toDouble() * (x - level.x).toDouble() +
                (y - level.y).toDouble() * (y - level.y).toDouble()
            )
            if (dist < minLevelDist) return false
        }

        return true
    }

    fun buildConnectionEdges(sects: List<WorldSect>): List<MSTEdge> {
        val edges = mutableListOf<MSTEdge>()
        for (i in sects.indices) {
            for (j in (i + 1) until sects.size) {
                val distance = sqrt(
                    (sects[i].x - sects[j].x) * (sects[i].x - sects[j].x) +
                    (sects[i].y - sects[j].y) * (sects[i].y - sects[j].y)
                ).toDouble()
                edges.add(MSTEdge(sects[i], sects[j], distance))
            }
        }
        return edges
    }

    fun getCaveReward(realm: Int): CaveRewardConfig {
        return when (realm) {
            5 -> CaveRewardConfig(20000.0, 1 to 2)    // 化神: 灵品~宝品
            4 -> CaveRewardConfig(100000.0, 2 to 3)   // 炼虚: 宝品~玄品
            3 -> CaveRewardConfig(300000.0, 2 to 5)   // 合体: 宝品~地品
            2 -> CaveRewardConfig(700000.0, 3 to 6)   // 大乘: 玄品~天品
            1 -> CaveRewardConfig(1500000.0, 5 to 6)  // 渡劫: 地品~天品
            else -> CaveRewardConfig(20000.0, 1 to 2)
        }
    }

    data class CaveRewardConfig(
        val baseSpiritStones: Double,
        val rarityRange: Pair<Int, Int>
    )
}
