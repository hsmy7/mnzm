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
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.HeavenlyTrialViewModel
import com.xianxia.sect.ui.game.components.ItemDetailDialog
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
                        // 信息区 — 占9份
                        Column(modifier = Modifier.weight(9f)) {
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
                        // 挑战区 — 占1份
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
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
    var detailTarget by remember { mutableStateOf<Any?>(null) }

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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(skill.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        if (skill.isAoe) {
                            Text(" [全体]", fontSize = 9.sp, color = Color(0xFFE65100))
                        }
                    }
                    if (skill.damageMultiplier > 0) {
                        val dmgType = if (skill.damageType == com.xianxia.sect.core.DamageType.PHYSICAL) "物理" else "法术"
                        Text("${dmgType}伤害 ×${(skill.damageMultiplier * 100).toInt()}%  ${skill.hits}连击  冷却${skill.cooldown}回合  消耗${skill.mpCost}灵力", fontSize = 9.sp, color = Color.Black)
                    }
                    skill.buffs.forEach { buff ->
                        val buffName = buff.first.displayName
                        Text("$buffName +${(buff.second * 100).toInt()}% 持续${buff.third}回合", fontSize = 9.sp, color = Color.Black)
                    }
                    if (skill.buffs.isEmpty() && skill.buffType != null && skill.buffValue > 0) {
                        val bt = skill.buffType
                        if (bt != null) {
                            Text("${bt.displayName} +${(skill.buffValue * 100).toInt()}% 持续${skill.buffDuration}回合", fontSize = 9.sp, color = Color.Black)
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
        } else {
            // 装备槽位 — 4列，卡片自带名称无需底部文字
            Spacer(Modifier.height(6.dp))
            Text("装备", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                                onLongPress = if (template != null) {
                                    { detailTarget = template }
                                } else null
                            )
                        }
                    }
                }
            }

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
            val paddedSkills = if (manualSkills.size % 4 == 0) manualSkills
                else manualSkills + List(4 - manualSkills.size % 4) { Triple("", 1, null as ManualDatabase.ManualTemplate?) }
            val rows = paddedSkills.chunked(4)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                                        onLongPress = if (manual != null) {
                                            { detailTarget = manual }
                                        } else null
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 长按详情弹窗 — 使用正式的 ItemDetailDialog
    if (detailTarget != null) {
        ItemDetailDialog(
            item = detailTarget!!,
            onDismiss = { detailTarget = null }
        )
    }
}

