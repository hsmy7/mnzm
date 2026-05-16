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
    "凤炎刃" -> R.drawable.feng_yan_ren
    "青碧刃" -> R.drawable.qing_bi_ren
    "暗影刃" -> R.drawable.an_ying_ren
    "玄玉刃" -> R.drawable.xuan_yu_ren
    "桃木杖" -> R.drawable.tao_mu_zhang
    "碧玉杖" -> R.drawable.bi_yu_zhang
    "玄雷杖" -> R.drawable.xuan_lei_zhang
    "虚华杖" -> R.drawable.xu_hua_zhang
    "天玄杖" -> R.drawable.tian_xuan_zhang
    "天星杖" -> R.drawable.tian_xing_zhang
    else -> null
}
