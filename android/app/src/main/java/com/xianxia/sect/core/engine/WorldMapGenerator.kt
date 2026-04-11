package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import kotlin.math.sqrt
import kotlin.random.Random

object WorldMapGenerator {
    private val MAP_WIDTH get() = GameConfig.WorldMap.MAP_WIDTH
    private val MAP_HEIGHT get() = GameConfig.WorldMap.MAP_HEIGHT
    private val BORDER_PADDING get() = GameConfig.WorldMap.BORDER_PADDING
    private val TARGET_SECT_COUNT get() = GameConfig.WorldMap.TARGET_SECT_COUNT

    val INITIAL_SECT_FAVOR get() = GameConfig.WorldMap.INITIAL_SECT_FAVOR
    private val SAME_ALIGNMENT_BONUS get() = GameConfig.WorldMap.SAME_ALIGNMENT_BONUS

    private val RELAXATION_ITERATIONS get() = GameConfig.WorldMap.RELAXATION_ITERATIONS
    private val RELAXATION_STRENGTH get() = GameConfig.WorldMap.RELAXATION_STRENGTH
    private val K_NEAREST_NEIGHBORS get() = GameConfig.WorldMap.K_NEAREST_NEIGHBORS
    private val TARGET_CONNECTIONS_PER_SECT get() = GameConfig.WorldMap.TARGET_CONNECTIONS_PER_SECT
    private val MAX_CONNECTIONS_PER_SECT get() = GameConfig.WorldMap.MAX_CONNECTIONS_PER_SECT
    private val MIN_CONNECTIONS_PER_SECT get() = GameConfig.WorldMap.MIN_CONNECTIONS_PER_SECT
    private val CONNECTION_DISTANCE_LIMIT get() = GameConfig.WorldMap.CONNECTION_DISTANCE_LIMIT

    private val righteousSectNames = listOf(
        "青云门", "紫霄宫", "太华宗", "昆仑派", "峨眉山", "武当山", "青城山", "龙虎山",
        "华山派", "衡山派", "泰山派", "恒山派", "嵩山寺", "终南山", "罗浮山", "括苍山",
        "玄天宗", "天道宗", "无极宗", "太虚宗", "紫阳宗", "纯阳宗", "全真教", "正一教",
        "天师道", "上清宗", "灵宝宗", "神霄派", "清微派", "净明道", "茅山派", "阁皂山",
        "凌霄阁", "飘渺宫", "逍遥宗", "长生殿", "不老山", "永生门", "问道宗", "悟道山",
        "仙霞山", "云雾峰", "紫云宗", "白云观", "青霞山", "丹霞山", "碧云天", "云海宗",
        "浩然宗", "正气门", "仁义堂", "侠义庄", "忠义门", "义薄云", "正道盟", "光明顶",
        "天正义", "正心宗", "明德门", "至善宗", "仁爱堂", "礼义山", "信义门", "忠孝堂"
    )

    private val evilSectNames = listOf(
        "血刀门", "血煞宗", "血影教", "血魂殿", "血魔山", "血灵宗", "血海宫", "血炼堂",
        "嗜血门", "饮血宗", "血河派", "血池山", "血冢", "血陵", "血域", "血渊",
        "幽冥宗", "鬼王谷", "阴山派", "冥河宗", "黄泉门", "幽魂殿", "鬼哭山", "阴风谷",
        "黑风山", "暗影宗", "影杀门", "夜枭谷", "幽冥殿", "鬼门关", "阴曹府", "冥界门",
        "天魔宗", "邪王谷", "魔教", "邪道盟", "万魔殿", "魔音谷", "邪心宗", "魔魂山",
        "魔罗宗", "邪灵派", "魔影门", "邪神殿", "魔道宗", "邪魔山", "魔心谷", "邪月宗",
        "五毒教", "万毒谷", "毒龙宗", "蛊神山", "毒王谷", "百毒门", "毒煞宗", "蛊毒殿",
        "毒心谷", "毒牙山", "毒蝎门", "毒蛛谷", "毒蟾宫", "毒蛇岭", "毒雾山", "毒瘴谷"
    )

    data class WorldGenerationResult(
        val sects: List<WorldSect>,
        val aiSectDisciples: Map<String, List<Disciple>>
    )

    fun generateWorldSects(playerSectName: String = "青云宗"): WorldGenerationResult {
        val sects = mutableListOf<WorldSect>()
        val usedNames = mutableSetOf<String>()
        val aiDisciplesMap = mutableMapOf<String, List<Disciple>>()

        val positions = generateSectPositions(TARGET_SECT_COUNT)

        val playerIndex = Random.nextInt(positions.size)
        val (playerX, playerY) = positions[playerIndex]

        val playerSect = WorldSect(
            id = "player_sect",
            name = playerSectName,
            x = playerX,
            y = playerY,
            isPlayerSect = true,
            isRighteous = true,
            level = 1,
            levelName = "中型宗门",
            disciples = generateDisciplesForLevel(1),
            relation = 100,
            discovered = true
        )
        sects.add(playerSect)
        usedNames.add(playerSectName)

        for (i in positions.indices) {
            if (i == playerIndex) continue

            val (sectX, sectY) = positions[i]
            val (name, isRighteous) = generateUniqueNameWithType(usedNames)
            val levelInfo = generateSectLevelAndDisciples()
            val initialRelation = Random.nextInt(20, 51)

            val sect = WorldSect(
                id = "sect_${sects.size}",
                name = name,
                x = sectX,
                y = sectY,
                isPlayerSect = false,
                isRighteous = isRighteous,
                level = levelInfo.level,
                levelName = levelInfo.levelName,
                disciples = levelInfo.disciples,
                relation = initialRelation,
                discovered = false,
                connectedSectIds = emptyList(),
                tradeItems = emptyList(),
                tradeLastRefreshYear = 0,
                giftPreference = generateRandomGiftPreference()
            )

            val (aiDisciples, _) = AISectDiscipleManager.initializeSectDisciples(sect.name, sect.level)
            aiDisciplesMap[sect.id] = aiDisciples
            sects.add(sect)
        }

        return WorldGenerationResult(
            sects = generateConnections(sects),
            aiSectDisciples = aiDisciplesMap
        )
    }

    private fun generateSectPositions(count: Int): List<Pair<Float, Float>> {
        val availableWidth = MAP_WIDTH - 2 * BORDER_PADDING
        val availableHeight = MAP_HEIGHT - 2 * BORDER_PADDING
        val aspectRatio = availableWidth.toDouble() / availableHeight.toDouble()

        val gridCols = sqrt(count.toDouble() * aspectRatio).toInt().coerceIn(1, count)
        val gridRows = ((count + gridCols - 1) / gridCols).coerceIn(1, count)

        val cellWidth = availableWidth.toDouble() / gridCols
        val cellHeight = availableHeight.toDouble() / gridRows

        val positions = mutableListOf<Pair<Double, Double>>()
        val maxJitterX = cellWidth * 0.2
        val maxJitterY = cellHeight * 0.2

        var placed = 0
        for (row in 0 until gridRows) {
            for (col in 0 until gridCols) {
                if (placed >= count) break

                val centerX = BORDER_PADDING + cellWidth * (col + 0.5)
                val centerY = BORDER_PADDING + cellHeight * (row + 0.5)

                val jitterX = Random.nextDouble(-maxJitterX, maxJitterX)
                val jitterY = Random.nextDouble(-maxJitterY, maxJitterY)

                val x = (centerX + jitterX).coerceIn(
                    BORDER_PADDING.toDouble(),
                    (MAP_WIDTH - BORDER_PADDING).toDouble()
                )
                val y = (centerY + jitterY).coerceIn(
                    BORDER_PADDING.toDouble(),
                    (MAP_HEIGHT - BORDER_PADDING).toDouble()
                )

                positions.add(Pair(x, y))
                placed++
            }
        }

        applyRelaxation(positions)

        return positions.map { Pair(it.first.toFloat(), it.second.toFloat()) }
    }

    private fun applyRelaxation(positions: MutableList<Pair<Double, Double>>) {
        val idealDistance = calculateIdealDistance(positions.size)
        val strength = RELAXATION_STRENGTH
        val iterations = RELAXATION_ITERATIONS

        for (iter in 0 until iterations) {
            val forces = Array(positions.size) { doubleArrayOf(0.0, 0.0) }

            for (i in positions.indices) {
                for (j in (i + 1) until positions.size) {
                    val dx = positions[i].first - positions[j].first
                    val dy = positions[i].second - positions[j].second
                    val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1.0)

                    if (dist < idealDistance) {
                        val repulsionForce = (idealDistance - dist) / dist * strength
                        forces[i][0] += dx * repulsionForce
                        forces[i][1] += dy * repulsionForce
                        forces[j][0] -= dx * repulsionForce
                        forces[j][1] -= dy * repulsionForce
                    }
                }
            }

            for (i in positions.indices) {
                val x = positions[i].first
                val y = positions[i].second
                val margin = BORDER_PADDING.toDouble()
                val boundaryForce = strength * 30.0

                if (x < margin + 80) forces[i][0] += boundaryForce * (1.0 - (x - margin) / 80.0)
                if (x > MAP_WIDTH - margin - 80) forces[i][0] -= boundaryForce * (1.0 - (MAP_WIDTH - margin - x) / 80.0)
                if (y < margin + 80) forces[i][1] += boundaryForce * (1.0 - (y - margin) / 80.0)
                if (y > MAP_HEIGHT - margin - 80) forces[i][1] -= boundaryForce * (1.0 - (MAP_HEIGHT - margin - y) / 80.0)

                val newX = (x + forces[i][0]).coerceIn(margin, (MAP_WIDTH - margin).toDouble())
                val newY = (y + forces[i][1]).coerceIn(margin, (MAP_HEIGHT - margin).toDouble())
                positions[i] = Pair(newX, newY)
            }
        }
    }

    private fun calculateIdealDistance(count: Int): Double {
        val availableWidth = MAP_WIDTH - 2 * BORDER_PADDING
        val availableHeight = MAP_HEIGHT - 2 * BORDER_PADDING
        val area = availableWidth.toDouble() * availableHeight
        return sqrt(area / count)
    }

    private fun generateUniqueNameWithType(usedNames: MutableSet<String>): Pair<String, Boolean> {
        val rand = Random.nextDouble()

        return if (rand < 0.67) {
            val availableNames = righteousSectNames.filter { it !in usedNames }
            val name = if (availableNames.isNotEmpty()) {
                availableNames.random()
            } else {
                generateFallbackName("正道", usedNames)
            }
            usedNames.add(name)
            Pair(name, true)
        } else {
            val availableNames = evilSectNames.filter { it !in usedNames }
            val name = if (availableNames.isNotEmpty()) {
                availableNames.random()
            } else {
                generateFallbackName("魔道", usedNames)
            }
            usedNames.add(name)
            Pair(name, false)
        }
    }

    private fun generateFallbackName(prefix: String, usedNames: MutableSet<String>): String {
        val suffixes = listOf("宗", "门", "派", "宫", "殿", "阁", "山", "谷")
        val chars = listOf("天", "地", "玄", "黄", "金", "木", "水", "火", "土", "风", "雷", "云", "雾", "星", "月", "日")
        var name: String
        do {
            name = chars.random() + chars.random() + suffixes.random()
        } while (name in usedNames)
        return name
    }

    fun generateConnections(sects: List<WorldSect>): List<WorldSect> {
        if (sects.size <= 1) return sects

        val result = sects.toMutableList()
        val connections = mutableMapOf<String, MutableSet<String>>()
        sects.forEach { connections[it.id] = mutableSetOf() }
        val sectMap = sects.associateBy { it.id }

        val allEdges = mutableListOf<Edge>()
        for (i in sects.indices) {
            for (j in (i + 1) until sects.size) {
                val dist = calculateDistance(sects[i], sects[j])
                allEdges.add(Edge(sects[i].id, sects[j].id, dist))
            }
        }
        allEdges.sortBy { it.dist }

        val parent = mutableMapOf<String, String>()
        fun find(x: String): String {
            if (parent[x] != x) parent[x] = find(parent[x]!!)
            return parent[x]!!
        }
        fun union(x: String, y: String) {
            val rootX = find(x)
            val rootY = find(y)
            if (rootX != rootY) parent[rootX] = rootY
        }

        sects.forEach { parent[it.id] = it.id }

        for (edge in allEdges) {
            if (find(edge.from) != find(edge.to)) {
                union(edge.from, edge.to)
                connections[edge.from]!!.add(edge.to)
                connections[edge.to]!!.add(edge.from)
            }
            if (sects.map { find(it.id) }.toSet().size == 1) break
        }

        val kNearest = mutableMapOf<String, List<Pair<String, Double>>>()
        for (sect in sects) {
            val neighbors = allEdges
                .filter { it.from == sect.id || it.to == sect.id }
                .map { edge ->
                    val neighborId = if (edge.from == sect.id) edge.to else edge.from
                    Pair(neighborId, edge.dist)
                }
                .sortedBy { it.second }
                .take(K_NEAREST_NEIGHBORS)
            kNearest[sect.id] = neighbors
        }

        val sortedSects = sects.sortedBy { connections[it.id]!!.size }

        for (sect in sortedSects) {
            val currentCount = connections[sect.id]!!.size
            if (currentCount >= TARGET_CONNECTIONS_PER_SECT) continue

            val needed = TARGET_CONNECTIONS_PER_SECT - currentCount
            val candidates = kNearest[sect.id]!!
                .filter { neighbor ->
                    neighbor.first !in connections[sect.id]!! &&
                    connections[neighbor.first]!!.size < MAX_CONNECTIONS_PER_SECT
                }
                .sortedBy { neighbor ->
                    val crossingPenalty = countEdgeCrossings(
                        sect.id, neighbor.first, connections, sectMap
                    )
                    neighbor.second + crossingPenalty * 300.0
                }
                .take(needed)

            for ((neighborId, _) in candidates) {
                connections[sect.id]!!.add(neighborId)
                connections[neighborId]!!.add(sect.id)
            }
        }

        for (sect in sects) {
            val currentCount = connections[sect.id]!!.size
            if (currentCount >= MIN_CONNECTIONS_PER_SECT) continue

            val needed = MIN_CONNECTIONS_PER_SECT - currentCount
            val candidates = allEdges
                .filter { edge ->
                    val neighborId = if (edge.from == sect.id) edge.to else edge.from
                    (edge.from == sect.id || edge.to == sect.id) &&
                    neighborId !in connections[sect.id]!! &&
                    connections[neighborId]!!.size < MAX_CONNECTIONS_PER_SECT &&
                    edge.dist <= CONNECTION_DISTANCE_LIMIT
                }
                .sortedBy { it.dist }
                .take(needed)

            for (edge in candidates) {
                val neighborId = if (edge.from == sect.id) edge.to else edge.from
                if (connections[sect.id]!!.size < MAX_CONNECTIONS_PER_SECT &&
                    connections[neighborId]!!.size < MAX_CONNECTIONS_PER_SECT) {
                    connections[sect.id]!!.add(neighborId)
                    connections[neighborId]!!.add(sect.id)
                }
            }
        }

        for (i in result.indices) {
            val sectId = result[i].id
            result[i] = result[i].copy(connectedSectIds = connections[sectId]!!.toList())
        }

        return result
    }

    private fun countEdgeCrossings(
        fromId: String,
        toId: String,
        connections: Map<String, Set<String>>,
        sectMap: Map<String, WorldSect>
    ): Int {
        val from = sectMap[fromId] ?: return 0
        val to = sectMap[toId] ?: return 0
        var crossings = 0
        val processed = mutableSetOf<Pair<String, String>>()

        for ((sectId, connectedIds) in connections) {
            for (connectedId in connectedIds) {
                val edgeKey = if (sectId < connectedId) sectId to connectedId else connectedId to sectId
                if (edgeKey in processed) continue
                processed.add(edgeKey)

                if (sectId == fromId || sectId == toId || connectedId == fromId || connectedId == toId) continue

                val sect1 = sectMap[sectId] ?: continue
                val sect2 = sectMap[connectedId] ?: continue

                if (segmentsIntersect(
                        from.x, from.y, to.x, to.y,
                        sect1.x, sect1.y, sect2.x, sect2.y
                    )) {
                    crossings++
                }
            }
        }

        return crossings
    }

    private fun segmentsIntersect(
        x1: Float, y1: Float, x2: Float, y2: Float,
        x3: Float, y3: Float, x4: Float, y4: Float
    ): Boolean {
        fun cross(ox: Float, oy: Float, px: Float, py: Float, qx: Float, qy: Float): Float {
            return (px - ox) * (qy - oy) - (py - oy) * (qx - ox)
        }

        val d1 = cross(x3, y3, x4, y4, x1, y1)
        val d2 = cross(x3, y3, x4, y4, x2, y2)
        val d3 = cross(x1, y1, x2, y2, x3, y3)
        val d4 = cross(x1, y1, x2, y2, x4, y4)

        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
        ) {
            return true
        }

        return false
    }

    private fun calculateDistance(sect1: WorldSect, sect2: WorldSect): Double {
        val dx = (sect1.x - sect2.x).toDouble()
        val dy = (sect1.y - sect2.y).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    private fun generateSectLevelAndDisciples(): SectLevelInfo {
        val levelWeights = listOf(0.4, 0.35, 0.2, 0.05)
        val rand = Random.nextDouble()
        var level = 0
        var cumulative = 0.0

        for (i in levelWeights.indices) {
            cumulative += levelWeights[i]
            if (rand <= cumulative) {
                level = i
                break
            }
        }

        val levelNames = listOf("小型宗门", "中型宗门", "大型宗门", "顶级宗门")
        val maxRealmByLevel = listOf(6, 4, 3, 1)
        val maxRealm = maxRealmByLevel[level]

        val disciples = mutableMapOf<Int, Int>()
        for (realm in 0..9) {
            disciples[realm] = 0
        }

        disciples[maxRealm] = if (level == 3) {
            Random.nextInt(1, 4)
        } else {
            Random.nextInt(1, 6)
        }

        for (realm in (maxRealm + 1)..9) {
            disciples[realm] = Random.nextInt(5, 21)
        }

        return SectLevelInfo(level, levelNames[level], disciples)
    }

    private fun generateDisciplesForLevel(level: Int): Map<Int, Int> {
        val disciples = mutableMapOf<Int, Int>()
        val maxRealmByLevel = listOf(6, 4, 3, 1)
        val maxRealm = maxRealmByLevel.getOrElse(level) { 9 }

        for (realm in 0..9) {
            disciples[realm] = 0
        }

        disciples[maxRealm] = Random.nextInt(1, 6)

        for (realm in (maxRealm + 1)..9) {
            disciples[realm] = Random.nextInt(5, 21)
        }

        return disciples
    }

    fun initializeSectRelations(sects: List<WorldSect>): List<SectRelation> {
        val relations = mutableListOf<SectRelation>()
        val playerSect = sects.find { it.isPlayerSect }
        val aiSects = sects.filter { !it.isPlayerSect }

        for (i in aiSects.indices) {
            for (j in i + 1 until aiSects.size) {
                val sect1 = aiSects[i]
                val sect2 = aiSects[j]

                val initialFavor = calculateInitialFavor(sect1.isRighteous == sect2.isRighteous)

                relations.add(SectRelation(
                    sectId1 = minOf(sect1.id, sect2.id),
                    sectId2 = maxOf(sect1.id, sect2.id),
                    favor = initialFavor,
                    lastInteractionYear = 0
                ))
            }
        }

        if (playerSect != null) {
            for (aiSect in aiSects) {
                relations.add(SectRelation(
                    sectId1 = minOf(playerSect.id, aiSect.id),
                    sectId2 = maxOf(playerSect.id, aiSect.id),
                    favor = INITIAL_SECT_FAVOR,
                    lastInteractionYear = 0
                ))
            }
        }

        return relations
    }

    fun calculateInitialFavorForSects(sect1: WorldSect, sect2: WorldSect): Int {
        return calculateInitialFavor(sect1.isRighteous == sect2.isRighteous)
    }

    private fun calculateInitialFavor(sameAlignment: Boolean): Int {
        var favor = INITIAL_SECT_FAVOR
        if (sameAlignment) {
            favor += SAME_ALIGNMENT_BONUS
        }
        return favor.coerceIn(10, 90)
    }

    private fun generateRandomGiftPreference(): GiftPreferenceType {
        val rand = Random.nextDouble()
        return when {
            rand < 0.25 -> GiftPreferenceType.EQUIPMENT
            rand < 0.50 -> GiftPreferenceType.MANUAL
            rand < 0.75 -> GiftPreferenceType.PILL
            rand < 0.90 -> GiftPreferenceType.SPIRIT_STONE
            else -> GiftPreferenceType.NONE
        }
    }

    private data class Edge(val from: String, val to: String, val dist: Double)

    data class SectLevelInfo(
        val level: Int,
        val levelName: String,
        val disciples: Map<Int, Int>
    )
}
