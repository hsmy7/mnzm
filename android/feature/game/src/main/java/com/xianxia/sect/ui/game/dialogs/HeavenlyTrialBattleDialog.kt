package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.core.config.HeavenlyTrialConfig
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.components.watchKeyOf
import com.xianxia.sect.ui.game.HeavenlyTrialViewModel
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.game.components.detail.SLOT_GRID_COLUMNS
import com.xianxia.sect.ui.game.components.detail.SLOT_GRID_SPACING
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun HeavenlyTrialBattleDialog(
    levelIndex: Int,
    viewModel: HeavenlyTrialViewModel,
    gameViewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val config = remember(levelIndex) { HeavenlyTrialConfig.getLevel(levelIndex) }
    // remember 包裹——预览敌人生成（固定种子，不消费全局 RNG）
    // 只执行一次，重组不重复生成（属性稳定 + 零性能浪费）
    val phase1Enemies = remember(levelIndex) {
        viewModel.trialService.getEnemiesForPhase(levelIndex, 0)
    }
    val phase2Enemies = remember(levelIndex) {
        viewModel.trialService.getEnemiesForPhase(levelIndex, 1)
    }
    var selectedPhaseIndex by remember { mutableIntStateOf(0) }
    var selectedEnemyIndex by remember { mutableIntStateOf(0) }
    val currentEnemies = if (selectedPhaseIndex == 0) phase1Enemies else phase2Enemies
    val selectedEnemy = currentEnemies.getOrNull(selectedEnemyIndex)
    val screen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val showDiscipleSelect = screen is HeavenlyTrialViewModel.Screen.DiscipleSelect
    val trialState by viewModel.trialState.collectAsStateWithLifecycle()

    androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize(), color = GameColors.PageBackground) {
        Box(modifier = Modifier.fillMaxSize()) {
            TrialBackgroundImage()
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
                TrialTitleBar(label = config?.label ?: "天道试炼", onDismiss = onDismiss)
                Row(modifier = Modifier.weight(1f)) {
                    // === 关卡列 (1) ===
                    PhaseSelectionColumn(
                        selectedPhaseIndex = selectedPhaseIndex,
                        onPhaseSelect = { idx -> selectedPhaseIndex = idx; selectedEnemyIndex = 0 },
                        phase1Cleared = trialState.isPhase1Cleared(levelIndex),
                        phase2Cleared = trialState.isPhase2Cleared(levelIndex)
                    )
                    // 竖线1
                    TrialDivider()
                    // === 挑战对象列 (2) ===
                    EnemySelectionList(
                        enemies = currentEnemies,
                        selectedEnemyIndex = selectedEnemyIndex,
                        onEnemySelect = { selectedEnemyIndex = it }
                    )
                    // 竖线2
                    TrialDivider()
                    // === 信息+挑战区 (7) ===
                    EnemyInfoChallengeColumn(
                        selectedEnemy = selectedEnemy,
                        gameViewModel = gameViewModel,
                        onChallenge = { viewModel.startDiscipleSelect(selectedPhaseIndex) }
                    )
                }
            }
            TrialDiscipleSelectOverlay(
                show = showDiscipleSelect,
                viewModel = viewModel,
                gameViewModel = gameViewModel
            )
        }
    }
}

/** 出战弟子选择覆盖层：半屏覆盖在挑战界面上 */
@Composable
private fun TrialDiscipleSelectOverlay(
    show: Boolean,
    viewModel: HeavenlyTrialViewModel,
    gameViewModel: GameViewModel
) {
    // 选择出战弟子 — 半屏覆盖在挑战界面上
    if (show) {
        HeavenlyTrialDiscipleDialog(
            viewModel = viewModel,
            gameViewModel = gameViewModel,
            onDismiss = { viewModel.dismissDiscipleSelect() }
        )
    }
}

/** 背景图 */
@Composable
private fun BoxScope.TrialBackgroundImage() {
    Image(
        painter = painterResource(id = SpriteResRegistry.resolve("heavenly_trial_challenge_bg") ?: 0),
        contentDescription = null,
        modifier = Modifier.matchParentSize(),
        contentScale = ContentScale.Crop
    )
}

/** 标题栏 */
@Composable
private fun TrialTitleBar(label: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(Modifier.weight(1f))
        CloseButton(onClick = onDismiss)
    }
}

/** 关卡选择列：阶段一/二图标 + 通关状态 */
@Composable
private fun RowScope.PhaseSelectionColumn(
    selectedPhaseIndex: Int,
    onPhaseSelect: (Int) -> Unit,
    phase1Cleared: Boolean,
    phase2Cleared: Boolean
) {
    Column(
        modifier = Modifier.weight(1f).fillMaxHeight(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TrialPhaseIcon(
            spriteRes = SpriteResRegistry.resolve("heavenly_trial_phase1") ?: 0,
            isSelected = selectedPhaseIndex == 0,
            isCleared = phase1Cleared,
            onSelect = { onPhaseSelect(0) }
        )
        Spacer(Modifier.height(8.dp))
        TrialPhaseIcon(
            spriteRes = SpriteResRegistry.resolve("heavenly_trial_phase2") ?: 0,
            isSelected = selectedPhaseIndex == 1,
            isCleared = phase2Cleared,
            onSelect = { onPhaseSelect(1) }
        )
    }
}

/** 单阶段图标：选中边框 + 通关标记 */
@Composable
private fun TrialPhaseIcon(
    spriteRes: Int,
    isSelected: Boolean,
    isCleared: Boolean,
    onSelect: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(
                    2.dp,
                    if (isSelected) GameColors.Gold else GameColors.Border,
                    RoundedCornerShape(8.dp)
                )
                .clickable(onClick = onSelect),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = spriteRes),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.FillBounds
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (isCleared) "已通关" else "未通关",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (isCleared) GameColors.Success else Color.Red
        )
    }
}

/** 竖线分隔 */
@Composable
private fun TrialDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(GameColors.Divider)
    )
}

/** 挑战对象列：敌方名单 */
@Composable
private fun RowScope.EnemySelectionList(
    enemies: List<Combatant>,
    selectedEnemyIndex: Int,
    onEnemySelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier.weight(2f).fillMaxHeight()
    ) {
        enemies.forEachIndexed { idx, enemy ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEnemySelect(idx) }
                    .background(
                        if (idx == selectedEnemyIndex) Color(0x33FFD700)
                        else Color.Transparent,
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    enemy.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }
    }
}

/** 信息+挑战区：敌方详情 + 挑战按钮 */
@Composable
private fun RowScope.EnemyInfoChallengeColumn(
    selectedEnemy: Combatant?,
    gameViewModel: GameViewModel?,
    onChallenge: () -> Unit
) {
    Column(modifier = Modifier.weight(7f).fillMaxHeight()) {
        // 信息区 — 占9份
        Column(modifier = Modifier.weight(9f)) {
            if (selectedEnemy != null) {
                EnemyInfoDetail(
                    enemy = selectedEnemy,
                    gameViewModel = gameViewModel
                )
            }
        }
        // 横线
        Box(
            Modifier
                .height(1.dp)
                .fillMaxWidth()
                .background(GameColors.Divider)
        )
        // 挑战区 — 占1份
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            GameButton("挑战", onClick = onChallenge)
        }
    }
}

@Composable
private fun EnemyInfoDetail(
    enemy: Combatant,
    gameViewModel: GameViewModel? = null
) {
    var detailTarget by remember { mutableStateOf<Any?>(null) }

    val watchedKeys = gameViewModel?.watchedItemIds?.collectAsStateWithLifecycle()?.value
        ?: emptySet()

    Column(modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
        // 基本信息
        EnemyBasicInfo(enemy = enemy)

        if (enemy.isBeast) {
            EnemyBeastSkills(enemy = enemy)
        } else {
            // 装备槽位
            EnemyEquipmentSection(
                enemy = enemy,
                watchedKeys = watchedKeys,
                onLongPress = { detailTarget = it }
            )

            // 功法槽位
            EnemyManualSection(
                enemy = enemy,
                watchedKeys = watchedKeys,
                onLongPress = { detailTarget = it }
            )
        }
    }

    // 长按详情弹窗 — 使用正式的 ItemDetailDialog
    if (detailTarget != null) {
        ItemDetailDialog(
            item = checkNotNull(detailTarget) { "detailTarget is null" },
            onDismiss = { detailTarget = null },
            viewModel = gameViewModel
        )
    }
}

/** 基本信息：名称 + 境界/血量 + 属性 */
@Composable
private fun EnemyBasicInfo(enemy: Combatant) {
    Text(enemy.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    Spacer(Modifier.height(2.dp))
    Text("${enemy.realmName}${enemy.realmLayer}层  HP:${enemy.hp}/${enemy.maxHp}  MP:${enemy.mp}/${enemy.maxMp}",
        fontSize = 10.sp, color = Color.Black)
    Text("物攻${enemy.physicalAttack} 法攻${enemy.magicAttack} 物防${enemy.physicalDefense} 法防${enemy.magicDefense} " +
        "速度${enemy.speed}", fontSize = 10.sp, color = Color.Black)
}

/** 妖兽技能区 */
@Composable
private fun EnemyBeastSkills(enemy: Combatant) {
    Spacer(Modifier.height(6.dp))
    Text("妖兽技能", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    Spacer(Modifier.height(4.dp))
    enemy.skills.forEach { skill ->
        Column(modifier = Modifier.padding(vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(skill.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                if (skill.isAoe) {
                    Text(" [全体]", fontSize = 9.sp, color = Color(0xFFE65100))
                }
            }
            if (skill.damageMultiplier > 0) {
                val dmgType = if (skill.damageType == com.xianxia.sect.core.DamageType.PHYSICAL) "物理" else "法术"
                Text("${dmgType}伤害 ×${(skill.damageMultiplier * 100).toInt()}%  ${skill.hits}连击  " +
                    "冷却${skill.cooldown}回合  消耗${skill.mpCost}灵力", fontSize = 9.sp, color = Color.Black)
            }
            skill.buffs.forEach { buff ->
                val buffName = buff.first.displayName
                Text("$buffName +${(buff.second * 100).toInt()}% 持续${buff.third}回合", fontSize = 9.sp,
                    color = Color.Black)
            }
            if (skill.buffs.isEmpty() && skill.buffType != null && skill.buffValue > 0) {
                val bt = skill.buffType
                if (bt != null) {
                    Text("${bt.displayName} +${(skill.buffValue * 100).toInt()}% 持续${skill.buffDuration}回合",
                        fontSize = 9.sp, color = Color.Black)
                }
            }
            if (skill.healPercent > 0) {
                val healType = if (skill.healType == com.xianxia.sect.core.HealType.HP) "生命" else "灵力"
                Text("恢复${(skill.healPercent * 100).toInt()}%$healType", fontSize = 9.sp, color = Color.Black)
            }
            if (skill.skillDescription.isNotEmpty()) {
                Text(skill.skillDescription, fontSize = 9.sp, color = Color(0xFF666666))
            }
        }
    }
}

/** 装备槽位区：4 列装备卡片 */
@Composable
private fun EnemyEquipmentSection(
    enemy: Combatant,
    watchedKeys: Set<String>,
    onLongPress: (Any) -> Unit
) {
    // 装备槽位 — 4列，卡片自带名称无需底部文字
    Spacer(Modifier.height(6.dp))
    Text("装备", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SLOT_GRID_SPACING)
    ) {
        listOf(enemy.weaponName, enemy.armorName,
            enemy.bootsName, enemy.accessoryName).forEach { name ->
            val recipe = name?.let { n -> ForgeRecipeDatabase.getAllRecipes().find { it.name == n } }
            val template = name?.let { n -> EquipmentDatabase.getTemplateByName(n) }
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (recipe != null) {
                    UnifiedItemCard(
                        data = ItemCardData(name = recipe.name, rarity = recipe.rarity),
                        showQuantity = false,
                        isFollowed = template?.let { watchKeyOf(it)?.let { k -> k in watchedKeys } }
                            ?: false,
                        onLongPress = if (template != null) {
                            { onLongPress(template) }
                        } else null
                    )
                }
            }
        }
    }
}

/** 功法槽位区：4 列网格，不足 4 个用占位符补齐 */
@Composable
private fun EnemyManualSection(
    enemy: Combatant,
    watchedKeys: Set<String>,
    onLongPress: (Any) -> Unit
) {
    // 功法槽位 — 4列网格，不足4个用占位符补齐
    Spacer(Modifier.height(6.dp))
    Text("功法", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    Spacer(Modifier.height(4.dp))
    val manualSkills = enemy.skills.map { skill ->
        val manualName = skill.manualName.ifEmpty { skill.name }
        val manual = ManualDatabase.allManuals.values.find { it.name == manualName }
        val rarity = manual?.rarity ?: 1
        Triple(manualName, rarity, manual)
    }
    val paddedSkills = if (manualSkills.size % SLOT_GRID_COLUMNS == 0) manualSkills
        else manualSkills + List(SLOT_GRID_COLUMNS - manualSkills.size % SLOT_GRID_COLUMNS) { Triple("", 1,
            null as ManualDatabase.ManualTemplate?) }
    val rows = paddedSkills.chunked(SLOT_GRID_COLUMNS)
    Column(verticalArrangement = Arrangement.spacedBy(SLOT_GRID_SPACING)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SLOT_GRID_SPACING)
            ) {
                row.forEach { (name, rarity, manual) ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        if (name.isNotEmpty()) {
                            UnifiedItemCard(
                                data = ItemCardData(name = name, rarity = rarity, isManual = true),
                                showQuantity = false,
                                isFollowed = manual?.let { watchKeyOf(it)?.let { k -> k in watchedKeys } }
                                    ?: false,
                                onLongPress = if (manual != null) {
                                    { onLongPress(manual) }
                                } else null
                            )
                        }
                    }
                }
            }
        }
    }
}
