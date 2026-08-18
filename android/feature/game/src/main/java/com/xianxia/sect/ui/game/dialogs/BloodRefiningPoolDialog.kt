package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.getRarityColor
import com.xianxia.sect.ui.components.materialSpriteRes
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.BloodRefiningUiState
import com.xianxia.sect.ui.game.BloodRefiningViewModel
import com.xianxia.sect.core.util.watchKey
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog
import com.xianxia.sect.ui.components.rememberChasingProgress
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import kotlinx.coroutines.launch
import com.xianxia.sect.ui.components.clickableWithSound



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

    val bloodMaterials = rememberBloodMaterials(materials = materials)

    var showMaterialSelection by remember { mutableStateOf(false) }
    var showDiscipleSelection by remember { mutableStateOf(false) }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "血炼池",
        mode = DialogMode.Half,
        scrollableContent = true
    ) {
        BloodRefiningContent(
            uiState = uiState,
            bloodRefiningViewModel = bloodRefiningViewModel,
            buildingInstanceId = buildingInstanceId,
            onSelectMaterial = { showMaterialSelection = true },
            onSelectDisciple = { showDiscipleSelection = true }
        )
    }

    if (showMaterialSelection) {
        MaterialSelectorDialog(
            bloodMaterials = bloodMaterials,
            viewModel = viewModel,
            onDismiss = { showMaterialSelection = false },
            onSelect = { mat, qty ->
                bloodRefiningViewModel.selectMaterial(mat, qty)
                showMaterialSelection = false
            }
        )
    }

    if (showDiscipleSelection) {
        BloodRefiningDiscipleSelectionDialog(
            gameData = gameData,
            disciples = disciples,
            viewModel = viewModel,
            bloodRefiningViewModel = bloodRefiningViewModel,
            onSelected = { bloodRefiningViewModel.selectDisciple(it) },
            onDismiss = { showDiscipleSelection = false }
        )
    }
}

/** 血炼材料库存收集（BloodRefiningPoolDialog 拆分）：仓库全部妖血材料（含不足门槛，全量显示） */
@Composable
private fun rememberBloodMaterials(materials: List<Material>): List<Pair<BeastMaterialDatabase.BeastMaterial, Int>> {
    return remember(materials) {
        val bloodBeastMaterials = BeastMaterialDatabase.getBloodMaterials()
        bloodBeastMaterials.map { beastMat ->
            val totalQty = materials
                .filter { it.name == beastMat.name && it.rarity == beastMat.rarity }
                .sumOf { it.quantity }
            beastMat to totalQty
        }
    }
}

/** 血炼池主内容区（BloodRefiningPoolDialog 拆分）：放入材料 + 放入弟子 + 洗炼操作 */
@Composable
private fun BloodRefiningContent(
    uiState: BloodRefiningUiState,
    bloodRefiningViewModel: BloodRefiningViewModel,
    buildingInstanceId: String,
    onSelectMaterial: () -> Unit,
    onSelectDisciple: () -> Unit
) {
    val isRefining = uiState.isRefining && uiState.currentProgress != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ===== 放入材料区域 =====
        BloodRefiningMaterialSection(
            selectedMaterial = uiState.selectedMaterial,
            selectedQuantity = uiState.selectedMaterialQuantity,
            requiredQuantity = BloodRefiningViewModel.REQUIRED_MATERIAL_COUNT,
            isRefining = isRefining,
            onSlotClick = onSelectMaterial
        )

        // ===== 放入弟子区域 =====
        BloodRefiningDiscipleSection(
            uiState = uiState,
            bloodRefiningViewModel = bloodRefiningViewModel,
            buildingInstanceId = buildingInstanceId,
            isRefining = isRefining,
            onSelectDisciple = onSelectDisciple
        )

        BloodRefiningActionSection(
            isRefining = isRefining,
            canStartRefine = uiState.canStartRefine,
            errorMessage = uiState.errorMessage,
            bloodRefiningViewModel = bloodRefiningViewModel,
            buildingInstanceId = buildingInstanceId
        )
    }
}

/** 放入材料区（BloodRefiningPoolDialog 拆分） */
@Composable
private fun BloodRefiningMaterialSection(
    selectedMaterial: BeastMaterialDatabase.BeastMaterial?,
    selectedQuantity: Int,
    requiredQuantity: Int,
    isRefining: Boolean,
    onSlotClick: () -> Unit
) {
    Text("放入材料", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        MaterialSlotBox(
            selectedMaterial = selectedMaterial,
            selectedQuantity = selectedQuantity,
            requiredQuantity = requiredQuantity,
            onClick = { if (!isRefining) onSlotClick() }
        )
    }
}

/** 放入弟子区（BloodRefiningPoolDialog 拆分）：进度条 + 弟子槽位 */
@Composable
private fun BloodRefiningDiscipleSection(
    uiState: BloodRefiningUiState,
    bloodRefiningViewModel: BloodRefiningViewModel,
    buildingInstanceId: String,
    isRefining: Boolean,
    onSelectDisciple: () -> Unit
) {
    Text("放入弟子", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)

    // 血炼中：进度条 + 剩余月份（弟子槽位上方，宽度=52dp）
    if (isRefining) {
        BloodRefiningProgressSection(
            currentProgress = uiState.currentProgress,
            remainingMonths = uiState.remainingMonths
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        DiscipleSlot(
            disciple = uiState.selectedDisciple,
            showActions = uiState.selectedDisciple != null,
            onSlotClick = { },
            onEmptySlotClick = { if (!isRefining) onSelectDisciple() },
            onDismiss = {
                if (isRefining) bloodRefiningViewModel.cancelRefine(buildingInstanceId)
                else bloodRefiningViewModel.selectDisciple(null)
            },
            onSwap = {
                if (isRefining) bloodRefiningViewModel.cancelRefine(buildingInstanceId)
                onSelectDisciple()
            }
        )
    }
}

/** 血炼进度条（BloodRefiningPoolDialog 拆分）：剩余月份 + 进度条 */
@Composable
private fun BloodRefiningProgressSection(
    currentProgress: com.xianxia.sect.core.model.BloodRefinementProgress?,
    remainingMonths: Int
) {
    val progress = currentProgress ?: return
    val remaining = remainingMonths
    val total = progress.durationMonths
    val fraction = if (total > 0) (total - remaining).toFloat() / total else 0f
    val animFractionState = rememberChasingProgress(target = fraction)

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
                progress = { animFractionState.value },
                modifier = Modifier.width(52.dp).height(4.dp),
                color = GameColors.Success,
                trackColor = Color(0x334CAF50),
                drawStopIndicator = {}
            )
        }
    }
}

/** 洗炼操作区（BloodRefiningPoolDialog 拆分）：消耗提示 + 按钮 + 错误提示 */
@Composable
private fun BloodRefiningActionSection(
    isRefining: Boolean,
    canStartRefine: Boolean,
    errorMessage: String?,
    bloodRefiningViewModel: BloodRefiningViewModel,
    buildingInstanceId: String
) {
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
            enabled = !isRefining && canStartRefine,
            modifier = Modifier
                .width(ButtonSizes.StandardWidth)
                .height(ButtonSizes.StandardHeight)
        )
    }

    // 错误提示
    errorMessage?.let { error ->
        Text(text = error, color = Color.Black, fontSize = 12.sp, textAlign = TextAlign.Center)
        LaunchedEffect(error) { bloodRefiningViewModel.clearError() }
    }
}

/** 血炼弟子选择弹窗（BloodRefiningPoolDialog 拆分） */
// 拆分搬移:参数保留原签名语义
@Suppress("UnusedParameter")
@Composable
private fun BloodRefiningDiscipleSelectionDialog(
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    bloodRefiningViewModel: BloodRefiningViewModel,
    onSelected: (DiscipleAggregate) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val showAllEnabled = gameData?.showAllAvailableDisciples ?: false
    val battleAndExplorationIds = remember(gameData) {
        val allBattleIds = gameData?.battleTeams?.flatMap { it.slots.mapNotNull { s -> s.discipleId.takeIf(String::isNotEmpty) } } ?: emptyList()
        val allCaveExplorationIds = gameData?.caveExplorationTeams?.flatMap { it.memberIds } ?: emptyList()
        (allBattleIds + allCaveExplorationIds).toSet()
    }
    val eligibleDisciples = disciples.filter { it.isAlive }
    DiscipleSelectorDialog(
        config = DiscipleSelectorConfig(title = "选择弟子", emptyMessage = "没有空闲弟子"),
        disciples = eligibleDisciples,
        showAllEnabled = showAllEnabled,
        battleAndExplorationIds = battleAndExplorationIds,
        onDismiss = onDismiss,
        onConfirm = { selected ->
            selected.firstOrNull()?.let {
                scope.launch {
                    if (showAllEnabled && it.status != com.xianxia.sect.core.model.DiscipleStatus.IDLE) {
                        viewModel.releaseDiscipleForReassignment(it.id)
                    }
                    onSelected(it)
                }
            }
            onDismiss()
        },
        viewModel = viewModel
    )
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
            .clickableWithSound(onClick = onClick)
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
    viewModel: GameViewModel? = null,
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
        var selectedMaterialId by remember { mutableStateOf<String?>(null) }
        var showInsufficientPrompt by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (bloodMaterials.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("无符合条件的材料", fontSize = 14.sp, color = Color.Black)
                }
            } else {
                // 列表区内部滚动：按钮固定在底部始终可见，无需滚动找按钮
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    BloodMaterialList(
                        bloodMaterials = bloodMaterials,
                        viewModel = viewModel,
                        selectedMaterialId = selectedMaterialId,
                        onMaterialClick = { mat ->
                            selectedMaterialId = if (selectedMaterialId == mat.id) null else mat.id
                        },
                        onShowDetail = { mat ->
                            detailMaterial = mat
                            showDetail = true
                        }
                    )
                }
            }

            // 底部"使用"按钮：选中材料数量不足门槛时弹提示，充足则放入材料槽并关闭弹窗
            val selectedEntry = bloodMaterials.firstOrNull { it.first.id == selectedMaterialId }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                GameButton(
                    text = "使用",
                    onClick = {
                        val entry = selectedEntry
                        if (entry != null && entry.second >= BloodRefiningViewModel.REQUIRED_MATERIAL_COUNT) {
                            onSelect(entry.first, entry.second)
                            onDismiss()
                        } else if (entry != null) {
                            showInsufficientPrompt = true
                        }
                    },
                    enabled = selectedEntry != null,
                    modifier = Modifier
                        .width(ButtonSizes.StandardWidth)
                        .height(ButtonSizes.StandardHeight)
                )
            }
        }

        if (showInsufficientPrompt) {
            StandardPromptDialog(
                onDismissRequest = { showInsufficientPrompt = false },
                title = "材料不足",
                text = "材料需要${BloodRefiningViewModel.REQUIRED_MATERIAL_COUNT}个，" +
                        "不足${BloodRefiningViewModel.REQUIRED_MATERIAL_COUNT}不可使用"
            )
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
                },
                viewModel = viewModel
            )
        }
    }
}

/** 血炼材料分组列表（MaterialSelectorDialog 拆分）：按妖兽类型分组排序渲染物品卡 */
@Composable
private fun BloodMaterialList(
    bloodMaterials: List<Pair<BeastMaterialDatabase.BeastMaterial, Int>>,
    viewModel: GameViewModel?,
    selectedMaterialId: String?,
    onMaterialClick: (BeastMaterialDatabase.BeastMaterial) -> Unit,
    onShowDetail: (BeastMaterialDatabase.BeastMaterial) -> Unit
) {
    val bloodOrder = listOf("tiger", "snake", "turtle")
    val grouped = bloodMaterials.groupBy { BeastMaterialDatabase.getBloodTypeFromMaterialId(it.first.id) ?: "" }

    val watchedKeys = viewModel?.watchedItemIds?.collectAsStateWithLifecycle()?.value
        ?: emptySet()
    bloodOrder.forEach { bloodType ->
        val items = grouped[bloodType] ?: return@forEach
        items.sortedWith(
            compareByDescending<Pair<BeastMaterialDatabase.BeastMaterial, Int>> {
                watchKey("material", it.first.name) in watchedKeys
            }.thenByDescending { it.first.tier }
        ).forEach { (beastMat, qty) ->
            UnifiedItemCard(
                data = ItemCardData(
                    id = beastMat.id,
                    name = beastMat.name,
                    rarity = beastMat.rarity,
                    quantity = qty,
                    description = beastMat.description,
                    isMaterial = true
                ),
                isSelected = beastMat.id == selectedMaterialId,
                isFollowed = watchKey("material", beastMat.name) in watchedKeys,
                showQuantity = true,
                onClick = { onMaterialClick(beastMat) },
                onLongPress = {
                    onShowDetail(beastMat)
                }
            )
        }
    }
}
