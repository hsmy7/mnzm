package com.xianxia.sect.ui.game

import androidx.compose.animation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import kotlinx.coroutines.delay
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.BeastMaterialDatabase
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.PillRecipeDatabase
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameEvent
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Manual
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.SupportTeam
import com.xianxia.sect.core.model.ExplorationTeam
import com.xianxia.sect.core.model.MapMarker
import com.xianxia.sect.core.model.MapMarkerType
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.XianxiaColorScheme
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.game.components.GiftDialog
import com.xianxia.sect.ui.game.components.AllianceDialog
import com.xianxia.sect.ui.game.components.EnvoyDiscipleSelectDialog
import com.xianxia.sect.ui.game.components.RequestSupportDialog
import com.xianxia.sect.ui.game.components.ScoutDiscipleSelectDialog

enum class MainTab {
    OVERVIEW, DISCIPLES, BUILDINGS, WAREHOUSE, SETTINGS
}

@Composable
fun MainGameScreen(
    viewModel: GameViewModel,
    onLogout: () -> Unit
) {
    val gameData by viewModel.gameData.collectAsState()
    val disciples by viewModel.disciples.collectAsState()
    val events by viewModel.events.collectAsState()
    val warTeams by viewModel.warTeams.collectAsState()
    val teams by viewModel.teams.collectAsState()
    val equipment by viewModel.equipment.collectAsState()
    val manuals by viewModel.manuals.collectAsState()
    val pills by viewModel.pills.collectAsState()
    val materials by viewModel.materials.collectAsState()
    val herbs by viewModel.herbs.collectAsState()

    var selectedTab by remember { mutableStateOf(MainTab.OVERVIEW) }

    // 对话框状态
    val showWarHallDialog by viewModel.showWarHallDialog.collectAsState()
    val showRecruitDialog by viewModel.showRecruitDialog.collectAsState()
    val showTournamentDialog by viewModel.showTournamentDialog.collectAsState()
    val showDiplomacyDialog by viewModel.showDiplomacyDialog.collectAsState()
    val showMerchantDialog by viewModel.showMerchantDialog.collectAsState()
    val showEventLogDialog by viewModel.showEventLogDialog.collectAsState()
    val showSalaryConfigDialog by viewModel.showSalaryConfigDialog.collectAsState()
    val showSectManagementDialog by viewModel.showSectManagementDialog.collectAsState()
    val showWorldMapDialog by viewModel.showWorldMapDialog.collectAsState()
    val showSecretRealmDialog by viewModel.showSecretRealmDialog.collectAsState()
    val showSectTradeDialog by viewModel.showSectTradeDialog.collectAsState()
    val selectedTradeSectId by viewModel.selectedTradeSectId.collectAsState()
    val sectTradeItems by viewModel.sectTradeItems.collectAsState()
    val showGiftDialog by viewModel.showGiftDialog.collectAsState()
    val selectedGiftSectId by viewModel.selectedGiftSectId.collectAsState()
    
    val showAllianceDialog by viewModel.showAllianceDialog.collectAsState()
    val selectedAllianceSectId by viewModel.selectedAllianceSectId.collectAsState()
    val showEnvoyDiscipleSelectDialog by viewModel.showEnvoyDiscipleSelectDialog.collectAsState()
    val showRequestSupportDialog by viewModel.showRequestSupportDialog.collectAsState()
    
    val showScoutDialog by viewModel.showScoutDialog.collectAsState()
    val selectedScoutSectId by viewModel.selectedScoutSectId.collectAsState()
    val showTianshuHallDialog by viewModel.showTianshuHallDialog.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    MainTab.OVERVIEW -> OverviewTab(
                        gameData = gameData,
                        events = events,
                        viewModel = viewModel
                    )
                    MainTab.DISCIPLES -> DisciplesTab(
                        gameData = gameData,
                        disciples = disciples.filter { it.isAlive },
                        equipment = equipment,
                        manuals = manuals,
                        viewModel = viewModel
                    )
                    MainTab.BUILDINGS -> BuildingsTab(viewModel = viewModel)
                    MainTab.WAREHOUSE -> WarehouseTab(viewModel = viewModel)
                    MainTab.SETTINGS -> SettingsTab(viewModel = viewModel, onLogout = onLogout)
                }
            }

            BottomNavigationBar(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        }

        // 对话框
        if (showWarHallDialog) {
            WarHallDialog(
                warTeams = warTeams,
                viewModel = viewModel,
                onDismiss = { viewModel.closeWarHallDialog() }
            )
        }

        if (showRecruitDialog) {
            RecruitDialog(
                recruitList = gameData?.recruitList ?: emptyList(),
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = { viewModel.closeRecruitDialog() }
            )
        }

        if (showTournamentDialog) {
            TournamentDialog(
                gameData = gameData,
                disciples = disciples.filter { it.isAlive },
                equipment = equipment,
                manuals = manuals,
                pills = pills,
                materials = materials,
                herbs = herbs,
                viewModel = viewModel,
                onDismiss = { viewModel.closeTournamentDialog() }
            )
        }

        if (showDiplomacyDialog) {
            DiplomacyDialog(
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = { viewModel.closeDiplomacyDialog() }
            )
        }

        if (showMerchantDialog) {
            MerchantDialog(
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = { viewModel.closeMerchantDialog() }
            )
        }

        if (showEventLogDialog) {
            EventLogDialog(
                events = events,
                onDismiss = { viewModel.closeEventLogDialog() }
            )
        }

        if (showSalaryConfigDialog) {
            SalaryConfigDialog(
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = { viewModel.closeSalaryConfigDialog() }
            )
        }

        if (showSectManagementDialog) {
            SectManagementDialog(
                viewModel = viewModel,
                onDismiss = { viewModel.closeSectManagementDialog() }
            )
        }

        if (showWorldMapDialog) {
            WorldMapDialog(
                worldSects = gameData?.worldMapSects ?: emptyList(),
                supportTeams = gameData?.supportTeams ?: emptyList(),
                scoutTeams = teams,
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = { viewModel.closeWorldMapDialog() }
            )
        }

        if (showSecretRealmDialog) {
            SecretRealmDialog(
                disciples = disciples.filter { it.isAlive },
                viewModel = viewModel,
                onDismiss = { viewModel.closeSecretRealmDialog() }
            )
        }

        if (showSectTradeDialog) {
            val selectedSect = gameData?.worldMapSects?.find { it.id == selectedTradeSectId }
            SectTradeDialog(
                sect = selectedSect,
                gameData = gameData,
                tradeItems = sectTradeItems,
                viewModel = viewModel,
                onDismiss = { viewModel.closeSectTradeDialog() }
            )
        }
        
        if (showGiftDialog) {
            val selectedSect = gameData?.worldMapSects?.find { it.id == selectedGiftSectId }
            GiftDialog(
                sect = selectedSect,
                gameData = gameData,
                equipment = equipment,
                manuals = manuals,
                pills = pills,
                viewModel = viewModel,
                onDismiss = { viewModel.closeGiftDialog() }
            )
        }
        
        if (showAllianceDialog) {
            val selectedSect = gameData?.worldMapSects?.find { it.id == selectedAllianceSectId }
            AllianceDialog(
                sect = selectedSect,
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = { viewModel.closeAllianceDialog() }
            )
        }
        
        if (showEnvoyDiscipleSelectDialog) {
            val selectedSect = gameData?.worldMapSects?.find { it.id == selectedAllianceSectId }
            val eligibleDisciples = selectedSect?.level?.let { viewModel.getEligibleEnvoyDisciples(it) } ?: emptyList()
            EnvoyDiscipleSelectDialog(
                sect = selectedSect,
                disciples = eligibleDisciples,
                viewModel = viewModel,
                onDismiss = { viewModel.closeEnvoyDiscipleSelectDialog() }
            )
        }
        
        if (showRequestSupportDialog) {
            val allies = viewModel.getPlayerAllies()
            val eligibleDisciples = viewModel.getEligibleRequestDisciples()
            RequestSupportDialog(
                allies = allies,
                disciples = eligibleDisciples,
                viewModel = viewModel,
                onDismiss = { viewModel.closeRequestSupportDialog() }
            )
        }
        
        if (showScoutDialog) {
            val selectedSect = gameData?.worldMapSects?.find { it.id == selectedScoutSectId }
            val eligibleDisciples = viewModel.getEligibleScoutDisciples()
            ScoutDiscipleSelectDialog(
                sect = selectedSect,
                disciples = eligibleDisciples,
                viewModel = viewModel,
                onDismiss = { viewModel.closeScoutDialog() }
            )
        }
    }
}

@Composable
private fun WarehouseItemDetailDialog(
    item: Any,
    onDismiss: () -> Unit
) {
    val name: String
    val rarity: Int
    val description: String
    val effects: List<String>
    
    when (item) {
        is Equipment -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                add("部位：${item.slot.displayName}")
                if (item.nurtureLevel > 0) {
                    add("孕养等级：Lv.${item.nurtureLevel}")
                }
                add("")
                add("属性:")
                val finalStats = item.getFinalStats()
                if (finalStats.physicalAttack > 0) add("  物理攻击 +${finalStats.physicalAttack}")
                if (finalStats.magicAttack > 0) add("  法术攻击 +${finalStats.magicAttack}")
                if (finalStats.physicalDefense > 0) add("  物理防御 +${finalStats.physicalDefense}")
                if (finalStats.magicDefense > 0) add("  法术防御 +${finalStats.magicDefense}")
                if (finalStats.speed > 0) add("  速度 +${finalStats.speed}")
                if (finalStats.hp > 0) add("  生命 +${finalStats.hp}")
                if (finalStats.mp > 0) add("  灵力 +${finalStats.mp}")
                if (item.critChance > 0) add("  暴击率 +${String.format("%.1f%%", item.critChance * 100)}")
            }
        }
        is Manual -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                add("类型：${item.type.displayName}")
                if (item.minRealm < 9) {
                    add("需求境界：${com.xianxia.sect.core.GameConfig.Realm.getName(item.minRealm)}")
                }
                add("")
                val stats = item.stats
                if (stats.isNotEmpty()) {
                    add("属性加成:")
                    stats.forEach { (key, value) ->
                        val statName = when (key) {
                            "cultivationSpeedPercent" -> "修炼速度"
                            "physicalAttack" -> "物理攻击"
                            "magicAttack" -> "法术攻击"
                            "physicalDefense" -> "物理防御"
                            "magicDefense" -> "法术防御"
                            "hp" -> "生命"
                            "mp" -> "灵力"
                            "speed" -> "速度"
                            "critChance" -> "暴击率"
                            else -> key
                        }
                        if (key.contains("Percent")) {
                            add("  $statName +$value%")
                        } else {
                            add("  $statName +$value")
                        }
                    }
                }
                item.skill?.let { skill ->
                    add("")
                    add("技能：${skill.name}")
                    add("  伤害类型：${if (skill.damageType == com.xianxia.sect.core.engine.DamageType.PHYSICAL) "物理" else "法术"}")
                    add("  伤害倍率：${String.format("%.1f%%", skill.damageMultiplier * 100)}")
                    add("  连击次数：${skill.hits}")
                    add("  冷却回合：${skill.cooldown}")
                    add("  灵力消耗：${skill.mpCost}")
                }
            }
        }
        is Pill -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                add("类型：${item.category.displayName}")
                add("数量：${item.quantity}")
                add("")
                add("效果:")
                when (item.category) {
                    com.xianxia.sect.core.model.PillCategory.BREAKTHROUGH -> {
                        if (item.breakthroughChance > 0) {
                            add("  突破概率 +${String.format("%.1f%%", item.breakthroughChance * 100)}")
                        }
                        if (item.targetRealm > 0) {
                            add("  目标境界：${com.xianxia.sect.core.GameConfig.Realm.getName(item.targetRealm)}")
                        }
                        if (item.isAscension) {
                            add("  可用于渡劫")
                        }
                    }
                    com.xianxia.sect.core.model.PillCategory.CULTIVATION -> {
                        if (item.cultivationPercent > 0) {
                            add("  修为 +${String.format("%.1f%%", item.cultivationPercent * 100)}")
                        }
                        if (item.cultivationSpeed > 1.0) {
                            add("  修炼速度 x${item.cultivationSpeed}")
                        }
                        if (item.skillExpPercent > 0) {
                            add("  功法熟练度 +${String.format("%.1f%%", item.skillExpPercent * 100)}")
                        }
                        if (item.extendLife > 0) {
                            add("  延寿 ${item.extendLife} 年")
                        }
                    }
                    com.xianxia.sect.core.model.PillCategory.BATTLE -> {
                        if (item.physicalAttackPercent > 0) add("  物理攻击 +${String.format("%.1f%%", item.physicalAttackPercent * 100)}")
                        if (item.magicAttackPercent > 0) add("  法术攻击 +${String.format("%.1f%%", item.magicAttackPercent * 100)}")
                        if (item.physicalDefensePercent > 0) add("  物理防御 +${String.format("%.1f%%", item.physicalDefensePercent * 100)}")
                        if (item.magicDefensePercent > 0) add("  法术防御 +${String.format("%.1f%%", item.magicDefensePercent * 100)}")
                        if (item.hpPercent > 0) add("  生命上限 +${String.format("%.1f%%", item.hpPercent * 100)}")
                        if (item.mpPercent > 0) add("  灵力上限 +${String.format("%.1f%%", item.mpPercent * 100)}")
                        if (item.speedPercent > 0) add("  速度 +${String.format("%.1f%%", item.speedPercent * 100)}")
                        if (item.battleCount > 0) add("  持续 ${item.battleCount} 场战斗")
                    }
                    com.xianxia.sect.core.model.PillCategory.HEALING -> {
                        if (item.heal > 0) add("  恢复生命 ${item.heal}")
                        if (item.healPercent > 0) add("  恢复生命 ${String.format("%.1f%%", item.healPercent * 100)}")
                        if (item.healMaxHpPercent > 0) add("  恢复生命 ${String.format("%.1f%%", item.healMaxHpPercent * 100)} 最大生命")
                        if (item.mpRecoverMaxMpPercent > 0) add("  恢复灵力 ${String.format("%.1f%%", item.mpRecoverMaxMpPercent * 100)} 最大灵力")
                        if (item.revive) add("  可复活弟子")
                        if (item.clearAll) add("  清除所有负面状态")
                    }
                }
                if (item.duration > 0 && item.category != com.xianxia.sect.core.model.PillCategory.BATTLE) {
                    add("  持续 ${item.duration} 月")
                }
                if (item.cannotStack) {
                    add("  不可叠加")
                }
            }
        }
        is Material -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                add("类型：${item.category.displayName}")
                add("数量：${item.quantity}")
                
                // 查找可用于制作的装备
                val recipes = com.xianxia.sect.core.data.ForgeRecipeDatabase.getRecipesByMaterial(item.id)
                if (recipes.isNotEmpty()) {
                    add("")
                    add("可用于锻造：")
                    recipes.forEach { recipe ->
                        add("  • ${recipe.name}")
                    }
                }
            }
        }
        is Herb -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                if (item.category.isNotEmpty()) {
                    add("类型：${item.category}")
                }
                add("数量：${item.quantity}")
                
                // 查找可用于制作的丹药
                val recipes = com.xianxia.sect.core.data.PillRecipeDatabase.getRecipesByHerb(item.id)
                if (recipes.isNotEmpty()) {
                    add("")
                    add("可用于炼制：")
                    recipes.forEach { recipe ->
                        add("  • ${recipe.name}")
                    }
                }
            }
        }
        is com.xianxia.sect.core.model.Seed -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                add("数量：${item.quantity}")
                add("成熟时间：${item.growTime}月")
                add("预计收获：${item.yield}个")
                
                // 查找长成的草药信息
                val herb = com.xianxia.sect.core.data.HerbDatabase.getHerbFromSeed(item.id)
                if (herb != null) {
                    add("")
                    add("长成后：${herb.name}")
                    add("  类型：${herb.category}")
                }
            }
        }
        else -> {
            name = "未知物品"
            rarity = 1
            description = ""
            effects = emptyList()
        }
    }
    
    val rarityColor = when (rarity) {
        1 -> Color(0xFF95A5A6)
        2 -> Color(0xFF27AE60)
        3 -> Color(0xFF3498DB)
        4 -> Color(0xFF9B59B6)
        5 -> Color(0xFFF39C12)
        6 -> Color(0xFFE74C3C)
        else -> Color(0xFF95A5A6)
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Text(
                text = name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = rarityColor
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                Text(
                    text = when (rarity) {
                        1 -> "普通"
                        2 -> "灵品"
                        3 -> "宝品"
                        4 -> "玄品"
                        5 -> "地品"
                        6 -> "天品"
                        else -> "普通"
                    },
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = Color(0xFFE0E0E0), thickness = 1.dp)
                Spacer(modifier = Modifier.height(8.dp))
                
                effects.forEach { effect ->
                    if (effect.isEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                    } else {
                        Text(
                            text = effect,
                            fontSize = 12.sp,
                            color = if (effect.startsWith("属性") || effect.startsWith("效果") || effect.startsWith("技能")) {
                                Color(0xFF3498DB)
                            } else {
                                Color(0xFF333333)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                
                if (description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = Color(0xFFE0E0E0), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = description,
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = Color(0xFF666666))
            }
        }
    )
}

@Composable
private fun TopStatusBar(
    gameData: GameData?,
    discipleCount: Int,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 第一行：宗门名称和时间
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = gameData?.sectName ?: "青云宗",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${gameData?.gameYear ?: 1}年${gameData?.gameMonth ?: 1}月${gameData?.gameDay ?: 1}日",
                    fontSize = 12.sp,
                    color = Color.Black
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 第二行：资源信息
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ResourceItem(
                icon = "💎",
                value = "${gameData?.spiritStones ?: 0}",
                label = "灵石"
            )
            ResourceItem(
                icon = "👥",
                value = "$discipleCount",
                label = "弟子"
            )
            ResourceItem(
                icon = "⚔️",
                value = "${gameData?.warTeams?.size ?: 0}",
                label = "战团"
            )
        }
    }
}

@Composable
private fun ResourceItem(
    icon: String,
    value: String,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = icon,
            fontSize = 12.sp,
            color = Color.Black
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.Black
        )
    }
}

@Composable
private fun OverviewTab(
    gameData: GameData?,
    events: List<GameEvent>,
    viewModel: GameViewModel
) {
    val disciples by viewModel.disciples.collectAsState()
    val aliveDiscipleCount = remember(disciples) {
        disciples.count { it.isAlive }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // 第一板块：宗门信息板块（固定高度）
        SectInfoPanel(
            gameData = gameData,
            discipleCount = aliveDiscipleCount
        )

        // 第二板块：快捷操作板块（固定高度）
        QuickActionPanel(
            viewModel = viewModel
        )

        // 第三板块：宗门消息板块（占据剩余空间）
        SectMessagePanel(
            events = events
        )
    }
}

@Composable
private fun QuickActionGrid(viewModel: GameViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "快捷操作",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickActionButton(
                    icon = Icons.Default.AccountBalance,
                    label = "宗门管理",
                    color = Color.Black,
                    onClick = { /* TODO */ }
                )
                QuickActionButton(
                    icon = Icons.Default.Public,
                    label = "世界地图",
                    color = Color.Black,
                    onClick = { /* TODO */ }
                )
                QuickActionButton(
                    icon = Icons.Default.PersonAdd,
                    label = "招募弟子",
                    color = Color.Black,
                    onClick = { viewModel.openRecruitDialog() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickActionButton(
                    icon = Icons.Default.ShoppingCart,
                    label = "云游商人",
                    color = Color.Black,
                    onClick = { viewModel.openMerchantDialog() }
                )
                QuickActionButton(
                    icon = Icons.Default.Security,
                    label = "宗门战争",
                    color = Color.Black,
                    onClick = { viewModel.openWarHallDialog() }
                )
                QuickActionButton(
                    icon = Icons.Default.Explore,
                    label = "秘境探索",
                    color = Color.Black,
                    onClick = { /* TODO */ }
                )
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.Black,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.Black
        )
    }
}

@Composable
private fun EventLogCard(
    events: List<GameEvent>,
    onViewAll: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "事件记录",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                GameButton(
                    text = "查看全部",
                    onClick = onViewAll
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (events.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无事件",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            } else {
                events.forEach { event ->
                    EventItem(event = event)
                    if (event != events.last()) {
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EventItem(event: GameEvent) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = when (event.type.name) {
            "SUCCESS" -> "✓"
            "WARNING" -> "⚠"
            "ERROR" -> "✕"
            "BATTLE" -> "⚔"
            else -> "•"
        }

        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Text(text = icon, fontSize = 12.sp, color = Color.Black)
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.message,
                fontSize = 12.sp,
                color = Color.Black
            )
            Text(
                text = event.displayTime,
                fontSize = 12.sp,
                color = Color.Black
            )
        }
    }
}

@Composable
private fun BottomNavigationBar(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit
) {
    NavigationBar(
        containerColor = Color.White,
        tonalElevation = 8.dp
    ) {
        NavigationBarItem(
            icon = { Box {} },
            label = { Text("总览", fontSize = 12.sp) },
            selected = selectedTab == MainTab.OVERVIEW,
            onClick = { onTabSelected(MainTab.OVERVIEW) },
            colors = NavigationBarItemDefaults.colors(
                selectedTextColor = Color.Black,
                unselectedTextColor = Color.Black,
                indicatorColor = Color.Transparent
            )
        )
        NavigationBarItem(
            icon = { Box {} },
            label = { Text("弟子", fontSize = 12.sp) },
            selected = selectedTab == MainTab.DISCIPLES,
            onClick = { onTabSelected(MainTab.DISCIPLES) },
            colors = NavigationBarItemDefaults.colors(
                selectedTextColor = Color.Black,
                unselectedTextColor = Color.Black,
                indicatorColor = Color.Transparent
            )
        )
        NavigationBarItem(
            icon = { Box {} },
            label = { Text("建筑", fontSize = 12.sp) },
            selected = selectedTab == MainTab.BUILDINGS,
            onClick = { onTabSelected(MainTab.BUILDINGS) },
            colors = NavigationBarItemDefaults.colors(
                selectedTextColor = Color.Black,
                unselectedTextColor = Color.Black,
                indicatorColor = Color.Transparent
            )
        )
        NavigationBarItem(
            icon = { Box {} },
            label = { Text("仓库", fontSize = 12.sp) },
            selected = selectedTab == MainTab.WAREHOUSE,
            onClick = { onTabSelected(MainTab.WAREHOUSE) },
            colors = NavigationBarItemDefaults.colors(
                selectedTextColor = Color.Black,
                unselectedTextColor = Color.Black,
                indicatorColor = Color.Transparent
            )
        )
        NavigationBarItem(
            icon = { Box {} },
            label = { Text("设置", fontSize = 12.sp) },
            selected = selectedTab == MainTab.SETTINGS,
            onClick = { onTabSelected(MainTab.SETTINGS) },
            colors = NavigationBarItemDefaults.colors(
                selectedTextColor = Color.Black,
                unselectedTextColor = Color.Black,
                indicatorColor = Color.Transparent
            )
        )
    }
}

// 其他Tab的占位实现
@Composable
private fun DisciplesTab(
    gameData: GameData?,
    disciples: List<Disciple>,
    equipment: List<Equipment>,
    manuals: List<Manual>,
    viewModel: GameViewModel
) {
    var selectedRealmFilter by remember { mutableStateOf<Int?>(null) }
    var selectedDisciple by remember { mutableStateOf<Disciple?>(null) }

    val realmFilters = listOf(
        0 to "仙人",
        1 to "渡劫",
        2 to "大乘",
        3 to "合体",
        4 to "炼虚",
        5 to "化神",
        6 to "元婴",
        7 to "金丹",
        8 to "筑基",
        9 to "炼气"
    )

    val realmCounts = remember(disciples) {
        disciples.groupingBy { it.realm }.eachCount()
    }

    val filteredDisciples = remember(disciples, selectedRealmFilter) {
        val sorted = disciples.sortedWith(
            compareBy<Disciple> { disciple ->
                val hasNoRealm = disciple.realmLayer == 0 || disciple.age < 5
                if (hasNoRealm) Int.MAX_VALUE else disciple.realm
            }.thenByDescending { it.realmLayer }
        )
        if (selectedRealmFilter == null) {
            sorted
        } else {
            sorted.filter { it.realm == selectedRealmFilter }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        RealmFilterBar(
            filters = realmFilters,
            realmCounts = realmCounts,
            selectedFilter = selectedRealmFilter,
            onFilterSelected = { selectedRealmFilter = it }
        )

        if (filteredDisciples.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无弟子",
                    fontSize = 12.sp,
                    color = Color(0xFF999999)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(filteredDisciples) { disciple ->
                    DiscipleCard(
                        disciple = disciple,
                        onClick = { selectedDisciple = disciple }
                    )
                }
            }
        }
    }

    selectedDisciple?.let { selected ->
        val updatedDisciple = disciples.find { it.id == selected.id } ?: selected
        DiscipleDetailDialog(
            disciple = updatedDisciple,
            allDisciples = disciples,
            allEquipment = equipment,
            allManuals = manuals,
            manualProficiencies = gameData?.manualProficiencies ?: emptyMap(),
            viewModel = viewModel,
            onDismiss = { selectedDisciple = null }
        )
    }
}

@Composable
private fun RealmFilterBar(
    filters: List<Pair<Int, String>>,
    realmCounts: Map<Int, Int>,
    selectedFilter: Int?,
    onFilterSelected: (Int?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            filters.take(5).forEach { (realm, name) ->
                val isSelected = selectedFilter == realm
                val count = realmCounts[realm] ?: 0
                FilterChip(
                    text = "$name $count",
                    isSelected = isSelected,
                    onClick = { onFilterSelected(if (isSelected) null else realm) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            filters.drop(5).forEach { (realm, name) ->
                val isSelected = selectedFilter == realm
                val count = realmCounts[realm] ?: 0
                FilterChip(
                    text = "$name $count",
                    isSelected = isSelected,
                    onClick = { onFilterSelected(if (isSelected) null else realm) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun FilterChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) Color(0xFFE0E0E0) else Color.White)
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = Color.Black
        )
    }
}

@Composable
private fun DiscipleCard(
    disciple: Disciple,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = disciple.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = disciple.status.displayName,
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val spiritRootColor = try {
                    Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                } catch (e: Exception) {
                    Color(0xFF666666)
                }
                Text(
                    text = disciple.spiritRootName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = spiritRootColor,
                    maxLines = 1
                )
                Text(
                    text = disciple.realmName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "悟性:${disciple.comprehension}",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )
                Text(
                    text = "忠诚:${disciple.loyalty}",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )
                Text(
                    text = "道德:${disciple.morality}",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )
            }
        }
    }
}

@Composable
private fun SectManagementDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val gameData by viewModel.gameData.collectAsState()
    val disciples by viewModel.disciples.collectAsState()
    var showDiscipleSelection by remember { mutableStateOf<String?>(null) }
    var showDirectDiscipleSelection by remember { mutableStateOf<Pair<String, Int>?>(null) }
    
    val elderSlots = gameData.elderSlots
    val elderSlotTypes = listOf(
        "herbGarden" to "灵植长老",
        "alchemy" to "炼丹长老",
        "forge" to "天工长老",
        "library" to "藏经阁长老",
        "spiritMine" to "灵矿长老",
        "recruit" to "纳徒长老"
    )
    
    CommonDialog(
        title = "宗门管理",
        onDismiss = onDismiss
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            elderSlotTypes.chunked(3).forEach { rowSlots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    rowSlots.forEach { (slotType, slotName) ->
                        val elderId = when (slotType) {
                            "herbGarden" -> elderSlots.herbGardenElder
                            "alchemy" -> elderSlots.alchemyElder
                            "forge" -> elderSlots.forgeElder
                            "library" -> elderSlots.libraryElder
                            "spiritMine" -> elderSlots.spiritMineElder
                            "recruit" -> elderSlots.recruitElder
                            else -> null
                        }
                        val elder = viewModel.getElderDisciple(elderId)
                        
                        val directDisciples = when (slotType) {
                            "herbGarden" -> elderSlots.herbGardenDisciples
                            "alchemy" -> elderSlots.alchemyDisciples
                            "forge" -> elderSlots.forgeDisciples
                            "library" -> elderSlots.libraryDisciples
                            "spiritMine" -> elderSlots.spiritMineDisciples
                            "recruit" -> elderSlots.recruitDisciples
                            else -> emptyList()
                        }
                        
                        ElderSlotWithDisciples(
                            slotName = slotName,
                            slotType = slotType,
                            elder = elder,
                            directDisciples = directDisciples,
                            onElderClick = { showDiscipleSelection = slotType },
                            onElderRemove = { viewModel.removeElder(slotType) },
                            onDirectDiscipleClick = { index ->
                                showDirectDiscipleSelection = Pair(slotType, index)
                            },
                            onDirectDiscipleRemove = { index ->
                                viewModel.removeDirectDisciple(slotType, index)
                            }
                        )
                    }
                }
            }
        }
    }
    
    showDiscipleSelection?.let { slotType ->
        val currentElderId = when (slotType) {
            "herbGarden" -> elderSlots.herbGardenElder
            "alchemy" -> elderSlots.alchemyElder
            "forge" -> elderSlots.forgeElder
            "library" -> elderSlots.libraryElder
            "spiritMine" -> elderSlots.spiritMineElder
            "recruit" -> elderSlots.recruitElder
            else -> null
        }
        // 根据槽位类型确定需要显示的属性
        val requiredAttribute = when (slotType) {
            "herbGarden" -> "spiritPlanting" to "灵植"
            "alchemy" -> "pillRefining" to "炼丹"
            "forge" -> "artifactRefining" to "炼器"
            "library" -> "teaching" to "传道"
            "spiritMine" -> "morality" to "道德"
            "recruit" -> "charm" to "魅力"
            else -> null
        }
        ElderDiscipleSelectionDialog(
            disciples = disciples.filter { it.isAlive && it.realm <= 6 },
            currentElderId = currentElderId,
            requiredAttribute = requiredAttribute,
            elderSlots = elderSlots,
            onDismiss = { showDiscipleSelection = null },
            onSelect = { discipleId ->
                viewModel.assignElder(slotType, discipleId)
                showDiscipleSelection = null
            }
        )
    }
    
    showDirectDiscipleSelection?.let { (slotType, slotIndex) ->
        // 根据槽位类型确定需要显示的属性
        val requiredAttribute = when (slotType) {
            "herbGarden" -> "spiritPlanting" to "灵植"
            "alchemy" -> "pillRefining" to "炼丹"
            "forge" -> "artifactRefining" to "炼器"
            "library" -> "teaching" to "传道"
            "spiritMine" -> "morality" to "道德"
            "recruit" -> "charm" to "魅力"
            else -> null
        }
        DirectDiscipleSelectionDialog(
            disciples = disciples.filter { it.isAlive },
            requiredAttribute = requiredAttribute,
            elderSlots = elderSlots,
            onDismiss = { showDirectDiscipleSelection = null },
            onSelect = { discipleId ->
                viewModel.assignDirectDisciple(slotType, slotIndex, discipleId)
                showDirectDiscipleSelection = null
            }
        )
    }
}

@Composable
private fun ElderSlotWithDisciples(
    slotName: String,
    slotType: String,
    elder: Disciple?,
    directDisciples: List<DirectDiscipleSlot>,
    onElderClick: () -> Unit,
    onElderRemove: () -> Unit,
    onDirectDiscipleClick: (Int) -> Unit,
    onDirectDiscipleRemove: (Int) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = slotName,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(4.dp))
        
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp))
                .clickable(onClick = onElderClick),
            contentAlignment = Alignment.Center
        ) {
            if (elder != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = elder.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                    Text(
                        text = elder.realmName,
                        fontSize = 8.sp,
                        color = Color(0xFF666666),
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 20.sp,
                    color = Color(0xFF999999)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(2.dp))
        
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                .clickable(onClick = onElderRemove)
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = "卸任",
                fontSize = 9.sp,
                color = Color.Black
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(8.dp)
                .background(Color(0xFFCCCCCC))
        )
        
        Text(
            text = "亲传弟子",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF666666)
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            (0..1).forEach { index ->
                val disciple = directDisciples.find { it.index == index }
                DirectDiscipleSlotItem(
                    disciple = disciple,
                    onClick = { onDirectDiscipleClick(index) },
                    onRemove = { onDirectDiscipleRemove(index) }
                )
            }
        }
    }
}

@Composable
private fun DirectDiscipleSlotItem(
    disciple: DirectDiscipleSlot?,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (disciple != null && disciple.isActive) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = disciple.discipleName,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                    Text(
                        text = disciple.discipleRealm,
                        fontSize = 7.sp,
                        color = Color(0xFF666666),
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 16.sp,
                    color = Color(0xFF999999)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(2.dp))
        
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(3.dp))
                .clickable(onClick = onRemove)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = "卸任",
                fontSize = 8.sp,
                color = Color.Black
            )
        }
    }
}

@Composable
private fun DirectDiscipleSelectionDialog(
    disciples: List<Disciple>,
    requiredAttribute: Pair<String, String>?,
    elderSlots: ElderSlots,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Int?>(null) }

    val realmFilters = listOf(
        0 to "仙人",
        1 to "渡劫",
        2 to "大乘",
        3 to "合体",
        4 to "炼虚",
        5 to "化神",
        6 to "元婴",
        7 to "金丹",
        8 to "筑基",
        9 to "炼气"
    )

    val filteredDisciplesBase = remember(disciples, elderSlots) {
        disciples.filter { 
            it.realmLayer > 0 && 
            it.age >= 5 && 
            it.status == DiscipleStatus.IDLE &&
            it.discipleType == "inner" &&
            !isDiscipleInAnyPosition(it.id, elderSlots)
        }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(filteredDisciplesBase, requiredAttribute) {
        val attrKey = requiredAttribute?.first
        filteredDisciplesBase.sortedWith(
            compareByDescending<Disciple> { disciple ->
                when (attrKey) {
                    "spiritPlanting" -> disciple.spiritPlanting
                    "pillRefining" -> disciple.pillRefining
                    "artifactRefining" -> disciple.artifactRefining
                    "teaching" -> disciple.teaching
                    "morality" -> disciple.morality
                    "charm" -> disciple.charm
                    else -> 0
                }
            }.thenBy { it.realm }
                .thenByDescending { it.realmLayer }
        )
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter) {
        if (selectedRealmFilter == null) {
            sortedDisciples
        } else {
            sortedDisciples.filter { it.realm == selectedRealmFilter }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择亲传弟子",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(Color(0xFFF5F5F5)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        realmFilters.take(5).forEach { (realm, name) ->
                            val isSelected = selectedRealmFilter == realm
                            val count = realmCounts[realm] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) Color(0xFFE0E0E0) else Color.White)
                                    .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) null else realm }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$name $count",
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        realmFilters.drop(5).forEach { (realm, name) ->
                            val isSelected = selectedRealmFilter == realm
                            val count = realmCounts[realm] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) Color(0xFFE0E0E0) else Color.White)
                                    .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) null else realm }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$name $count",
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredDisciples) { disciple ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White)
                                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp))
                                .clickable { onSelect(disciple.id) }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = disciple.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val spiritRootColor = try {
                                        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                                    } catch (e: Exception) {
                                        Color(0xFF666666)
                                    }
                                    Text(
                                        text = disciple.spiritRootName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = spiritRootColor
                                    )
                                    requiredAttribute?.let { (attrKey, attrName) ->
                                        val attrValue = when (attrKey) {
                                            "spiritPlanting" -> disciple.spiritPlanting
                                            "pillRefining" -> disciple.pillRefining
                                            "artifactRefining" -> disciple.artifactRefining
                                            "teaching" -> disciple.teaching
                                            "morality" -> disciple.morality
                                            "charm" -> disciple.charm
                                            else -> 0
                                        }
                                        Text(
                                            text = "$attrName:$attrValue",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF2196F3)
                                        )
                                    }
                                    Text(
                                        text = disciple.realmName,
                                        fontSize = 12.sp,
                                        color = Color(0xFF666666)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

private fun isDiscipleInAnyPosition(discipleId: String, elderSlots: ElderSlots): Boolean {
    if (elderSlots.viceSectMaster == discipleId) {
        return true
    }
    
    val allElderIds = listOf(
        elderSlots.herbGardenElder,
        elderSlots.alchemyElder,
        elderSlots.forgeElder,
        elderSlots.libraryElder,
        elderSlots.spiritMineElder,
        elderSlots.recruitElder
    )
    
    if (allElderIds.contains(discipleId)) {
        return true
    }
    
    val allDirectDiscipleIds = listOf(
        elderSlots.herbGardenDisciples,
        elderSlots.alchemyDisciples,
        elderSlots.forgeDisciples,
        elderSlots.libraryDisciples,
        elderSlots.spiritMineDisciples,
        elderSlots.recruitDisciples
    ).flatten().mapNotNull { it.discipleId }
    
    return allDirectDiscipleIds.contains(discipleId)
}

@Composable
private fun ElderDiscipleSelectionDialog(
    disciples: List<Disciple>,
    currentElderId: String?,
    requiredAttribute: Pair<String, String>?,
    elderSlots: ElderSlots,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Int?>(null) }

    val realmFilters = listOf(
        0 to "仙人",
        1 to "渡劫",
        2 to "大乘",
        3 to "合体",
        4 to "炼虚",
        5 to "化神",
        6 to "元婴",
        7 to "金丹",
        8 to "筑基",
        9 to "炼气"
    )

    val filteredDisciplesBase = remember(disciples, elderSlots) {
        disciples.filter { 
            it.realmLayer > 0 && 
            it.age >= 5 && 
            it.status == DiscipleStatus.IDLE &&
            it.discipleType == "inner" &&
            !isDiscipleInAnyPosition(it.id, elderSlots)
        }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(filteredDisciplesBase, requiredAttribute) {
        val attrKey = requiredAttribute?.first
        filteredDisciplesBase.sortedWith(
            compareByDescending<Disciple> { disciple ->
                when (attrKey) {
                    "spiritPlanting" -> disciple.spiritPlanting
                    "pillRefining" -> disciple.pillRefining
                    "artifactRefining" -> disciple.artifactRefining
                    "teaching" -> disciple.teaching
                    "morality" -> disciple.morality
                    "charm" -> disciple.charm
                    else -> 0
                }
            }.thenBy { it.realm }
                .thenByDescending { it.realmLayer }
        )
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter) {
        if (selectedRealmFilter == null) {
            sortedDisciples
        } else {
            sortedDisciples.filter { it.realm == selectedRealmFilter }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择弟子",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(Color(0xFFF5F5F5)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        realmFilters.take(5).forEach { (realm, name) ->
                            val isSelected = selectedRealmFilter == realm
                            val count = realmCounts[realm] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) Color(0xFFE0E0E0) else Color.White)
                                    .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) null else realm }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$name $count",
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        realmFilters.drop(5).forEach { (realm, name) ->
                            val isSelected = selectedRealmFilter == realm
                            val count = realmCounts[realm] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) Color(0xFFE0E0E0) else Color.White)
                                    .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) null else realm }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$name $count",
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredDisciples) { disciple ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White)
                                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp))
                                .clickable { onSelect(disciple.id) }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = disciple.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val spiritRootColor = try {
                                        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                                    } catch (e: Exception) {
                                        Color(0xFF666666)
                                    }
                                    Text(
                                        text = disciple.spiritRootName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = spiritRootColor
                                    )
                                    requiredAttribute?.let { (attrKey, attrName) ->
                                        val attrValue = when (attrKey) {
                                            "spiritPlanting" -> disciple.spiritPlanting
                                            "pillRefining" -> disciple.pillRefining
                                            "artifactRefining" -> disciple.artifactRefining
                                            "teaching" -> disciple.teaching
                                            "morality" -> disciple.morality
                                            "charm" -> disciple.charm
                                            else -> 0
                                        }
                                        Text(
                                            text = "$attrName:$attrValue",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF2196F3)
                                        )
                                    }
                                    Text(
                                        text = disciple.realmName,
                                        fontSize = 12.sp,
                                        color = Color(0xFF666666)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun BuildingsTab(viewModel: GameViewModel) {
    val gameData by viewModel.gameData.collectAsState()
    val alchemySlots by viewModel.alchemySlots.collectAsState()
    val forgeSlots by viewModel.forgeSlots.collectAsState()
    val materials by viewModel.materials.collectAsState()
    val herbs by viewModel.herbs.collectAsState()
    val seeds by viewModel.seeds.collectAsState()
    val manuals by viewModel.manuals.collectAsState()
    val disciples by viewModel.disciples.collectAsState()
    val equipment by viewModel.equipment.collectAsState()
    val pills by viewModel.pills.collectAsState()
    
    val showAlchemyDialog by viewModel.showAlchemyDialog.collectAsState()
    val showForgeDialog by viewModel.showForgeDialog.collectAsState()
    val showHerbGardenDialog by viewModel.showHerbGardenDialog.collectAsState()
    val showSpiritMineDialog by viewModel.showSpiritMineDialog.collectAsState()
    val showLibraryDialog by viewModel.showLibraryDialog.collectAsState()
    val showWenDaoPeakDialog by viewModel.showWenDaoPeakDialog.collectAsState()
    val showQingyunPeakDialog by viewModel.showQingyunPeakDialog.collectAsState()
    val showTournamentDialog by viewModel.showTournamentDialog.collectAsState()
    val showTianshuHallDialog by viewModel.showTianshuHallDialog.collectAsState()
    val showLawEnforcementHallDialog by viewModel.showLawEnforcementHallDialog.collectAsState()

    val buildings = listOf(
        Triple("灵矿场", "开采灵石资源") { viewModel.openSpiritMineDialog() },
        Triple("灵药宛", "种植灵药材料") { viewModel.openHerbGardenDialog() },
        Triple("丹鼎殿", "炼制丹药") { viewModel.openAlchemyDialog() },
        Triple("天工峰", "锻造装备") { viewModel.openForgeDialog() },
        Triple("藏经阁", "功法管理") { viewModel.openLibraryDialog() },
        Triple("问道峰", "管理外门弟子") { viewModel.openWenDaoPeakDialog() },
        Triple("青云峰", "管理内门弟子") { viewModel.openQingyunPeakDialog() },
        Triple("天枢殿", "处理宗门事务") { viewModel.openTianshuHallDialog() },
        Triple("执法堂", "维护宗门纪律") { viewModel.openLawEnforcementHallDialog() },
        Triple("思过崖", "悔过自新之地") { viewModel.openReflectionCliffDialog() },
        Triple("演武堂", "宗门大比") { viewModel.openTournamentDialog() }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(12.dp)
    ) {
        Text(
            text = "建筑管理",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            buildings.chunked(2).forEach { rowBuildings ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowBuildings.forEach { (name, desc, onClick) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
                                .clickable { onClick() }
                                .padding(12.dp)
                        ) {
                            Column {
                                Text(
                                    text = name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = desc,
                                    fontSize = 12.sp,
                                    color = Color(0xFF666666)
                                )
                            }
                        }
                    }
                    if (rowBuildings.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    if (showSpiritMineDialog) {
        SpiritMineDialog(
            viewModel = viewModel,
            onDismiss = { viewModel.closeSpiritMineDialog() }
        )
    }

    if (showHerbGardenDialog) {
        HerbGardenDialog(
            plantSlots = gameData?.herbGardenPlantSlots ?: emptyList(),
            seeds = seeds,
            gameData = gameData,
            disciples = disciples.filter { it.isAlive },
            viewModel = viewModel,
            onDismiss = { viewModel.closeHerbGardenDialog() }
        )
    }

    if (showAlchemyDialog) {
        AlchemyDialog(
            alchemySlots = alchemySlots,
            materials = materials,
            herbs = herbs,
            gameData = gameData,
            disciples = disciples.filter { it.isAlive },
            viewModel = viewModel,
            colors = XianxiaColorScheme(),
            onDismiss = { viewModel.closeAlchemyDialog() }
        )
    }

    if (showForgeDialog) {
        ForgeDialog(
            forgeSlots = forgeSlots,
            materials = materials,
            gameData = gameData,
            viewModel = viewModel,
            colors = XianxiaColorScheme(),
            onDismiss = { viewModel.closeForgeDialog() }
        )
    }

    if (showLibraryDialog) {
        LibraryDialog(
            manuals = manuals,
            disciples = disciples.filter { it.isAlive },
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { viewModel.closeLibraryDialog() }
        )
    }

    if (showWenDaoPeakDialog) {
        WenDaoPeakDialog(
            disciples = disciples.filter { it.isAlive },
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { viewModel.closeWenDaoPeakDialog() }
        )
    }

    if (showQingyunPeakDialog) {
        QingyunPeakDialog(
            disciples = disciples.filter { it.isAlive },
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { viewModel.closeQingyunPeakDialog() }
        )
    }

    if (showTournamentDialog) {
        TournamentDialog(
            gameData = gameData,
            disciples = disciples.filter { it.isAlive },
            equipment = equipment,
            manuals = manuals,
            pills = pills,
            materials = materials,
            herbs = herbs,
            viewModel = viewModel,
            onDismiss = { viewModel.closeTournamentDialog() }
        )
    }

    if (showTianshuHallDialog) {
        TianshuHallDialog(
            gameData = gameData,
            disciples = disciples.filter { it.isAlive },
            viewModel = viewModel,
            onDismiss = { viewModel.closeTianshuHallDialog() }
        )
    }

    if (showLawEnforcementHallDialog) {
        LawEnforcementHallDialog(
            disciples = disciples.filter { it.isAlive },
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { viewModel.closeLawEnforcementHallDialog() }
        )
    }

    val showReflectionCliffDialog by viewModel.showReflectionCliffDialog.collectAsState()
    if (showReflectionCliffDialog) {
        ReflectionCliffDialog(
            disciples = disciples.filter { it.isAlive },
            gameData = gameData,
            onDismiss = { viewModel.closeReflectionCliffDialog() }
        )
    }
}

private enum class WarehouseFilter(val displayName: String) {
    ALL("全部"),
    EQUIPMENT("装备"),
    PILL("丹药"),
    MANUAL("功法"),
    HERB("草药"),
    SEED("种子"),
    MATERIAL("材料")
}

@Composable
private fun WarehouseTab(viewModel: GameViewModel) {
    val allEquipment by viewModel.equipment.collectAsState()
    val allManuals by viewModel.manuals.collectAsState()
    val pills by viewModel.pills.collectAsState()
    val materials by viewModel.materials.collectAsState()
    val herbs by viewModel.herbs.collectAsState()
    val seeds by viewModel.seeds.collectAsState()
    
    val equipment = remember(allEquipment) {
        allEquipment.filter { !it.isEquipped }
    }
    
    val manuals = remember(allManuals) {
        allManuals.filter { !it.isLearned }
    }
    
    var selectedFilter by remember { mutableStateOf(WarehouseFilter.ALL) }
    var selectedItem by remember { mutableStateOf<Any?>(null) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var selectedItemId by remember { mutableStateOf<String?>(null) }
    var showBulkSellDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "仓库",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            
            // 一键出售按钮
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFE74C3C))
                    .clickable { showBulkSellDialog = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "一键出售",
                    fontSize = 12.sp,
                    color = Color.White
                )
            }
        }
        
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WarehouseFilterButton(
                    text = WarehouseFilter.ALL.displayName,
                    selected = selectedFilter == WarehouseFilter.ALL,
                    onClick = { selectedFilter = WarehouseFilter.ALL },
                    modifier = Modifier.weight(1f)
                )
                WarehouseFilterButton(
                    text = WarehouseFilter.EQUIPMENT.displayName,
                    selected = selectedFilter == WarehouseFilter.EQUIPMENT,
                    onClick = { selectedFilter = WarehouseFilter.EQUIPMENT },
                    modifier = Modifier.weight(1f)
                )
                WarehouseFilterButton(
                    text = WarehouseFilter.PILL.displayName,
                    selected = selectedFilter == WarehouseFilter.PILL,
                    onClick = { selectedFilter = WarehouseFilter.PILL },
                    modifier = Modifier.weight(1f)
                )
                WarehouseFilterButton(
                    text = WarehouseFilter.MANUAL.displayName,
                    selected = selectedFilter == WarehouseFilter.MANUAL,
                    onClick = { selectedFilter = WarehouseFilter.MANUAL },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WarehouseFilterButton(
                    text = WarehouseFilter.HERB.displayName,
                    selected = selectedFilter == WarehouseFilter.HERB,
                    onClick = { selectedFilter = WarehouseFilter.HERB },
                    modifier = Modifier.weight(1f)
                )
                WarehouseFilterButton(
                    text = WarehouseFilter.SEED.displayName,
                    selected = selectedFilter == WarehouseFilter.SEED,
                    onClick = { selectedFilter = WarehouseFilter.SEED },
                    modifier = Modifier.weight(1f)
                )
                WarehouseFilterButton(
                    text = WarehouseFilter.MATERIAL.displayName,
                    selected = selectedFilter == WarehouseFilter.MATERIAL,
                    onClick = { selectedFilter = WarehouseFilter.MATERIAL },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        val allItems = when (selectedFilter) {
            WarehouseFilter.ALL -> equipment + manuals + pills + materials + herbs + seeds
            WarehouseFilter.EQUIPMENT -> equipment
            WarehouseFilter.PILL -> pills
            WarehouseFilter.MANUAL -> manuals
            WarehouseFilter.HERB -> herbs
            WarehouseFilter.SEED -> seeds
            WarehouseFilter.MATERIAL -> materials
        }
        
        if (allItems.isEmpty()) {
            EmptyWarehouseMessage()
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (selectedFilter) {
                    WarehouseFilter.ALL -> {
                        items(equipment, key = { "equipment_${it.id}" }) { item ->
                            WarehouseEquipmentCard(
                                equipment = item,
                                isSelected = selectedItemId == item.id,
                                onSelect = {
                                    selectedItemId = if (selectedItemId == item.id) null else item.id
                                },
                                onViewDetail = {
                                    selectedItem = item
                                    showDetailDialog = true
                                }
                            )
                        }
                        items(manuals, key = { "manual_${it.id}" }) { item ->
                            WarehouseManualCard(
                                manual = item,
                                isSelected = selectedItemId == item.id,
                                onSelect = {
                                    selectedItemId = if (selectedItemId == item.id) null else item.id
                                },
                                onViewDetail = {
                                    selectedItem = item
                                    showDetailDialog = true
                                }
                            )
                        }
                        items(pills, key = { "pill_${it.id}_${it.quantity}" }) { item ->
                            WarehousePillCard(
                                pill = item,
                                isSelected = selectedItemId == item.id,
                                onSelect = {
                                    selectedItemId = if (selectedItemId == item.id) null else item.id
                                },
                                onViewDetail = {
                                    selectedItem = item
                                    showDetailDialog = true
                                }
                            )
                        }
                        items(materials, key = { "material_${it.id}_${it.quantity}" }) { item ->
                            WarehouseMaterialCard(
                                material = item,
                                isSelected = selectedItemId == item.id,
                                onSelect = {
                                    selectedItemId = if (selectedItemId == item.id) null else item.id
                                },
                                onViewDetail = {
                                    selectedItem = item
                                    showDetailDialog = true
                                }
                            )
                        }
                        items(herbs, key = { "herb_${it.id}_${it.quantity}" }) { item ->
                            WarehouseHerbCard(
                                herb = item,
                                isSelected = selectedItemId == item.id,
                                onSelect = {
                                    selectedItemId = if (selectedItemId == item.id) null else item.id
                                },
                                onViewDetail = {
                                    selectedItem = item
                                    showDetailDialog = true
                                }
                            )
                        }
                        items(seeds, key = { "seed_${it.id}_${it.quantity}" }) { item ->
                            WarehouseSeedCard(
                                seed = item,
                                isSelected = selectedItemId == item.id,
                                onSelect = {
                                    selectedItemId = if (selectedItemId == item.id) null else item.id
                                },
                                onViewDetail = {
                                    selectedItem = item
                                    showDetailDialog = true
                                }
                            )
                        }
                    }
                    WarehouseFilter.EQUIPMENT -> {
                        items(equipment, key = { "equipment_${it.id}" }) { item ->
                            WarehouseEquipmentCard(
                                equipment = item,
                                isSelected = selectedItemId == item.id,
                                onSelect = {
                                    selectedItemId = if (selectedItemId == item.id) null else item.id
                                },
                                onViewDetail = {
                                    selectedItem = item
                                    showDetailDialog = true
                                }
                            )
                        }
                    }
                    WarehouseFilter.PILL -> {
                        items(pills, key = { "pill_${it.id}_${it.quantity}" }) { item ->
                            WarehousePillCard(
                                pill = item,
                                isSelected = selectedItemId == item.id,
                                onSelect = {
                                    selectedItemId = if (selectedItemId == item.id) null else item.id
                                },
                                onViewDetail = {
                                    selectedItem = item
                                    showDetailDialog = true
                                }
                            )
                        }
                    }
                    WarehouseFilter.MANUAL -> {
                        items(manuals, key = { "manual_${it.id}" }) { item ->
                            WarehouseManualCard(
                                manual = item,
                                isSelected = selectedItemId == item.id,
                                onSelect = {
                                    selectedItemId = if (selectedItemId == item.id) null else item.id
                                },
                                onViewDetail = {
                                    selectedItem = item
                                    showDetailDialog = true
                                }
                            )
                        }
                    }
                    WarehouseFilter.HERB -> {
                        items(herbs, key = { "herb_${it.id}_${it.quantity}" }) { item ->
                            WarehouseHerbCard(
                                herb = item,
                                isSelected = selectedItemId == item.id,
                                onSelect = {
                                    selectedItemId = if (selectedItemId == item.id) null else item.id
                                },
                                onViewDetail = {
                                    selectedItem = item
                                    showDetailDialog = true
                                }
                            )
                        }
                    }
                    WarehouseFilter.SEED -> {
                        items(seeds, key = { "seed_${it.id}_${it.quantity}" }) { item ->
                            WarehouseSeedCard(
                                seed = item,
                                isSelected = selectedItemId == item.id,
                                onSelect = {
                                    selectedItemId = if (selectedItemId == item.id) null else item.id
                                },
                                onViewDetail = {
                                    selectedItem = item
                                    showDetailDialog = true
                                }
                            )
                        }
                    }
                    WarehouseFilter.MATERIAL -> {
                        items(materials, key = { "material_${it.id}_${it.quantity}" }) { item ->
                            WarehouseMaterialCard(
                                material = item,
                                isSelected = selectedItemId == item.id,
                                onSelect = {
                                    selectedItemId = if (selectedItemId == item.id) null else item.id
                                },
                                onViewDetail = {
                                    selectedItem = item
                                    showDetailDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    if (showDetailDialog && selectedItem != null) {
        WarehouseItemDetailDialog(
            item = selectedItem!!,
            onDismiss = {
                showDetailDialog = false
                selectedItemId = null
            }
        )
    }
    
    if (showBulkSellDialog) {
        BulkSellDialog(
            viewModel = viewModel,
            onDismiss = { showBulkSellDialog = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BulkSellDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val allEquipment by viewModel.equipment.collectAsState()
    val allManuals by viewModel.manuals.collectAsState()
    val pills by viewModel.pills.collectAsState()
    val materials by viewModel.materials.collectAsState()
    val herbs by viewModel.herbs.collectAsState()
    val seeds by viewModel.seeds.collectAsState()
    
    val equipment = remember(allEquipment) {
        allEquipment.filter { !it.isEquipped }
    }
    
    val manuals = remember(allManuals) {
        allManuals.filter { !it.isLearned }
    }
    
    var selectedRarities by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedTypes by remember { mutableStateOf<Set<String>>(emptySet()) }
    
    // 使用游戏中的品阶名称：凡品、灵品、宝品、玄品、地品、天品
    val rarityOptions = listOf(
        1 to "凡品",
        2 to "灵品",
        3 to "宝品",
        4 to "玄品",
        5 to "地品",
        6 to "天品"
    )
    
    // 使用仓库的分类：全部、装备、丹药、功法、草药、种子、材料
    val typeOptions = listOf(
        "ALL" to "全部",
        "EQUIPMENT" to "装备",
        "PILL" to "丹药",
        "MANUAL" to "功法",
        "HERB" to "草药",
        "SEED" to "种子",
        "MATERIAL" to "材料"
    )
    
    // 计算可出售的物品
    val finalTypes = remember(selectedTypes) {
        if (selectedTypes.contains("ALL")) {
            setOf("EQUIPMENT", "PILL", "MANUAL", "HERB", "SEED", "MATERIAL")
        } else {
            selectedTypes
        }
    }
    
    val sellableEquipment = remember(equipment, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("EQUIPMENT")) {
            equipment.filter { selectedRarities.contains(it.rarity) }
        } else emptyList()
    }
    
    val sellableManuals = remember(manuals, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("MANUAL")) {
            manuals.filter { selectedRarities.contains(it.rarity) }
        } else emptyList()
    }
    
    val sellablePills = remember(pills, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("PILL")) {
            pills.filter { selectedRarities.contains(it.rarity) }
        } else emptyList()
    }
    
    val sellableMaterials = remember(materials, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("MATERIAL")) {
            materials.filter { selectedRarities.contains(it.rarity) }
        } else emptyList()
    }
    
    val sellableHerbs = remember(herbs, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("HERB")) {
            herbs.filter { selectedRarities.contains(it.rarity) }
        } else emptyList()
    }
    
    val sellableSeeds = remember(seeds, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("SEED")) {
            seeds.filter { selectedRarities.contains(it.rarity) }
        } else emptyList()
    }
    
    val totalItems = sellableEquipment.size + sellableManuals.size + sellablePills.size +
            sellableMaterials.size + sellableHerbs.size + sellableSeeds.size
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "一键出售",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                
                // 品阶选择 - 4列显示，支持多选
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "选择品阶（可多选）：",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                    // 分4列显示
                    rarityOptions.chunked(4).forEach { rowOptions ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowOptions.forEach { (rarity, name) ->
                                val isSelected = selectedRarities.contains(rarity)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) Color.Black else Color(0xFFF0F0F0))
                                        .clickable {
                                            selectedRarities = if (isSelected) {
                                                selectedRarities - rarity
                                            } else {
                                                selectedRarities + rarity
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = name,
                                        fontSize = 11.sp,
                                        color = if (isSelected) Color.White else Color.Black
                                    )
                                }
                            }
                            // 补齐4列
                            repeat(4 - rowOptions.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                
                // 类型选择 - 4列显示
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "选择物品类型（可多选）：",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                    // 分4列显示
                    typeOptions.chunked(4).forEach { rowOptions ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowOptions.forEach { (type, name) ->
                                val isSelected = selectedTypes.contains(type)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) Color.Black else Color(0xFFF0F0F0))
                                        .clickable { 
                                            selectedTypes = if (isSelected) {
                                                selectedTypes - type
                                            } else {
                                                selectedTypes + type
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = name,
                                        fontSize = 11.sp,
                                        color = if (isSelected) Color.White else Color.Black
                                    )
                                }
                            }
                            // 补齐4列
                            repeat(4 - rowOptions.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                
                // 可出售物品列表
                if (totalItems > 0) {
                    Text(
                        text = "可出售物品（共${totalItems}件）：",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 装备
                        if (sellableEquipment.isNotEmpty()) {
                            item {
                                Text(
                                    text = "装备 (${sellableEquipment.size}件)",
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            items(sellableEquipment.chunked(4)) { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowItems.forEach { item ->
                                        BulkSellItemCard(
                                            name = item.name,
                                            rarity = item.rarity,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(4 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        
                        // 功法
                        if (sellableManuals.isNotEmpty()) {
                            item {
                                Text(
                                    text = "功法 (${sellableManuals.size}件)",
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            items(sellableManuals.chunked(4)) { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowItems.forEach { item ->
                                        BulkSellItemCard(
                                            name = item.name,
                                            rarity = item.rarity,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(4 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        
                        // 丹药
                        if (sellablePills.isNotEmpty()) {
                            item {
                                Text(
                                    text = "丹药 (${sellablePills.size}件)",
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            items(sellablePills.chunked(4)) { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowItems.forEach { item ->
                                        BulkSellItemCard(
                                            name = item.name,
                                            rarity = item.rarity,
                                            quantity = item.quantity,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(4 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        
                        // 材料
                        if (sellableMaterials.isNotEmpty()) {
                            item {
                                Text(
                                    text = "材料 (${sellableMaterials.size}件)",
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            items(sellableMaterials.chunked(4)) { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowItems.forEach { item ->
                                        BulkSellItemCard(
                                            name = item.name,
                                            rarity = item.rarity,
                                            quantity = item.quantity,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(4 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        
                        // 草药
                        if (sellableHerbs.isNotEmpty()) {
                            item {
                                Text(
                                    text = "草药 (${sellableHerbs.size}件)",
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            items(sellableHerbs.chunked(4)) { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowItems.forEach { item ->
                                        BulkSellItemCard(
                                            name = item.name,
                                            rarity = item.rarity,
                                            quantity = item.quantity,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(4 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        
                        // 种子
                        if (sellableSeeds.isNotEmpty()) {
                            item {
                                Text(
                                    text = "种子 (${sellableSeeds.size}件)",
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            items(sellableSeeds.chunked(4)) { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowItems.forEach { item ->
                                        BulkSellItemCard(
                                            name = item.name,
                                            rarity = item.rarity,
                                            quantity = item.quantity,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(4 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                } else if (selectedRarities.isNotEmpty() && selectedTypes.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "没有符合条件的物品",
                            fontSize = 12.sp,
                            color = Color(0xFF999999)
                        )
                    }
                }
                
                // 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFE0E0E0))
                            .clickable { onDismiss() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "取消",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (totalItems > 0) Color(0xFFE74C3C) else Color(0xFFCCCCCC)
                            )
                            .then(
                                if (totalItems > 0) {
                                    Modifier.clickable {
                                        viewModel.bulkSellItems(selectedRarities, finalTypes)
                                        onDismiss()
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "确认出售",
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BulkSellItemCard(
    name: String,
    rarity: Int,
    quantity: Int = 1,
    modifier: Modifier = Modifier
) {
    val rarityColor = when (rarity) {
        1 -> Color(0xFF95A5A6)
        2 -> Color(0xFF27AE60)
        3 -> Color(0xFF3498DB)
        4 -> Color(0xFF9B59B6)
        5 -> Color(0xFFF39C12)
        6 -> Color(0xFFE74C3C)
        else -> Color(0xFF95A5A6)
    }
    
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .border(2.dp, rarityColor, RoundedCornerShape(6.dp))
            .background(Color.White)
            .padding(4.dp)
    ) {
        Text(
            text = name,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = rarityColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 2.dp)
        )

        if (quantity > 1) {
            Text(
                text = "x${quantity}",
                fontSize = 8.sp,
                color = Color(0xFF666666),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
            )
        }
    }
}

@Composable
private fun WarehouseFilterButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color.Black else Color.White)
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else Color.Black
        )
    }
}

@Composable
private fun EmptyWarehouseMessage() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "暂无物品",
            fontSize = 12.sp,
            color = Color(0xFF999999)
        )
    }
}

@Composable
private fun WarehouseEquipmentCard(
    equipment: Equipment,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onViewDetail: () -> Unit = {}
) {
    val rarityColor = when (equipment.rarity) {
        1 -> Color(0xFF95A5A6)
        2 -> Color(0xFF27AE60)
        3 -> Color(0xFF3498DB)
        4 -> Color(0xFF9B59B6)
        5 -> Color(0xFFF39C12)
        6 -> Color(0xFFE74C3C)
        else -> Color(0xFF95A5A6)
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (isSelected) 3.dp else 2.dp,
                color = if (isSelected) Color(0xFFFFD700) else rarityColor,
                shape = RoundedCornerShape(6.dp)
            )
            .background(if (isSelected) Color(0xFFFFF8E1) else Color.White)
            .clickable { onSelect() }
            .padding(4.dp)
    ) {
        Text(
            text = equipment.name,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = rarityColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 2.dp)
        )

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFFFFD700))
                    .clickable { onViewDetail() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "查看",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun WarehouseManualCard(
    manual: Manual,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onViewDetail: () -> Unit = {}
) {
    val rarityColor = when (manual.rarity) {
        1 -> Color(0xFF95A5A6)
        2 -> Color(0xFF27AE60)
        3 -> Color(0xFF3498DB)
        4 -> Color(0xFF9B59B6)
        5 -> Color(0xFFF39C12)
        6 -> Color(0xFFE74C3C)
        else -> Color(0xFF95A5A6)
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (isSelected) 3.dp else 2.dp,
                color = if (isSelected) Color(0xFFFFD700) else rarityColor,
                shape = RoundedCornerShape(6.dp)
            )
            .background(if (isSelected) Color(0xFFFFF8E1) else Color.White)
            .clickable { onSelect() }
            .padding(4.dp)
    ) {
        Text(
            text = manual.name,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = rarityColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 2.dp)
        )

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFFFFD700))
                    .clickable { onViewDetail() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "查看",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun WarehousePillCard(
    pill: Pill,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onViewDetail: () -> Unit = {}
) {
    val rarityColor = when (pill.rarity) {
        1 -> Color(0xFF95A5A6)
        2 -> Color(0xFF27AE60)
        3 -> Color(0xFF3498DB)
        4 -> Color(0xFF9B59B6)
        5 -> Color(0xFFF39C12)
        6 -> Color(0xFFE74C3C)
        else -> Color(0xFF95A5A6)
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (isSelected) 3.dp else 2.dp,
                color = if (isSelected) Color(0xFFFFD700) else rarityColor,
                shape = RoundedCornerShape(6.dp)
            )
            .background(if (isSelected) Color(0xFFFFF8E1) else Color.White)
            .clickable { onSelect() }
            .padding(4.dp)
    ) {
        Text(
            text = pill.name,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = rarityColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 2.dp)
        )

        if (pill.quantity > 1) {
            Text(
                text = "x${pill.quantity}",
                fontSize = 8.sp,
                color = Color(0xFF666666),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
            )
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFFFFD700))
                    .clickable { onViewDetail() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "查看",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun WarehouseMaterialCard(
    material: Material,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onViewDetail: () -> Unit = {}
) {
    val rarityColor = when (material.rarity) {
        1 -> Color(0xFF95A5A6)
        2 -> Color(0xFF27AE60)
        3 -> Color(0xFF3498DB)
        4 -> Color(0xFF9B59B6)
        5 -> Color(0xFFF39C12)
        6 -> Color(0xFFE74C3C)
        else -> Color(0xFF95A5A6)
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (isSelected) 3.dp else 2.dp,
                color = if (isSelected) Color(0xFFFFD700) else rarityColor,
                shape = RoundedCornerShape(6.dp)
            )
            .background(if (isSelected) Color(0xFFFFF8E1) else Color.White)
            .clickable { onSelect() }
            .padding(4.dp)
    ) {
        Text(
            text = material.name,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = rarityColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 2.dp)
        )

        if (material.quantity > 1) {
            Text(
                text = "x${material.quantity}",
                fontSize = 8.sp,
                color = Color(0xFF666666),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
            )
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFFFFD700))
                    .clickable { onViewDetail() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "查看",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun WarehouseHerbCard(
    herb: Herb,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onViewDetail: () -> Unit = {}
) {
    val rarityColor = when (herb.rarity) {
        1 -> Color(0xFF95A5A6)
        2 -> Color(0xFF27AE60)
        3 -> Color(0xFF3498DB)
        4 -> Color(0xFF9B59B6)
        5 -> Color(0xFFF39C12)
        6 -> Color(0xFFE74C3C)
        else -> Color(0xFF95A5A6)
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (isSelected) 3.dp else 2.dp,
                color = if (isSelected) Color(0xFFFFD700) else rarityColor,
                shape = RoundedCornerShape(6.dp)
            )
            .background(if (isSelected) Color(0xFFFFF8E1) else Color.White)
            .clickable { onSelect() }
            .padding(4.dp)
    ) {
        Text(
            text = herb.name,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = rarityColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 2.dp)
        )

        if (herb.quantity > 1) {
            Text(
                text = "x${herb.quantity}",
                fontSize = 8.sp,
                color = Color(0xFF666666),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
            )
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFFFFD700))
                    .clickable { onViewDetail() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "查看",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun WarehouseSeedCard(
    seed: com.xianxia.sect.core.model.Seed,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onViewDetail: () -> Unit = {}
) {
    val rarityColor = when (seed.rarity) {
        1 -> Color(0xFF95A5A6)
        2 -> Color(0xFF27AE60)
        3 -> Color(0xFF3498DB)
        4 -> Color(0xFF9B59B6)
        5 -> Color(0xFFF39C12)
        6 -> Color(0xFFE74C3C)
        else -> Color(0xFF95A5A6)
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (isSelected) 3.dp else 2.dp,
                color = if (isSelected) Color(0xFFFFD700) else rarityColor,
                shape = RoundedCornerShape(6.dp)
            )
            .background(if (isSelected) Color(0xFFFFF8E1) else Color.White)
            .clickable { onSelect() }
            .padding(4.dp)
    ) {
        Text(
            text = seed.name,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = rarityColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 2.dp)
        )

        if (seed.quantity > 1) {
            Text(
                text = "x${seed.quantity}",
                fontSize = 8.sp,
                color = Color(0xFF666666),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
            )
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFFFFD700))
                    .clickable { onViewDetail() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "查看",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun SettingsTab(
    viewModel: GameViewModel,
    onLogout: () -> Unit
) {
    val timeSpeed by viewModel.timeSpeed.collectAsState()
    val gameData by viewModel.gameData.collectAsState()
    
    var showSaveSlotDialog by remember { mutableStateOf(false) }
    var showRestartConfirmDialog by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(16.dp)
    ) {
        Text(
            text = "设置",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "时间流速",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                val isPaused by viewModel.isPaused.collectAsState()
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isPaused) Color.Black else Color.White)
                            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp))
                            .clickable { viewModel.togglePause() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂停",
                            fontSize = 12.sp,
                            color = if (isPaused) Color.White else Color.Black
                        )
                    }
                    
                    listOf(1, 2).forEach { speed ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (timeSpeed == speed && !isPaused) Color.Black else Color.White)
                                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp))
                                .clickable { viewModel.setTimeSpeed(speed) }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${speed}倍速",
                                fontSize = 12.sp,
                                color = if (timeSpeed == speed && !isPaused) Color.White else Color.Black
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "自动存档",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(3, 6, 12).forEach { interval ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (gameData.autoSaveIntervalMonths == interval) Color.Black else Color.White)
                                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp))
                                .clickable { viewModel.setAutoSaveIntervalMonths(interval) }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${interval}月",
                                fontSize = 12.sp,
                                color = if (gameData.autoSaveIntervalMonths == interval) Color.White else Color.Black
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "月俸设置",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp))
                        .clickable { viewModel.openSalaryConfigDialog() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "配置月俸",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "存档管理",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White)
                            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp))
                            .clickable { showSaveSlotDialog = true }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "查看存档",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "其他",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFE74C3C))
                        .clickable { showRestartConfirmDialog = true }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "重新开始",
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp))
                        .clickable { onLogout() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "退出游戏",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }
        }
    }

    if (showSaveSlotDialog) {
        SaveSlotDialog(
            viewModel = viewModel,
            onDismiss = { showSaveSlotDialog = false }
        )
    }

    if (showRestartConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRestartConfirmDialog = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = "确认重新开始",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            },
            text = {
                Text(
                    text = "确定要重新开始游戏吗？当前游戏进度将会丢失！",
                    fontSize = 12.sp,
                    color = Color(0xFF666666)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestartConfirmDialog = false
                        viewModel.restartGame()
                    },
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE74C3C))
                ) {
                    Text("确认", fontSize = 12.sp)
                }
            },
            dismissButton = {
                GameButton(
                    text = "取消",
                    onClick = { showRestartConfirmDialog = false }
                )
            }
        )
    }
}

@Composable
private fun SaveSlotDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val saveSlots by viewModel.saveSlots.collectAsState()
    var selectedSlot by remember { mutableStateOf<Int?>(null) }
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = Color.White
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "存档信息",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )

                    GameButton(
                        text = "关闭",
                        onClick = onDismiss
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(saveSlots) { slot ->
                        SaveSlotCard(
                            slot = slot,
                            isSelected = selectedSlot == slot.slot,
                            onClick = { selectedSlot = slot.slot }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedSlot != null) Color.Black else Color(0xFFCCCCCC))
                            .then(
                                if (selectedSlot != null) {
                                    Modifier.clickable {
                                        viewModel.saveGame(selectedSlot.toString())
                                        onDismiss()
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "保存",
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (selectedSlot != null && saveSlots.find { it.slot == selectedSlot }?.isEmpty == false) {
                                    Color.Black
                                } else {
                                    Color(0xFFCCCCCC)
                                }
                            )
                            .then(
                                if (selectedSlot != null && saveSlots.find { it.slot == selectedSlot }?.isEmpty == false) {
                                    Modifier.clickable {
                                        viewModel.loadGame(saveSlots.find { it.slot == selectedSlot }!!)
                                        onDismiss()
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "读取",
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveSlotCard(
    slot: SaveSlot,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) Color(0xFFF0F0F0) else Color.White)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) Color.Black else Color(0xFFE0E0E0),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "槽位 ${slot.slot}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Text(
                    text = if (slot.isEmpty) "空" else slot.saveTime,
                    fontSize = 12.sp,
                    color = Color(0xFF666666)
                )
            }
            
            if (!slot.isEmpty) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = slot.sectName,
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                    Text(
                        text = slot.displayTime,
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "弟子: ${slot.discipleCount}",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = "灵石: ${slot.spiritStones}",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        }
    }
}

// ==================== 游戏主界面三个板块组件 ====================

/**
 * 第一板块：宗门信息板块
 * 包含：宗门名称、弟子数量、灵石数量、游戏内时间
 */
@Composable
private fun SectInfoPanel(
    gameData: GameData?,
    discipleCount: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = gameData?.sectName ?: "青云宗",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            letterSpacing = 0.sp
        )

        Text(
            text = "${gameData?.gameYear ?: 1}年${gameData?.gameMonth ?: 1}月${gameData?.gameDay ?: 1}日",
            fontSize = 12.sp,
            color = Color(0xFF666666),
            fontWeight = FontWeight.Normal
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            InfoItem(
                label = "弟子",
                value = "$discipleCount"
            )

            InfoItem(
                label = "灵石",
                value = "${gameData?.spiritStones ?: 0}"
            )
        }
    }
}

/**
 * 信息项组件 - 纯文字显示
 */
@Composable
private fun InfoItem(
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.Black
        )
    }
}

/**
 * 第二板块：快捷操作板块
 * 包含：宗门管理、世界地图、招募弟子、秘境探索
 */
@Composable
private fun QuickActionPanel(
    viewModel: GameViewModel
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "快捷操作",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionButton(
                        text = "宗门管理",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openSectManagementDialog() }
                    )
                    QuickActionButton(
                        text = "世界地图",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openWorldMapDialog() }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionButton(
                        text = "招募弟子",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openRecruitDialog() }
                    )
                    QuickActionButton(
                        text = "云游商人",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openMerchantDialog() }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionButton(
                        text = "宗门外交",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openDiplomacyDialog() }
                    )
                    QuickActionButton(
                        text = "秘境探索",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openSecretRealmDialog() }
                    )
                }
            }
        }
    }
}

/**
 * 快捷操作按钮 - 纯文字按钮
 */
@Composable
private fun QuickActionButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = Color.Black,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 第三板块：宗门消息板块
 * 显示宗门发生的事件
 */
@Composable
private fun SectMessagePanel(
    events: List<GameEvent>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(12.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "宗门消息",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (events.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无消息",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(events.take(20)) { event ->
                        EventMessageItem(event = event)
                        if (event != events.take(20).last()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(top = 8.dp),
                                color = Color(0xFFEEEEEE),
                                thickness = 0.5.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 事件消息项 - 纯文字显示
 */
@Composable
private fun EventMessageItem(event: GameEvent) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = event.message,
                fontSize = 12.sp,
                color = Color.Black,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = event.displayTime,
                fontSize = 12.sp,
                color = Color.Black
            )
        }
    }
}

@Composable
private fun CommonDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(Color(0xFFF5F5F5)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                content()
            }
        },
        confirmButton = {}
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WorldMapDialog(
    worldSects: List<WorldSect>,
    supportTeams: List<SupportTeam> = emptyList(),
    scoutTeams: List<ExplorationTeam> = emptyList(),
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedSect by remember { mutableStateOf<WorldSect?>(null) }
    var showSectDetail by remember { mutableStateOf(false) }
    
    val markers = remember(worldSects) {
        worldSects.map { sect ->
            com.xianxia.sect.core.model.MapMarker(
                id = sect.id,
                name = sect.name,
                type = com.xianxia.sect.core.model.MapMarkerType.SECT,
                x = (sect.x / 4000f).coerceIn(0f, 1f),
                y = (sect.y / 3500f).coerceIn(0f, 1f),
                level = sect.level,
                ownerId = if (sect.isPlayerSect) "player" else sect.id,
                isCapital = sect.isPlayerSect,
                description = sect.levelName
            )
        }
    }
    
    val paths = remember(worldSects) {
        val sectIds = worldSects.map { it.id }.toSet()
        val pathSet = mutableSetOf<Pair<String, String>>()
        worldSects.forEach { sect ->
            (sect.connectedSectIds ?: emptyList()).forEach { connectedId ->
                if (connectedId in sectIds) {
                    val (id1, id2) = if (sect.id < connectedId) {
                        sect.id to connectedId
                    } else {
                        connectedId to sect.id
                    }
                    pathSet.add(id1 to id2)
                }
            }
        }
        pathSet.map { (from, to) -> com.xianxia.sect.core.model.MapPath(from, to) }
    }
    
    val window = androidx.compose.ui.platform.LocalContext.current.let {
        (it as? android.app.Activity)?.window
    }
    
    androidx.compose.runtime.LaunchedEffect(Unit) {
        window?.let { w ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).let { controller ->
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
    
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
        }
    }
    
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopStart,
        offset = androidx.compose.ui.unit.IntOffset(0, 0),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            clippingEnabled = false
        )
    ) {
        Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .background(Color(0xFFA8B878))
        ) {
            WorldMapScreen(
                markers = markers,
                paths = paths,
                supportTeams = supportTeams,
                scoutTeams = scoutTeams,
                onBack = onDismiss,
                onMarkerClick = { marker ->
                    val sect = worldSects.find { it.id == marker.id }
                    if (sect != null) {
                        selectedSect = sect
                        showSectDetail = true
                    }
                },
                onFunctionClick = { function ->
                }
            )
        }
    }
    
    if (showSectDetail && selectedSect != null) {
        WorldMapSectDetailDialog(
            sect = selectedSect!!,
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { 
                showSectDetail = false
                selectedSect = null
            }
        )
    }
}

@Composable
private fun WorldMapSectDetailDialog(
    sect: WorldSect,
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val currentYear = gameData?.gameYear ?: 1
    val isAlly = viewModel.isAlly(sect.id)
    val hasGiftedThisYear = sect.lastGiftYear == currentYear
    var showGiftedMessage by remember { mutableStateOf(false) }
    
    val relationColor = when {
        sect.relation >= 70 -> Color(0xFF4CAF50)
        sect.relation >= 50 -> Color(0xFF8BC34A)
        sect.relation >= 30 -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = sect.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = sect.levelName,
                        fontSize = 10.sp,
                        color = Color(0xFF666666),
                        modifier = Modifier
                            .background(
                                Color(0xFFF5F5F5),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                    if (sect.isPlayerSect) {
                        Text(
                            text = "本宗",
                            fontSize = 10.sp,
                            color = Color(0xFFFF8C00),
                            modifier = Modifier
                                .background(
                                    Color(0xFFFFF3E0),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    } else if (isAlly) {
                        Text(
                            text = "盟友",
                            fontSize = 10.sp,
                            color = Color(0xFF4CAF50),
                            modifier = Modifier
                                .background(
                                    Color(0xFFE8F5E9),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(Color(0xFFF5F5F5)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!sect.isPlayerSect) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "好感度",
                            fontSize = 12.sp,
                            color = Color(0xFF666666)
                        )
                        Text(
                            text = "${sect.relation}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = relationColor
                        )
                    }
                }
                
                if (!sect.isPlayerSect) {
                    HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)
                    
                    Text(
                        text = "弟子分布",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF333333)
                    )
                    
                    val realmNames = listOf("仙人", "渡劫", "大乘", "合体", "炼虚", "化神", "元婴", "金丹", "筑基", "炼气")
                    val scoutInfo = sect.scoutInfo
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        realmNames.take(5).forEachIndexed { index, realmName ->
                            val realmIndex = index
                            val count = scoutInfo?.disciples?.get(realmIndex) ?: 0
                            val displayText = if (scoutInfo != null && scoutInfo.disciples.containsKey(realmIndex)) {
                                if (count > 0) "$count" else "0"
                            } else {
                                "?"
                            }
                            val textColor = if (scoutInfo != null && scoutInfo.disciples.containsKey(realmIndex)) {
                                if (count > 0) Color(0xFF4CAF50) else Color(0xFF999999)
                            } else {
                                Color(0xFFFF9800)
                            }
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = realmName,
                                    fontSize = 10.sp,
                                    color = Color(0xFF666666)
                                )
                                Text(
                                    text = displayText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textColor
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        realmNames.drop(5).forEachIndexed { index, realmName ->
                            val realmIndex = index + 5
                            val count = scoutInfo?.disciples?.get(realmIndex) ?: 0
                            val displayText = if (scoutInfo != null && scoutInfo.disciples.containsKey(realmIndex)) {
                                if (count > 0) "$count" else "0"
                            } else {
                                "?"
                            }
                            val textColor = if (scoutInfo != null && scoutInfo.disciples.containsKey(realmIndex)) {
                                if (count > 0) Color(0xFF4CAF50) else Color(0xFF999999)
                            } else {
                                Color(0xFFFF9800)
                            }
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = realmName,
                                    fontSize = 10.sp,
                                    color = Color(0xFF666666)
                                )
                                Text(
                                    text = displayText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textColor
                                )
                            }
                        }
                    }
                }
                
                if (!sect.isPlayerSect) {
                    HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)
                    
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            GameButton(
                                text = "探查",
                                onClick = {
                                    viewModel.openScoutDialog(sect.id)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            
                            GameButton(
                                text = if (hasGiftedThisYear) "已送礼" else "送礼",
                                onClick = {
                                    if (hasGiftedThisYear) {
                                        showGiftedMessage = true
                                    } else {
                                        viewModel.openGiftDialog(sect.id)
                                        onDismiss()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            
                            GameButton(
                                text = if (isAlly) "盟约" else "结盟",
                                onClick = {
                                    viewModel.formAlliance(sect.id)
                                    onDismiss()
                                },
                                enabled = sect.relation >= 90 || isAlly,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            GameButton(
                                text = "交易",
                                onClick = {
                                    viewModel.openSectTradeDialog(sect.id)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            
                            Spacer(modifier = Modifier.weight(1f))
                            
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                } else {
                    HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFF8E1), RoundedCornerShape(6.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "这是您的宗门",
                            fontSize = 12.sp,
                            color = Color(0xFF8B7355)
                        )
                    }
                }
            }
        },
        confirmButton = {}
    )
    
    if (showGiftedMessage) {
        GiftedMessageToast(
            message = "今年已送过礼品等明年再来吧",
            onDismiss = { showGiftedMessage = false }
        )
    }
}

@Composable
fun DiplomacyDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val worldSects = gameData?.worldMapSects?.filter { !it.isPlayerSect }?.sortedByDescending { it.relation } ?: emptyList()
    val currentYear = gameData?.gameYear ?: 1
    
    var showGiftedMessage by remember { mutableStateOf(false) }
    
    Box(modifier = Modifier.fillMaxSize()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color.White,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "宗门外交",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                            .background(Color(0xFFF5F5F5)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            fontSize = 16.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
            },
            text = {
                if (worldSects.isEmpty()) {
                    Text(
                        text = "暂无其他宗门",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(worldSects.size) { index ->
                            val sect = worldSects[index]
                            DiplomacySectCard(
                                sect = sect,
                                currentYear = currentYear,
                                isAlly = viewModel.isAlly(sect.id),
                                onGift = {
                                    viewModel.openGiftDialog(sect.id)
                                },
                                onFormAlliance = {
                                    viewModel.formAlliance(sect.id)
                                },
                                onTrade = {
                                    viewModel.openSectTradeDialog(sect.id)
                                },
                                onShowGiftedMessage = {
                                    showGiftedMessage = true
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {}
        )
        
        if (showGiftedMessage) {
            GiftedMessageToast(
                message = "今年已送过礼品等明年再来吧",
                onDismiss = { showGiftedMessage = false }
            )
        }
    }
}

@Composable
private fun DiplomacySectCard(
    sect: WorldSect,
    currentYear: Int,
    isAlly: Boolean,
    onGift: () -> Unit,
    onFormAlliance: () -> Unit,
    onTrade: () -> Unit,
    onShowGiftedMessage: () -> Unit
) {
    val relationColor = when {
        sect.relation >= 70 -> Color(0xFF4CAF50)
        sect.relation >= 50 -> Color(0xFF8BC34A)
        sect.relation >= 30 -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
    
    val hasGiftedThisYear = sect.lastGiftYear == currentYear
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = sect.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        if (isAlly) {
                            Text(
                                text = "盟友",
                                fontSize = 10.sp,
                                color = Color(0xFF4CAF50),
                                modifier = Modifier
                                    .background(
                                        Color(0xFFE8F5E9),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Text(
                        text = sect.levelName,
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                }
                
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "好感度",
                        fontSize = 10.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = "${sect.relation}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = relationColor
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GameButton(
                    text = "送礼",
                    onClick = {
                        if (hasGiftedThisYear) {
                            onShowGiftedMessage()
                        } else {
                            onGift()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                
                GameButton(
                    text = if (isAlly) "盟约" else "结盟",
                    onClick = onFormAlliance,
                    enabled = sect.relation >= 90 || isAlly,
                    modifier = Modifier.weight(1f)
                )
                
                GameButton(
                    text = "交易",
                    onClick = onTrade,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun GiftedMessageToast(
    message: String,
    onDismiss: () -> Unit
) {
    var offsetY by remember { mutableStateOf(0f) }
    var alpha by remember { mutableStateOf(1f) }
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        repeat(6) {
            offsetY -= 3f
            alpha -= 1f / 6f
            kotlinx.coroutines.delay(150)
        }
        onDismiss()
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset(y = offsetY.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF666666).copy(alpha = alpha.coerceIn(0f, 1f)),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SectTradeDialog(
    sect: WorldSect?,
    gameData: GameData?,
    tradeItems: List<MerchantItem>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedItem by remember { mutableStateOf<MerchantItem?>(null) }
    var buyQuantity by remember { mutableStateOf(1) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var showRelationWarning by remember { mutableStateOf(false) }
    
    val relation = sect?.relation ?: 0
    val isAlly = sect?.let { viewModel.isAlly(it.id) } ?: false
    
    val maxAllowedRarity = when {
        relation >= 90 -> 6
        relation >= 80 -> 5
        relation >= 70 -> 4
        relation >= 60 -> 3
        relation >= 50 -> 2
        relation >= 40 -> 1
        else -> 0
    }
    
    val priceMultiplier = when {
        isAlly -> (0.9 * (1.0 - maxOf(0, relation - 70) * 0.01)).coerceAtLeast(0.3)  // 盟友额外10%优惠，最低30%
        relation >= 70 -> (1.0 - (relation - 70) * 0.01).coerceAtLeast(0.3)  // 最低30%
        else -> 1.0
    }
    
    val relationColor = when {
        relation >= 70 -> Color(0xFF4CAF50)
        relation >= 50 -> Color(0xFF8BC34A)
        relation >= 40 -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
    
    val canTrade = relation >= 40

    LaunchedEffect(showRelationWarning) {
        if (showRelationWarning) {
            delay(1000)
            showRelationWarning = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.CardBackground
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${sect?.name ?: "宗门"}交易",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "好感度:",
                                    fontSize = 11.sp,
                                    color = GameColors.TextSecondary
                                )
                                Text(
                                    text = "$relation",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = relationColor
                                )
                                if (isAlly) {
                                    Text(
                                        text = "(盟友)",
                                        fontSize = 10.sp,
                                        color = Color(0xFF4CAF50),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "(${String.format("%.1f%%", (1 - priceMultiplier) * 100)}折扣)",
                                        fontSize = 10.sp,
                                        color = Color(0xFF4CAF50)
                                    )
                                } else if (relation >= 70) {
                                    Text(
                                        text = "(${String.format("%.1f%%", (1 - priceMultiplier) * 100)}折扣)",
                                        fontSize = 10.sp,
                                        color = Color(0xFF4CAF50)
                                    )
                                } else if (relation < 40) {
                                    Text(
                                        text = "(无法交易)",
                                        fontSize = 10.sp,
                                        color = Color(0xFFF44336)
                                    )
                                }
                            }
                            Text(
                                text = "灵石: ${gameData?.spiritStones ?: 0}",
                                fontSize = 11.sp,
                                color = GameColors.TextSecondary
                            )
                        }

                        GameButton(
                            text = "关闭",
                            onClick = onDismiss
                        )
                    }

                    if (tradeItems.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "暂无商品\n请稍后再来",
                                fontSize = 12.sp,
                                color = GameColors.TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(68.dp),
                            modifier = Modifier
                                .weight(1f)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(tradeItems.size) { index ->
                                val item = tradeItems[index]
                                val canBuyThisItem = canTrade && item.rarity <= maxAllowedRarity
                                val rarityColor = when (item.rarity) {
                                    1 -> GameColors.RarityCommon
                                    2 -> GameColors.RaritySpirit
                                    3 -> GameColors.RarityTreasure
                                    4 -> GameColors.RarityMystic
                                    5 -> GameColors.RarityEarth
                                    6 -> GameColors.RarityHeaven
                                    else -> GameColors.RarityCommon
                                }
                                
                                val adjustedPrice = (item.price * priceMultiplier).toInt()
                                
                                Box(
                                    modifier = Modifier.size(68.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(68.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (canBuyThisItem) Color.White else Color(0xFFE0E0E0))
                                            .border(
                                                width = if (selectedItem?.id == item.id) 3.dp else 2.dp,
                                                color = if (!canBuyThisItem) Color(0xFFBDBDBD) 
                                                    else if (selectedItem?.id == item.id) Color(0xFFFFD700) 
                                                    else rarityColor,
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                            .clickable {
                                                if (!canBuyThisItem) {
                                                    showRelationWarning = true
                                                } else {
                                                    if (selectedItem?.id == item.id) {
                                                        selectedItem = null
                                                        buyQuantity = 1
                                                    } else {
                                                        selectedItem = item
                                                        buyQuantity = 1
                                                    }
                                                }
                                            }
                                            .padding(4.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = item.name,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (canBuyThisItem) GameColors.TextPrimary else Color(0xFF9E9E9E),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.fillMaxWidth(),
                                                textAlign = TextAlign.Center
                                            )
                                            
                                            Spacer(modifier = Modifier.height(2.dp))
                                            
                                            Text(
                                                text = "${adjustedPrice}灵石",
                                                fontSize = 9.sp,
                                                color = if (canBuyThisItem) GameColors.GoldDark else Color(0xFF9E9E9E),
                                                maxLines = 1
                                            )
                                        }
                                        
                                        Text(
                                            text = "x${item.quantity}",
                                            fontSize = 9.sp,
                                            color = if (canBuyThisItem) GameColors.TextSecondary else Color(0xFF9E9E9E),
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(2.dp)
                                        )
                                    }
                                    
                                    if (selectedItem?.id == item.id && canBuyThisItem) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(x = 6.dp, y = (-6).dp)
                                                .size(24.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFFFFD700))
                                                .clickable {
                                                    selectedItem = item
                                                    showDetailDialog = true
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "查看",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White,
                        tonalElevation = 4.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (selectedItem != null) {
                                val item = selectedItem!!
                                val adjustedPrice = (item.price * priceMultiplier).toInt()
                                val totalPrice = adjustedPrice * buyQuantity
                                val canAfford = (gameData?.spiritStones ?: 0) >= totalPrice
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = item.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GameColors.TextPrimary
                                        )
                                        Text(
                                            text = "单价: $adjustedPrice 灵石",
                                            fontSize = 10.sp,
                                            color = GameColors.TextSecondary
                                        )
                                    }
                                    
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "购买数量:",
                                            fontSize = 11.sp,
                                            color = GameColors.TextSecondary
                                        )
                                        
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(GameColors.Background)
                                                .clickable { buyQuantity = (buyQuantity - 1).coerceAtLeast(1) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("-", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary)
                                        }
                                        
                                        Text(
                                            text = "$buyQuantity",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GameColors.TextPrimary,
                                            modifier = Modifier.widthIn(min = 24.dp),
                                            textAlign = TextAlign.Center
                                        )
                                        
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(GameColors.Background)
                                                .clickable { buyQuantity = (buyQuantity + 1).coerceAtMost(item.quantity) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary)
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "总价: $totalPrice 灵石",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (canAfford) GameColors.GoldDark else Color.Red
                                    )
                                    
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        GameButton(
                                            text = "取消",
                                            onClick = {
                                                selectedItem = null
                                                buyQuantity = 1
                                            }
                                        )
                                        
                                        GameButton(
                                            text = "确认购买",
                                            onClick = {
                                                viewModel.buyFromSectTrade(item.id, buyQuantity)
                                                selectedItem = null
                                                buyQuantity = 1
                                            },
                                            enabled = canAfford && buyQuantity > 0
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "请选择要购买的商品",
                                    fontSize = 12.sp,
                                    color = GameColors.TextSecondary
                                )
                            }
                        }
                    }
                }
                
                if (showRelationWarning) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .animateContentSize()
                                .alpha(if (showRelationWarning) 1f else 0f),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xCC000000)
                        ) {
                            Text(
                                text = "好感度太低无法交易",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDetailDialog && selectedItem != null) {
        val item = selectedItem!!
        val rarityColor = when (item.rarity) {
            1 -> GameColors.RarityCommon
            2 -> GameColors.RaritySpirit
            3 -> GameColors.RarityTreasure
            4 -> GameColors.RarityMystic
            5 -> GameColors.RarityEarth
            6 -> GameColors.RarityHeaven
            else -> GameColors.RarityCommon
        }
        val rarityName = when (item.rarity) {
            1 -> "凡品"
            2 -> "灵品"
            3 -> "宝品"
            4 -> "玄品"
            5 -> "地品"
            6 -> "天品"
            else -> "凡品"
        }
        val typeName = when (item.type) {
            "equipment" -> "装备"
            "manual" -> "功法"
            "pill" -> "丹药"
            "material" -> "材料"
            "herb" -> "灵草"
            "seed" -> "种子"
            else -> "物品"
        }

        AlertDialog(
            onDismissRequest = { showDetailDialog = false },
            containerColor = Color.White,
            title = {
                Column {
                    Text(
                        text = item.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = rarityColor
                    )
                    Text(
                        text = "$typeName · $rarityName",
                        fontSize = 11.sp,
                        color = GameColors.TextSecondary
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Divider(color = GameColors.Background, thickness = 1.dp)
                    
                    Text(
                        text = "道具效果",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    
                    Text(
                        text = getItemEffectTextForSectTrade(item),
                        fontSize = 11.sp,
                        color = GameColors.TextSecondary,
                        lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                GameButton(
                    text = "关闭",
                    onClick = { showDetailDialog = false }
                )
            }
        )
    }
}

private fun getItemEffectTextForSectTrade(item: MerchantItem): String {
    val template = EquipmentDatabase.getTemplateByName(item.name)
    if (template != null) {
        val effects = mutableListOf<String>()
        if (template.physicalAttack > 0) effects.add("物理攻击+${template.physicalAttack}")
        if (template.magicAttack > 0) effects.add("法术攻击+${template.magicAttack}")
        if (template.physicalDefense > 0) effects.add("物理防御+${template.physicalDefense}")
        if (template.magicDefense > 0) effects.add("法术防御+${template.magicDefense}")
        if (template.hp > 0) effects.add("生命值+${template.hp}")
        if (template.mp > 0) effects.add("灵力值+${template.mp}")
        if (template.speed > 0) effects.add("速度+${template.speed}")
        if (template.critChance > 0) effects.add("暴击率+${String.format("%.1f%%", template.critChance * 100)}")
        return if (effects.isNotEmpty()) effects.joinToString("，") else template.description
    }

    val manualTemplate = ManualDatabase.getByName(item.name)
    if (manualTemplate != null) {
        val effects = mutableListOf<String>()
        val stats = manualTemplate.stats
        if (stats.containsKey("cultivationSpeedPercent")) effects.add("修炼速度+${stats["cultivationSpeedPercent"]}%")
        if (stats.containsKey("breakthroughChance")) effects.add("突破概率+${stats["breakthroughChance"]}%")
        if (stats.containsKey("physicalAttack")) effects.add("物攻+${stats["physicalAttack"]}")
        if (stats.containsKey("magicAttack")) effects.add("法攻+${stats["magicAttack"]}")
        if (stats.containsKey("physicalDefense")) effects.add("物防+${stats["physicalDefense"]}")
        if (stats.containsKey("magicDefense")) effects.add("法防+${stats["magicDefense"]}")
        if (stats.containsKey("hp")) effects.add("生命+${stats["hp"]}")
        if (stats.containsKey("mp")) effects.add("灵力+${stats["mp"]}")
        if (stats.containsKey("speed")) effects.add("速度+${stats["speed"]}")
        if (stats.containsKey("critRate")) effects.add("暴击率+${stats["critRate"]}%")
        return if (effects.isNotEmpty()) effects.joinToString("，") else manualTemplate.description
    }

    val pillRecipe = PillRecipeDatabase.getRecipeByName(item.name)
    if (pillRecipe != null) {
        return pillRecipe.description
    }

    val herb = HerbDatabase.getHerbByName(item.name)
    if (herb != null) {
        return "用于炼制丹药的材料"
    }

    val seed = HerbDatabase.getSeedByName(item.name)
    if (seed != null) {
        return "种植后可获得${seed.yield}个${seed.name.removeSuffix("种")}"
    }

    val material = BeastMaterialDatabase.getMaterialByName(item.name)
    if (material != null) {
        return "用于炼制装备的材料"
    }

    return "神秘的物品，用途未知"
}

@Composable
fun TianshuHallDialog(
    gameData: GameData?,
    disciples: List<Disciple>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val elderSlots = gameData?.elderSlots
    val viceSectMasterId = elderSlots?.viceSectMaster
    val viceSectMaster = disciples.find { it.id == viceSectMasterId }
    
    var showViceSectMasterSelectDialog by remember { mutableStateOf(false) }
    var showSectAffairsDialog by remember { mutableStateOf(false) }
    var showSectPoliciesDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "天枢殿",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "副宗主",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White)
                            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp))
                            .clickable { showViceSectMasterSelectDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        if (viceSectMaster != null) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = viceSectMaster.name,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black,
                                    maxLines = 1
                                )
                                Text(
                                    text = viceSectMaster.realmName,
                                    fontSize = 9.sp,
                                    color = Color(0xFF666666),
                                    maxLines = 1
                                )
                            }
                        } else {
                            Text(
                                text = "+",
                                fontSize = 24.sp,
                                color = Color(0xFF999999)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White)
                            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                            .clickable {
                                if (viceSectMasterId != null) {
                                    viewModel.removeViceSectMaster()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "卸任",
                            fontSize = 10.sp,
                            color = Color.Black
                        )
                    }
                }
                
                HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GameButton(
                        text = "宗门事务",
                        onClick = { showSectAffairsDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                    
                    GameButton(
                        text = "宗门政策",
                        onClick = { showSectPoliciesDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {}
    )

    if (showViceSectMasterSelectDialog) {
        val eligibleDisciples = disciples.filter { 
            it.isAlive && 
            it.id != viceSectMasterId && 
            it.status == DiscipleStatus.IDLE && 
            it.realm <= 4 &&
            it.discipleType == "inner" &&
            it.realmLayer > 0 &&
            !isDiscipleInAnyPosition(it.id, elderSlots ?: ElderSlots())
        }
        
        AlertDialog(
            onDismissRequest = { showViceSectMasterSelectDialog = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = "选择副宗主",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier.height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(eligibleDisciples) { disciple ->
                        val spiritRootColor = try {
                            Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                        } catch (e: Exception) {
                            Color(0xFF666666)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White)
                                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp))
                                .clickable {
                                    viewModel.setViceSectMaster(disciple.id)
                                    showViceSectMasterSelectDialog = false
                                }
                                .padding(12.dp)
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = disciple.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                    Text(
                                        text = disciple.realmName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = disciple.spiritRootName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = spiritRootColor,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "智力:${disciple.intelligence}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF666666)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                GameButton(
                    text = "取消",
                    onClick = { showViceSectMasterSelectDialog = false }
                )
            }
        )
    }

    if (showSectAffairsDialog) {
        AlertDialog(
            onDismissRequest = { showSectAffairsDialog = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = "宗门事务",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "宗门日常事务管理功能开发中...",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                }
            },
            confirmButton = {
                GameButton(
                    text = "关闭",
                    onClick = { showSectAffairsDialog = false }
                )
            }
        )
    }

    if (showSectPoliciesDialog) {
        AlertDialog(
            onDismissRequest = { showSectPoliciesDialog = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = "宗门政策",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val sectPolicies = gameData?.sectPolicies
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "灵矿增产",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Text(
                                text = "每月灵石产出+20%，采矿弟子忠诚-1",
                                fontSize = 10.sp,
                                color = Color(0xFF666666)
                            )
                        }
                        
                        Checkbox(
                            checked = sectPolicies?.spiritMineBoost ?: false,
                            onCheckedChange = { viewModel.toggleSpiritMineBoost() }
                        )
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color(0xFFE0E0E0))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "增强治安",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Text(
                                text = "每月消耗5000灵石，执法堂抓捕率+30%",
                                fontSize = 10.sp,
                                color = Color(0xFF666666)
                            )
                            Text(
                                text = "副宗主智力影响：以50为基准，每高5点+1%",
                                fontSize = 10.sp,
                                color = Color(0xFF999999)
                            )
                        }
                        
                        Checkbox(
                            checked = sectPolicies?.enhancedSecurity ?: false,
                            onCheckedChange = { viewModel.toggleEnhancedSecurity() }
                        )
                    }
                }
            },
            confirmButton = {
                GameButton(
                    text = "关闭",
                    onClick = { showSectPoliciesDialog = false }
                )
            }
        )
    }
}
