package com.xianxia.sect.core.config

/**
 * 宗门送礼反馈文本
 * 根据宗门等级和送礼结果返回不同的反馈文本
 */
object SectResponseTexts {

    // 宗门等级对应的称呼
    private val SECT_TITLES = mapOf(
        0 to "道友",    // 小型宗门
        1 to "道友",    // 中型宗门
        2 to "阁下",    // 大型宗门
        3 to "阁下"     // 顶级宗门
    )

    // 宗门等级对应的自称
    private val SECT_SELF_TITLES = mapOf(
        0 to "本门",    // 小型宗门
        1 to "敝宗",    // 中型宗门
        2 to "本宗",    // 大型宗门
        3 to "本宗"     // 顶级宗门
    )

    // 接受送礼的成功文本
    private val ACCEPT_RESPONSES = mapOf(
        // 小型宗门
        0 to listOf(
            "多谢道友厚礼！{SECT_SELF}上下感激不尽！",
            "这份礼物{SECT_SELF}收下了，日后定当回报！",
            "道友如此慷慨，{SECT_SELF}铭记于心！",
            "多谢道友美意，{SECT_SELF}与贵宗情谊更深了！"
        ),
        // 中型宗门
        1 to listOf(
            "道友盛情，{SECT_SELF}心领了，愿两宗友谊长存！",
            "如此厚礼，{SECT_SELF}愧不敢当，定当铭记于心！",
            "道友慷慨，{SECT_SELF}上下皆感佩，愿两宗永世交好！",
            "这份心意{SECT_SELF}收下了，愿两宗情谊日久弥深！"
        ),
        // 大型宗门
        2 to listOf(
            "阁下盛情，{SECT_SELF}深感荣幸，愿两宗永结同盟！",
            "如此厚礼，{SECT_SELF}定当珍视，愿两宗世代友好！",
            "阁下高义，{SECT_SELF}上下钦佩，愿两宗携手共进！",
            "这份情谊{SECT_SELF}铭记，愿两宗共创辉煌！"
        ),
        // 顶级宗门
        3 to listOf(
            "阁下如此诚意，{SECT_SELF}深感欣慰，愿两宗共铸仙途！",
            "此礼珍贵，{SECT_SELF}定当善加利用，愿两宗同登大道！",
            "阁下高义，{SECT_SELF}铭记于心，愿两宗共参天道！",
            "这份厚礼{SECT_SELF}收下了，愿两宗共创盛世！"
        )
    )

    // 拒绝送礼的文本
    private val REJECT_RESPONSES = mapOf(
        // 小型宗门
        0 to listOf(
            "道友心意{SECT_SELF}领了，但此礼太过贵重，{SECT_SELF}实在不敢收。",
            "道友美意{SECT_SELF}心领，但{SECT_SELF}小门小户，受不起如此厚礼。",
            "这份礼物...{SECT_SELF}实在无福消受，还请道友收回。"
        ),
        // 中型宗门
        1 to listOf(
            "道友盛情{SECT_SELF}心领，但此礼{SECT_SELF}实在不便收下。",
            "道友厚意{SECT_SELF}感激，但{SECT_SELF}有门规，此礼恕难接受。",
            "这份礼物{SECT_SELF}受之有愧，还请道友见谅。"
        ),
        // 大型宗门
        2 to listOf(
            "阁下盛情{SECT_SELF}心领，但此礼太过轻薄，{SECT_SELF}实在看不上眼。",
            "阁下美意{SECT_SELF}感激，但{SECT_SELF}不缺这些，还请收回。",
            "这点东西...阁下是在打发{SECT_SELF}吗？还请拿回吧。"
        ),
        // 顶级宗门
        3 to listOf(
            "阁下盛情{SECT_SELF}心领，但此等俗物{SECT_SELF}实在不缺。",
            "阁下厚意{SECT_SELF}感激，但{SECT_SELF}眼界甚高，此礼恕难接受。",
            "这份礼物...阁下还是留着自己用吧，{SECT_SELF}用不上。"
        )
    )

    // 物品类型对应的中文名称
    private val ITEM_TYPE_NAMES = mapOf(
        "manual" to "功法",
        "equipment" to "法宝",
        "pill" to "丹药",
        "spirit_stones" to "灵石"
    )

    /**
     * 获取接受送礼的反馈文本
     * @param sectLevel 宗门等级 (0-3)
     * @param itemType 物品类型
     * @param itemName 物品名称
     * @param favorChange 好感度变化
     * @return 反馈文本
     */
    fun getAcceptResponse(sectLevel: Int, itemType: String, itemName: String, favorChange: Int): String {
        val responses = ACCEPT_RESPONSES[sectLevel] ?: ACCEPT_RESPONSES[0]!!
        val template = responses.random()

        val selfTitle = SECT_SELF_TITLES[sectLevel] ?: "本门"

        return template
            .replace("{SECT_SELF}", selfTitle)
            .replace("{ITEM_NAME}", itemName)
            .replace("{FAVOR}", favorChange.toString())
    }

    /**
     * 获取拒绝送礼的反馈文本
     * @param sectLevel 宗门等级 (0-3)
     * @param itemType 物品类型
     * @param itemName 物品名称
     * @return 反馈文本
     */
    fun getRejectResponse(sectLevel: Int, itemType: String, itemName: String): String {
        val responses = REJECT_RESPONSES[sectLevel] ?: REJECT_RESPONSES[0]!!
        val template = responses.random()

        val selfTitle = SECT_SELF_TITLES[sectLevel] ?: "本门"
        val itemTypeName = ITEM_TYPE_NAMES[itemType] ?: "礼物"

        return template
            .replace("{SECT_SELF}", selfTitle)
            .replace("{ITEM_NAME}", itemName)
            .replace("{ITEM_TYPE}", itemTypeName)
    }

    /**
     * 获取宗门称呼
     * @param sectLevel 宗门等级 (0-3)
     * @return 称呼
     */
    fun getSectTitle(sectLevel: Int): String {
        return SECT_TITLES[sectLevel] ?: "道友"
    }

    /**
     * 获取宗门自称
     * @param sectLevel 宗门等级 (0-3)
     * @return 自称
     */
    fun getSectSelfTitle(sectLevel: Int): String {
        return SECT_SELF_TITLES[sectLevel] ?: "本门"
    }
}
