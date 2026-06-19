package com.xianxia.sect.core.engine

import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.config.FixedSectPositions
import com.xianxia.sect.core.config.SectAlignment
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.model.*
import kotlin.random.Random

object WorldMapGenerator {

    private val righteousSectNames = listOf(
        "青云门", "紫霄宫", "太华宗", "昆仑派", "峨眉山", "武当山", "青城山", "龙虎山",
        "华山派", "衡山派", "泰山派", "恒山派", "嵩山寺", "终南山", "罗浮山", "括苍山",
        "玄天宗", "天道宗", "无极宗", "太虚宗", "紫阳宗", "纯阳宗", "全真教", "正一教",
        "天师道", "上清宗", "灵宝宗", "神霄派", "清微派", "净明道", "茅山派", "阁皂山",
        "凌霄阁", "飘渺宫", "逍遥宗", "长生殿", "不老山", "永生门", "问道宗", "悟道山",
        "仙霞山", "云雾峰", "紫云宗", "白云观", "青霞山", "丹霞山", "碧云天", "云海宗",
        "浩然宗", "正气门", "仁义堂", "侠义庄", "忠义门", "义薄云", "正道盟", "光明顶",
        "天正义", "正心宗", "明德门", "至善宗", "仁爱堂", "礼义山", "信义门", "忠孝堂",
        "天机阁", "玉清宗", "太乙门", "玄都观", "紫微宫", "瑶池宫", "蓬莱阁", "方丈山",
        "瀛洲岛", "天柱峰", "九华山", "普陀山", "五台山", "雁荡山", "武夷山", "天台山",
        "栖霞山", "千山派", "崆峒山", "点苍山", "无量山", "苍山派", "天山派", "昆仑虚",
        "玉泉院", "紫竹林", "白鹤观", "青牛谷", "金顶寺", "银瓶峰", "翠微山", "碧落宫",
        "天音寺", "梵音谷", "禅心院", "菩提阁", "般若堂", "舍利塔", "法华宗", "华严寺",
        "灵鹫宫", "大悲院", "慈恩寺", "净慈庵", "法雨阁", "甘露寺", "云门宗", "法眼宗",
        "临济宗", "曹洞宗", "沩仰宗", "云门派", "天台宗", "华严宗", "法相宗", "三论宗",
        "净土宗", "禅宗院", "律宗门", "密宗寺", "显宗阁", "心宗殿", "道宗观", "儒宗院"
    )

    private val evilSectNames = listOf(
        "血刀门", "血煞宗", "血影教", "血魂殿", "血魔山", "血灵宗", "血海宫", "血炼堂",
        "嗜血门", "饮血宗", "血河派", "血池山", "血冢", "血陵", "血域", "血渊",
        "幽冥宗", "鬼王谷", "阴山派", "冥河宗", "黄泉门", "幽魂殿", "鬼哭山", "阴风谷",
        "黑风山", "暗影宗", "影杀门", "夜枭谷", "幽冥殿", "鬼门关", "阴曹府", "冥界门",
        "天魔宗", "邪王谷", "魔教", "邪道盟", "万魔殿", "魔音谷", "邪心宗", "魔魂山",
        "魔罗宗", "邪灵派", "魔影门", "邪神殿", "魔道宗", "邪魔山", "魔心谷", "邪月宗",
        "五毒教", "万毒谷", "毒龙宗", "蛊神山", "毒王谷", "百毒门", "毒煞宗", "蛊毒殿",
        "毒心谷", "毒牙山", "毒蝎门", "毒蛛谷", "毒蟾宫", "毒蛇岭", "毒雾山", "毒瘴谷",
        "噬魂殿", "摄魄门", "夺舍宗", "炼魂谷", "碎骨山", "裂魂崖", "灭魂洞", "销魄窟",
        "九幽门", "十殿阁", "阎罗殿", "判官府", "黑白司", "无常谷", "牛头山", "马面崖",
        "修罗场", "修罗宗", "阿修罗", "修罗殿", "修罗界", "修罗道", "修罗城", "修罗宫",
        "化骨门", "腐骨宗", "枯骨山", "白骨洞", "骷髅谷", "尸王殿", "僵尸门", "丧尸谷",
        "炼尸宗", "赶尸门", "御尸派", "控尸谷", "养尸洞", "藏尸山", "封尸窟", "镇尸塔",
        "暗夜宗", "永夜门", "极夜谷", "长夜山", "无光殿", "灭光阁", "噬光窟", "吞光洞",
        "混沌宗", "虚无门", "虚空谷", "太虚山", "无极殿", "太极阁", "两仪窟", "四象洞",
        "八卦门", "六爻宗", "奇门谷", "遁甲山", "天机殿", "地机阁", "人机窟", "神机洞"
    )

    private val neutralSectNames = listOf(
        "云水居", "听风阁", "望月楼", "栖霞观", "落星谷", "碧波潭", "翠竹林", "紫烟阁",
        "清风渡", "明月轩", "幽兰苑", "寒梅亭", "苍松院", "白鹤峰", "青莲寺", "紫藤庐",
        "烟雨楼", "霜雪殿", "晨曦阁", "暮云观", "流泉居", "飞瀑崖", "静水庵", "浮云渡",
        "天星阁", "地灵殿", "玄冰谷", "黄沙堡", "金风楼", "木灵居", "水月庵", "火云观",
        "土灵殿", "风雷阁", "云海楼", "雾隐居", "星河渡", "月华庵", "日曜阁", "辰星观"
    )

    data class WorldGenerationResult(
        val sects: List<WorldSect>,
        val aiSectDisciples: Map<String, List<Disciple>>
    )

    fun generateWorldSects(playerSectName: String = "青云宗"): WorldGenerationResult {
        val sects = mutableListOf<WorldSect>()
        val usedNames = mutableSetOf<String>()
        val aiDisciplesMap = mutableMapOf<String, List<Disciple>>()

        val positions = FixedSectPositions.ALL

        for ((index, pos) in positions.withIndex()) {
            val isPlayerSect = (index == FixedSectPositions.PLAYER_INDEX)

            if (isPlayerSect) {
                val playerSect = WorldSect(
                    id = "player_sect",
                    name = playerSectName,
                    x = pos.worldX,
                    y = pos.worldY,
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
            } else {
                val isRighteous = pos.alignment == SectAlignment.RIGHTEOUS
                val name = if (pos.sectName.isNotEmpty()) {
                    pos.sectName.also { usedNames.add(it) }
                } else {
                    generateNameForAlignment(pos.alignment, usedNames)
                }
                val levelInfo = generateSectLevelAndDisciples(pos.level)
                val initialRelation = Random.nextInt(20, 51)

                val sect = WorldSect(
                    id = "sect_${sects.size}",
                    name = name,
                    x = pos.worldX,
                    y = pos.worldY,
                    isPlayerSect = false,
                    isRighteous = isRighteous,
                    level = levelInfo.level,
                    levelName = levelInfo.levelName,
                    disciples = levelInfo.disciples,
                    relation = initialRelation,
                    discovered = false,
                )

                val (aiDisciples, _) = AISectDiscipleManager.initializeSectDisciples(sect.name, sect.level)
                aiDisciplesMap[sect.id] = aiDisciples
                sects.add(sect)
            }
        }

        return WorldGenerationResult(
            sects = sects,
            aiSectDisciples = aiDisciplesMap
        )
    }

    private fun generateNameForAlignment(alignment: SectAlignment, usedNames: MutableSet<String>): String {
        val namePool = when (alignment) {
            SectAlignment.RIGHTEOUS -> righteousSectNames.filter { it !in usedNames }
            SectAlignment.EVIL -> evilSectNames.filter { it !in usedNames }
            SectAlignment.NEUTRAL -> neutralSectNames.filter { it !in usedNames }
        }
        val name = if (namePool.isNotEmpty()) {
            namePool.random()
        } else {
            val prefix = when (alignment) {
                SectAlignment.RIGHTEOUS -> "正道"
                SectAlignment.EVIL -> "魔道"
                SectAlignment.NEUTRAL -> "中立"
            }
            generateFallbackName(prefix, usedNames)
        }
        usedNames.add(name)
        return name
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

    private fun generateSectLevelAndDisciples(level: Int): SectLevelInfo {
        val levelNames = listOf("小型宗门", "中型宗门", "大型宗门", "顶级宗门")
        val maxRealm = SectLevel.maxRealmForLevel(level)

        // 所有 AI 宗门固定 50 名弟子，境界在允许范围内随机分配
        val normalCount = 50

        val disciples = mutableMapOf<Int, Int>()
        for (realm in 0..9) {
            disciples[realm] = 0
        }

        val realmRange = (maxRealm + 1)..9
        if (!realmRange.isEmpty()) {
            val weights = realmRange.associateWith { realm ->
                when (realm) {
                    9 -> 3
                    8 -> 2
                    7 -> 2
                    else -> 1
                }
            }
            val totalWeight = weights.values.sum()

            var assigned = 0
            for (realm in realmRange) {
                val weight = weights[realm] ?: 1
                val count = (normalCount * weight / totalWeight)
                disciples[realm] = count
                assigned += count
            }

            var remaining = normalCount - assigned
            if (remaining > 0) {
                val sortedRealms = realmRange.sortedByDescending { weights[it] ?: 1 }
                for (realm in sortedRealms) {
                    if (remaining <= 0) break
                    disciples[realm] = (disciples[realm] ?: 0) + 1
                    remaining--
                }
            }
        }

        return SectLevelInfo(level, levelNames[level], disciples)
    }


    private fun generateDisciplesForLevel(level: Int): Map<Int, Int> {
        // 玩家宗门的 WorldSect.disciples 仅用于显示，使用固定数值
        val maxRealm = SectLevel.maxRealmForLevel(level)
        val disciples = mutableMapOf<Int, Int>()
        for (realm in 0..9) {
            disciples[realm] = 0
        }

        val normalCount = 50

        val realmRange = (maxRealm + 1)..9
        if (!realmRange.isEmpty()) {
            val weights = realmRange.associateWith { realm ->
                when (realm) {
                    9 -> 3
                    8 -> 2
                    7 -> 2
                    else -> 1
                }
            }
            val totalWeight = weights.values.sum()

            var assigned = 0
            for (realm in realmRange) {
                val weight = weights[realm] ?: 1
                val count = (normalCount * weight / totalWeight)
                disciples[realm] = count
                assigned += count
            }

            var remaining = normalCount - assigned
            if (remaining > 0) {
                val sortedRealms = realmRange.sortedByDescending { weights[it] ?: 1 }
                for (realm in sortedRealms) {
                    if (remaining <= 0) break
                    disciples[realm] = (disciples[realm] ?: 0) + 1
                    remaining--
                }
            }
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

                val initialFavor = calculateInitialFavor()

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
                    favor = calculateInitialFavor(),
                    lastInteractionYear = 0
                ))
            }
        }

        return relations
    }

    fun calculateInitialFavorForSects(): Int {
        return calculateInitialFavor()
    }

    private fun calculateInitialFavor(): Int {
        return Random.nextInt(40, 61)
    }

    data class SectLevelInfo(
        val level: Int,
        val levelName: String,
        val disciples: Map<Int, Int>
    )
}
