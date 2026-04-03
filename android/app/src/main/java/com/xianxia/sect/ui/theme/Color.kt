package com.xianxia.sect.ui.theme

import androidx.compose.ui.graphics.Color

object GameColors {
    val Primary = Color(0xFF4A90E2)
    val PrimaryVariant = Color(0xFF3A7BC8)
    val Secondary = Color(0xFF00A86B)
    val SecondaryVariant = Color(0xFF008B5A)
    
    val PageBackground = Color(0xFFF8F5F2)
    val Background = Color(0xFFF8F5F2)
    val BackgroundDark = Color(0xFFF8F5F2)
    val Surface = Color(0xFFF8F5F2)
    val SurfaceDark = Color(0xFFF8F5F2)
    
    val TextPrimary = Color(0xFF000000)
    val TextSecondary = Color(0xFF666666)
    val TextTertiary = Color(0xFF999999)
    val TextOnPrimary = Color(0xFFFFFFFF)
    
    val Border = Color(0xFFDCD6D0)
    val BorderLight = Color(0xFFDCD6D0)
    val Divider = Color(0xFFDCD6D0)
    
    val Success = Color(0xFF4CAF50)
    val Warning = Color(0xFFFF9800)
    val Error = Color(0xFFF44336)
    val Info = Color(0xFF2196F3)
    
    val Gold = Color(0xFFFFD700)
    val GoldDark = Color(0xFFB8860B)
    val JadeGreen = Color(0xFF00A86B)
    val SpiritBlue = Color(0xFF4A90E2)
    val CultivationPurple = Color(0xFF9B59B6)
    
    val CardBackground = Color(0xFFE9E4DF)
    val CardBackgroundSelected = Color(0xFFE9E4DF)
    
    val RarityCommon = Color(0xFF95A5A6)
    val RaritySpirit = Color(0xFF27AE60)
    val RarityTreasure = Color(0xFF3498DB)
    val RarityMystic = Color(0xFF9B59B6)
    val RarityEarth = Color(0xFFF39C12)
    val RarityHeaven = Color(0xFFE74C3C)
    
    val RealmLianQi = Color(0xFF95A5A6)
    val RealmZhuJi = Color(0xFF27AE60)
    val RealmJinDan = Color(0xFF3498DB)
    val RealmYuanYing = Color(0xFF9B59B6)
    val RealmHuaShen = Color(0xFFF39C12)
    val RealmLianXu = Color(0xFFE74C3C)
    val RealmHeTi = Color(0xFFE91E63)
    val RealmDaCheng = Color(0xFFFF5722)
    val RealmDuJie = Color(0xFF795548)
    val RealmXianRen = Color(0xFFFFD700)
    
    val SpiritRootMetal = Color(0xFFF1C40F)
    val SpiritRootWood = Color(0xFF27AE60)
    val SpiritRootWater = Color(0xFF3498DB)
    val SpiritRootFire = Color(0xFFE74C3C)
    val SpiritRootEarth = Color(0xFF95A5A6)
    
    val SingleRoot = Color(0xFFE74C3C)
    val DoubleRoot = Color(0xFFF39C12)
    val TripleRoot = Color(0xFF9B59B6)
    val QuadRoot = Color(0xFF27AE60)
    val PentaRoot = Color(0xFF95A5A6)
    
    val HpBar = Color(0xFFE74C3C)
    val MpBar = Color(0xFF3498DB)
    val ExpBar = Color(0xFF4CAF50)
    
    val ButtonPrimary = Color.White
    val ButtonSecondary = Color(0xFF00A86B)
    val ButtonDanger = Color(0xFFE74C3C)
    val ButtonDisabled = Color(0xFFBDBDBD)
    
    val ButtonBackground = Color(0xFFF5F5DC)
    val ButtonBorder = Color(0xFFC4A484)
    val SelectedBorder = Color(0xFFFFD700)
    
    val TapTapGreen = Color(0xFF00D26A)
    
    fun getRarityColor(rarity: Int): Color = when (rarity) {
        1 -> RarityCommon
        2 -> RaritySpirit
        3 -> RarityTreasure
        4 -> RarityMystic
        5 -> RarityEarth
        6 -> RarityHeaven
        else -> RarityCommon
    }
    
    fun getRealmColor(realm: Int): Color = when (realm) {
        9 -> RealmLianQi
        8 -> RealmZhuJi
        7 -> RealmJinDan
        6 -> RealmYuanYing
        5 -> RealmHuaShen
        4 -> RealmLianXu
        3 -> RealmHeTi
        2 -> RealmDaCheng
        1 -> RealmDuJie
        0 -> RealmXianRen
        else -> RealmLianQi
    }
    
    fun getSpiritRootColor(rootType: String): Color = when (rootType.trim().lowercase()) {
        "metal", "金" -> SpiritRootMetal
        "wood", "木" -> SpiritRootWood
        "water", "水" -> SpiritRootWater
        "fire", "火" -> SpiritRootFire
        "earth", "土" -> SpiritRootEarth
        else -> SpiritRootMetal
    }
    
    fun getSpiritRootCountColor(count: Int): Color = when (count) {
        1 -> SingleRoot
        2 -> DoubleRoot
        3 -> TripleRoot
        4 -> QuadRoot
        5 -> PentaRoot
        else -> PentaRoot
    }
}

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// 顶层便捷函数
fun getRealmColor(realm: Int): Color = GameColors.getRealmColor(realm)

fun getRarityColor(rarity: Int): Color = GameColors.getRarityColor(rarity)

fun getSpiritRootColor(rootType: String): Color = GameColors.getSpiritRootColor(rootType)

// XianxiaColorScheme for UI components
data class XianxiaColorScheme(
    val cardBackground: Color = Color(0xFFE9E4DF),
    val cardBorder: Color = Color(0xFFDCD6D0),
    val primaryGold: Color = Color(0xFFFFD700),
    val textLight: Color = Color(0xFF666666),
    val textDark: Color = Color(0xFF333333),
    val jade: Color = Color(0xFF00A86B),
    val spiritBlue: Color = Color(0xFF4A90E2),
    val bloodRed: Color = Color(0xFFE74C3C),
    val rarityColors: Map<Int, Color> = mapOf(
        1 to Color(0xFF95A5A6),
        2 to Color(0xFF27AE60),
        3 to Color(0xFF3498DB),
        4 to Color(0xFF9B59B6),
        5 to Color(0xFFF39C12),
        6 to Color(0xFFE74C3C)
    )
)
