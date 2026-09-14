package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.beastSpriteRes
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.ui.components.BattleParticipantSlot
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.core.GameConfig
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*




private fun resolveBeastImageRes(enemyName: String): Int? {
    val idx = GameConfig.Beast.TYPES.indexOfFirst { enemyName.endsWith(it.name) }
    return if (idx >= 0) beastSpriteRes(idx) else null
}

/**
 * 推断战斗日志的具体战斗名称。
 * PVE 被妖兽战和任务战复用，需结合 details 区分。
 */
internal fun resolveBattleTypeName(log: BattleLog): String = when (log.type) {
    BattleType.SECT_WAR ->
        if (log.attackerName == "玩家队伍") "宗门战" else "宗门防守战"
    BattleType.SCOUT -> "探查战"
    BattleType.CAVE_EXPLORATION -> "洞府战"
    BattleType.PVE ->
        if (log.details.contains("任务")) "任务战" else "妖兽战"
    BattleType.PVP -> "PVP战斗"
    BattleType.ENCOUNTER -> "遭遇战"
}

@Composable
internal fun BattleLogDetailDialog(
    log: BattleLog,
    onDismiss: () -> Unit,
    scrimEnabled: Boolean = true
) {
    val resultColor = when (log.result) {
        BattleResult.WIN -> GameColors.Success
        BattleResult.LOSE -> GameColors.Error
        BattleResult.DRAW -> GameColors.Warning
    }

    val resultText = when (log.result) {
        BattleResult.WIN -> "胜利"
        BattleResult.LOSE -> "失败"
        BattleResult.DRAW -> "平局"
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "战斗详情",
        mode = DialogMode.Half,
        scrollableContent = false,
        scrimEnabled = scrimEnabled
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            HorizontalDivider(color = GameColors.SurfaceLightGray, thickness = 1.dp)

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                item { BattleDetailHeader(log = log, resultColor = resultColor, resultText = resultText) }

                itemsIndexed(log.teamMembers.chunked(4), key = { index, _ -> "team_$index" }) { _, rowMembers ->
                    BattleMemberRow(members = rowMembers)
                }

                item { BattleEnemyHeader(log = log) }

                itemsIndexed(log.enemies.chunked(4), key = { index, _ -> "enemy_$index" }) { _, rowEnemies ->
                    BattleEnemyRow(enemies = rowEnemies)
                }

                // 战利品/被掠夺物品（敌方槽位区域下方）
                if (log.drops.isNotEmpty()) {
                    item { BattleDropsSection(log = log) }
                }

                if (log.rounds.isNotEmpty()) {
                    item { BattleRoundsHeader() }

                    itemsIndexed(log.rounds, key = { index, round -> "round_${round.roundNumber}_$index" }) { _,
                        round ->
                        BattleRoundItem(round = round)
                    }
                }
            }
        }
    }
}

/** 战斗结果徽标 */
@Composable
internal fun BattleResultBadge(resultColor: Color, resultText: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(resultColor)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = resultText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/** 战斗详情头部：日期 + 结果徽标 + 回合数 + 我方弟子标题 */
@Composable
private fun BattleDetailHeader(
    log: BattleLog,
    resultColor: Color,
    resultText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "第${log.year}年${log.month}月",
            fontSize = 12.sp,
            color = Color.Black
        )
        BattleResultBadge(resultColor = resultColor, resultText = resultText)
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "战斗回合: ${log.turns}",
        fontSize = 11.sp,
        color = Color.Black
    )

    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "我方弟子",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Black
    )
    Spacer(modifier = Modifier.height(8.dp))
}

/** 我方弟子一行：4 槽位 + 空位占位 */
@Composable
private fun BattleMemberRow(members: List<BattleLogMember>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        members.forEach { member ->
            BattleParticipantSlot(
                name = member.name,
                realmName = member.realmName,
                hp = member.hp,
                maxHp = member.maxHp,
                isAlive = member.isAlive,
                portraitRes = member.portraitRes
            )
        }
        repeat(4 - members.size) {
            Spacer(modifier = Modifier.width(52.dp).height(84.dp))
        }
    }
}

/** 敌方阵营标题 */
@Composable
private fun BattleEnemyHeader(log: BattleLog) {
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = when (log.type) {
            BattleType.PVE -> "敌方妖兽"
            BattleType.SECT_WAR, BattleType.SCOUT -> "敌方宗门弟子"
            BattleType.CAVE_EXPLORATION -> "敌方守护兽"
            else -> "敌方"
        },
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Black
    )
    Spacer(modifier = Modifier.height(8.dp))
}

/** 敌方一行：含妖兽精灵图兜底解析 */
@Composable
private fun BattleEnemyRow(enemies: List<BattleLogEnemy>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        enemies.forEach { enemy ->
            val portraitRes = enemy.portraitRes.ifEmpty {
                val beastResId = resolveBeastImageRes(enemy.name)
                if (beastResId != null) "beast_$beastResId" else ""
            }
            BattleParticipantSlot(
                name = enemy.name,
                realmName = enemy.realmName,
                hp = enemy.hp,
                maxHp = enemy.maxHp,
                isAlive = enemy.isAlive,
                portraitRes = portraitRes
            )
        }
        repeat(4 - enemies.size) {
            Spacer(modifier = Modifier.width(52.dp).height(84.dp))
        }
    }
}

/** 战利品/被掠夺物品区 */
@Composable
private fun BattleDropsSection(log: BattleLog) {
    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = GameColors.SurfaceLightGray, thickness = 1.dp)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = if (log.result == BattleResult.LOSE) "被掠夺物品" else "战利品",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Black
    )
    Spacer(modifier = Modifier.height(6.dp))
    log.drops.forEach { drop ->
        Text(
            text = "· $drop",
            fontSize = 11.sp,
            color = Color(0xFF555555)
        )
    }
}

/** SpiritStoneSource.key → 中文显示名映射表（sourceDisplayName 查表） */
internal val SPIRIT_STONE_SOURCE_NAMES: Map<String, String> = mapOf(
    "Mine" to "灵矿", "Battle" to "战斗", "Quest" to "任务",
    "Mail" to "邮件", "MerchantTrade" to "交易",
    "Exploration" to "探索", "RedeemCode" to "兑换码", "Cave" to "洞府",
    "HeavenlyTrial" to "天道试炼", "SectLevelReward" to "宗门等级奖励",
    "Salary" to "俸禄", "StorageBag" to "储物袋", "Refund" to "退款",
    "Sell" to "售卖", "SecretRealm" to "秘境", "Internal" to "内部"
)

/** SpiritStoneReason.key → 中文显示名映射表（reasonDisplayName 查表） */
internal val SPIRIT_STONE_REASON_NAMES: Map<String, String> = mapOf(
    "Building" to "建筑", "PolicyCost" to "政策消耗", "Salary" to "年俸",
    "Gift" to "赠礼", "Diplomacy" to "外交", "VassalTribute" to "附属上贡",
    "Purchase" to "购买", "AutoSell" to "自动售卖", "Exchange" to "兑换",
    "Theft" to "盗窃", "ExplorationLoot" to "探索战利品", "BeastTribute" to "妖兽上贡",
    "Internal" to "内部"
)

internal val EQUIP_SOURCE_NAMES: Map<String, String> = mapOf(
    "forge" to "锻造", "battle" to "战斗", "exploration" to "探索",
    "quest" to "任务", "mail" to "邮件", "cave" to "洞府",
    "trial" to "天道试炼", "merchant" to "商人",
    "sect_level" to "宗门等级", "storage_bag" to "储物袋",
    "building" to "建筑", "unknown" to "未知",
    "redeem" to "兑换码", "disciple_death" to "弟子死亡",
    "cave_world" to "洞府世界", "secret_realm" to "秘境",
    "sect_trade" to "宗门交易", "confiscate" to "没收",
    "disciple_expel" to "逐出弟子"
)

internal val PILL_SOURCE_NAMES: Map<String, String> = mapOf(
    "alchemy" to "炼丹", "battle" to "战斗", "exploration" to "探索",
    "quest" to "任务", "mail" to "邮件", "cave" to "洞府",
    "trial" to "天道试炼", "merchant" to "商人",
    "sect_level" to "宗门等级", "storage_bag" to "储物袋",
    "building" to "建筑", "unknown" to "未知",
    "redeem" to "兑换码", "disciple_death" to "弟子死亡",
    "cave_world" to "洞府世界", "secret_realm" to "秘境",
    "sect_trade" to "宗门交易", "confiscate" to "没收",
    "disciple_expel" to "逐出弟子"
)

internal val HERB_SOURCE_NAMES: Map<String, String> = mapOf(
    "spirit_field" to "灵田", "exploration" to "探索", "battle" to "战斗",
    "quest" to "任务", "mail" to "邮件", "storage_bag" to "储物袋",
    "cave" to "洞府", "trial" to "天道试炼", "merchant" to "商人",
    "unknown" to "未知",
    "redeem" to "兑换码", "disciple_death" to "弟子死亡",
    "secret_realm" to "秘境", "sect_trade" to "宗门交易",
    "confiscate" to "没收", "disciple_expel" to "逐出弟子"
)
