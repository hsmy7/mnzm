package com.xianxia.sect.ui.game.dialogs.heavenlytrial

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.ui.components.beastSpriteRes
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.PresentationRandom
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.zIndex

/**
 * 战斗单位格子 UI（血条、肖像、飞行动画、受击抖动、选中高亮）
 */
@Composable
internal fun CombatUnitCell(
    combatant: Combatant?,
    random: PresentationRandom,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isAllySelected: Boolean = false,
    isEnemySelected: Boolean = false,
    isShaking: Boolean = false,
    flightAnim: FlightAnimState = FlightAnimState(),
    onClick: () -> Unit
) {
    val bgColor = when {
        isCurrent -> GameColors.Gold.copy(alpha = 0.3f)
        isAllySelected -> Color.Green.copy(alpha = 0.3f)
        isEnemySelected -> Color.Red.copy(alpha = 0.3f)
        else -> Color.Transparent
    }

    val shakeOffset = rememberShakeOffset(isShaking = isShaking)
    val flightProgress = rememberFlightProgress(flightAnim = flightAnim)

    val transX = flightAnim.deltaX * flightProgress.value
    val transY = flightAnim.deltaY * flightProgress.value

    Box(
        modifier = modifier
            .zIndex(if (flightAnim.isActive) 10f else 0f)
            .background(bgColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (combatant != null && !combatant.isDead) {
            CombatUnitCellContent(
                combatant = combatant,
                random = random,
                shakeOffset = shakeOffset,
                transX = transX,
                transY = transY
            )
        }
    }
}

/** 受击抖动偏移：isShaking 触发 6 段往返抖动 */
@Composable
private fun rememberShakeOffset(isShaking: Boolean): Animatable<Float, AnimationVector1D> {
    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(isShaking) {
        if (isShaking) {
            shakeOffset.animateTo(5f, tween(40))
            shakeOffset.animateTo(-4f, tween(40))
            shakeOffset.animateTo(3f, tween(40))
            shakeOffset.animateTo(-2f, tween(40))
            shakeOffset.animateTo(1f, tween(40))
            shakeOffset.animateTo(0f, tween(40))
        }
    }
    return shakeOffset
}

/** 飞行动画进度：按相位推进 0→1→0 */
@Composable
private fun rememberFlightProgress(flightAnim: FlightAnimState): Animatable<Float, AnimationVector1D> {
    val flightProgress = remember { Animatable(0f) }
    LaunchedEffect(flightAnim.phase, flightAnim.isActive) {
        if (flightAnim.isActive) {
            when (flightAnim.phase) {
                AnimPhase.MOVE_TO_TARGET -> {
                    flightProgress.snapTo(0f)
                    flightProgress.animateTo(1f, tween(250, easing = LinearEasing))
                }
                AnimPhase.IMPACT -> flightProgress.snapTo(1f)
                AnimPhase.RETURN_TO_START -> {
                    flightProgress.snapTo(1f)
                    flightProgress.animateTo(0f, tween(250, easing = LinearEasing))
                }
                else -> flightProgress.snapTo(0f)
            }
        } else {
            flightProgress.snapTo(0f)
        }
    }
    return flightProgress
}

/** 战斗单位血量/头像内容：状态文本 + 血条 + 肖像 */
@Composable
private fun CombatUnitCellContent(
    combatant: Combatant,
    random: PresentationRandom,
    shakeOffset: Animatable<Float, AnimationVector1D>,
    transX: Float,
    transY: Float
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.graphicsLayer {
            translationX = shakeOffset.value + transX
            translationY = transY
        }
    ) {
        if (combatant.hasControlEffect) {
            Text("晕眩", fontSize = 9.sp, color = Color.Red)
        } else {
            Text("${combatant.hp}/${combatant.maxHp}", fontSize = 9.sp, color = Color.White)
        }
        Spacer(Modifier.height(2.dp))

        val hpPercent = (combatant.hp.toFloat() / combatant.maxHp).coerceIn(0f, 1f)
        val barColor = when {
            hpPercent > 0.5f -> GameColors.Success
            hpPercent > 0.25f -> Color(0xFFFFEB3B)
            else -> GameColors.Error
        }
        Box(
            modifier = Modifier
                .width(44.dp).height(5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.DarkGray)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = hpPercent)
                    .background(barColor, RoundedCornerShape(2.dp))
            )
        }
        Spacer(Modifier.height(4.dp))
        CombatantPortrait(combatant = combatant, random = random, size = 44)
    }
}

/**
 * 参战者头像组件（弟子圆形肖像 / 妖兽无框立绘）。
 *
 * @param random 表现类随机源（[PresentationRandom]）——三支中"无立绘"分支按性别随机
 *               取一张肖像；该抽取**不写任何状态**（纯立绘选择），故走表现流而非
 *               决策分区（此前经已删除的 `GameRandom` 直连全局随机，且位于 UI 层，
 *               属架构违规）。抽取经 `scene("trial.portrait.<id>")` 场景流派生：
 *               **同一参战者永远同一张立绘**（跨会话一致，不再"退出重进换一张"）。
 */
@Composable
internal fun CombatantPortrait(combatant: Combatant, random: PresentationRandom, size: Int = 44) {
    val portraitResId = remember(combatant.id, combatant.portraitRes, combatant.isBeast) {
        when {
            combatant.isBeast -> {
                val index = combatant.portraitRes.removePrefix("beast_").toIntOrNull() ?: 0
                beastSpriteRes(index) ?: beastSpriteRes(0) ?: 0
            }
            combatant.portraitRes.isNotBlank() -> {
                PortraitPool.getResourceId(combatant.portraitRes).takeIf { it != 0 }
                    ?: SpriteResRegistry.resolve("disciple_portrait") ?: 0
            }
            else -> {
                // 场景键含参战者身份：他就是他（键纪律见 PresentationRandom KDoc）
                val sceneRandom = random.scene("trial.portrait.${combatant.id}")
                val randomPortrait = PortraitPool.getRandomPortrait(
                    if (sceneRandom.nextBoolean()) "male" else "female",
                    sceneRandom.boundPicker()
                )
                PortraitPool.getResourceId(randomPortrait).takeIf { it != 0 }
                    ?: SpriteResRegistry.resolve("disciple_portrait") ?: 0
            }
        }
    }

    val sizeDp = size.dp
    if (combatant.isBeast) {
        Image(
            painter = painterResource(id = portraitResId),
            contentDescription = null,
            modifier = Modifier.size(sizeDp),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = Modifier
                .size(sizeDp).clip(CircleShape)
                .border(2.dp, Color.Gray, CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = portraitResId),
                contentDescription = null,
                modifier = Modifier.size((size - 4).dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}

/**
 * 浮动伤害数字（含暴击放大、描边效果、上浮淡出动画）
 */
@Composable
internal fun FloatingDamageNumber(
    damage: Int,
    isCrit: Boolean,
    isPhysical: Boolean,
    isHeal: Boolean = false,
    screenX: Float,
    screenY: Float,
    onFadeComplete: () -> Unit
) {
    val floatOffset = remember { Animatable(0f) }
    val damageScale = remember { Animatable(1f) }
    val damageAlpha = remember { Animatable(1f) }

    val textColor = when {
        isHeal -> GameColors.DamageHeal
        isCrit -> GameColors.DamageCrit
        isPhysical -> GameColors.DamagePhysical
        else -> GameColors.DamageMagic
    }
    val fontSize = if (isCrit) 24 else 18

    LaunchedEffect(Unit) {
        launch { damageScale.animateTo(1.3f, tween(150)) }
        launch { floatOffset.animateTo(-120f, tween(1200, easing = LinearEasing)) }
        delay(150)
        launch { damageScale.animateTo(1.0f, tween(200)) }
        delay(500)
        damageAlpha.animateTo(0f, tween(400, easing = LinearEasing))
        onFadeComplete()
    }

    val displayText = if (isCrit) "暴击!$damage"
        else if (isHeal) "+$damage"
        else "$damage"
    val fontWeight = if (isCrit) FontWeight.Bold else FontWeight.ExtraBold

    Box(
        modifier = Modifier
            .offset { IntOffset(screenX.roundToInt(), (screenY + floatOffset.value).roundToInt()) }
            .graphicsLayer {
                scaleX = damageScale.value
                scaleY = damageScale.value
                this.alpha = damageAlpha.value
            }
    ) {
        val strokeDirs = listOf(
            -1 to -1, -1 to 0, -1 to 1,
            0 to -1, 0 to 1,
            1 to -1, 1 to 0, 1 to 1
        )
        strokeDirs.forEach { (dx, dy) ->
            Text(
                text = displayText, fontSize = fontSize.sp,
                fontWeight = fontWeight, color = Color.Black,
                modifier = Modifier.offset { IntOffset(dx, dy) }
            )
        }
        Text(
            text = displayText, fontSize = fontSize.sp,
            fontWeight = fontWeight, color = textColor
        )
    }
}
