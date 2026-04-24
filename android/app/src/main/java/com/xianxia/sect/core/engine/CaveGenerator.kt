package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import kotlin.math.sqrt
import kotlin.random.Random

object CaveGenerator {
    
    private val MAP_WIDTH get() = GameConfig.WorldMap.MAP_WIDTH
    private val MAP_HEIGHT get() = GameConfig.WorldMap.MAP_HEIGHT
    private val BORDER_PADDING get() = GameConfig.WorldMap.BORDER_PADDING
    
    private val caveNames = listOf(
        "化神洞府", "炼虚洞府", "合体洞府", "大乘洞府", "渡劫洞府"
    )
    
    private val cavePrefixes = listOf(
        "玄天", "紫霄", "太虚", "青云", "幽冥",
        "焚天", "冰魄", "龙吟", "凤鸣", "麒麟"
    )
    
    private val caveSuffixes = listOf(
        "仙府", "洞天", "秘境", "福地", "仙居"
    )
    
    private val realmConfigs = listOf(
        CaveRealmConfig(5, "化神", listOf(3, 4, 5)),
        CaveRealmConfig(4, "炼虚", listOf(3, 4, 5)),
        CaveRealmConfig(3, "合体", listOf(4, 5)),
        CaveRealmConfig(2, "大乘", listOf(4, 5, 6)),
        CaveRealmConfig(1, "渡劫", listOf(5, 6))
    )
    
    private val caveSpawnProbabilities = mapOf(
        5 to 0.506,
        4 to 0.25,
        3 to 0.20,
        2 to 0.028,
        1 to 0.016
    )
    
    fun generateCaves(
        existingSects: List<WorldSect>,
        connectionEdges: List<MSTEdge>,
        currentYear: Int,
        currentMonth: Int,
        existingCaves: List<CultivatorCave>,
        maxNewCaves: Int = 2
    ): List<CultivatorCave> {
        val caves = mutableListOf<CultivatorCave>()
        
        val usedPositions = mutableSetOf<Pair<Int, Int>>()
        
        existingSects.forEach { sect ->
            usedPositions.add(Pair(sect.x.toInt(), sect.y.toInt()))
        }
        
        existingCaves.forEach { cave ->
            usedPositions.add(Pair(cave.x.toInt(), cave.y.toInt()))
        }
        
        val newCaveCount = Random.nextInt(0, maxNewCaves + 1)
        
        var attempts = 0
        while (caves.size < newCaveCount && attempts < 5000) {
            attempts++
            
            val x = Random.nextInt(BORDER_PADDING, MAP_WIDTH - BORDER_PADDING)
            val y = Random.nextInt(BORDER_PADDING, MAP_HEIGHT - BORDER_PADDING)
            
            if (!isValidPosition(x, y, usedPositions, existingSects, connectionEdges, existingCaves)) {
                continue
            }
            
            val realmConfig = selectRandomRealm()
            
            val cave = CultivatorCave(
                id = "cave_${System.currentTimeMillis()}_${caves.size}",
                name = generateCaveName(realmConfig.realmName),
                ownerRealm = realmConfig.realm,
                ownerRealmName = realmConfig.realmName,
                x = x.toFloat(),
                y = y.toFloat(),
                spawnYear = currentYear,
                spawnMonth = currentMonth,
                expiryYear = currentYear + 1,
                expiryMonth = currentMonth,
                status = CaveStatus.AVAILABLE
            )
            
            caves.add(cave)
            usedPositions.add(Pair(x, y))
        }
        
        return caves
    }
    
    private fun selectRandomRealm(): CaveRealmConfig {
        val rand = Random.nextDouble()
        var cumulative = 0.0
        
        for ((realm, prob) in caveSpawnProbabilities.toList().sortedByDescending { it.first }) {
            cumulative += prob
            if (rand <= cumulative) {
                return realmConfigs.find { it.realm == realm } ?: realmConfigs.first()
            }
        }
        
        return realmConfigs.first()
    }
    
    private fun generateCaveName(realmName: String): String {
        val prefix = cavePrefixes.random()
        val suffix = caveSuffixes.random()
        return "$prefix${realmName}$suffix"
    }
    
    private fun isValidPosition(
        x: Int,
        y: Int,
        usedPositions: Set<Pair<Int, Int>>,
        sects: List<WorldSect>,
        edges: List<MSTEdge>,
        existingCaves: List<CultivatorCave>
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
            if (isPointNearCurvedPath(x, y, edge, minPathDist)) return false
        }
        
        val minCaveDist = GameConfig.WorldMap.CAVE_MIN_CAVE_DISTANCE
        for (cave in existingCaves) {
            val dist = sqrt(
                (x - cave.x).toDouble() * (x - cave.x).toDouble() +
                (y - cave.y).toDouble() * (y - cave.y).toDouble()
            )
            if (dist < minCaveDist) return false
        }
        
        return true
    }
    
    private fun isPointNearCurvedPath(px: Int, py: Int, edge: MSTEdge, threshold: Double): Boolean {
        val waypoints = WorldMapGenerator.generatePathWaypoints(
            edge.sect1.x, edge.sect1.y,
            edge.sect2.x, edge.sect2.y,
            edge.sect1.id, edge.sect2.id
        )
        
        if (waypoints.isEmpty()) {
            return isPointNearLineSegment(
                px.toDouble(), py.toDouble(),
                edge.sect1.x.toDouble(), edge.sect1.y.toDouble(),
                edge.sect2.x.toDouble(), edge.sect2.y.toDouble(),
                threshold
            )
        }
        
        val points = mutableListOf<Pair<Double, Double>>()
        points.add(Pair(edge.sect1.x.toDouble(), edge.sect1.y.toDouble()))
        for (wp in waypoints) {
            points.add(Pair(wp.first.toDouble(), wp.second.toDouble()))
        }
        points.add(Pair(edge.sect2.x.toDouble(), edge.sect2.y.toDouble()))
        
        for (i in 0 until points.size - 1) {
            if (isPointNearLineSegment(
                    px.toDouble(), py.toDouble(),
                    points[i].first, points[i].second,
                    points[i + 1].first, points[i + 1].second,
                    threshold
                )) {
                return true
            }
        }
        
        return false
    }
    
    private fun isPointNearLineSegment(
        px: Double, py: Double,
        x1: Double, y1: Double,
        x2: Double, y2: Double,
        threshold: Double
    ): Boolean {
        val lineLenSq = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)
        if (lineLenSq == 0.0) {
            return sqrt((px - x1) * (px - x1) + (py - y1) * (py - y1)) < threshold
        }
        
        val t = ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / lineLenSq
        val tClamped = t.coerceIn(0.0, 1.0)
        
        val nearestX = x1 + tClamped * (x2 - x1)
        val nearestY = y1 + tClamped * (y2 - y1)
        
        val dist = sqrt((px - nearestX) * (px - nearestX) + (py - nearestY) * (py - nearestY))
        return dist < threshold
    }
    
    fun getRarityRangeForCave(ownerRealm: Int): List<Int> {
        return realmConfigs.find { it.realm == ownerRealm }?.rarityRange ?: listOf(1, 2, 3)
    }
    
    data class CaveRealmConfig(
        val realm: Int,
        val realmName: String,
        val rarityRange: List<Int>
    )
}

data class MSTEdge(
    val sect1: WorldSect,
    val sect2: WorldSect,
    val weight: Double
)
