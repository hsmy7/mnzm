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
    "碧木扇" -> R.drawable.bi_mu_shan
    "灵风扇" -> R.drawable.ling_feng_shan
    "玄冰扇" -> R.drawable.xuan_bing_shan
    "凰焰扇" -> R.drawable.huang_yan_shan
    "阴阳扇" -> R.drawable.yin_yang_shan
    "天玄扇" -> R.drawable.tian_xuan_shan
    "锁子甲" -> R.drawable.suo_zi_jia
    "皮甲" -> R.drawable.pi_jia
    "灵竹衣" -> R.drawable.ling_zhu_yi
    "精铁甲" -> R.drawable.jing_tie_jia
    "碧叶甲" -> R.drawable.bi_ye_jia
    "丹羽衣" -> R.drawable.dan_yu_yi
    "青鳞铠" -> R.drawable.qing_lin_kai
    "银板铠" -> R.drawable.yin_ban_kai
    "汐流衣" -> R.drawable.xi_liu_yi
    "灵丝袍" -> R.drawable.ling_si_pao
    "云纹袍" -> R.drawable.yun_wen_pao
    "龙鳞铠" -> R.drawable.long_lin_kai
    "渊岩铠" -> R.drawable.yuan_yan_kai
    "瑶光袍" -> R.drawable.yao_guang_pao
    "月华袍" -> R.drawable.yue_hua_pao
    "星辰袍" -> R.drawable.xing_chen_pao
    "玄幽袍" -> R.drawable.xuan_you_pao
    "墨幽铠" -> R.drawable.mo_you_kai
    "凌星袍" -> R.drawable.ling_xing_pao
    "定海铠" -> R.drawable.ding_hai_kai
    "不朽铠" -> R.drawable.bu_xiu_kai
    "苍罡铠" -> R.drawable.cang_gang_kai
    "曦光铠" -> R.drawable.xi_guang_kai
    "云影袍" -> R.drawable.yun_ying_pao
    else -> null
}
