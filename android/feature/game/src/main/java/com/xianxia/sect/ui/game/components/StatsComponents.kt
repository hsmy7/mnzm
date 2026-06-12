package com.xianxia.sect.ui.game.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TwoColumnStatsDisplay(
    stats: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        stats.chunked(2).forEach { rowStats ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                rowStats.forEach { (label, value) ->
                    Row(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "$label: ",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                        Text(
                            text = value,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }
                if (rowStats.size == 1) {
                    Row(modifier = Modifier.weight(1f)) {}
                }
            }
        }
    }
}

@Composable
fun TwoColumnSkillAttrsDisplay(
    attrs: Map<String, Int>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        attrs.entries.chunked(2).forEach { rowAttrs ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                rowAttrs.forEach { (label, value) ->
                    Row(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "$label: ",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                        Text(
                            text = value.toString(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.Black
                        )
                    }
                }
                if (rowAttrs.size == 1) {
                    Row(modifier = Modifier.weight(1f)) {}
                }
            }
        }
    }
}
