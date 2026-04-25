package com.xianxia.sect.core.util

import kotlin.random.Random

object NameService {

    data class NameResult(val surname: String, val fullName: String)

    enum class NameStyle { COMMON, XIANXIA, FULL }

    private val compoundSurnames = listOf(
        "慕容", "上官", "欧阳", "司徒", "南宫", "诸葛", "东方", "西门",
        "独孤", "令狐", "皇甫", "公孙", "轩辕", "太史", "端木", "百里"
    )

    private val singleSurnames = listOf(
        "李", "张", "王", "刘", "陈", "杨", "赵", "黄", "周", "吴",
        "孙", "郑", "冯", "蒋", "沈", "韩", "朱", "秦", "许", "何",
        "吕", "施", "曹", "袁", "邓", "彭", "苏", "卢", "蔡", "丁",
        "萧", "叶", "顾", "孟", "林", "徐", "方", "程", "谢", "宋",
        "楚", "墨", "白", "青", "紫", "玄", "苍", "凌", "寒", "云",
        "胡", "高", "郭", "马", "罗", "梁", "唐", "于", "董", "萧"
    )

    private val commonSurnames = listOf(
        "李", "王", "张", "刘", "陈", "杨", "赵", "黄", "周", "吴",
        "孙", "郑", "冯", "蒋", "沈", "韩", "朱", "秦", "许", "何",
        "吕", "施", "曹", "袁", "邓", "彭", "苏", "卢", "蔡", "丁",
        "萧", "叶", "顾", "孟", "林", "徐", "方", "程", "谢", "宋"
    )

    private val xianxiaSurnames = listOf(
        "慕容", "上官", "欧阳", "司徒", "南宫", "诸葛", "东方", "西门",
        "独孤", "令狐", "皇甫", "公孙", "轩辕", "太史", "端木", "百里",
        "楚", "墨", "白", "青", "紫", "玄", "苍", "凌", "寒", "云",
        "风", "萧", "叶", "林"
    )

    private val maleDoubleNames = listOf(
        "逍遥", "无忌", "长生", "问道", "清风", "明月", "玄真", "道尘",
        "云飞", "天行", "凌霄", "御风", "踏云", "惊鸿", "逐月", "追星",
        "悟道", "通玄", "归真", "化神", "凝神",
        "剑心", "剑尘", "剑歌", "剑影", "剑魄",
        "丹辰", "丹华", "丹心", "丹青", "丹枫",
        "子轩", "子涵", "子墨", "子瑜", "子琪",
        "怀瑾", "景行", "承宇", "君浩", "亦尘", "云深",
        "晏清", "知远", "修远", "秉文", "若谷", "临渊",
        "望舒", "归鸿", "寒山", "听雨", "忘机", "抱朴",
        "乐天", "安然", "致远", "明轩", "文渊", "廷玉",
        "浩然", "瑾瑜", "星野", "澄之", "衡之",
        "器宇", "器灵", "器心", "器魂",
        "阵玄", "阵灵", "阵心", "阵尘",
        "符玄", "符灵", "符心", "符尘"
    )

    private val femaleDoubleNames = listOf(
        "月华", "紫烟", "灵芸", "清音", "玉瑶", "雪晴", "碧云", "青鸾",
        "紫霞", "晨曦", "幽兰", "寒梅", "翠竹", "青松",
        "月影", "花颜", "梦璃", "霜华", "冰心", "凝露",
        "瑶光", "璇玑", "灵犀", "素心", "清浅", "如烟",
        "芷若", "沐云", "晓霜", "凌波", "听澜", "念真",
        "疏影", "流萤", "惜音", "惊鸿", "采薇", "青衣",
        "婉清", "静姝", "灵犀", "芳菲", "梵音", "墨染",
        "丹青", "笙箫", "洛神", "湘君", "素问", "兰若",
        "梦璃", "含烟", "弄影", "踏歌", "凝霜", "映雪"
    )

    private val maleSingleNames = listOf(
        "风", "云", "雷", "电", "剑", "明", "华", "天", "玄", "宇",
        "龙", "虎", "鹤", "鹰", "轩", "尘", "渊", "峰", "辰", "墨"
    )

    private val femaleSingleNames = listOf(
        "月", "雪", "花", "梅", "兰", "竹", "菊", "莲", "芸", "芳",
        "玉", "珠", "翠", "霞", "虹", "露", "霜", "雨", "烟", "鸾"
    )

    private val allSurnames: List<String> get() = singleSurnames + compoundSurnames

    fun generateName(
        gender: String,
        style: NameStyle = NameStyle.XIANXIA,
        existingNames: Set<String> = emptySet()
    ): NameResult {
        var attempts = 0
        while (attempts < 50) {
            val surname = pickSurname(style)
            val givenName = pickGivenName(gender)
            val fullName = "$surname$givenName"
            if (fullName !in existingNames) {
                return NameResult(surname, fullName)
            }
            attempts++
        }
        val surname = pickSurname(style)
        val givenName = pickGivenName(gender)
        return NameResult(surname, "$surname$givenName")
    }

    fun inheritName(
        parentSurname: String,
        gender: String,
        existingNames: Set<String> = emptySet()
    ): NameResult {
        var attempts = 0
        while (attempts < 50) {
            val givenName = pickGivenName(gender)
            val fullName = "$parentSurname$givenName"
            if (fullName !in existingNames) {
                return NameResult(parentSurname, fullName)
            }
            attempts++
        }
        val givenName = pickGivenName(gender)
        return NameResult(parentSurname, "$parentSurname$givenName")
    }

    fun extractSurname(fullName: String): String {
        for (compound in compoundSurnames) {
            if (fullName.startsWith(compound)) return compound
        }
        return fullName.firstOrNull()?.toString() ?: ""
    }

    private fun pickSurname(style: NameStyle): String {
        val pool = when (style) {
            NameStyle.COMMON -> commonSurnames
            NameStyle.XIANXIA -> xianxiaSurnames
            NameStyle.FULL -> allSurnames
        }
        return pool.random()
    }

    private fun pickGivenName(gender: String): String {
        val useDoubleName = Random.nextDouble() < 0.75
        return if (useDoubleName) {
            if (gender == "male") maleDoubleNames.random() else femaleDoubleNames.random()
        } else {
            if (gender == "male") maleSingleNames.random() else femaleSingleNames.random()
        }
    }
}
