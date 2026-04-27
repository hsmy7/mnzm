package com.xianxia.sect.ui.game

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.core.util.isFollowed
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

@Composable
fun RealtimeCultivationProgress(
    disciple: DiscipleAggregate,
    realtimeCultivation: Map<String, Double>,
    modifier: Modifier = Modifier
) {
    val currentCultivation by remember(disciple.id, realtimeCultivation) {
        derivedStateOf {
            realtimeCultivation[disciple.id] ?: disciple.cultivation
        }
    }
    
    val progress by remember(currentCultivation, disciple.core.maxCultivation) {
        derivedStateOf {
            if (disciple.core.maxCultivation > 0) {
                (currentCultivation / disciple.core.maxCultivation).coerceIn(0.0, 1.0).toFloat()
            } else 0f
        }
    }
    
    val progressColor by remember(progress) {
        derivedStateOf {
            when {
                progress >= 0.9f -> Color(0xFFE74C3C)
                progress >= 0.7f -> Color(0xFFF39C12)
                progress >= 0.5f -> Color(0xFF3498DB)
                else -> Color(0xFF27AE60)
            }
        }
    }
    
    val prevProgressTarget = remember { mutableStateOf(progress) }
    val shouldSnap = progress < prevProgressTarget.value - 0.5f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = if (shouldSnap) snap() else tween(durationMillis = 300),
        label = "cultivationProgress"
    )
    SideEffect { prevProgressTarget.value = progress }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "修为",
                fontSize = 11.sp,
                color = Color(0xFF666666)
            )
            Text(
                text = "${String.format(Locale.getDefault(), "%.1f", currentCultivation)} / ${disciple.core.maxCultivation}",
                fontSize = 10.sp,
                color = Color(0xFF999999)
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(GameColors.Border),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = animatedProgress)
                    .fillMaxHeight()
                    .background(progressColor)
            )
        }
    }
}

@Composable
fun RealtimeDiscipleCard(
    disciple: DiscipleAggregate,
    realtimeCultivation: Map<String, Double>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val realmColor = remember(disciple.realm) {
        when (disciple.realm) {
            0 -> Color(0xFFFFD700)
            1 -> Color(0xFFE74C3C)
            2 -> Color(0xFF9B59B6)
            3 -> Color(0xFF3498DB)
            4 -> Color(0xFF1ABC9C)
            5 -> Color(0xFF27AE60)
            6 -> Color(0xFFF39C12)
            7 -> Color(0xFFE67E22)
            8 -> Color(0xFF95A5A6)
            else -> Color(0xFF7F8C8D)
        }
    }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = GameColors.PageBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = disciple.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    if (disciple.isFollowed) {
                        FollowedTag()
                    }
                }
                
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(realmColor)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = disciple.realmName,
                        fontSize = 11.sp,
                        color = Color.White
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            RealtimeCultivationProgress(
                disciple = disciple,
                realtimeCultivation = realtimeCultivation
            )
        }
    }
}

@Composable
fun rememberRealtimeCultivation(
    highFrequencyData: StateFlow<HighFrequencyData>
): Map<String, Double> {
    val data by highFrequencyData.collectAsState()
    return remember(data.timestamp) {
        data.cultivationUpdates
    }
}
