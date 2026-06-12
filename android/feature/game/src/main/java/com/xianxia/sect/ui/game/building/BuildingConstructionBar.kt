package com.xianxia.sect.ui.game.building

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.Image
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun BuildingConstructionBar(
    buildingList: List<Pair<String, (GridBuildingData?) -> Unit>>,
    placedBuildings: List<GridBuildingData>,
    buildingCosts: Map<String, Long>,
    spiritStones: Long,
    onSelectBuilding: (String) -> Unit,
    modifier: Modifier = Modifier,
    getBuildingCount: (String) -> Int = { name -> placedBuildings.count { it.displayName == name } },
    getBuildingMaxCount: (String) -> Int = { 1 }
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Image(
            painter = painterResource(id = R.drawable.bg_horizontal),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            buildingList.forEach { (name, _) ->
                val built = placedBuildings.count { it.displayName == name } >= getBuildingMaxCount(name)
                val cost = buildingCosts[name] ?: 1000L
                val canAfford = spiritStones >= cost
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier
                            .width(64.dp)
                            .height(60.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, GameColors.ButtonBorder, RoundedCornerShape(6.dp))
                            .clickable(enabled = !built && canAfford) { onSelectBuilding(name) }
                    ) {
                        Text(
                            text = name,
                            fontSize = 8.sp,
                            lineHeight = 8.sp,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.7f))
                        )
                        Image(
                            painter = painterResource(id = BuildingRegistry.drawableRes(name)),
                            contentDescription = name,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                            alpha = if (built || !canAfford) 0.4f else 1f
                        )
                        Text(
                            text = "${cost}灵石",
                            fontSize = 7.sp,
                            lineHeight = 7.sp,
                            color = Color.Black,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.7f))
                        )
                    }
                    val maxCount = getBuildingMaxCount(name)
                    if (maxCount < Int.MAX_VALUE) {
                        Text(
                            text = "${getBuildingCount(name)}/$maxCount",
                            fontSize = 9.sp,
                            color = Color.Black,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
