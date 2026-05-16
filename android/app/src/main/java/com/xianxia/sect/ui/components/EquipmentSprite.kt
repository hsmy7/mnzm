package com.xianxia.sect.ui.components

import com.xianxia.sect.R

/**
 * Returns the drawable resource ID for the given equipment name, or null if no sprite exists.
 */
fun equipmentSpriteRes(name: String): Int? = when (name) {
    "精铁剑" -> R.drawable.jing_tie_jian
    "精铁刀" -> R.drawable.jing_tie_dao
    "烈焰剑" -> R.drawable.lie_yan_jian
    "灵锋剑" -> R.drawable.ling_feng_jian
    "凌华刀" -> R.drawable.ling_hua_dao
    "雷霆剑" -> R.drawable.lei_ting_jian
    "青莲剑" -> R.drawable.qing_lian_jian
    "诛仙剑" -> R.drawable.zhu_xian_jian
    else -> null
}
