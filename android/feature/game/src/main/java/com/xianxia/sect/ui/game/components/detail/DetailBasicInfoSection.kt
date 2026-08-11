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
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.accessoryId
import com.xianxia.sect.core.model.armorId
import com.xianxia.sect.core.model.bootsId
import com.xianxia.sect.core.model.comprehension
import com.xianxia.sect.core.model.griefEndYear
import com.xianxia.sect.core.model.weaponId
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.rememberChasingProgress
import com.xianxia.sect.ui.theme.GameColors

/**
 * 弟子详情"基本信息"区块。
 *
 * 2026-08-11 从 DetailCultivationSection.kt 拆出（LongMethod 280 行 + 复杂度 39 +
 * TooManyFunctions 15 项修复），本文件内聚 BasicInfoSection 全部子组件与纯计算辅助。
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
    gameYear: Int = 1,
    gameSpeed: Int = 1,
    bloodRefinementPct: BloodRefinementPctTotal? = null,
    onWashSpiritRootClick: (() -> Unit)? = null,
    onBreakthroughJadeClick: (() -> Unit)? = null
) {
    val discipleMap = allDisciples.associateBy { it.id }
    val griefBreakthroughPenalty = discipleGriefPenalty(disciple, gameYear)
    val masterDiscipleBonus = discipleMasterBonus(disciple, discipleMap)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "基本信息",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        BasicInfoIdentityRow(disciple, onWashSpiritRootClick)

        BasicInfoBreakthroughRow(
            disciple = disciple,
            discipleMap = discipleMap,
            elderSlots = elderSlots,
            masterDiscipleBonus = masterDiscipleBonus,
            griefBreakthroughPenalty = griefBreakthroughPenalty,
            onBreakthroughJadeClick = onBreakthroughJadeClick
        )

        BasicInfoRealmRow(
            disciple = disciple,
            data = RealmRowData(
                allManuals, manualProficiencies, elderSlots, allDisciples,
                sectPolicies, residenceSlots, placedBuildings, gameSpeed
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        val equipmentMap = remember(
            disciple.weaponId, disciple.armorId, disciple.bootsId,
            disciple.accessoryId, allEquipment
        ) {
            discipleEquipmentMap(disciple, allEquipment)
        }
        val manualMap = remember(allManuals) { allManuals.associateBy { it.id } }
        val discipleProficiencies = remember(disciple.id, manualProficiencies) {
            manualProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap()
        }
        val finalStats = remember(disciple, equipmentMap, manualMap, discipleProficiencies, bloodRefinementPct) {
            disciple.getFinalStats(equipmentMap, manualMap, discipleProficiencies, bloodRefinementPct)
        }

        HpMpBars(disciple, finalStats.maxHp, finalStats.maxMp, gameSpeed = gameSpeed)
    }
}

// ── BasicInfoSection 子组件（2026-08-11 拆分，LongMethod 280 行 → 5 个小组件）──

@Composable
private fun BasicInfoIdentityRow(
    disciple: DiscipleAggregate,
    onWashSpiritRootClick: (() -> Unit)?
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
            text = disciple.status.displayName,
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
        // 灵根与洗炼入口（+ 号）嵌套 Row：spacedBy(4.dp) 保证按钮与灵根间距
        // 恰好 4dp（外层 Row 的 spacedBy(16.dp) 会对每个相邻子项加间距，
        // 若平铺会导致 16+4+16=36dp 的实际间距）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = disciple.spiritRootName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = spiritRootCountColor,
                maxLines = 1
            )
            // 洗炼灵根入口（+ 号按钮，间距 4dp，大小与突破率右侧按钮一致）
            if (onWashSpiritRootClick != null) {
                SpriteImage(
                    name = "ui_add_button",
                    contentDescription = "洗炼灵根",
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onWashSpiritRootClick),
                    contentScale = ContentScale.FillBounds
                )
            }
        }
    }
}

@Composable
private fun BasicInfoBreakthroughRow(
    disciple: DiscipleAggregate,
    discipleMap: Map<String, DiscipleAggregate>,
    elderSlots: ElderSlots?,
    masterDiscipleBonus: Double,
    griefBreakthroughPenalty: Double,
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
        adBonus = disciple.statusData["adBreakthroughBonus"]?.toDoubleOrNull() ?: 0.0,
        masterDiscipleBonus = masterDiscipleBonus,
        griefBreakthroughPenalty = griefBreakthroughPenalty
    )
    val adBonusValue = disciple.statusData["adBreakthroughBonus"]?.toDoubleOrNull() ?: 0.0
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        InfoItem("寿命 ${disciple.age}/${disciple.lifespan}", Modifier.weight(1f))
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
        elder.comprehension
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
    val gameSpeed = data.gameSpeed
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
                // 每秒值 × 每旬秒数 → 每旬值
                val perSecond = disciple.calculateCultivationSpeed(
                    manualsMap, proficiencyMap,
                    buildingBonus = buildingBonus,
                    preachingElderBonus = preachingElderBonus,
                    preachingMastersBonus = preachingMastersBonus,
                    cultivationSubsidyBonus = cultivationSubsidyBonus
                ).coerceIn(1.0, 1000.0)
                perSecond * com.xianxia.sect.core.engine.system.GameTimeClock.MS_PER_PHASE_1X / 1000.0
            }

            CultivationProgressRow(disciple, cultivationSpeed, gameSpeed)
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
    cultivationSpeed: Double,
    gameSpeed: Int
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
            target = disciple.cultivationProgress.toFloat().coerceIn(0f, 1f),
            paused = gameSpeed == 0
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

// ── BasicInfoSection 纯计算辅助（2026-08-11 LongMethod 拆分时提取）──

private fun discipleGriefPenalty(disciple: DiscipleAggregate, gameYear: Int): Double =
    if ((disciple.griefEndYear ?: 0) > gameYear) {
        DiscipleStatCalculator.GRIEF_BREAKTHROUGH_CHANCE_PENALTY
    } else {
        0.0
    }

private fun discipleMasterBonus(
    disciple: DiscipleAggregate,
    discipleMap: Map<String, DiscipleAggregate>
): Double = disciple.masterId?.let { mid ->
    val master = discipleMap[mid]
    if (master != null && master.isAlive) {
        DiscipleStatCalculator.getMasterDiscipleBreakthroughBonus(disciple.realm, master.realm)
    } else {
        0.0
    }
} ?: 0.0

private fun discipleEquipmentMap(
    disciple: DiscipleAggregate,
    allEquipment: List<EquipmentInstance>
): Map<String, EquipmentInstance> {
    val map = mutableMapOf<String, EquipmentInstance>()
    listOfNotNull(disciple.weaponId, disciple.armorId, disciple.bootsId, disciple.accessoryId)
        .filter { it.isNotEmpty() }
        .forEach { id -> allEquipment.find { it.id == id }?.let { map[it.id] = it } }
    return map
}

/** BasicInfoRealmRow 参数打包（9 参数 → 1 data class，LongParameterList 修复） */
private data class RealmRowData(
    val allManuals: List<ManualInstance>,
    val manualProficiencies: Map<String, List<ManualProficiencyData>>,
    val elderSlots: ElderSlots?,
    val allDisciples: List<DiscipleAggregate>,
    val sectPolicies: SectPolicies?,
    val residenceSlots: List<ResidenceSlot>,
    val placedBuildings: List<GridBuildingData>,
    val gameSpeed: Int
)
