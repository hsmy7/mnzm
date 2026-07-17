package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import javax.inject.Inject
import kotlin.math.sqrt

class LevelGenerator @Inject constructor(
    private val rngManager: GameRngManager
) {
    private val rng get() = rngManager.getRng(RngPartition.EXPLORATION)

    companion object {
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

        /**
         * 妖兽境界年份锚点权重。
         * 索引 0=仙人, 1=渡劫, 2=大乘, 3=合体, 4=炼虚,
         *      5=化神, 6=元婴, 7=金丹, 8=筑基, 9=炼气
         */
        private data class BeastRealmAnchor(
            val year: Int,
            val weights: List<Int>
        )

        private val BEAST_REALM_ANCHORS = listOf(
            BeastRealmAnchor(
                year = 1,
                weights = listOf(1, 3, 8, 15, 30, 60, 120, 220, 320, 400)
            ),
            BeastRealmAnchor(
                year = 500,
                weights = listOf(40, 60, 90, 130, 170, 180, 150, 100, 50, 20)
            ),
            BeastRealmAnchor(
                year = 2000,
                weights = listOf(160, 170, 150, 120, 90, 60, 30, 15, 6, 2)
            )
        )

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
    }

    /**
     * 按年份加权随机选取妖兽境界。
     * 在两个锚点之间线性插值权重，归一化后加权随机。
     *
     * @param year 当前游戏年份（≥1，小于1钳制为1）
     * @return 境界值 0~9（0=仙人…9=炼气）
     */
    internal fun selectBeastRealm(year: Int): Int {
        val clampedYear = year.coerceAtLeast(1)
        val anchors = BEAST_REALM_ANCHORS

        val lower = anchors.last { it.year <= clampedYear }
        val upper = anchors.firstOrNull { it.year >= clampedYear } ?: lower

        val weights = if (lower == upper) {
            lower.weights.map { it.toDouble() }
        } else {
            val fraction = (clampedYear - lower.year).toDouble() /
                (upper.year - lower.year)
            lower.weights.zip(upper.weights).map { (lo, hi) ->
                lo + (hi - lo) * fraction
            }
        }

        // 加权随机选取
        val total = weights.sum()
        var roll = rng.nextDouble() * total
        for (i in weights.indices) {
            roll -= weights[i]
            if (roll <= 0.0) return i
        }
        return 9 // fallback
    }

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

        val safeBound = if (maxNewLevels >= Int.MAX_VALUE - 1) Int.MAX_VALUE else maxNewLevels + 1
        val newLevelCount = if (maxNewLevels <= 0) 0 else rng.nextInt(safeBound - 1) + 1

        var attempts = 0
        while (newLevels.size < newLevelCount && attempts < 5000) {
            attempts++

            val x = rng.nextInt(MAP_WIDTH - BORDER_PADDING * 2) + BORDER_PADDING
            val y = rng.nextInt(MAP_HEIGHT - BORDER_PADDING * 2) + BORDER_PADDING

            val allLevels = existingLevels + newLevels
            if (!isValidPosition(x, y, usedPositions, existingSects, connectionEdges, allLevels)) {
                continue
            }

            // 随机选择类型：妖兽 80/85，洞府 5/85
            val isCave = rng.nextDouble() < (5.0 / 85.0)

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
        val beastTypeIndex = rng.nextInt(8)
        val beastConfig = GameConfig.Beast.getType(beastTypeIndex)
        val realm = selectBeastRealm(currentYear)
        val realmLayer = rng.nextInt(9) + 1
        val count = rng.nextInt(13) + 1

        // ========== 预计算妖兽最终属性（含随机方差） ==========
        // 与 BattleSystem.createBeast 使用相同公式，但用 EXPLORATION 分区 RNG 代替 kotlin.random.Random
        // 生成的最终属性直接用于战斗和战力计算，不再重新随机
        val layerMult = 1.0 + (realmLayer - 1) * 0.1
        val stats = GameConfig.Beast.getRealmStats(realm)

        val hpVariance = -0.2 + rng.nextDouble() * 0.4
        val atkVariance = -0.2 + rng.nextDouble() * 0.4
        val defVariance = -0.2 + rng.nextDouble() * 0.4
        val speedVariance = -0.2 + rng.nextDouble() * 0.4

        val maxHp = (stats.hp * layerMult * (beastConfig.hpMod + hpVariance)).toInt()
        val maxMp = (stats.mp * layerMult * (beastConfig.hpMod + hpVariance)).toInt()
        val atk = (stats.attack * layerMult * (beastConfig.atkMod + atkVariance)).toInt()
        val def_ = (stats.defense * layerMult * (beastConfig.defMod + defVariance)).toInt()
        val speed = (stats.speed * layerMult * (beastConfig.speedMod + speedVariance)).toInt()

        // 持续6个月
        val beastNewMonth = currentMonth + 6
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
            count = count,
            // 预计算妖兽最终属性
            beastMaxHp = maxHp,
            beastMaxMp = maxMp,
            beastPhysicalAttack = atk,
            beastMagicAttack = atk,
            beastPhysicalDefense = def_,
            beastMagicDefense = def_,
            beastSpeed = speed
        )
    }

    private fun generateCaveLevel(
        currentYear: Int, currentMonth: Int, x: Int, y: Int
    ): WorldLevel {
        val caveRealm = when (rng.nextInt(5)) {
            0 -> 5  // 化神
            1 -> 4  // 炼虚
            2 -> 3  // 合体
            3 -> 2  // 大乘
            4 -> 1  // 渡劫
            else -> 5
        }
        val realmLayer = rng.nextInt(9) + 1
        val caveImageIndex = rng.nextInt(3)
        val guardianName = "${guardianPrefixes[rng.nextInt(guardianPrefixes.size)]}${guardianSuffixes[rng.nextInt(guardianSuffixes.size)]}"
        val realmName = GameConfig.Realm.getName(caveRealm)
        val caveName = "${caveNamePrefixes[rng.nextInt(caveNamePrefixes.size)]}$realmName${caveNameSuffixes[rng.nextInt(caveNameSuffixes.size)]}"

        // 持续6个月
        val caveNewMonth = currentMonth + 6
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

    data class CaveRewardConfig(
        val baseSpiritStones: Double,
        val rarityRange: Pair<Int, Int>
    )
}
