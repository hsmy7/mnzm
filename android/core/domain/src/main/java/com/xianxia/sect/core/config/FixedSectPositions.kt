package com.xianxia.sect.core.config

/**
 * 中州世界宗门固定坐标。
 * 世界坐标系: 1698x926，间距 >= 50px。
 *
 * 分布策略：
 *  - 北部雪山（4）：沿北部雪山山脉横向均匀分布
 *  - 中部山脉（4）：中央高地山脊线
 *  - 南部山脉（2）：南部丘陵高地
 *  - 西部山脉（2）：西部独立山岭
 *  - 陆地平原/河谷（7）：散布于河流两岸和平原
 *  - 东部林地（2）：东部半岛和密林
 *  - 东海岛屿（4）：对准右侧海域 4 座实际岛屿
 *  - 玩家宗门：正中州中央
 */
object FixedSectPositions {

    /** 玩家宗门位置（中州中央） */
    val PLAYER_SECT = SectPosition(849f, 463f, "中州中央", SectAlignment.RIGHTEOUS)

    /** 全部 25 个宗门位置 */
    /** 宗门等级: 0=小型 1=中型 2=大型 3=顶级 */
    val ALL: List<SectPosition> = listOf(
        PLAYER_SECT,
        // ---------- 顶级宗门 3 ----------
        SectPosition(200f,  120f, "西北雪峰",    SectAlignment.RIGHTEOUS, 3, "太虚殿"),
        SectPosition(450f,  90f,  "北岳雪峰",    SectAlignment.RIGHTEOUS, 3, "凌霄阁"),
        SectPosition(950f,  100f, "极北雪峰",    SectAlignment.RIGHTEOUS, 3, "天罡门"),
        // ---------- 大型宗门 5 ----------
        SectPosition(700f,  110f, "北方雪峰",    SectAlignment.RIGHTEOUS, 2, "万剑宗"),
        SectPosition(350f,  280f, "中央主峰",    SectAlignment.RIGHTEOUS, 2, "玄天宗"),
        SectPosition(600f,  250f, "东山主峰",    SectAlignment.RIGHTEOUS, 2, "灵墟派"),
        SectPosition(550f,  350f, "中部平原",    SectAlignment.RIGHTEOUS, 2, "碧落宫"),
        SectPosition(1000f, 300f, "东部林地",    SectAlignment.NEUTRAL,   2, "九幽殿"),
        // ---------- 中型宗门 6 ----------
        SectPosition(800f,  220f, "东北山脊",    SectAlignment.RIGHTEOUS, 1, "苍梧山"),
        SectPosition(500f,  400f, "南山山脊",    SectAlignment.RIGHTEOUS, 1, "云岚宗"),
        SectPosition(700f,  480f, "南部山脉",    SectAlignment.RIGHTEOUS, 1, "赤霄峰"),
        SectPosition(400f,  550f, "西南山岭",    SectAlignment.RIGHTEOUS, 1, "玄冥谷"),
        SectPosition(750f,  350f, "东部平原",    SectAlignment.RIGHTEOUS, 1, "天策门"),
        SectPosition(250f,  450f, "西部平原",    SectAlignment.RIGHTEOUS, 1, "青霄阁"),
        // ---------- 小型宗门 11 ----------
        SectPosition(300f,  200f, "北部河谷",    SectAlignment.RIGHTEOUS, 0, "流云观"),
        SectPosition(150f,  350f, "西麓山脉",    SectAlignment.RIGHTEOUS, 0, "紫霞山"),
        SectPosition(120f,  500f, "西南山脉",    SectAlignment.RIGHTEOUS, 0, "玉虚门"),
        SectPosition(600f,  550f, "南部河谷",    SectAlignment.RIGHTEOUS, 0, "落星谷"),
        SectPosition(850f,  550f, "东南平原",    SectAlignment.RIGHTEOUS, 0, "望月楼"),
        SectPosition(250f,  647f, "明德南麓",    SectAlignment.RIGHTEOUS, 0, "听风阁"),
        SectPosition(500f,  700f, "南部平原",    SectAlignment.RIGHTEOUS, 0, "烟雨楼"),
        SectPosition(679f,  700f, "南部东麓",    SectAlignment.RIGHTEOUS, 0, "九霄门"),
        SectPosition(891f,  700f, "东南东麓",    SectAlignment.RIGHTEOUS, 0, "灵元宗"),
        SectPosition(1100f, 440f, "东南林地",    SectAlignment.NEUTRAL,   0, "噬魂殿"),
        SectPosition(1260f, 180f, "东北森域",    SectAlignment.EVIL,      0, "血煞门"),
        SectPosition(1320f, 380f, "东海中岛",    SectAlignment.EVIL,      0, "暗影阁"),
        SectPosition(1400f, 600f, "东海南岛",    SectAlignment.EVIL,      0, "魔渊殿"),
        SectPosition(1256f, 600f, "东部森域",    SectAlignment.EVIL,      0, "玄煞殿"),
    )

    /** 宗门总数 */
    const val COUNT = 29

    /** 玩家宗门在列表中的索引 */
    const val PLAYER_INDEX = 0
}

/** 宗门等级: 0=小型 1=中型 2=大型 3=顶级 */
data class SectPosition(
    val worldX: Float,
    val worldY: Float,
    val terrainName: String,
    val alignment: SectAlignment,
    val level: Int = 1,
    val sectName: String = ""
)

enum class SectAlignment { RIGHTEOUS, NEUTRAL, EVIL }
