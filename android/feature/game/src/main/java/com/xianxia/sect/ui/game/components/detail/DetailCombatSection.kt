package com.xianxia.sect.ui.game.components.detail

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.ui.components.DiscipleAttrText

@Composable
fun AttributesSection(disciple: DiscipleAggregate) {
    // 属性区只显示最终值，不显示基础值与括号加成
    val baseStats = remember(disciple) { disciple.getBaseStats() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "属性",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DiscipleAttrText("悟性", baseStats.comprehension, Modifier.weight(1f))
            DiscipleAttrText("智力", baseStats.intelligence, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DiscipleAttrText("魅力", baseStats.charm, Modifier.weight(1f))
            DiscipleAttrText("炼器", baseStats.artifactRefining, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DiscipleAttrText("炼丹", baseStats.pillRefining, Modifier.weight(1f))
            DiscipleAttrText("灵植", baseStats.spiritPlanting, Modifier.weight(1f))
            DiscipleAttrText("传道", baseStats.teaching, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DiscipleAttrText("道德", baseStats.morality, Modifier.weight(1f))
            DiscipleAttrText("采矿", baseStats.mining, Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun CombatStatsSection(
    disciple: DiscipleAggregate,
    equipped: List<EquipmentInstance>,
    learnedManuals: List<ManualInstance>,
    manualProficiencies: Map<String, List<ManualProficiencyData>>
) {
    // B3 实例轨：六部位已穿实例单列表（装配点由调用方按 displayOrder 解析）
    val equipmentMap = remember(equipped) {
        equipped.associateBy { it.id }
    }

    val manualMap = remember(learnedManuals) {
        learnedManuals.associateBy { it.id }
    }

    val discipleProficiencies = remember(disciple.id, manualProficiencies) {
        manualProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap()
    }

    val finalStats = remember(disciple, equipmentMap, manualMap, discipleProficiencies) {
        disciple.getFinalStats(equipmentMap, manualMap, discipleProficiencies)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "战斗属性",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        // 固有伤害属性标签（B1 §15.3：普攻类型由角色模板固定）
        val innateLabel = when (disciple.resolvedInnateDamageType) {
            "MAGIC" -> "法术"
            else -> "物理"
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatItem("攻击力", finalStats.attack, Modifier.weight(1f))
            StatItem("防御力", finalStats.defense, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatItem("速度", finalStats.speed, Modifier.weight(1f))
            StatItem("普攻属性", innateLabel, Modifier.weight(1f))
        }
    }
}

@Composable
fun StatItem(name: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            text = name,
            fontSize = 11.sp,
            color = Color.Black
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}

@Composable
fun StatItem(name: String, value: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            text = name,
            fontSize = 11.sp,
            color = Color.Black
        )
        Text(
            text = value.toString(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}

