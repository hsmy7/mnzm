package com.xianxia.sect.ui.game.components.detail

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.ui.components.DiscipleAttrText

@Composable
fun AttributesSection(disciple: DiscipleAggregate) {
    // 2026-08-12 修复：属性区只显示最终值（含天赋 Flat 加成，如洗炼"青帝 灵植+18"），
    // 不再显示基础值——洗出加灵植的天赋后面板应立即体现（用户确认口径：不显示括号加成）
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
            DiscipleAttrText("魅力", baseStats.charm, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DiscipleAttrText("忠诚", baseStats.loyalty, Modifier.weight(1f))
            DiscipleAttrText("炼器", baseStats.artifactRefining, Modifier.weight(1f))
            DiscipleAttrText("炼丹", baseStats.pillRefining, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DiscipleAttrText("灵植", baseStats.spiritPlanting, Modifier.weight(1f))
            DiscipleAttrText("传道", baseStats.teaching, Modifier.weight(1f))
            DiscipleAttrText("道德", baseStats.morality, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DiscipleAttrText("采矿", baseStats.mining, Modifier.weight(1f))
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
    manualProficiencies: Map<String, List<ManualProficiencyData>>,
    bloodRefinementPct: BloodRefinementPctTotal? = null
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

    val finalStats = remember(disciple, equipmentMap, manualMap, discipleProficiencies, bloodRefinementPct) {
        disciple.getFinalStats(equipmentMap, manualMap, discipleProficiencies, bloodRefinementPct)
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
            StatItem("物攻", finalStats.physicalAttack, Modifier.weight(1f))
            StatItem("法攻", finalStats.magicAttack, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatItem("物防", finalStats.physicalDefense, Modifier.weight(1f))
            StatItem("法防", finalStats.magicDefense, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatItem("速度", finalStats.speed, Modifier.weight(1f))
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

