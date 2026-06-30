package com.xianxia.sect.core.util

/**
 * 宗门名称违禁词系统。
 *
 * 用于 [InputValidator.validateSectName] 的子串匹配检查，
 * 禁止包含政治敏感、违法、低俗、恶毒、歧视等不文明用语。
 *
 * 所有词条均≥2 字符，避免单字误匹配正常修仙词汇。
 */
object BannedWords {

    /** 违禁词集合（不可变，子串匹配用）。按类别分组注释，便于维护。 */
    val words: Set<String> = setOf(
        // ======== 政治敏感 ========
        "共匪", "台独", "藏独", "疆独", "港独",
        "法轮", "六四", "天安门", "暴政", "独裁",
        "纳粹", "希特勒", "法西斯", "军国",

        // ======== 违法相关 ========
        "毒品", "海洛因", "冰毒", "大麻", "鸦片",
        "赌博", "赌场", "洗钱", "枪支", "弹药",
        "裸聊", "嫖娼", "卖淫",

        // ======== 低俗色情 ========
        "色情", "淫秽", "淫荡", "骚逼", "傻逼",
        "操你", "日你", "他妈", "你妈", "贱人",
        "婊子", "妓女", "鸡巴", "阴茎", "阴道",
        "乳房", "裸体", "做爱", "性交", "口交",
        "肛交", "强奸", "轮奸", "乱伦", "兽交",

        // ======== 恶毒诅咒 ========
        "去死", "死全家", "断子绝孙", "不得好死",
        "灭门", "诛九族", "千刀万剐",

        // ======== 歧视用语 ========
        "支那", "黑鬼", "白猪", "印度阿三",
        "残疾", "弱智", "白痴", "脑残", "智障",
        "乡巴佬", "土包子"
    )

    /**
     * 检查 [input] 是否包含违禁词子串。
     * 空输入返回 false。
     */
    fun containsBannedWord(input: String): Boolean {
        if (input.isBlank()) return false
        return words.any { input.contains(it, ignoreCase = true) }
    }

    /**
     * 返回第一个匹配到的违禁词（用于错误提示），
     * 未匹配返回 null。
     */
    fun findFirstBannedWord(input: String): String? {
        if (input.isBlank()) return null
        return words.firstOrNull { input.contains(it, ignoreCase = true) }
    }
}
