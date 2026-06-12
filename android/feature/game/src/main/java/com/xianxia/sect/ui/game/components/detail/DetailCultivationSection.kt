package com.xianxia.sect.ui.game.components.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.GameUtils

import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.game.GameViewModel
import java.util.Locale

internal fun calculatePreachingBonusesForDisplay(
    disciple: DiscipleAggregate,
    elderSlots: ElderSlots?,
    allDisciples: List<DiscipleAggregate>,
    sectPolicies: SectPolicies? = null
): Triple<Double, Double, Double> {
    if (elderSlots == null) return Triple(0.0, 0.0, 0.0)
    val allDisciplesById = allDisciples.associateBy { it.id }
    val dtype = disciple.discipleType
    val dRealm = disciple.realm
    var elderBonus = 0.0
    var mastersBonus = 0.0

    if (dtype == "outer") {
        val elderId = elderSlots.preachingElder
        if (elderId.isNotEmpty()) {
            val elder = allDisciplesById[elderId]
            if (elder != null && elder.isAlive) {
                val t = elder.getBaseStats().teaching
                if (dRealm >= elder.realm && t >= 80) {
                    elderBonus += (t - 80) * 0.01
                }
            }
        }
        for (slot in elderSlots.preachingMasters) {
            val mid = slot.discipleId
            if (mid.isNotEmpty()) {
                val m = allDisciplesById[mid]
                if (m != null && m.isAlive) {
                    val t = m.getBaseStats().teaching
                    if (dRealm >= m.realm && t >= 80) {
                        mastersBonus += (t - 80) * 0.005
                    }
                }
            }
        }
    }

    if (dtype == "inner") {
        val elderId = elderSlots.qingyunPreachingElder
        if (elderId.isNotEmpty()) {
            val elder = allDisciplesById[elderId]
            if (elder != null && elder.isAlive) {
                val t = elder.getBaseStats().teaching
                if (dRealm >= elder.realm && t >= 80) {
                    elderBonus += (t - 80) * 0.01
                }
            }
        }
        for (slot in elderSlots.qingyunPreachingMasters) {
            val mid = slot.discipleId
            if (mid.isNotEmpty()) {
                val m = allDisciplesById[mid]
                if (m != null && m.isAlive) {
                    val t = m.getBaseStats().teaching
                    if (dRealm >= m.realm && t >= 80) {
                        mastersBonus += (t - 80) * 0.005
                    }
                }
            }
        }
    }

    var cultivationSubsidyBonus = 0.0
    if (sectPolicies != null && sectPolicies.cultivationSubsidy && dRealm > 5) {
        cultivationSubsidyBonus = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_BASE_EFFECT
    }

    return Triple(elderBonus, mastersBonus, cultivationSubsidyBonus)
}

@Composable
fun BasicInfoSection(
    disciple: DiscipleAggregate,
    allEquipment: List<EquipmentInstance> = emptyList(),
    allManuals: List<ManualInstance> = emptyList(),
    manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),
    position: String? = null,
    isWorkStatusPosition: Boolean = false,
    elderSlots: ElderSlots? = null,
    allDisciples: List<DiscipleAggregate> = emptyList(),
    sectPolicies: SectPolicies? = null,
    residenceSlots: List<ResidenceSlot> = emptyList(),
    placedBuildings: List<GridBuildingData> = emptyList(),
    viewModel: GameViewModel? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "基本信息",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = disciple.genderName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            if (position != null) {
                Text(
                    text = position,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isWorkStatusPosition) Color(0xFFFF9800) else Color(0xFF4CAF50)
                )
            }
            Text(
                text = disciple.status.displayName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            val spiritRootCountColor = remember(disciple.spiritRoot.countColor) {
                try {
                    Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                } catch (e: Exception) {
                    Color.Black
                }
            }
            Text(
                text = disciple.spiritRootName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = spiritRootCountColor,
                maxLines = 1
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoItem("寿命 ${disciple.age}/${disciple.lifespan}", Modifier.weight(1f))
            val breakthroughChance = disciple.getBreakthroughChance()
            val innerElderComp = elderSlots?.innerElder?.let { eid ->
                allDisciples.find { it.id == eid }?.comprehension ?: 0
            } ?: 0
            val outerElderComp = elderSlots?.outerElder?.let { eid ->
                allDisciples.find { it.id == eid }?.comprehension ?: 0
            } ?: 0
            val detail = DiscipleStatCalculator.getBreakthroughBonusDetail(
                disciple,
                innerElderComprehension = innerElderComp,
                outerElderComprehensionBonus = if (outerElderComp >= 80) ((outerElderComp - 80) / 4) * 0.01 else 0.0,
                adBonus = disciple.statusData["adBreakthroughBonus"]?.toDoubleOrNull() ?: 0.0
            )
            var showBreakthroughDetail by remember { mutableStateOf(false) }
            val adBonusValue = disciple.statusData["adBreakthroughBonus"]?.toDoubleOrNull() ?: 0.0
            val context = LocalContext.current
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "突破率 ${GameUtils.formatPercent(breakthroughChance)}",
                    fontSize = 12.sp,
                    color = Color.Black,
                    modifier = Modifier.weight(1f)
                )
                Image(
                    painter = painterResource(id = R.drawable.ui_detail_button),
                    contentDescription = "详情",
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable { showBreakthroughDetail = true },
                    contentScale = ContentScale.FillBounds
                )
                if (adBonusValue < 0.25) {
                    // Ad feature removed - requires app-layer TapTap SDK dependency
                }
            }
            if (showBreakthroughDetail) {
                BreakthroughDetailDialog(
                    detail = detail,
                    onDismiss = { showBreakthroughDetail = false }
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (disciple.realm != 0) {
                val manualsMap = remember(allManuals) {
                    allManuals.associateBy { it.id }
                }
                val proficiencyMap = remember(manualProficiencies, disciple.id) {
                    manualProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap()
                }
                val buildingBonus = remember(disciple, residenceSlots, placedBuildings) {
                    val slot = residenceSlots.firstOrNull { it.discipleId == disciple.id }
                    val building = slot?.let { s ->
                        placedBuildings.firstOrNull { it.instanceId == s.buildingInstanceId }
                    }
                    when (building?.displayName) {
                        "中级单人住所" -> 1.50
                        "单人住所" -> 1.25
                        "多人住所" -> 1.10
                        else -> 1.0
                    }
                }
                val cultivationSpeed = remember(disciple, manualsMap, proficiencyMap, allDisciples, elderSlots, sectPolicies, buildingBonus) {
                    val (preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus) = calculatePreachingBonusesForDisplay(
                        disciple, elderSlots, allDisciples,
                        sectPolicies = sectPolicies
                    )
                    disciple.calculateCultivationSpeed(
                        manualsMap, proficiencyMap,
                        buildingBonus = buildingBonus,
                        preachingElderBonus = preachingElderBonus,
                        preachingMastersBonus = preachingMastersBonus,
                        cultivationSubsidyBonus = cultivationSubsidyBonus
                    ).coerceIn(1.0, 1000.0)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = disciple.realmName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    val cultivationTarget = disciple.cultivationProgress.toFloat().coerceIn(0f, 1f)
                    val cultivationProgressState = rememberUpdatedState(cultivationTarget)
                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                    ) {
                        val progress = cultivationProgressState.value
                        drawRect(Color(0xFFE8E8E8))
                        drawRect(
                            Color(0xFF4CAF50),
                            size = Size(size.width * progress, size.height)
                        )
                    }
                    Text(
                        text = "${disciple.cultivation.toInt()}/${disciple.maxCultivation.toInt()}",
                        fontSize = 7.sp,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                    Text(
                        text = "${String.format(Locale.getDefault(), "%.1f", cultivationSpeed)}/秒",
                        fontSize = 10.sp,
                        color = Color(0xFF4CAF50)
                    )
                }
            } else {
                Text(
                    text = disciple.realmName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val equipmentMap = remember(disciple.weaponId, disciple.armorId, disciple.bootsId, disciple.accessoryId, allEquipment) {
            mutableMapOf<String, EquipmentInstance>().apply {
                listOfNotNull(disciple.weaponId, disciple.armorId, disciple.bootsId, disciple.accessoryId)
                    .filter { it.isNotEmpty() }
                    .forEach { id -> allEquipment.find { it.id == id }?.let { put(it.id, it) } }
            }
        }
        val manualMap = remember(allManuals) { allManuals.associateBy { it.id } }
        val discipleProficiencies = remember(disciple.id, manualProficiencies) {
            manualProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap()
        }
        val finalStats = remember(disciple, equipmentMap, manualMap, discipleProficiencies) {
            disciple.getFinalStats(equipmentMap, manualMap, discipleProficiencies)
        }

        HpMpBars(disciple, finalStats.maxHp, finalStats.maxMp)
    }
}

@Composable
fun HpMpBars(disciple: DiscipleAggregate, maxHpOverride: Int? = null, maxMpOverride: Int? = null) {
    val maxHp = maxHpOverride ?: disciple.maxHp
    val maxMp = maxMpOverride ?: disciple.maxMp
    val rawCurrentHp = disciple.currentHp
    val rawCurrentMp = disciple.currentMp
    val currentHpDisplay = if (rawCurrentHp < 0) maxHp else rawCurrentHp
    val currentMpDisplay = if (rawCurrentMp < 0) maxMp else rawCurrentMp
    val hpFraction = if (maxHp > 0) (currentHpDisplay.toFloat() / maxHp).coerceIn(0f, 1f) else 1f
    val mpFraction = if (maxMp > 0) (currentMpDisplay.toFloat() / maxMp).coerceIn(0f, 1f) else 1f

    val prevHpTarget = remember { mutableStateOf(hpFraction) }
    val prevMpTarget = remember { mutableStateOf(mpFraction) }
    val hpShouldSnap = hpFraction < prevHpTarget.value - 0.5f
    val mpShouldSnap = mpFraction < prevMpTarget.value - 0.5f

    val animatedHpFraction by animateFloatAsState(
        targetValue = hpFraction,
        animationSpec = if (hpShouldSnap) snap() else tween(durationMillis = 300),
        label = "hpProgress"
    )
    val animatedMpFraction by animateFloatAsState(
        targetValue = mpFraction,
        animationSpec = if (mpShouldSnap) snap() else tween(durationMillis = 300),
        label = "mpProgress"
    )
    val hpProgressState = rememberUpdatedState(animatedHpFraction)
    val mpProgressState = rememberUpdatedState(animatedMpFraction)
    SideEffect {
        prevHpTarget.value = hpFraction
        prevMpTarget.value = mpFraction
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "气血",
                fontSize = 9.sp,
                color = Color.Black,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(1.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                drawRect(Color(0xFFE8E8E8))
                drawRect(
                    Color(0xFFE74C3C),
                    size = Size(size.width * hpProgressState.value, size.height)
                )
            }
            Text(
                text = "$currentHpDisplay/$maxHp",
                fontSize = 7.sp,
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                style = TextStyle(
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                )
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "灵力",
                fontSize = 9.sp,
                color = Color.Black,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(1.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                drawRect(Color(0xFFE8E8E8))
                drawRect(
                    Color(0xFF3498DB),
                    size = Size(size.width * mpProgressState.value, size.height)
                )
            }
            Text(
                text = "$currentMpDisplay/$maxMp",
                fontSize = 7.sp,
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                style = TextStyle(
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                )
            )
        }
    }
}

@Composable
fun InfoItem(value: String, modifier: Modifier = Modifier, color: Color = Color.Black) {
    Text(
        text = value,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = modifier
    )
}

@Composable
fun BreakthroughDetailDialog(
    detail: DiscipleStatCalculator.BreakthroughBonusDetail,
    onDismiss: () -> Unit
) {
    val items = buildList {
        if (detail.innerElderBonus > 0) add("内门执事加成" to detail.innerElderBonus)
        if (detail.outerElderBonus > 0) add("外门执事加成" to detail.outerElderBonus)
        if (detail.talentBonus > 0) add("天赋加成" to detail.talentBonus)
        if (detail.soulPowerBonus > 0) add("神魂加成" to detail.soulPowerBonus)
        if (detail.pillBonus > 0) add("丹药加成" to detail.pillBonus)
        if (detail.adBonus > 0) add("广告加成" to detail.adBonus)
        if (detail.griefPenalty > 0) add("丧亲减益" to -detail.griefPenalty)
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
        ) {
            Image(
                painter = painterResource(id = R.drawable.bg_horizontal),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "突破率详情",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        modifier = Modifier.weight(1f)
                    )
                    CloseButton(onClick = onDismiss)
                }

                HorizontalDivider(color = Color(0xFFDDDDDD), thickness = 1.dp)

                if (items.isEmpty()) {
                    Text("无额外加成", fontSize = 13.sp, color = Color.Black)
                } else {
                    val columns = items.chunked(3)
                    columns.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            row.forEach { (label, value) ->
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        color = Color.Black
                                    )
                                    Text(
                                        text = "+${GameUtils.formatPercent(value)}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                            }
                            repeat(3 - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
