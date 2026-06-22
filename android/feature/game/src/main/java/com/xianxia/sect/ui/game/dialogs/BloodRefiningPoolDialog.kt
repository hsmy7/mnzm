package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.ui.components.*
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.BloodRefiningViewModel
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog
import com.xianxia.sect.ui.game.components.ItemDetailDialog

@Composable
fun BloodRefiningPoolDialog(
    buildingInstanceId: String,
    viewModel: GameViewModel,
    bloodRefiningViewModel: BloodRefiningViewModel,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    materials: List<Material>,
    onDismiss: () -> Unit
) {
    val uiState by bloodRefiningViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(buildingInstanceId) {
        bloodRefiningViewModel.loadActiveProgress(buildingInstanceId)
    }

    val bloodMaterials = remember(materials) {
        val bloodBeastMaterials = BeastMaterialDatabase.getBloodMaterials()
        bloodBeastMaterials.mapNotNull { beastMat ->
            val totalQty = materials
                .filter { it.name == beastMat.name && it.rarity == beastMat.rarity }
                .sumOf { it.quantity }
            if (totalQty >= BloodRefiningViewModel.REQUIRED_MATERIAL_COUNT) beastMat to totalQty else null
        }
    }

    var showMaterialSelection by remember { mutableStateOf(false) }
    var showDiscipleSelection by remember { mutableStateOf(false) }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "血炼池",
        mode = DialogMode.Half
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val isRefining = uiState.isRefining && uiState.currentProgress != null

            // ===== 放入材料区域 =====
            Text("放入材料", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                MaterialSlotBox(
                    selectedMaterial = uiState.selectedMaterial,
                    selectedQuantity = uiState.selectedMaterialQuantity,
                    requiredQuantity = BloodRefiningViewModel.REQUIRED_MATERIAL_COUNT,
                    onClick = { if (!isRefining) showMaterialSelection = true }
                )
            }

            // ===== 放入弟子区域 =====
            Text("放入弟子", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)

            // 血炼中：进度条 + 剩余月份（弟子槽位上方，宽度=52dp）
            if (isRefining) {
                val progress = uiState.currentProgress ?: return@Column
                val remaining = uiState.remainingMonths
                val total = progress.durationMonths
                val fraction = if (total > 0) (total - remaining).toFloat() / total else 0f

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${remaining}月",
                            color = Color.Black,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.width(52.dp).height(4.dp),
                            color = Color(0xFF4CAF50),
                            trackColor = Color(0x334CAF50),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                DiscipleSlot(
                    disciple = uiState.selectedDisciple,
                    showActions = uiState.selectedDisciple != null,
                    onSlotClick = { },
                    onEmptySlotClick = { if (!isRefining) showDiscipleSelection = true },
                    onDismiss = {
                        if (isRefining) bloodRefiningViewModel.cancelRefine(buildingInstanceId)
                        else bloodRefiningViewModel.selectDisciple(null)
                    },
                    onSwap = {
                        if (isRefining) bloodRefiningViewModel.cancelRefine(buildingInstanceId)
                        showDiscipleSelection = true
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 红色小字（按钮上方）
            Text(
                text = "消耗 100 万灵石",
                color = Color(0xFFCC0000),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // 洗炼按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                GameButton(
                    text = if (isRefining) "血炼中..." else "洗炼",
                    onClick = { bloodRefiningViewModel.startRefine(buildingInstanceId = buildingInstanceId) },
                    enabled = !isRefining && uiState.canStartRefine,
                    modifier = Modifier
                        .width(ButtonSizes.StandardWidth)
                        .height(ButtonSizes.StandardHeight)
                )
            }

            // 错误提示
            uiState.errorMessage?.let { error ->
                Text(text = error, color = Color.Black, fontSize = 12.sp, textAlign = TextAlign.Center)
                LaunchedEffect(error) { bloodRefiningViewModel.clearError() }
            }
        }
    }

    // 材料选择弹窗
    if (showMaterialSelection) {
        MaterialSelectorDialog(
            bloodMaterials = bloodMaterials,
            onDismiss = { showMaterialSelection = false },
            onSelect = { mat, qty ->
                bloodRefiningViewModel.selectMaterial(mat, qty)
                showMaterialSelection = false
            }
        )
    }

    // 弟子选择弹窗
    if (showDiscipleSelection) {
        val eligibleDisciples = disciples.filter {
            it.isAlive && it.status == com.xianxia.sect.core.model.DiscipleStatus.IDLE
        }
        DiscipleSelectorDialog(
            config = DiscipleSelectorConfig(title = "选择弟子", emptyMessage = "没有空闲弟子"),
            disciples = eligibleDisciples,
            onDismiss = { showDiscipleSelection = false },
            onConfirm = { selected ->
                selected.firstOrNull()?.let { bloodRefiningViewModel.selectDisciple(it) }
                showDiscipleSelection = false
            }
        )
    }
}

// ==================== 材料槽位（复用 UnifiedDiscipleSlot 同款容器） ====================

@Composable
private fun MaterialSlotBox(
    selectedMaterial: BeastMaterialDatabase.BeastMaterial?,
    selectedQuantity: Int,
    requiredQuantity: Int,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(52.dp)
            .height(88.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
    ) {
        if (selectedMaterial != null) {
            val rarityColor = getRarityColor(selectedMaterial.rarity)
            Column(modifier = Modifier.fillMaxSize()) {
                // 精灵图区域 — 品阶色背景
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(rarityColor),
                    contentAlignment = Alignment.Center
                ) {
                    val spriteRes = materialSpriteRes(selectedMaterial.name)
                    if (spriteRes != null) {
                        Image(
                            painter = painterResource(id = spriteRes),
                            contentDescription = selectedMaterial.name,
                            modifier = Modifier.fillMaxSize().padding(3.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                // 分隔线
                HorizontalDivider(thickness = 1.dp, color = GameColors.Border)
                // 名称区域 — 白色背景
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        selectedMaterial.name,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(GameColors.PageBackground),
                contentAlignment = Alignment.Center
            ) {
                Text("材料", color = Color(0xFF999999), fontSize = 10.sp)
            }
        }
    }
}

// ==================== 材料选择弹窗 ====================

@Composable
private fun MaterialSelectorDialog(
    bloodMaterials: List<Pair<BeastMaterialDatabase.BeastMaterial, Int>>,
    onDismiss: () -> Unit,
    onSelect: (BeastMaterialDatabase.BeastMaterial, Int) -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "选择妖兽精血",
        mode = DialogMode.Half
    ) {
        var showDetail by remember { mutableStateOf(false) }
        var detailMaterial by remember { mutableStateOf<BeastMaterialDatabase.BeastMaterial?>(null) }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (bloodMaterials.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("无符合条件的材料", fontSize = 14.sp, color = Color.Black)
                }
            } else {
                val bloodOrder = listOf("tiger", "snake", "turtle")
                val grouped = bloodMaterials.groupBy { BeastMaterialDatabase.getBloodTypeFromMaterialId(it.first.id) ?: "" }

                bloodOrder.forEach { bloodType ->
                    val items = grouped[bloodType] ?: return@forEach
                    items.sortedByDescending { it.first.tier }.forEach { (beastMat, qty) ->
                        UnifiedItemCard(
                            data = ItemCardData(
                                id = beastMat.id,
                                name = beastMat.name,
                                rarity = beastMat.rarity,
                                quantity = qty,
                                description = beastMat.description,
                                isMaterial = true
                            ),
                            isSelected = false,
                            showQuantity = true,
                            onClick = { onSelect(beastMat, qty) },
                            onLongPress = {
                                detailMaterial = beastMat
                                showDetail = true
                            }
                        )
                    }
                }
            }
        }

        if (showDetail && detailMaterial != null) {
            val mat = checkNotNull(detailMaterial)
            ItemDetailDialog(
                item = Material(
                    id = mat.id,
                    name = mat.name,
                    description = mat.description,
                    rarity = mat.rarity
                ),
                onDismiss = {
                    showDetail = false
                    detailMaterial = null
                }
            )
        }
    }
}
