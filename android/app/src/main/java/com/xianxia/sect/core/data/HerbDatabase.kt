package com.xianxia.sect.core.data

object HerbDatabase {
    
    data class Herb(
        val id: String,
        val name: String,
        val tier: Int,
        val rarity: Int,
        val category: String,
        val description: String,
        val price: Int
    )
    
    data class Seed(
        val id: String,
        val name: String,
        val tier: Int,
        val rarity: Int,
        val growTime: Int,
        val yield: Int,
        val description: String,
        val price: Int
    )

    private val tier1Herbs = listOf(
        Herb("spiritGrass1", "聚灵草", 1, 1, "grass", "吸收天地灵气而生的灵草，炼丹基础材料", 1200),
        Herb("spiritGrass2", "清心草", 1, 1, "grass", "叶片清凉，可清心明目，安神定志", 1200),
        Herb("spiritGrass3", "凝气草", 1, 1, "grass", "凝聚灵气的灵草，辅助修炼佳品", 1200),
        Herb("spiritFlower1", "云雾花", 1, 1, "flower", "生于云雾间的灵花，清热解毒", 1200),
        Herb("spiritFlower2", "白莲", 1, 1, "flower", "洁白无瑕的莲花，清心降火", 1200),
        Herb("spiritFlower3", "晨露花", 1, 1, "flower", "清晨露水滋养的灵花，润肺生津", 1200),
        Herb("spiritFruit1", "精气果", 1, 1, "fruit", "蕴含精气的灵果，明目养肾", 1200),
        Herb("spiritFruit2", "赤心果", 1, 1, "fruit", "形似红心的灵果，补气养血", 1200),
        Herb("spiritFruit3", "灵韵果", 1, 1, "fruit", "蕴含灵韵的灵果，滋养经脉", 1200)
    )
    
    private val tier2Herbs = listOf(
        Herb("spiritGrass4", "寒霜草", 2, 2, "grass", "冰寒之地生长的灵草，蕴含寒气", 24000),
        Herb("spiritGrass5", "烈焰草", 2, 2, "grass", "火山附近生长的灵草，蕴含火力", 24000),
        Herb("spiritGrass6", "金灵草", 2, 2, "grass", "吸收金精之气而生，泻火解毒", 24000),
        Herb("spiritFlower4", "冰魄莲", 2, 2, "flower", "生于极寒之地的冰属性灵花，花瓣如冰晶", 24000),
        Herb("spiritFlower5", "双生花", 2, 2, "flower", "金银双色并蒂而生，可解百毒", 24000),
        Herb("spiritFlower6", "紫霄花", 2, 2, "flower", "紫气缭绕的灵花，安神定魄", 24000),
        Herb("spiritFruit4", "通灵果", 2, 2, "fruit", "可通经脉的灵果，消肿散结", 24000),
        Herb("spiritFruit5", "玄灵果", 2, 2, "fruit", "色如墨玉的灵果，滋补肝肾", 24000),
        Herb("spiritFruit6", "五行果", 2, 2, "fruit", "蕴含五行之气的奇果，调和阴阳", 24000)
    )
    
    private val tier3Herbs = listOf(
        Herb("spiritGrass7", "龙血草", 3, 3, "grass", "沾染真龙之血而生的灵草，蕴含龙威", 88000),
        Herb("spiritGrass8", "风铃草", 3, 3, "grass", "随风摇曳的灵草，祛风除湿", 88000),
        Herb("spiritGrass9", "九转灵草", 3, 3, "grass", "九转轮回方可成熟的灵草，蕴含轮回之力", 88000),
        Herb("spiritFlower7", "九转仙兰", 3, 3, "flower", "九转轮回方可盛开的仙界灵花", 88000),
        Herb("spiritFlower8", "凤凰花", 3, 3, "flower", "凤凰栖息之地生长的灵花，蕴含涅槃之力", 88000),
        Herb("spiritFlower9", "青龙花", 3, 3, "flower", "龙气滋养的灵花，强筋健骨", 88000),
        Herb("spiritFruit7", "赤阳果", 3, 3, "fruit", "吸收日精月华而生的赤红灵果", 88000),
        Herb("spiritFruit8", "玄灵莓", 3, 3, "fruit", "色如墨玉的灵果，滋补肝肾", 88000),
        Herb("spiritFruit9", "天元果", 3, 3, "fruit", "天元之力凝结的灵果，补气固本", 88000)
    )
    
    private val tier4Herbs = listOf(
        Herb("spiritGrass10", "玄冰草", 4, 4, "grass", "万年玄冰中孕育的冰魄精华", 320000),
        Herb("spiritGrass11", "风暴草", 4, 4, "grass", "飓风区域生长的灵草，化风定惊", 320000),
        Herb("spiritGrass12", "神命草", 4, 4, "grass", "神山之巅的灵草，起死回生", 320000),
        Herb("spiritFlower10", "日月同辉花", 4, 4, "flower", "日月同辉之时方可绽放的神花", 320000),
        Herb("spiritFlower11", "紫云花", 4, 4, "flower", "生于紫云深处的灵花，补气安神", 320000),
        Herb("spiritFlower12", "玄武花", 4, 4, "flower", "玄武守护之地生长的灵花，蕴含大地之力", 320000),
        Herb("spiritFruit10", "长生果", 4, 4, "fruit", "三千年一开花，三千年一结果的仙果", 320000),
        Herb("spiritFruit11", "仙灵果", 4, 4, "fruit", "仙界遗落的灵果，蕴含仙气", 320000),
        Herb("spiritFruit12", "天灵果", 4, 4, "fruit", "吸收天界灵气而生的神果", 320000)
    )
    
    private val tier5Herbs = listOf(
        Herb("spiritGrass13", "仙灵草", 5, 5, "grass", "仙界遗落的灵草，蕴含仙气", 1600000),
        Herb("spiritGrass14", "天灵草", 5, 5, "grass", "吸收天界灵气而生的神草", 1600000),
        Herb("spiritGrass15", "混沌草", 5, 5, "grass", "混沌初开时诞生的神草，蕴含混沌本源之力", 1600000),
        Herb("spiritFlower13", "涅槃凤仙花", 5, 5, "flower", "凤凰涅槃之地孕育的仙花，花瓣如凤羽般绚烂", 1600000),
        Herb("spiritFlower14", "龙鳞仙莲", 5, 5, "flower", "真龙栖息之池生长的仙莲，莲瓣如龙鳞般坚韧", 1600000),
        Herb("spiritFlower15", "白虎幽兰", 5, 5, "flower", "白虎栖息之谷生长的幽兰，花香蕴含杀伐之气", 1600000),
        Herb("spiritFruit13", "九叶还魂果", 5, 5, "fruit", "九叶齐生，有还魂续命之效的仙果", 1600000),
        Herb("spiritFruit14", "玄天灵果", 5, 5, "fruit", "玄天之上孕育的灵果，通体玄光流转", 1600000),
        Herb("spiritFruit15", "星陨神果", 5, 5, "fruit", "星辰陨落后凝结的神果，蕴含星力", 1600000)
    )
    
    private val tier6Herbs = listOf(
        Herb("spiritGrass16", "鸿蒙草", 6, 6, "grass", "鸿蒙初开时诞生的神草，蕴含鸿蒙本源", 4800000),
        Herb("spiritGrass17", "太初草", 6, 6, "grass", "太古时期诞生的神草，蕴含太初之力", 4800000),
        Herb("spiritGrass18", "永恒草", 6, 6, "grass", "永恒不灭的神草，时间法则的化身", 4800000),
        Herb("spiritFlower16", "永恒花", 6, 6, "flower", "永恒不谢的仙花，时间法则的化身", 4800000),
        Herb("spiritFlower17", "混沌仙莲", 6, 6, "flower", "混沌中诞生的仙莲，蕴含混沌本源", 4800000),
        Herb("spiritFlower18", "造化神花", 6, 6, "flower", "天地造化孕育的神花，蕴含造化之力", 4800000),
        Herb("spiritFruit16", "瑞麟仙果", 6, 6, "fruit", "瑞兽麒麟守护万年的仙果，已生灵智", 4800000),
        Herb("spiritFruit17", "玄武帝果", 6, 6, "fruit", "玄武守护之地生长的天品灵果，蕴含大地精华", 4800000),
        Herb("spiritFruit18", "混沌神果", 6, 6, "fruit", "混沌初开时诞生的神果，蕴含混沌本源", 4800000)
    )

    private val allHerbs = tier1Herbs + tier2Herbs + tier3Herbs + tier4Herbs + tier5Herbs + tier6Herbs

    private val tier1Seeds = listOf(
        Seed("spiritGrass1Seed", "聚灵草种", 1, 1, 2, 5, "种植后可收获聚灵草", 1200),
        Seed("spiritGrass2Seed", "清心草种", 1, 1, 2, 5, "种植后可收获清心草", 1200),
        Seed("spiritGrass3Seed", "凝气草种", 1, 1, 2, 4, "种植后可收获凝气草", 1200),
        Seed("spiritFlower1Seed", "云雾花种", 1, 1, 2, 5, "种植后可收获云雾花", 1200),
        Seed("spiritFlower2Seed", "白莲种", 1, 1, 2, 4, "种植后可收获白莲", 1200),
        Seed("spiritFlower3Seed", "晨露花种", 1, 1, 2, 5, "种植后可收获晨露花", 1200),
        Seed("spiritFruit1Seed", "精气果核", 1, 1, 2, 5, "种植后可收获精气果", 1200),
        Seed("spiritFruit2Seed", "赤心果核", 1, 1, 2, 4, "种植后可收获赤心果", 1200),
        Seed("spiritFruit3Seed", "灵韵果核", 1, 1, 2, 5, "种植后可收获灵韵果", 1200)
    )
    
    private val tier2Seeds = listOf(
        Seed("spiritGrass4Seed", "寒霜草种", 2, 2, 5, 4, "种植后可收获寒霜草", 24000),
        Seed("spiritGrass5Seed", "烈焰草种", 2, 2, 5, 4, "种植后可收获烈焰草", 24000),
        Seed("spiritGrass6Seed", "金灵草种", 2, 2, 5, 4, "种植后可收获金灵草", 24000),
        Seed("spiritFlower4Seed", "冰魄莲种", 2, 2, 5, 3, "种植后可收获冰魄莲", 24000),
        Seed("spiritFlower5Seed", "双生花种", 2, 2, 5, 4, "种植后可收获双生花", 24000),
        Seed("spiritFlower6Seed", "紫霄花种", 2, 2, 5, 4, "种植后可收获紫霄花", 24000),
        Seed("spiritFruit4Seed", "通灵果核", 2, 2, 5, 4, "种植后可收获通灵果", 24000),
        Seed("spiritFruit5Seed", "玄灵果核", 2, 2, 5, 4, "种植后可收获玄灵果", 24000),
        Seed("spiritFruit6Seed", "五行果核", 2, 2, 5, 3, "种植后可收获五行果", 24000)
    )
    
    private val tier3Seeds = listOf(
        Seed("spiritGrass7Seed", "龙血草种", 3, 3, 9, 2, "种植后可收获龙血草", 88000),
        Seed("spiritGrass8Seed", "风铃草种", 3, 3, 9, 2, "种植后可收获风铃草", 88000),
        Seed("spiritGrass9Seed", "九转灵草种", 3, 3, 9, 2, "种植后可收获九转灵草", 88000),
        Seed("spiritFlower7Seed", "九转仙兰种", 3, 3, 9, 2, "种植后可收获九转仙兰", 88000),
        Seed("spiritFlower8Seed", "凤凰花种", 3, 3, 9, 2, "种植后可收获凤凰花", 88000),
        Seed("spiritFlower9Seed", "青龙花种", 3, 3, 9, 2, "种植后可收获青龙花", 88000),
        Seed("spiritFruit7Seed", "赤阳果核", 3, 3, 9, 2, "种植后可收获赤阳果", 88000),
        Seed("spiritFruit8Seed", "玄灵莓种", 3, 3, 9, 2, "种植后可收获玄灵莓", 88000),
        Seed("spiritFruit9Seed", "天元果核", 3, 3, 9, 2, "种植后可收获天元果", 88000)
    )
    
    private val tier4Seeds = listOf(
        Seed("spiritGrass10Seed", "玄冰草种", 4, 4, 18, 1, "种植后可收获玄冰草", 320000),
        Seed("spiritGrass11Seed", "风暴草种", 4, 4, 18, 1, "种植后可收获风暴草", 320000),
        Seed("spiritGrass12Seed", "神命草种", 4, 4, 18, 1, "种植后可收获神命草", 320000),
        Seed("spiritFlower10Seed", "日月同辉种", 4, 4, 18, 1, "种植后可收获日月同辉花", 320000),
        Seed("spiritFlower11Seed", "紫云花种", 4, 4, 18, 1, "种植后可收获紫云花", 320000),
        Seed("spiritFlower12Seed", "玄武花种", 4, 4, 18, 1, "种植后可收获玄武花", 320000),
        Seed("spiritFruit10Seed", "长生果核", 4, 4, 18, 1, "种植后可收获长生果", 320000),
        Seed("spiritFruit11Seed", "仙灵果核", 4, 4, 18, 1, "种植后可收获仙灵果", 320000),
        Seed("spiritFruit12Seed", "天灵果核", 4, 4, 18, 1, "种植后可收获天灵果", 320000)
    )
    
    private val tier5Seeds = listOf(
        Seed("spiritGrass13Seed", "仙灵草种", 5, 5, 30, 1, "种植后可收获仙灵草", 1600000),
        Seed("spiritGrass14Seed", "天灵草种", 5, 5, 30, 1, "种植后可收获天灵草", 1600000),
        Seed("spiritGrass15Seed", "混沌草种", 5, 5, 30, 1, "种植后可收获混沌草", 1600000),
        Seed("spiritFlower13Seed", "凤仙花种", 5, 5, 30, 1, "种植后可收获涅槃凤仙花", 1600000),
        Seed("spiritFlower14Seed", "龙鳞莲种", 5, 5, 30, 1, "种植后可收获龙鳞仙莲", 1600000),
        Seed("spiritFlower15Seed", "白虎幽兰种", 5, 5, 30, 1, "种植后可收获白虎幽兰", 1600000),
        Seed("spiritFruit13Seed", "还魂果核", 5, 5, 30, 1, "种植后可收获九叶还魂果", 1600000),
        Seed("spiritFruit14Seed", "玄天灵果核", 5, 5, 30, 1, "种植后可收获玄天灵果", 1600000),
        Seed("spiritFruit15Seed", "星陨神果核", 5, 5, 30, 1, "种植后可收获星陨神果", 1600000)
    )
    
    private val tier6Seeds = listOf(
        Seed("spiritGrass16Seed", "鸿蒙草种", 6, 6, 48, 1, "种植后可收获鸿蒙草", 4800000),
        Seed("spiritGrass17Seed", "太初草种", 6, 6, 48, 1, "种植后可收获太初草", 4800000),
        Seed("spiritGrass18Seed", "永恒草种", 6, 6, 48, 1, "种植后可收获永恒草", 4800000),
        Seed("spiritFlower16Seed", "永恒花种", 6, 6, 48, 1, "种植后可收获永恒花", 4800000),
        Seed("spiritFlower17Seed", "混沌仙莲种", 6, 6, 48, 1, "种植后可收获混沌仙莲", 4800000),
        Seed("spiritFlower18Seed", "造化神花种", 6, 6, 48, 1, "种植后可收获造化神花", 4800000),
        Seed("spiritFruit16Seed", "瑞麟仙果核", 6, 6, 48, 1, "种植后可收获瑞麟仙果", 4800000),
        Seed("spiritFruit17Seed", "玄武帝果核", 6, 6, 48, 1, "种植后可收获玄武帝果", 4800000),
        Seed("spiritFruit18Seed", "混沌神果核", 6, 6, 48, 1, "种植后可收获混沌神果", 4800000)
    )

    private val allSeeds = tier1Seeds + tier2Seeds + tier3Seeds + tier4Seeds + tier5Seeds + tier6Seeds
    
    private val seedToHerbMap: Map<String, String> = buildMap {
        allSeeds.forEach { seed ->
            val herbId = seed.id.removeSuffix("Seed")
            put(seed.id, herbId)
        }
    }
    
    private val herbByIdMap: Map<String, Herb> = allHerbs.associateBy { it.id }
    private val herbByNameMap: Map<String, Herb> = allHerbs.associateBy { it.name }
    private val seedByIdMap: Map<String, Seed> = allSeeds.associateBy { it.id }
    private val seedByNameMap: Map<String, Seed> = allSeeds.associateBy { it.name }
    
    fun getHerbIdFromSeedId(seedId: String): String? = seedToHerbMap[seedId]
    
    fun getAllHerbs(): List<Herb> = allHerbs
    
    fun getHerbById(id: String): Herb? = herbByIdMap[id]
    
    fun getHerbByName(name: String): Herb? = herbByNameMap[name]
    
    fun getHerbsByTier(tier: Int): List<Herb> {
        return when (tier) {
            1 -> tier1Herbs
            2 -> tier2Herbs
            3 -> tier3Herbs
            4 -> tier4Herbs
            5 -> tier5Herbs
            6 -> tier6Herbs
            else -> emptyList()
        }
    }
    
    fun getAllSeeds(): List<Seed> = allSeeds
    
    fun getSeedById(id: String): Seed? = seedByIdMap[id]
    
    fun getSeedByName(name: String): Seed? = seedByNameMap[name]
    
    fun getByRarity(rarity: Int): List<Herb> = allHerbs.filter { it.rarity == rarity }
    
    fun getSeedsByRarity(rarity: Int): List<Seed> = allSeeds.filter { it.rarity == rarity }
    
    fun getSeedsByTier(tier: Int): List<Seed> {
        return when (tier) {
            1 -> tier1Seeds
            2 -> tier2Seeds
            3 -> tier3Seeds
            4 -> tier4Seeds
            5 -> tier5Seeds
            6 -> tier6Seeds
            else -> emptyList()
        }
    }

    fun generateRandomHerb(minRarity: Int = 1, maxRarity: Int = 6): Herb {
        val eligibleHerbs = allHerbs.filter { it.rarity in minRarity..maxRarity }
        return if (eligibleHerbs.isNotEmpty()) {
            eligibleHerbs.random()
        } else {
            tier1Herbs.random()
        }
    }

    fun generateRandomSeed(minRarity: Int = 1, maxRarity: Int = 6): Seed {
        val eligibleSeeds = allSeeds.filter { it.rarity in minRarity..maxRarity }
        return if (eligibleSeeds.isNotEmpty()) {
            eligibleSeeds.random()
        } else {
            tier1Seeds.random()
        }
    }

    // 根据种子ID获取长成的草药
    fun getHerbFromSeed(seedId: String): Herb? {
        val herbId = getHerbIdFromSeedId(seedId) ?: return null
        return getHerbById(herbId)
    }

    fun getHerbFromSeedName(seedName: String): Herb? {
        val seed = seedByNameMap[seedName] ?: return null
        val herbId = seedToHerbMap[seed.id] ?: return null
        return herbByIdMap[herbId]
    }

    // 根据种子名称获取长成的草药名称
    fun getHerbNameFromSeedName(seedName: String): String {
        return when {
            seedName.contains("草种") -> seedName.replace("草种", "草")
            seedName.contains("花种") -> seedName.replace("花种", "花")
            seedName.contains("种") -> seedName.replace("种", "")
            seedName.contains("核") -> seedName.replace("核", "")
            seedName.contains("果核") -> seedName.replace("果核", "果")
            else -> seedName
        }
    }
}
