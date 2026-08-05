package com.xianxia.sect.ui.game.components.dialog

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.xianxia.sect.core.domain.dialog.DialogType
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.guide.GuideTaskRegistry
import com.xianxia.sect.ui.game.ActivityViewModel
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.components.OverlayViewModels
import com.xianxia.sect.ui.game.dialogs.ActivityDialog
import com.xianxia.sect.ui.game.dialogs.BattleLogListDialog
import com.xianxia.sect.ui.game.dialogs.DiplomacyDialog
import com.xianxia.sect.ui.game.dialogs.GuideDialog
import com.xianxia.sect.ui.game.dialogs.LeaderboardDialog
import com.xianxia.sect.ui.game.dialogs.LizhanDialog
import com.xianxia.sect.ui.game.dialogs.MailDialog
import com.xianxia.sect.ui.game.dialogs.MerchantDialog
import com.xianxia.sect.ui.game.dialogs.PlantingDialog
import com.xianxia.sect.ui.game.dialogs.RecruitDialog
import com.xianxia.sect.ui.game.dialogs.WorldMapDialog
import com.xianxia.sect.ui.game.leaderboard.LeaderboardViewModel

/**
 * 玩法/系统功能类对话框路由（E1 拆分：Recruit/Guide/Diplomacy/Planting/Merchant/
 * WorldMap/BattleLog/Mail/Activity/Lizhan）。分支体行为与拆分前逐字节一致。
 */
@Composable
internal fun DialogType.renderFeatureRoutes(
    vms: OverlayViewModels,
    gameData: GameData,
    onDismiss: () -> Unit
) {
    val viewModel = vms.game
    when (this) {
        DialogType.Recruit -> renderRecruit(viewModel, gameData, onDismiss)
        DialogType.Guide -> renderGuide(viewModel, gameData, onDismiss)
        DialogType.Diplomacy -> renderDiplomacy(viewModel, gameData, vms, onDismiss)
        DialogType.Planting -> renderPlanting(viewModel, gameData, onDismiss)
        DialogType.Merchant -> renderMerchant(viewModel, gameData, onDismiss)
        DialogType.WorldMap -> renderWorldMap(viewModel, gameData, vms, onDismiss)
        DialogType.BattleLog -> renderBattleLog(viewModel, onDismiss)
        DialogType.Mail -> renderMail(viewModel, onDismiss)
        DialogType.Activity -> renderActivity(viewModel, onDismiss)
        DialogType.Lizhan -> renderLizhan(viewModel, onDismiss)
        DialogType.Leaderboard -> renderLeaderboard(onDismiss)
        else -> Unit
    }
}

@Composable
private fun renderRecruit(viewModel: GameViewModel, gameData: GameData, onDismiss: () -> Unit) {
    val recruitList by viewModel.recruitListAggregates.collectAsStateWithLifecycle()
    RecruitDialog(
        recruitList = recruitList,
        gameData = gameData,
        viewModel = viewModel,
        onDismiss = onDismiss
    )
}

@Composable
private fun renderGuide(viewModel: GameViewModel, gameData: GameData, onDismiss: () -> Unit) {
    val guideClaimedRewardIds by viewModel.guideClaimedRewardIds.collectAsStateWithLifecycle()
    GuideDialog(
        gameData = gameData,
        claimedRewardIds = guideClaimedRewardIds,
        allTasks = GuideTaskRegistry.ALL_TASKS,
        onClaimReward = { taskId -> viewModel.claimGuideReward(taskId) },
        onDismiss = onDismiss
    )
}

@Composable
private fun renderDiplomacy(
    viewModel: GameViewModel,
    gameData: GameData,
    vms: OverlayViewModels,
    onDismiss: () -> Unit
) {
    DiplomacyDialog(
        gameData = gameData,
        viewModel = viewModel,
        interactionViewModel = vms.worldMapInteraction,
        onDismiss = onDismiss
    )
}

@Composable
private fun renderPlanting(viewModel: GameViewModel, gameData: GameData, onDismiss: () -> Unit) {
    val seeds by viewModel.seeds.collectAsStateWithLifecycle()
    PlantingDialog(
        seeds = seeds,
        gameData = gameData,
        viewModel = viewModel,
        activeSectId = gameData.activeSectId,
        onDismiss = onDismiss
    )
}

@Composable
private fun renderMerchant(viewModel: GameViewModel, gameData: GameData, onDismiss: () -> Unit) {
    MerchantDialog(
        gameData = gameData,
        viewModel = viewModel,
        onDismiss = onDismiss
    )
}

@Composable
private fun renderWorldMap(
    viewModel: GameViewModel,
    gameData: GameData,
    vms: OverlayViewModels,
    onDismiss: () -> Unit
) {
    val mapRenderData by viewModel.worldMapRenderData.collectAsStateWithLifecycle()
    val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent
    ) {
        WorldMapDialog(
            worldSects = mapRenderData.worldMapSects,
            mapRenderData = mapRenderData,
            gameData = gameData,
            disciples = disciples,
            viewModel = viewModel,
            interactionViewModel = vms.worldMapInteraction,
            garrisonViewModel = vms.worldMapGarrison,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun renderBattleLog(viewModel: GameViewModel, onDismiss: () -> Unit) {
    val battleLogs by viewModel.battleLogs.collectAsStateWithLifecycle()
    val yearlyReports by viewModel.yearlyReports.collectAsStateWithLifecycle()
    BattleLogListDialog(
        battleLogs = battleLogs,
        yearlyReports = yearlyReports,
        onDismiss = onDismiss
    )
}

@Composable
private fun renderMail(viewModel: GameViewModel, onDismiss: () -> Unit) {
    MailDialog(
        viewModel = viewModel,
        onDismiss = onDismiss
    )
}

@Composable
private fun renderActivity(viewModel: GameViewModel, onDismiss: () -> Unit) {
    val activityViewModel = hiltViewModel<ActivityViewModel>()
    ActivityDialog(
        viewModel = activityViewModel,
        gameViewModel = viewModel,
        onDismiss = onDismiss
    )
}

@Composable
private fun renderLizhan(viewModel: GameViewModel, onDismiss: () -> Unit) {
    LizhanDialog(
        viewModel = viewModel,
        onDismiss = onDismiss
    )
}

@Composable
private fun renderLeaderboard(onDismiss: () -> Unit) {
    val leaderboardViewModel = hiltViewModel<LeaderboardViewModel>()
    LeaderboardDialog(
        viewModel = leaderboardViewModel,
        onDismiss = onDismiss
    )
}
