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
import com.xianxia.sect.R
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
            if (totalQty > 0) beastMat to totalQty else null
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
            if (uiState.isRefining && uiState.currentProgress != null) {
                RefiningInProgressSection(uiState.currentProgress!!, uiState.remainingMonths)
            } else {
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
                        onClick = {
                            if (bloodMaterials.isNotEmpty()) showMaterialSelection = true
                        }
                    )
                }

                // ===== 放入弟子区域 =====
                Text("放入弟子", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    DiscipleSlotWithActions(
                        disciple = uiState.selectedDisciple,
                        onSlotClick = { },
                        onEmptySlotClick = { showDiscipleSelection = true },
                        onDismiss = { bloodRefiningViewModel.selectDisciple(null) },
                        onSwap = { showDiscipleSelection = true }
                    )
                }

                // 时间显示（弟子槽位下方）
                if (uiState.selectedMaterial != null) {
                    val duration = BeastMaterialDatabase.getTierDuration(uiState.selectedMaterial!!.tier)
                    Text(
                        text = "${duration}月",
                        color = Color.Black,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
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
                        text = "洗炼",
                        onClick = { bloodRefiningViewModel.startRefine(buildingInstanceId = buildingInstanceId) },
                        enabled = uiState.canStartRefine,
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

// ==================== 进行中状态 ====================

@Composable
private fun RefiningInProgressSection(progress: BloodRefinementProgress, remainingMonths: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("血炼进行中", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        Text("弟子：${progress.discipleName}", fontSize = 14.sp, color = Color.Black)
        Text("材料：${progress.materialName}", fontSize = 14.sp, color = Color.Black)
        Text("剩余时间：${remainingMonths}月", fontSize = 14.sp, color = Color.Black)

        val elapsed = (progress.durationMonths - remainingMonths).coerceAtLeast(0)
        val fraction = if (progress.durationMonths > 0) elapsed.toFloat() / progress.durationMonths else 0f
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = Color(0xFFB71C1C),
            trackColor = Color(0x33B71C1C),
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
            .background(if (selectedMaterial != null) Color.White else GameColors.PageBackground)
            .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selectedMaterial != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(4.dp)
            ) {
                val spriteRes = materialSpriteRes(selectedMaterial.name)
                if (spriteRes != null) {
                    Image(
                        painter = painterResource(id = spriteRes),
                        contentDescription = selectedMaterial.name,
                        modifier = Modifier.size(32.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    selectedMaterial.name,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
                Text(
                    "$selectedQuantity/$requiredQuantity",
                    fontSize = 9.sp,
                    color = Color.Black,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Text("材料", color = Color(0xFF999999), fontSize = 10.sp)
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
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (bloodMaterials.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("仓库中没有血类材料", fontSize = 14.sp, color = Color.Black)
                }
            } else {
                val bloodOrder = listOf("tiger", "snake", "turtle")
                val grouped = bloodMaterials.groupBy { BeastMaterialDatabase.getBloodTypeFromMaterialId(it.first.id) ?: "" }

                bloodOrder.forEach { bloodType ->
                    val items = grouped[bloodType] ?: return@forEach
                    items.sortedByDescending { it.first.tier }.forEach { (beastMat, qty) ->
                        val canAfford = qty >= BloodRefiningViewModel.REQUIRED_MATERIAL_COUNT
                        // 材料选择卡片 — 匹配槽位样式
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
                            craftable = canAfford,
                            onClick = { if (canAfford) onSelect(beastMat, qty) }
                        )
                    }
                }
            }
        }
    }
}
