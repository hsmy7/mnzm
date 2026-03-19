package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.*
import kotlin.random.Random

object WorldMapGenerator {
    private const val MAP_WIDTH = 4000
    private const val MAP_HEIGHT = 3500
    private const val SECT_RADIUS = 70
    private const val MIN_DISTANCE = 80
    private const val MAX_CONNECTION_DISTANCE = 500.0
    private const val BORDER_PADDING = 150
    private const val TARGET_SECT_COUNT = 55
    private const val MAX_ATTEMPTS = 50000
    
    // 区块数据类
    private data class MapRegion(
        val index: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private val sectNames = listOf(
        "天剑宗", "万魔窟", "青云城", "妖兽森林", "炼丹阁",
        "血煞门", "灵虚观", "幽冥谷", "龙宫", "天机城",
        "焚天谷", "冰魄宫", "玄天宗", "合欢派", "鬼王宗",
        "仙剑派", "无量寺", "百花谷", "铁剑门", "飞星阁",
        "金刚寺", "紫霞宫", "玉虚观", "太虚门", "逍遥派",
        "神刀堂", "毒龙教", "万兽宗", "星宿派", "昆仑派",
        "蓬莱阁", "方丈山", "瀛洲岛", "丹霞谷", "黑水宗",
        "天音寺", "烈火宗", "寒冰宫", "雷音阁", "幻剑门",
        "阴阳宗", "五行门", "九霄宫", "碧海潮", "苍穹派",
        "落霞谷", "云梦泽", "赤焰山", "青城派", "峨眉山",
        "武当山", "华山派", "嵩山寺", "衡山宗", "恒山派",
        "青龙门", "白虎堂", "朱雀宫", "玄武观", "麒麟阁"
    )
    
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
    
    fun generateWorldSects(playerSectName: String = "青云宗"): List<WorldSect> {
        val sects = mutableListOf<WorldSect>()
        val usedPositions = mutableListOf<Pair<Int, Int>>()
        val usedNames = mutableSetOf<String>()
        
        // 步骤1：将地图划分为55个大小相近的区块
        val regions = divideMapIntoRegions(TARGET_SECT_COUNT)
        
        // 步骤2：随机选择一个区块作为玩家宗门
        val shuffledRegions = regions.shuffled()
        val playerRegion = shuffledRegions[0]
        val playerX = playerRegion.centerX
        val playerY = playerRegion.centerY + Random.nextInt(-30, 31) // 在中心附近随机偏移
        
        val playerSect = WorldSect(
            id = "player_sect",
            name = playerSectName,
            x = playerX.toFloat(),
            y = playerY.toFloat(),
            isPlayerSect = true,
            isRighteous = true,
            level = 1,
            levelName = "中型宗门",
            disciples = generateDisciplesForLevel(1),
            relation = 100,
            discovered = true
        )
        sects.add(playerSect)
        usedPositions.add(Pair(playerX, playerY))
        usedNames.add(playerSectName)
        
        // 步骤3：为每个剩余区块生成一个宗门
        for (i in 1 until shuffledRegions.size) {
            val region = shuffledRegions[i]
            
            // 在区块中心附近随机生成位置
            val (sectX, sectY) = generatePositionInRegion(region, usedPositions)
            
            val (name, isRighteous) = generateUniqueNameWithType(usedNames)
            val levelInfo = generateSectLevelAndDisciples()
            
            val initialRelation = Random.nextInt(20, 51)
            
            val sect = WorldSect(
                id = "sect_${sects.size}",
                name = name,
                x = sectX.toFloat(),
                y = sectY.toFloat(),
                isPlayerSect = false,
                isRighteous = isRighteous,
                level = levelInfo.level,
                levelName = levelInfo.levelName,
                disciples = levelInfo.disciples,
                relation = initialRelation,
                discovered = false,
                connectedSectIds = emptyList(),
                tradeItems = emptyList(),
                tradeLastRefreshYear = 0
            )
            
            val sectWithDisciples = AISectDiscipleManager.initializeSectDisciples(sect)
            sects.add(sectWithDisciples)
            usedNames.add(name)
            usedPositions.add(Pair(sectX, sectY))
        }
        
        return generateConnections(sects)
    }
    
    /**
     * 将地图划分为指定数量的区块
     * 使用 5x11 均匀网格布局，确保生成恰好 55 个区块
     */
    private fun divideMapIntoRegions(targetCount: Int): List<MapRegion> {
        val availableWidth = MAP_WIDTH - 2 * BORDER_PADDING
        val availableHeight = MAP_HEIGHT - 2 * BORDER_PADDING
        
        val regions = mutableListOf<MapRegion>()
        var regionIndex = 0

        // 使用 5x11 均匀网格布局 (5 * 11 = 55)
        val gridCols = 5
        val gridRows = 11

        val cellWidth = availableWidth / gridCols
        val cellHeight = availableHeight / gridRows

        for (row in 0 until gridRows) {
            for (col in 0 until gridCols) {
                if (regionIndex >= targetCount) break

                val left = BORDER_PADDING + col * cellWidth
                val top = BORDER_PADDING + row * cellHeight
                val right = if (col == gridCols - 1) MAP_WIDTH - BORDER_PADDING else left + cellWidth
                val bottom = if (row == gridRows - 1) MAP_HEIGHT - BORDER_PADDING else top + cellHeight

                regions.add(MapRegion(regionIndex, left, top, right, bottom))
                regionIndex++
            }
        }
        
        return regions
    }
    
    /**
     * 在指定区块内生成宗门位置
     * 优先在区块中心附近，避免与其他宗门重叠
     */
    private fun generatePositionInRegion(
        region: MapRegion,
        usedPositions: List<Pair<Int, Int>>
    ): Pair<Int, Int> {
        val padding = 30 // 区块内边距
        
        // 尝试在中心附近找到合适位置
        var attempts = 0
        val maxAttempts = 100
        
        while (attempts < maxAttempts) {
            attempts++
            
            // 在区块中心附近随机偏移
            val maxOffsetX = (region.width / 2 - padding).coerceAtLeast(10)
            val maxOffsetY = (region.height / 2 - padding).coerceAtLeast(10)
            
            val offsetX = Random.nextInt(-maxOffsetX, maxOffsetX + 1)
            val offsetY = Random.nextInt(-maxOffsetY, maxOffsetY + 1)
            
            val x = (region.centerX + offsetX).coerceIn(region.left + padding, region.right - padding)
            val y = (region.centerY + offsetY).coerceIn(region.top + padding, region.bottom - padding)
            
            // 检查与其他宗门的距离
            var validPosition = true
            for (pos in usedPositions) {
                val dist = Math.sqrt(
                    Math.pow((x - pos.first).toDouble(), 2.0) + 
                    Math.pow((y - pos.second).toDouble(), 2.0)
                )
                if (dist < MIN_DISTANCE) {
                    validPosition = false
                    break
                }
            }
            
            if (validPosition) {
                return Pair(x, y)
            }
        }
        
        // 如果中心附近找不到位置，在区块内随机尝试
        val randomX = Random.nextInt(region.left + padding, region.right - padding)
        val randomY = Random.nextInt(region.top + padding, region.bottom - padding)
        return Pair(randomX, randomY)
    }
    
    /**
     * 生成唯一的宗门名称
     */
    private fun generateUniqueName(usedNames: MutableSet<String>): String {
        val availableNames = sectNames.filter { it !in usedNames }
        return if (availableNames.isNotEmpty()) {
            availableNames.random()
        } else {
            val baseName = sectNames.random()
            var newName = baseName
            var suffix = 1
            while (newName in usedNames) {
                newName = "${baseName}_${suffix}"
                suffix++
            }
            newName
        }
    }
    
    /**
     * 生成唯一的宗门名称（带正道/魔道类型）
     * 正道宗门 67%，魔道宗门 33%
     */
    private fun generateUniqueNameWithType(usedNames: MutableSet<String>): Pair<String, Boolean> {
        val rand = Random.nextDouble()
        
        return if (rand < 0.67) {
            // 正道宗门
            val availableNames = righteousSectNames.filter { it !in usedNames }
            val name = if (availableNames.isNotEmpty()) {
                availableNames.random()
            } else {
                generateFallbackName("正道", usedNames)
            }
            usedNames.add(name)
            Pair(name, true)
        } else {
            // 魔道宗门
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
    
    /**
     * 生成备用名称
     */
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
        if (sects.isEmpty()) return sects
        
        val result = sects.toMutableList()
        val connections = mutableMapOf<String, MutableList<String>>()
        val CONNECTION_DISTANCE_LIMIT = 800.0
        
        sects.forEach { connections[it.id] = mutableListOf() }
        
        val allEdges = mutableListOf<Triple<String, String, Double>>()
        val sectDistances = mutableMapOf<String, MutableList<Pair<String, Double>>>()
        sects.forEach { sectDistances[it.id] = mutableListOf() }
        
        for (i in sects.indices) {
            for (j in (i + 1) until sects.size) {
                val dist = calculateDistance(sects[i], sects[j])
                allEdges.add(Triple(sects[i].id, sects[j].id, dist))
                if (dist <= CONNECTION_DISTANCE_LIMIT) {
                    sectDistances[sects[i].id]?.add(sects[j].id to dist)
                    sectDistances[sects[j].id]?.add(sects[i].id to dist)
                }
            }
        }
        
        sectDistances.forEach { (_, distances) -> distances.sortBy { it.second } }
        allEdges.sortBy { it.third }
        
        val targetConnectionsPerSect = 2
        val MAX_CONNECTIONS_PER_SECT = 3
        
        for (sect in sects) {
            val currentConnections = connections[sect.id] ?: mutableListOf()
            val availableNeighbors = sectDistances[sect.id] ?: emptyList()
            val neededConnections = (targetConnectionsPerSect - currentConnections.size).coerceAtLeast(0)
            
            if (neededConnections > 0) {
                val candidates = availableNeighbors
                    .filter { 
                        it.first !in currentConnections && 
                        (connections[it.first]?.size ?: 0) < MAX_CONNECTIONS_PER_SECT 
                    }
                    .sortedBy { connections[it.first]?.size ?: 0 }
                    .take(neededConnections)
                
                for ((neighborId, _) in candidates) {
                    connections[sect.id]?.add(neighborId)
                    connections[neighborId]?.add(sect.id)
                }
            }
        }
        
        val parent = mutableMapOf<String, String>()
        
        fun find(x: String): String {
            if (parent[x] != x) {
                parent[x] = find(parent[x]!!)
            }
            return parent[x] ?: x
        }
        
        fun union(x: String, y: String) {
            val rootX = find(x)
            val rootY = find(y)
            if (rootX != rootY) {
                parent[rootX] = rootY
            }
        }
        
        sects.forEach { parent[it.id] = it.id }
        
        for ((sectId, connectedIds) in connections) {
            for (connectedId in connectedIds) {
                union(sectId, connectedId)
            }
        }
        
        var rootIds = sects.map { find(it.id) }.toSet()
        var edgeIndex = 0
        while (rootIds.size > 1 && edgeIndex < allEdges.size) {
            val (from, to, _) = allEdges[edgeIndex]
            if (find(from) != find(to)) {
                union(from, to)
                if (to !in (connections[from] ?: emptyList())) {
                    connections[from]?.add(to)
                    connections[to]?.add(from)
                }
            }
            edgeIndex++
            rootIds = sects.map { find(it.id) }.toSet()
        }
        
        val MIN_CONNECTIONS_PER_SECT = 2
        for (sect in sects) {
            var currentConnections = connections[sect.id] ?: mutableListOf()
            var neededConnections = MIN_CONNECTIONS_PER_SECT - currentConnections.size
            
            while (neededConnections > 0) {
                val availableNeighbors = sectDistances[sect.id] ?: emptyList()
                
                val candidates = availableNeighbors
                    .filter { 
                        it.first !in currentConnections && 
                        (connections[it.first]?.size ?: 0) < MAX_CONNECTIONS_PER_SECT &&
                        currentConnections.size < MAX_CONNECTIONS_PER_SECT
                    }
                    .take(neededConnections)
                
                if (candidates.isEmpty()) break
                
                for ((neighborId, _) in candidates) {
                    if (currentConnections.size < MAX_CONNECTIONS_PER_SECT &&
                        (connections[neighborId]?.size ?: 0) < MAX_CONNECTIONS_PER_SECT) {
                        connections[sect.id]?.add(neighborId)
                        connections[neighborId]?.add(sect.id)
                        currentConnections = connections[sect.id] ?: mutableListOf()
                    }
                }
                
                neededConnections = MIN_CONNECTIONS_PER_SECT - currentConnections.size
            }
        }
        
        for (i in result.indices) {
            val sectId = result[i].id
            result[i] = result[i].copy(connectedSectIds = connections[sectId]?.toList() ?: emptyList())
        }
        
        return result
    }
    
    private fun calculateDistance(sect1: WorldSect, sect2: WorldSect): Double {
        return Math.sqrt(
            Math.pow((sect1.x - sect2.x).toDouble(), 2.0) + 
            Math.pow((sect1.y - sect2.y).toDouble(), 2.0)
        )
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
        val maxRealmByLevel = listOf(5, 3, 1, 0)
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
            disciples[realm] = Random.nextInt(10, 41)
        }
        
        return SectLevelInfo(level, levelNames[level], disciples)
    }
    
    private fun generateDisciplesForLevel(level: Int): Map<Int, Int> {
        val disciples = mutableMapOf<Int, Int>()
        val maxRealmByLevel = listOf(5, 3, 1, 0)
        val maxRealm = maxRealmByLevel.getOrElse(level) { 9 }
        
        for (realm in 0..9) {
            disciples[realm] = 0
        }
        
        disciples[maxRealm] = Random.nextInt(1, 6)
        
        for (realm in (maxRealm + 1)..9) {
            disciples[realm] = Random.nextInt(10, 41)
        }
        
        return disciples
    }
    
    /**
     * 初始化 AI 宗门间关系（包括玩家宗门与AI宗门的关系）
     */
    fun initializeSectRelations(sects: List<WorldSect>): List<SectRelation> {
        val relations = mutableListOf<SectRelation>()
        val playerSect = sects.find { it.isPlayerSect }
        val aiSects = sects.filter { !it.isPlayerSect }
        
        for (i in aiSects.indices) {
            for (j in i + 1 until aiSects.size) {
                val sect1 = aiSects[i]
                val sect2 = aiSects[j]
                
                var initialFavor = 50
                
                if (sect1.isRighteous == sect2.isRighteous) {
                    initialFavor += 10
                }
                
                initialFavor = initialFavor.coerceIn(10, 90)
                
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
                val initialFavor = aiSect.relation.coerceIn(0, 100)
                
                relations.add(SectRelation(
                    sectId1 = minOf(playerSect.id, aiSect.id),
                    sectId2 = maxOf(playerSect.id, aiSect.id),
                    favor = initialFavor,
                    lastInteractionYear = 0
                ))
            }
        }
        
        return relations
    }
    
    data class SectLevelInfo(
        val level: Int,
        val levelName: String,
        val disciples: Map<Int, Int>
    )
}
