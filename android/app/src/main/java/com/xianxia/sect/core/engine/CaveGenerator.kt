package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import kotlin.math.sqrt
import kotlin.random.Random

object CaveGenerator {
    
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
        CaveRealmConfig(5, "化神", listOf(3, 4, 5)),      // 化神洞府：宝品-地品
        CaveRealmConfig(4, "炼虚", listOf(3, 4, 5)),      // 炼虚洞府：宝品-地品
        CaveRealmConfig(3, "合体", listOf(4, 5)),         // 合体洞府：玄品-地品
        CaveRealmConfig(2, "大乘", listOf(4, 5, 6)),      // 大乘洞府：玄品-天品
        CaveRealmConfig(1, "渡劫", listOf(5, 6))          // 渡劫洞府：地品-天品
    )
    
    private val caveSpawnProbabilities = mapOf(
        5 to 0.30,  // 化神洞府 30%
        4 to 0.25,  // 炼虚洞府 25%
        3 to 0.20,  // 合体洞府 20%
        2 to 0.15,  // 大乘洞府 15%
        1 to 0.10   // 渡劫洞府 10%
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
        
        connectionEdges.forEach { edge ->
            addRoutePositions(edge, usedPositions)
        }
        
        val newCaveCount = Random.nextInt(0, maxNewCaves + 1)
        
        var attempts = 0
        while (caves.size < newCaveCount && attempts < 5000) {
            attempts++
            
            val x = Random.nextInt(50, 1950)
            val y = Random.nextInt(50, 950)
            
            if (!isValidPosition(x, y, usedPositions, existingSects, connectionEdges)) {
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
    
    private fun addRoutePositions(edge: MSTEdge, usedPositions: MutableSet<Pair<Int, Int>>) {
        val routeWidth = 50
        val dx = edge.sect2.x - edge.sect1.x
        val dy = edge.sect2.y - edge.sect1.y
        val length = sqrt((dx * dx + dy * dy).toDouble()).toInt()
        
        if (length == 0) return
        
        val steps = length / 10
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val x = (edge.sect1.x + dx * t).toInt()
            val y = (edge.sect1.y + dy * t).toInt()
            
            for (ox in -routeWidth..routeWidth) {
                for (oy in -routeWidth..routeWidth) {
                    usedPositions.add(Pair(x + ox, y + oy))
                }
            }
        }
    }
    
    private fun isValidPosition(
        x: Int,
        y: Int,
        usedPositions: Set<Pair<Int, Int>>,
        sects: List<WorldSect>,
        edges: List<MSTEdge>
    ): Boolean {
        if (Pair(x, y) in usedPositions) return false
        
        for (sect in sects) {
            val dist = sqrt(
                (x - sect.x).toDouble() * (x - sect.x).toDouble() +
                (y - sect.y).toDouble() * (y - sect.y).toDouble()
            )
            if (dist < 80) return false
        }
        
        for (edge in edges) {
            if (isPointNearLine(x, y, edge, 60.0)) return false
        }
        
        return true
    }
    
    private fun isPointNearLine(px: Int, py: Int, edge: MSTEdge, threshold: Double): Boolean {
        val x1 = edge.sect1.x.toDouble()
        val y1 = edge.sect1.y.toDouble()
        val x2 = edge.sect2.x.toDouble()
        val y2 = edge.sect2.y.toDouble()
        
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
