package com.xianxia.sect.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.R

/**
 * Returns the drawable resource ID for the given equipment name, or null if no sprite exists.
 */
fun equipmentSpriteRes(name: String): Int? = when (name) {
    "精铁剑" -> R.drawable.jing_tie_jian
    "烈焰剑" -> R.drawable.lie_yan_jian
    "雷霆剑" -> R.drawable.lei_ting_jian
    "诛仙剑" -> R.drawable.zhu_xian_jian
    else -> null
}

/**
 * Displays an equipment sprite if available, otherwise shows "敬请期待" placeholder.
 * Background is determined by the rarity grade color.
 */
@Composable
fun EquipmentSpriteBox(
    name: String,
    rarityColor: Color,
    modifier: Modifier = Modifier,
    spriteScale: ContentScale = ContentScale.Fit
) {
    val spriteRes = equipmentSpriteRes(name)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(rarityColor),
        contentAlignment = Alignment.Center
    ) {
        if (spriteRes != null) {
            Image(
                painter = painterResource(id = spriteRes),
                contentDescription = name,
                modifier = Modifier.fillMaxSize().padding(4.dp),
                contentScale = spriteScale
            )
        } else {
            Text(
                text = "敬请期待",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }
    }
}
