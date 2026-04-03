package com.xianxia.sect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.ui.theme.GameColors

data class ElderBonusInfo(
    val title: String,
    val requiredAttribute: String,
    val effectDescription: String,
    val bonusFormula: String
)

@Composable
fun ElderBonusInfoButton(
    bonusInfo: ElderBonusInfo,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }
    
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(GameColors.Info)
            .border(1.dp, GameColors.Info, CircleShape)
            .clickable { showDialog = true },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "?",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
    
    if (showDialog) {
        ElderBonusInfoDialog(
            bonusInfo = bonusInfo,
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
fun ElderBonusInfoDialog(
    bonusInfo: ElderBonusInfo,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(GameColors.CardBackground)
                .border(1.dp, GameColors.Border, RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = bonusInfo.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = GameColors.TextPrimary
                )
                
                HorizontalDivider(
                    color = GameColors.Border,
                    thickness = 1.dp
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "所需属性:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = GameColors.TextSecondary
                    )
                    Text(
                        text = bonusInfo.requiredAttribute,
                        fontSize = 13.sp,
                        color = GameColors.Primary
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "效果说明:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = GameColors.TextSecondary
                    )
                    Text(
                        text = bonusInfo.effectDescription,
                        fontSize = 13.sp,
                        color = GameColors.Success
                    )
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.5f))
                        .padding(12.dp)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "加成计算:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = GameColors.TextSecondary
                        )
                        Text(
                            text = bonusInfo.bonusFormula,
                            fontSize = 12.sp,
                            color = GameColors.TextTertiary,
                            lineHeight = 18.sp
                        )
                    }
                }
                
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = "关闭",
                        fontSize = 14.sp,
                        color = GameColors.Primary
                    )
                }
            }
        }
    }
}

object ElderBonusInfoProvider {
    fun getLawEnforcementElderInfo(): ElderBonusInfo = ElderBonusInfo(
        title = "执法长老",
        requiredAttribute = "道心",
        effectDescription = "提升弟子修炼速度和降低叛逃概率",
        bonusFormula = "道心以50为基准，每高5点增加1%修炼速度加成，\n每低5点减少1%修炼速度。\n同时影响弟子忠诚度，降低叛逃概率。"
    )
    
    fun getAlchemyElderInfo(): ElderBonusInfo = ElderBonusInfo(
        title = "炼丹长老",
        requiredAttribute = "炼丹",
        effectDescription = "提升丹药炼制速度和成功率",
        bonusFormula = "炼丹属性以50为基准，每高5点增加1%炼制速度，\n每高5点增加1%成功率。\n炼丹属性低于50时，炼制速度和成功率会降低。"
    )
    
    fun getForgeElderInfo(): ElderBonusInfo = ElderBonusInfo(
        title = "天工长老",
        requiredAttribute = "炼器",
        effectDescription = "长老提升锻造成功率，亲传弟子提升炼制速度",
        bonusFormula = "长老：炼器属性以80为基准，每高1点增加1%成功率，最多20%。\n亲传弟子：炼器属性以80为基准，每高1点增加1%炼制速度。\n低于80时无加成效果。"
    )
    
    fun getHerbGardenElderInfo(): ElderBonusInfo = ElderBonusInfo(
        title = "灵植长老",
        requiredAttribute = "灵植",
        effectDescription = "提升灵药成熟速度",
        bonusFormula = "灵植属性以50为基准，每高5点增加1%成熟速度，\n每低5点减少1%成熟速度。\n灵植属性越高，灵药成熟越快。"
    )
    
    fun getLibraryElderInfo(): ElderBonusInfo = ElderBonusInfo(
        title = "藏经阁长老",
        requiredAttribute = "传道",
        effectDescription = "提升弟子修炼功法速度",
        bonusFormula = "传道属性每多1点增加1%修炼功法速度。\n传道属性越高，弟子修炼功法越快。"
    )
    
    fun getOuterElderInfo(): ElderBonusInfo = ElderBonusInfo(
        title = "外门长老",
        requiredAttribute = "悟性",
        effectDescription = "提升外门弟子突破率（仅外门弟子有效，弟子境界超过长老时不生效）",
        bonusFormula = "悟性属性以80为基准，每高1点增加1%突破率，最高20%。\n悟性低于80时无加成效果。\n仅对境界不超过长老的外门弟子生效。"
    )

    fun getInnerElderInfo(): ElderBonusInfo = ElderBonusInfo(
        title = "内门长老",
        requiredAttribute = "悟性",
        effectDescription = "提升内门弟子突破成功率",
        bonusFormula = "悟性以80为基准，每多1点增加1%突破率，最多20%。\n仅对内门弟子有效，弟子境界超过长老境界时不享受增益。"
    )

    fun getPreachingElderInfo(): ElderBonusInfo = ElderBonusInfo(
        title = "传道长老",
        requiredAttribute = "传道",
        effectDescription = "提升内门弟子修炼速度",
        bonusFormula = "传道以80为基准，每多1点增加1%修炼速度。\n仅对内门弟子有效，弟子境界超过长老境界时不享受增益。"
    )

    fun getPreachingMasterInfo(): ElderBonusInfo = ElderBonusInfo(
        title = "问道峰传道师",
        requiredAttribute = "传道",
        effectDescription = "提升外门弟子修炼速度（仅外门弟子有效，弟子境界超过传道师时不生效）",
        bonusFormula = "传道属性以80为基准，每高1点增加0.5%修炼速度。\n传道低于80时无加成效果。\n仅对境界不超过传道师的外门弟子生效。\n多名传道师加成可叠加。"
    )

    fun getQingyunPreachingMasterInfo(): ElderBonusInfo = ElderBonusInfo(
        title = "青云峰传道师",
        requiredAttribute = "传道",
        effectDescription = "提升内门弟子修炼速度",
        bonusFormula = "传道以80为基准，每多1点增加0.5%修炼速度。\n仅对内门弟子有效，弟子境界超过传道师境界时不享受增益。\n多名传道师加成可叠加。"
    )
    
    fun getSpiritMineDeaconInfo(): ElderBonusInfo = ElderBonusInfo(
        title = "灵矿执事",
        requiredAttribute = "道心",
        effectDescription = "提升灵矿产出效率",
        bonusFormula = "道心以50为基准，每高5点增加2%产出效率，\n每低5点减少2%产出效率。\n道心属性越高，灵矿产出越多。"
    )
}
