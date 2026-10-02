package com.xianxia.sect.ui.game.components.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.rememberChasingProgress
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.core.engine.domain.disciple.getBreakthroughBonusDetail

/**
 * 弟子详情"基本信息"区块：内聚 BasicInfoSection 全部子组件与纯计算辅助。
 */
@Composable
fun BasicInfoSection(
    disciple: DiscipleAggregate,
    allEquipment: List<EquipmentInstance> = emptyList(),
    allManuals: List<ManualInstance> = emptyList(),
    manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),
    elderSlots: ElderSlots? = null,
    allDisciples: List<DiscipleAggregate> = emptyList(),
    sectPolicies: SectPolicies? = null,
    residenceSlots: List<ResidenceSlot> = emptyList(),
    placedBuildings: List<GridBuildingData> = emptyList(),
    onBreakthroughJadeClick: (() -> Unit)? = null
) {
    val discipleMap = allDisciples.associateBy { it.id }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "基本信息",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        BasicInfoIdentityRow(disciple)

        BasicInfoBreakthroughRow(
            disciple = disciple,
            discipleMap = discipleMap,
            elderSlots = elderSlots,
            onBreakthroughJadeClick = onBreakthroughJadeClick
        )

        BasicInfoRealmRow(
            disciple = disciple,
            data = RealmRowData(
                allManuals, manualProficiencies, elderSlots, allDisciples,
                sectPolicies, residenceSlots, placedBuildings
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        val equipmentMap = remember(
            disciple.headId, disciple.bodyId, disciple.handsId,
            disciple.feetId, allEquipment
        ) {
            discipleEquipmentMap(disciple, allEquipment)
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

// ── BasicInfoSection 子组件 ──

@Composable
private fun BasicInfoIdentityRow(
    disciple: DiscipleAggregate
) {
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
        Text(
            text = disciple.statusText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        val spiritRootCountColor = remember(disciple.spiritRoot.countColor) {
            try {
                Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
            } catch (expected: IllegalArgumentException) {
                // 颜色字符串非法时回退黑色（旧存档/外部构造数据可能写入非法色值）
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
}

@Composable
private fun BasicInfoBreakthroughRow(
    disciple: DiscipleAggregate,
    discipleMap: Map<String, DiscipleAggregate>,
    elderSlots: ElderSlots?,
    onBreakthroughJadeClick: (() -> Unit)?
) {
    val detail = DiscipleStatCalculator.getBreakthroughBonusDetail(
        disciple,
        innerElderComprehension = elderBreakthroughComprehension(
            disciple, "inner", elderSlots?.innerElder, discipleMap
        ),
        outerElderComprehension = elderBreakthroughComprehension(
            disciple, "outer", elderSlots?.outerElder, discipleMap
        ),
        adBonus = disciple.statusData["adBreakthroughBonus"]?.toDoubleOrNull() ?: 0.0
    )
    val adBonusValue = disciple.statusData["adBreakthroughBonus"]?.toDoubleOrNull() ?: 0.0
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "突破率 ${GameUtils.formatPercent(detail.total)}",
                fontSize = 12.sp,
                color = Color.Black
            )
            BreakthroughDetailButton(detail)
            // 玉符加成上限 0.30（2 次 × 0.15），达到后隐藏入口；无上层回调（viewModel 为空）也隐藏
            if (adBonusValue < GameConfig.JadePurchase.BREAKTHROUGH_BONUS_MAX && onBreakthroughJadeClick != null) {
                JadeBreakthroughButton(onClick = onBreakthroughJadeClick)
            }
        }
    }
}

/** 内/外门长老突破悟性加成（type 与弟子身份匹配才生效，弟子死亡或境界不足返回 0） */
private fun elderBreakthroughComprehension(
    disciple: DiscipleAggregate,
    discipleType: String,
    elderId: String?,
    discipleMap: Map<String, DiscipleAggregate>
): Int {
    val elder = elderId?.let { discipleMap[it] }
    if (disciple.discipleType != discipleType) return 0
    return if (elder != null && elder.isAlive && disciple.realm >= elder.realm) {
        // 长老悟性本体（与突破结算同源：getBaseStats().comprehension）
        elder.getBaseStats().comprehension
    } else {
        0
    }
}

@Composable
private fun BreakthroughDetailButton(
    detail: DiscipleStatCalculator.BreakthroughBonusDetail
) {
    var showBreakthroughDetail by remember { mutableStateOf(false) }
    Image(
        painter = painterResource(id = R.drawable.ui_detail_button),
        contentDescription = "详情",
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .clickable { showBreakthroughDetail = true },
        contentScale = ContentScale.FillBounds
    )
    if (showBreakthroughDetail) {
        BreakthroughDetailDialog(
            detail = detail,
            onDismiss = { showBreakthroughDetail = false }
        )
    }
}

/**
 * 突破率玉符购买入口（+ 号按钮）：点击回调上抛，由上层（DiscipleDetailDialog 根 Box 最末）
 * 渲染 [JadePurchaseFlow]——本组件位于滚动内容流内，直接渲染覆盖层会随内容滚动错位
 * 且被后续内容 Z 序覆盖（4.00.92 兑换码事故同源教训）。
 */
@Composable
private fun JadeBreakthroughButton(onClick: () -> Unit) {
    SpriteImage(
        name = "ui_add_button",
        contentDescription = "提高突破率",
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentScale = ContentScale.FillBounds
    )
}

@Composable
private fun BasicInfoRealmRow(
    disciple: DiscipleAggregate,
    data: RealmRowData
) {
    val allManuals = data.allManuals
    val manualProficiencies = data.manualProficiencies
    val elderSlots = data.elderSlots
    val allDisciples = data.allDisciples
    val sectPolicies = data.sectPolicies
    val residenceSlots = data.residenceSlots
    val placedBuildings = data.placedBuildings
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
                BuildingFeatureRegistry.residenceSpeedMultiplier(building?.displayName ?: "")
            }
            val cultivationSpeed = remember(
                disciple, manualsMap, proficiencyMap, allDisciples,
                elderSlots, sectPolicies, buildingBonus
            ) {
                val (preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus) =
                    calculatePreachingBonusesForDisplay(
                    disciple, elderSlots, allDisciples,
                    sectPolicies = sectPolicies
                )
                // calculateCultivationSpeed 直接返回每旬修炼值（乘区基准 REALM_SPEED_PER_PHASE
                // 即"每旬修为"），无需再按每秒值换算，显示值与实际结算值同刻度
                disciple.calculateCultivationSpeed(
                    manualsMap, proficiencyMap,
                    buildingBonus = buildingBonus,
                    preachingElderBonus = preachingElderBonus,
                    preachingMastersBonus = preachingMastersBonus,
                    cultivationSubsidyBonus = cultivationSubsidyBonus
                ).coerceAtLeast(1.0)
            }

            CultivationProgressRow(disciple, cultivationSpeed)
        } else {
            Text(
                text = disciple.realmName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        }
    }
}

@Composable
private fun CultivationProgressRow(
    disciple: DiscipleAggregate,
    cultivationSpeed: Double
) {
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
        // 修为进度条 — 100ms lerp 追赶动画
        val animatedCultivationProgress by rememberChasingProgress(
            target = disciple.cultivationProgress.toFloat().coerceIn(0f, 1f)
        )

        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
        ) {
            drawRect(Color(0xFFE8E8E8))
            drawRect(
                GameColors.Success,
                size = Size(size.width * animatedCultivationProgress, size.height)
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
            text = "${String.format(LocalLocale.current.platformLocale, "%.1f", cultivationSpeed)}/旬",
            fontSize = 10.sp,
            color = GameColors.Success
        )
    }
}

// ── BasicInfoSection 纯计算辅助 ──

private fun discipleEquipmentMap(
    disciple: DiscipleAggregate,
    allEquipment: List<EquipmentInstance>
): Map<String, EquipmentInstance> {
    val map = mutableMapOf<String, EquipmentInstance>()
    // 四部位（头/身/手/脚 = displayOrder）
    listOfNotNull(
        disciple.headId, disciple.bodyId, disciple.handsId, disciple.feetId
    )
        .filter { it.isNotEmpty() }
        .forEach { id -> allEquipment.find { it.id == id }?.let { map[it.id] = it } }
    return map
}

/** BasicInfoRealmRow 参数打包（8 参数 → 1 data class，LongParameterList 修复） */
private data class RealmRowData(
    val allManuals: List<ManualInstance>,
    val manualProficiencies: Map<String, List<ManualProficiencyData>>,
    val elderSlots: ElderSlots?,
    val allDisciples: List<DiscipleAggregate>,
    val sectPolicies: SectPolicies?,
    val residenceSlots: List<ResidenceSlot>,
    val placedBuildings: List<GridBuildingData>
)
