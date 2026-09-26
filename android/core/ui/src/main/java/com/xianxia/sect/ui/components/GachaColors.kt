package com.xianxia.sect.ui.components

import androidx.compose.ui.graphics.Color
import com.xianxia.sect.core.GameConfig

/**
 * 寻访域色板换算口——Q31 色表（[GameConfig.Gacha.RARITY_COLORS] /
 * [GameConfig.Gacha.SPIRIT_ROOT_COUNT_COLORS]）到 Compose [Color] 的**唯一**换算入口。
 *
 * ## 为什么需要这一个对象
 * 本仓此前没有「hex 字符串 → Compose Color」的共享 helper，11 处调用点各自内联
 * `android.graphics.Color.parseColor` 并各自兜底（兜底色互不一致：黑 / 边框灰 / 成功绿 /
 * 浅灰 / 紫）。寻访结果页与图鉴要按品阶色与灵根数色上色，再接一份内联解析就是第 12 份真源，
 * 故收敛到这里。
 *
 * ## 单源边界
 * 全仓品阶色只有 [GameConfig.Gacha.RARITY_COLORS] 一张值表：`theme/Color.kt` 的
 * `Rarity*` 六常量、`ItemCard.getRarityColor`、丹药品质色（下/中/上品）与
 * `GameConfig.Rarity.CONFIGS[].color` 全部委托本表；判据见 `GachaColorSingleSourceGuardTest`。
 */
object GachaColors {

    /** 物品品阶色（六金五红四紫三蓝二绿一灰） */
    fun rarityColor(rarity: Int): Color = parse(GameConfig.Gacha.rarityColor(rarity))

    /** 灵根数徽章色（单金双红三紫四蓝五灰）——角色碎片框与弟子/长老灵根徽章同一套 */
    fun spiritRootCountColor(rootCount: Int): Color = parse(GameConfig.Gacha.spiritRootCountColor(rootCount))

    /**
     * `#rrggbb` → [Color]（不依赖 `android.graphics`，纯 JVM 可在无 Robolectric 的测试里跑）。
     *
     * 解析失败按一阶灰处理——与 [GameConfig.Gacha.rarityColor] 自身「未知品阶回落 1 档」
     * 同口径，不让一个坏色值把整页打成异常。
     */
    fun parse(hex: String): Color {
        val rgb = hex.removePrefix("#").toLongOrNull(RADIX) ?: return FALLBACK_COLOR
        return Color(TRANSPARENT_BLACK or rgb)
    }

    private const val RADIX = 16
    private const val TRANSPARENT_BLACK = 0xFF000000L
    private val FALLBACK_COLOR = Color(0xFFB8B8B8)
}
