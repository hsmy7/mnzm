package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.config.HeavenlyTrialConfig
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.HeavenlyTrialViewModel
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun HeavenlyTrialBattleDialog(
    levelIndex: Int,
    viewModel: HeavenlyTrialViewModel,
    gameViewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val config = remember(levelIndex) { HeavenlyTrialConfig.getLevel(levelIndex) }

    val phase1Enemies = remember(levelIndex) { viewModel.trialService.getEnemiesForPhase(levelIndex, 0) }
    val phase2Enemies = remember(levelIndex) { viewModel.trialService.getEnemiesForPhase(levelIndex, 1) }

    var selectedPhaseIndex by remember { mutableStateOf(0) }
    var selectedEnemyIndex by remember { mutableStateOf(0) }
    val currentEnemies = if (selectedPhaseIndex == 0) phase1Enemies else phase2Enemies
    val selectedEnemy = currentEnemies.getOrNull(selectedEnemyIndex)

    val screen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val showDiscipleSelect = screen is HeavenlyTrialViewModel.Screen.DiscipleSelect

    androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize(), color = GameColors.PageBackground) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.heavenly_trial_challenge_bg),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )

            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        config?.label ?: "天道试炼",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Spacer(Modifier.weight(1f))
                    CloseButton(onClick = onDismiss)
                }

                Row(modifier = Modifier.weight(1f)) {
                    // === 关卡列 (1) ===
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    2.dp,
                                    if (selectedPhaseIndex == 0) GameColors.Gold else GameColors.Border,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedPhaseIndex = 0; selectedEnemyIndex = 0 },
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.heavenly_trial_phase1),
                                contentDescription = null,
                                modifier = Modifier.matchParentSize(),
                                contentScale = ContentScale.FillBounds
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    2.dp,
                                    if (selectedPhaseIndex == 1) GameColors.Gold else GameColors.Border,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedPhaseIndex = 1; selectedEnemyIndex = 0 },
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.heavenly_trial_phase2),
                                contentDescription = null,
                                modifier = Modifier.matchParentSize(),
                                contentScale = ContentScale.FillBounds
                            )
                        }
                    }

                    // 竖线1
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(GameColors.Divider)
                    )

                    // === 挑战对象列 (2) ===
                    Column(
                        modifier = Modifier.weight(2f).fillMaxHeight()
                    ) {
                        currentEnemies.forEachIndexed { idx, enemy ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedEnemyIndex = idx }
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

                    // 竖线2
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(GameColors.Divider)
                    )

                    // === 信息+挑战区 (7) ===
                    Column(modifier = Modifier.weight(7f).fillMaxHeight()) {
                        // 信息区
                        Column(modifier = Modifier.weight(1f)) {
                            if (selectedEnemy != null) {
                                EnemyInfoDetail(selectedEnemy)
                            }
                        }
                        // 横线
                        Box(
                            Modifier
                                .height(1.dp)
                                .fillMaxWidth()
                                .background(GameColors.Divider)
                        )
                        // 挑战区
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            GameButton("挑战", onClick = {
                                viewModel.startDiscipleSelect(selectedPhaseIndex)
                            })
                        }
                    }
                }
            }

            // 选择出战弟子 — 半屏覆盖在挑战界面上
            if (showDiscipleSelect) {
                HeavenlyTrialDiscipleDialog(
                    viewModel = viewModel,
                    gameViewModel = gameViewModel,
                    onDismiss = { viewModel.dismissDiscipleSelect() }
                )
            }
        }
    }
}

@Composable
private fun EnemyInfoDetail(enemy: Combatant) {
    var detailItem by remember { mutableStateOf<DetailItem?>(null) }

    Column(modifier = Modifier.padding(8.dp)) {
        // 基本信息
        Text(enemy.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        Spacer(Modifier.height(2.dp))
        Text("${enemy.realmName}${enemy.realmLayer}层  HP:${enemy.hp}/${enemy.maxHp}  MP:${enemy.mp}/${enemy.maxMp}", fontSize = 10.sp, color = Color.Black)
        Text("物攻${enemy.physicalAttack} 法攻${enemy.magicAttack} 物防${enemy.physicalDefense} 法防${enemy.magicDefense} 速度${enemy.speed}", fontSize = 10.sp, color = Color.Black)

        if (enemy.isBeast) {
            Spacer(Modifier.height(6.dp))
            Text("妖兽技能", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Spacer(Modifier.height(4.dp))
            enemy.skills.forEach { skill ->
                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(skill.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    Text(skill.skillDescription, fontSize = 10.sp, color = Color.Black)
                }
            }
        } else {
            // 装备槽位
            Spacer(Modifier.height(6.dp))
            Text("装备", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("武器" to enemy.weaponName, "护甲" to enemy.armorName,
                    "靴子" to enemy.bootsName, "饰品" to enemy.accessoryName).forEach { (label, name) ->
                    val recipe = name?.let { n -> ForgeRecipeDatabase.getAllRecipes().find { it.name == n } }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (recipe != null) {
                            UnifiedItemCard(
                                data = ItemCardData(name = recipe.name, rarity = recipe.rarity),
                                showQuantity = false
                            )
                        } else {
                            Box(
                                modifier = Modifier.size(60.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("-", fontSize = 9.sp, color = Color.Gray)
                            }
                        }
                        Text(label, fontSize = 9.sp, color = Color.Black)
                    }
                }
            }

            // 功法槽位
            Spacer(Modifier.height(6.dp))
            Text("功法", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                enemy.skills.forEach { skill ->
                    val manualName = skill.manualName.ifEmpty { skill.name }
                    val manual = ManualDatabase.allManuals.values.find { it.name == manualName }
                    val rarity = manual?.rarity ?: 1
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        UnifiedItemCard(
                            data = ItemCardData(name = manualName, rarity = rarity, isManual = true),
                            showQuantity = false,
                            onLongPress = {
                                detailItem = DetailItem(manualName,
                                    "类型: ${skill.skillType.name}\n" +
                                    "伤害: ${skill.damageType.name} ×${skill.damageMultiplier}\n" +
                                    "消耗: ${skill.mpCost}灵力  冷却: ${skill.cooldown}回合\n" +
                                    (if (skill.isAoe) "范围: 全体\n" else "") +
                                    skill.skillDescription)
                            }
                        )
                        Text(manualName.take(4), fontSize = 8.sp, color = Color.Black, maxLines = 1)
                    }
                }
            }
        }
    }

    // 长按详情弹窗
    if (detailItem != null) {
        val item = detailItem!!
        UnifiedGameDialog(
            onDismissRequest = { detailItem = null },
            title = item.title,
            mode = DialogMode.Half
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(item.detail, fontSize = 13.sp, color = Color.Black)
            }
        }
    }
}

private data class DetailItem(val title: String, val detail: String)

