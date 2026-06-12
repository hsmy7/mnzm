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
            DiscipleAttrText("悟性", disciple.comprehension, Modifier.weight(1f))
            DiscipleAttrText("智力", disciple.intelligence, Modifier.weight(1f))
            DiscipleAttrText("魅力", disciple.charm, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DiscipleAttrText("忠诚", disciple.loyalty, Modifier.weight(1f))
            DiscipleAttrText("炼器", disciple.artifactRefining, Modifier.weight(1f))
            DiscipleAttrText("炼丹", disciple.pillRefining, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DiscipleAttrText("灵植", disciple.spiritPlanting, Modifier.weight(1f))
            DiscipleAttrText("传道", disciple.teaching, Modifier.weight(1f))
            DiscipleAttrText("道德", disciple.morality, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DiscipleAttrText("采矿", disciple.mining, Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun CombatStatsSection(
    disciple: DiscipleAggregate,
    weapon: EquipmentInstance?,
    armor: EquipmentInstance?,
    boots: EquipmentInstance?,
    accessory: EquipmentInstance?,
    learnedManuals: List<ManualInstance>,
    manualProficiencies: Map<String, List<ManualProficiencyData>>
) {
    val equipmentMap = remember(weapon, armor, boots, accessory) {
        mutableMapOf<String, EquipmentInstance>().apply {
            weapon?.let { put(it.id, it) }
            armor?.let { put(it.id, it) }
            boots?.let { put(it.id, it) }
            accessory?.let { put(it.id, it) }
        }
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

    val baseStats = remember(disciple) {
        disciple.getBaseStats()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "战斗属性",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatItemWithBonus("物攻", baseStats.physicalAttack, finalStats.physicalAttack, Modifier.weight(1f))
            StatItemWithBonus("法攻", baseStats.magicAttack, finalStats.magicAttack, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatItemWithBonus("物防", baseStats.physicalDefense, finalStats.physicalDefense, Modifier.weight(1f))
            StatItemWithBonus("法防", baseStats.magicDefense, finalStats.magicDefense, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatItemWithBonus("速度", baseStats.speed, finalStats.speed, Modifier.weight(1f))
            StatItem("神魂", disciple.soulPower, Modifier.weight(1f))
        }
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

@Composable
fun StatItemWithBonus(name: String, baseValue: Int, finalValue: Int, modifier: Modifier = Modifier, currentDisplay: String? = null) {
    val bonus = finalValue - baseValue
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
            text = currentDisplay ?: finalValue.toString(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        if (bonus > 0) {
            Text(
                text = "(+$bonus)",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF27AE60)
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.Black
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}
