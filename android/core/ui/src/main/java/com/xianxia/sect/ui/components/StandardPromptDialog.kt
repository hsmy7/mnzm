package com.xianxia.sect.ui.components

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import com.xianxia.sect.core.ui.R
import androidx.core.view.WindowInsetsControllerCompat

/** 键盘/系统栏守卫统一日志 TAG（与 ImeVisibilityTracker 一致，便于 logcat 真机验证） */
private const val TAG = "ImeGuard"

/**
 * 解冻后延迟恢复系统栏隐藏的等待时长（毫秒）：
 * 覆盖键盘收起动画的剩余时长，等待 IME 状态落定再恢复隐藏，
 * 切断"键盘动画期间 hide() 对抗"（荣耀GT系列 + 第四根因键盘频闪根治）。
 * 与 MainActivity/GameActivity 的 SYSTEM_BAR_RESTORE_DELAY_MS 保持一致（三处同值）。
 */
private const val SYSTEM_BAR_RESTORE_DELAY_MS = 350L

/**
 * 在 Composable 挂载期间将目标窗口的 softInputMode 临时切换为 [mode]，
 * 卸载时自动恢复。适用于 [Dialog] 内的平台 Dialog 窗口和 Activity 内的 Box overlay。
 *
 * 模式（2026-09 IME 状态机根治升级）：默认 [SOFT_INPUT_ADJUST_RESIZE]——
 * API 30 起官方语义为向窗口派发 IME insets（兼容模式，官方 javadoc deprecated 真正
 * resize），配合 `decorFitsSystemWindows=false`（Compose Dialog 容器已设）+ Compose
 * `imePadding` 构成官方标准组合（docs/ime-android-system-research.md M1/M4）。
 * 历史 [SOFT_INPUT_ADJUST_PAN] 为过渡方案：官方仅作 fallback 且易错乱（pan 平移量在
 * edge-to-edge 下随系统栏抖动 = "界面反复下拉"放大器），已降级为 API<30/ROM 特例兜底。
 *
 * 使用本组件的窗口**禁止再叠加窗口级平移类避让**——pan + padding 双重位移正是
 * 历史键盘振荡频闪的根因配方（2026-08 根治，见 [isInsideDialogWindow] 与
 * rules/dialog-soft-input-guard.md）；imePadding 为应用层单一避让，与窗口 insets
 * 派发协同，不构成双重位移。
 *
 * 行业调研结论（2026-07 / 2026-09 两次调研）：
 * - Google IssueTracker #229378542: imePadding 在 Dialog 内不可靠（Compose 1.x 缺陷，
 *   显式 setDecorFitsSystemWindows(false) + ADJUST_RESIZE 后 Dialog 可进入 insets 管线）
 * - StackOverflow 社区共识: adjustPan 是 Compose Dialog 输入框的过渡实践
 * - Xiaomi MIUI/HyperOS 已知缺陷: imePadding 在 Dialog 窗口上无法正确处理 keyboard insets
 * - Unity/Flutter 游戏行业: 窗口零参与 + 应用层 insets/事件流避让
 *
 * @see DialogSystemBarGuard
 * @see ImeAwareContainer
 */
@Composable
fun DialogSoftInputGuard(
    mode: Int = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
) {
    // 遍历 View 层级寻找 DialogWindowProvider（平台 Dialog 窗口）
    val dialogWindow = generateSequence(LocalView.current) {
        it.parent as? View
    }
        .filterIsInstance<DialogWindowProvider>()
        .firstOrNull()
        ?.window
    val targetWindow = dialogWindow
        ?: run {
            // 回退路径：部分国产 ROM 的 Dialog 视图层级可能不包含 DialogWindowProvider，
            // 尝试通过 rootView（Dialog 窗口的顶层 View）获取
            val rootView = LocalView.current.rootView
            (rootView as? DialogWindowProvider)?.window
        }
        ?: (LocalActivity.current as? Activity)?.window
        ?: run {
            Log.w("DialogSoftInputGuard", "无法获取 Dialog/Activity 窗口引用，" +
                "softInputMode 防护失效——当前 ROM 可能在 Dialog 窗口上触发键盘振荡")
            return
        }
    val originalMode = remember { targetWindow.attributes.softInputMode }
    DisposableEffect(targetWindow) {
        targetWindow.setSoftInputMode(mode)
        onDispose {
            try {
                targetWindow.setSoftInputMode(originalMode)
            } catch (_: Exception) {
                // OEM 定制 Window 实现可能在窗口销毁后抛出异常，安全忽略
            }
        }
    }
}

/**
 * 输入对话框挂载期间的宿主窗口系统栏冻结 Effect（2026-08 荣耀 X70 键盘频闪根治）。
 *
 * [enabled] 为 true（含文本输入的对话框）时，挂载期间通过 [SystemBarFreezeScope]
 * 冻结宿主 Activity 的系统栏隐藏操作，销毁时解冻并触发宿主恢复隐藏。
 * 提取为独立 composable 供 [InlineStandardPromptDialog] 与 [UnifiedGameDialog] 复用，
 * 避免在各容器函数内增加分支复杂度（detekt CyclomaticComplexMethod 阈值守卫）。
 */
@Composable
internal fun SystemBarFreezeEffect(enabled: Boolean) {
    if (enabled) {
        DisposableEffect(Unit) {
            SystemBarFreezeScope.enterFreeze()
            onDispose { SystemBarFreezeScope.exitFreeze() }
        }
    }
}

/**
 * 判定给定 [View] 是否处于平台 Dialog 窗口（Compose [Dialog] 创建的独立 Window）内。
 *
 * 通过遍历 View 父链查找 [DialogWindowProvider] 实现。2026-09 IME 状态机根治后
 * **不再用于键盘避让判定**（全窗口统一 insets 管线 + imePadding，无 pan/padding 二选一）；
 * 仅用于**嵌套冻结传导**（[InlineStandardPromptDialog] 渲染于平台 Dialog 窗口内且
 * `freezeSystemBars = true` 时冻结外层窗口系统栏，第四根因机制保留）。
 */
internal fun isInsideDialogWindow(view: View): Boolean =
    generateSequence(view) { it.parent as? View }
        .filterIsInstance<DialogWindowProvider>()
        .firstOrNull() != null

/**
 * 在 Dialog Window 上应用 hideSystemBars()，使对话框内容全屏无状态栏/导航栏。
 *
 * Compose Dialog 创建独立平台 Window，不继承 Activity 的 systemUiVisibility 标志。
 * 此 composable 在 Dialog 挂载时对该 Window 应用隐藏标志，卸载时不需恢复（Window 销毁）。
 *
 * 冻结感知（2026-08 第四根因键盘频闪根治）：输入对话框（`freezeSystemBars = true`）
 * 挂载期间本窗口经 [DialogSystemBarFreezeScope] 处于冻结态——**只隐藏状态栏，不隐藏
 * 导航栏**：切断 HIDE_NAVIGATION 与键盘（IME）的冲突面（API<35 传统标志被 SystemUI
 * 完整执行，导航栏隐藏使 IME 布局区域失效触发键盘收起再弹；API 35 edge-to-edge 下
 * 系统在 IME 期间接管导航栏，应用隐藏与其对抗）。嵌套内联输入框
 * （[InlineStandardPromptDialog] 渲染于本窗口内）挂载时经冻结翻转回调恢复导航栏显示，
 * 解冻后延迟 [SYSTEM_BAR_RESTORE_DELAY_MS] 恢复隐藏。
 *
 * 零操作原则（第四根因核心）：键盘可见期间对系统栏**不做任何 hide/show 切换**——
 * 历史"IME 感知切换"（荣耀 GT 系列根治手段）在 HyperOS 2 / MagicOS 8/9 的键盘转场
 * 动画上自身成为振荡放大器，直接移除切换动作即根治；本守卫仍经 [ImeVisibilityTracker]
 * 跟踪本窗口键盘可见性（保证全局 `isImeVisible` 准确，供解冻恢复链路二次校验），
 * 但不再响应翻转切换系统栏。
 *
 * 双路径方案（对标 GameActivity.hideSystemBars()）：
 * 1. WindowInsetsControllerCompat（现代 API，API 30+ 推荐方式）
 * 2. 传统 SYSTEM_UI_FLAG_*（国产 OEM ROM 兼容性，API < 35）
 */
@Composable
fun DialogSystemBarGuard() {
    val dialogWindow = generateSequence(LocalView.current) {
        it.parent as? View
    }
        .filterIsInstance<DialogWindowProvider>()
        .firstOrNull()
        ?.window
        ?: return

    DisposableEffect(dialogWindow) {
        val state = DialogSystemBarState(dialogWindow)
        state.applyInitialState()
        DialogSystemBarFreezeScope.addOnFrozenChangedListener(dialogWindow, state::onFreezeChanged)
        // 保留本窗口的 IME 跟踪（全局 isImeVisible 准确性，供解冻恢复链路二次校验）；
        // 零操作原则下不再响应翻转切换系统栏
        ImeVisibilityTracker.attach(dialogWindow)
        onDispose { state.dispose() }
    }
}

/**
 * DialogSystemBarGuard 的窗口系统栏状态机（2026-08 第四根因键盘频闪根治）。
 *
 * 顶层拆分：detekt 圈复杂度按函数体（含 lambda/局部函数）统计，守卫全部副作用
 * 收敛于此类的独立方法，避免 DialogSystemBarGuard 组合函数超阈值。
 * 语义：冻结（含输入框）挂载只隐藏状态栏、不隐藏导航栏（切断 HIDE_NAVIGATION×IME
 * 冲突面）；键盘可见期间零系统栏切换；冻结进入恢复导航栏显示；解冻延迟恢复隐藏。
 */
private class DialogSystemBarState(
    private val window: Window
) {
    private val controller = WindowInsetsControllerCompat(window, window.decorView)
    private val decor = window.decorView
    /** 解冻延迟恢复用的主线程 Handler（与 View.postDelayed 语义等价，Robolectric 可推进） */
    private val restoreHandler = Handler(Looper.getMainLooper())

    /** 当前是否处于"系统栏已隐藏"态（冻结进入恢复 / 解冻延迟恢复的状态机跟踪） */
    var hideApplied: Boolean = false
        private set

    /** 隐藏系统栏；[hideNavigationBar] 为 false（冻结态）时保留导航栏，切断 IME 冲突面 */
    fun applyHide(hideNavigationBar: Boolean) {
        // 路径 1: WindowInsetsController 方式（现代 API，API 30+ 推荐）
        val hideTypes = if (hideNavigationBar) {
            WindowInsetsCompat.Type.statusBars() or
                WindowInsetsCompat.Type.navigationBars()
        } else {
            WindowInsetsCompat.Type.statusBars()
        }
        controller.hide(hideTypes)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // 路径 2: 传统 SYSTEM_UI_FLAGS 方式（国产 OEM ROM 兼容，与 GameActivity.hideSystemBars 一致）
        // 注：始终执行（不按 API level 过滤），因为国产 OEM ROM 即使在 API 35+ 上
        // 仍可能对 WindowInsetsController 支持不完整，传统标志作为补充。在纯 AOSP 35+
        // 上这些 flag 是 deprecated 但无害的 no-op。
        @Suppress("DEPRECATION")
        decor.systemUiVisibility = decor.systemUiVisibility or
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
             View.SYSTEM_UI_FLAG_FULLSCREEN or
             View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
             View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
             if (hideNavigationBar) {
                 View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                     View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
             } else {
                 0
             })
    }

    /**
     * 冻结进入（嵌套输入框挂载）：恢复导航栏显示，清除与 IME 冲突的 HIDE_NAVIGATION。
     * 仅涉及导航栏：键盘位于底部，状态栏无冲突，FULLSCREEN 标志保留不动。
     */
    fun applyShowNavigation() {
        controller.show(WindowInsetsCompat.Type.navigationBars())
        @Suppress("DEPRECATION")
        decor.systemUiVisibility =
            decor.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION.inv()
    }

    /** 初始状态：全局键盘可见 → 零操作；冻结（含输入框）→ 只隐藏状态栏；未冻结 → 全隐藏 */
    fun applyInitialState() {
        val frozen = DialogSystemBarFreezeScope.isFrozen(window)
        val imeVisible = ImeVisibilityTracker.isImeVisible
        when {
            frozen && !imeVisible -> {
                applyHide(hideNavigationBar = false)
                hideApplied = true
            }
            !frozen && !imeVisible -> {
                applyHide(hideNavigationBar = true)
                hideApplied = true
            }
            else -> Log.d(TAG, "DialogSystemBarGuard: 挂载时键盘可见，零系统栏操作")
        }
    }

    /** 解冻延迟恢复：等待键盘收起动画结束、IME 状态落定后恢复隐藏（二次校验放行） */
    fun scheduleRestoreAfterUnfreeze() {
        restoreHandler.postDelayed({
            if (!DialogSystemBarFreezeScope.isFrozen(window) &&
                !ImeVisibilityTracker.isImeVisible
            ) {
                applyHide(hideNavigationBar = true)
                hideApplied = true
                Log.d(TAG, "DialogSystemBarGuard: 解冻延迟恢复系统栏隐藏")
            }
        }, SYSTEM_BAR_RESTORE_DELAY_MS)
    }

    /**
     * 冻结翻转回调：冻结进入（嵌套输入框挂载）→ 恢复导航栏显示；
     * 解冻（输入对话框销毁）→ 延迟恢复隐藏。
     */
    fun onFreezeChanged() {
        if (DialogSystemBarFreezeScope.isFrozen(window)) {
            if (hideApplied) {
                applyShowNavigation()
                hideApplied = false
                Log.d(TAG, "DialogSystemBarGuard: 冻结进入，恢复导航栏显示")
            }
        } else if (!hideApplied) {
            scheduleRestoreAfterUnfreeze()
        }
    }

    /** 卸载清理：注销冻结监听、解除 IME 跟踪、清理解冻延迟恢复的残留回调 */
    fun dispose() {
        DialogSystemBarFreezeScope.removeOnFrozenChangedListener(window, ::onFreezeChanged)
        ImeVisibilityTracker.detach(window)
        restoreHandler.removeCallbacksAndMessages(null)
    }
}

/**
 * 输入对话框挂载期间的 Dialog 窗口系统栏冻结 Effect（2026-08 第四根因键盘频闪根治）。
 *
 * [enabled] 为 true（含文本输入的对话框）时，挂载期间对**本 Dialog 窗口**执行
 * [DialogSystemBarFreezeScope.enterFreeze]——[DialogSystemBarGuard] 据此只隐藏状态栏、
 * 不隐藏导航栏（切断 HIDE_NAVIGATION×IME 冲突面），销毁时解冻并触发 guard 延迟恢复。
 * 与 [SystemBarFreezeEffect]（冻结宿主 Activity 的 `hideSystemBars`）职责互补：
 * 前者管 Activity 侧，本 Effect 管 Dialog 窗口侧。
 *
 * 必须在 Dialog{} 块内调用（[LocalView] 解析为 Dialog 窗口视图）；
 * 找不到 DialogWindowProvider 时 Log.w 后跳过（冻结传导退化，由零操作策略兜底）。
 */
@Composable
internal fun DialogSystemBarFreezeEffect(enabled: Boolean) {
    if (!enabled) return
    val dialogWindow = generateSequence(LocalView.current) {
        it.parent as? View
    }
        .filterIsInstance<DialogWindowProvider>()
        .firstOrNull()
        ?.window
    if (dialogWindow == null) {
        Log.w("DialogSystemBarFreezeEffect", "无法获取 Dialog 窗口引用，窗口级系统栏冻结失效")
        return
    }
    DisposableEffect(dialogWindow) {
        DialogSystemBarFreezeScope.enterFreeze(dialogWindow)
        onDispose { DialogSystemBarFreezeScope.exitFreeze(dialogWindow) }
    }
}

@Composable
fun StandardPromptDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String? = null,
    confirmLabel: String = "确定",
    onConfirm: () -> Unit = onDismissRequest,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
    customButtons: (@Composable RowScope.() -> Unit)? = null,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    showCloseButton: Boolean = false,
    scrimEnabled: Boolean = true,
    titleColor: Color = Color.Black,
    @DrawableRes dialogBackgroundRes: Int = R.drawable.dialog_box,
    @DrawableRes buttonBackgroundRes: Int = R.drawable.ui_button,
    @DrawableRes closeButtonRes: Int = R.drawable.ui_close_button,
    /** 含文本输入框时传 true：挂载期间冻结本 Dialog 窗口系统栏（第四根因键盘频闪根治，见 DialogSystemBarFreezeScope） */
    freezeSystemBars: Boolean = false,
    content: @Composable (ColumnScope.() -> Unit) = {}
) {
    // D-34：LocalWindowInfo.current.containerSize 替代 Configuration.screenWidthDp/screenHeightDp
    //（Android 15 edge-to-edge 下两者 insets 行为差异且取整精度不同）
    // containerSize 单位是像素，需经 LocalDensity 换算为 dp（D-34 回归修复：勿直接 .dp 使用像素值）
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val dialogWidth = with(density) { (windowSize.width / 2).toDp() }
    val dialogHeight = with(density) { (windowSize.height * 0.55f).toDp() }
    // 宿主（GameOverlayHost）已画单例遮罩时强制禁用自画遮罩，防多窗口遮罩 α 叠加变黑
    val scrimActuallyEnabled = scrimEnabled && !LocalDialogScrimHosted.current

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = false
        )
    ) {
        // 输入对话框挂载期间冻结本 Dialog 窗口系统栏（第四根因根治，见 DialogSystemBarFreezeScope）
        DialogSystemBarFreezeEffect(freezeSystemBars)
        // 在 Dialog 窗口内切换 softInputMode，切断 HyperOS 震荡回路
        DialogSoftInputGuard()
        // 隐藏 Dialog Window 的系统状态栏/导航栏（该 Window 不继承 Activity 的设置）
        DialogSystemBarGuard()
        // Dialog 窗口销毁前清除焦点并隐藏软键盘，防止文本选择 FloatingActionMode
        // 在窗口 token 失效后尝试弹出 PopupWindow 导致 BadTokenException（Bugly #3026）
        DialogFocusGuard()

        // 键盘避让（2026-09 IME 状态机根治）：平台 Dialog 窗口内容区挂
        // ImeAwareContainer 事件驱动避让（键盘可见翻转 → 对话框一次性上移，
        // 不依赖 Dialog 窗口 imePadding 的历史可靠性 #229378542）；无输入框时
        // 键盘永不弹出、offset 恒 0，零行为变化。
        PromptDialogScrim(
            onDismissRequest = onDismissRequest,
            scrimEnabled = scrimActuallyEnabled,
            dismissOnClickOutside = dismissOnClickOutside
        ) {
            ImeAwareContainer {
                PromptDialogFrame(
                    dialogWidth = dialogWidth,
                    dialogHeight = dialogHeight,
                    dialogBackgroundRes = dialogBackgroundRes,
                    config = PromptDialogContent(
                        title = title,
                        titleColor = titleColor,
                        showCloseButton = showCloseButton,
                        closeButtonRes = closeButtonRes,
                        text = text,
                        onDismissRequest = onDismissRequest,
                        confirmLabel = confirmLabel,
                        onConfirm = onConfirm,
                        dismissLabel = dismissLabel,
                        onDismiss = onDismiss,
                        customButtons = customButtons,
                        buttonBackgroundRes = buttonBackgroundRes
                    ),
                    content = content
                )
            }
        }
    }
}

/**
 * 内联版标准提示框 — 不使用平台 [Dialog] 窗口，改为 Box 覆盖层渲染。
 *
 * 接口签名与 [StandardPromptDialog] 完全一致，但通过内联 Box overlay 避免
 * 平台 Dialog 窗口与 IME 键盘交互导致的频闪问题（[decorFitsSystemWindows] 与
 * [adjustResize] 组合引起的窗口尺寸震荡）。2026-08 恢复覆盖层形态（历史上
 * 2133597c 曾统一改回平台 Dialog 窗口造成键盘振荡回归，见
 * docs/adr/dialog-system-refactoring.md）。
 *
 * 屏幕尺寸在 composition 入口处 [remember] 缓存，键盘弹出后不再变化，
 * 从而彻底杜绝重组震荡。
 *
 * 键盘避让（2026-09 IME 状态机根治，统一 insets 管线）：
 * 本组件恒挂官方标准组合 `imePadding`（外层 Box）——manifest/DialogSoftInputGuard
 * 均 ADJUST_RESIZE + edge-to-edge（`decorFitsSystemWindows=false`）前置条件满足，
 * 键盘弹出时覆盖层可用区域收缩到键盘上方，对话框整体上移。**删除**历史
 * "isInsideDialogWindow 二选一 / 软件渲染切 ADJUST_PAN"分支（渲染模式分支消亡），
 * 全渲染模式统一；[isInsideDialogWindow] 仅保留用于嵌套冻结传导。
 *
 * 系统栏冻结机制（2026-08 荣耀 X70 键盘频闪根治）：
 * [freezeSystemBars] 为 true（含文本输入的对话框）时，挂载期间通过
 * [SystemBarFreezeScope] 冻结宿主 Activity 的系统栏隐藏操作。Android 15
 * 强制 edge-to-edge 下 IME 可见期间系统接管导航栏，应用 hide() 与其对抗 +
 * 荣耀 MagicOS 键盘弹出/收起期间的窗口焦点抖动（onWindowFocusChanged 反复
 * 回调触发 hide()）会形成"键盘弹出→收起→再弹出"振荡回路，冻结后切断放大器。
 * 销毁时解冻并触发宿主恢复系统栏隐藏。无输入框的提示框传 false（默认），
 * 保持原有行为。
 */
// 平铺参数签名与 StandardPromptDialog 对齐，聚合数据类会破坏 20+ 调用点语义
@Suppress("LongParameterList")
@Composable
fun InlineStandardPromptDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String? = null,
    confirmLabel: String = "确定",
    onConfirm: () -> Unit = onDismissRequest,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
    customButtons: (@Composable RowScope.() -> Unit)? = null,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    showCloseButton: Boolean = false,
    scrimEnabled: Boolean = true,
    titleColor: Color = Color.Black,
    @DrawableRes dialogBackgroundRes: Int = R.drawable.dialog_box,
    @DrawableRes buttonBackgroundRes: Int = R.drawable.ui_button,
    @DrawableRes closeButtonRes: Int = R.drawable.ui_close_button,
    freezeSystemBars: Boolean = false,
    content: @Composable (ColumnScope.() -> Unit) = {}
) {
    // 输入对话框挂载期间冻结宿主窗口系统栏操作，切断键盘弹出收起振荡回路的
    // 放大器环节（荣耀 X70 根治，见 SystemBarFreezeScope KDoc）
    SystemBarFreezeEffect(freezeSystemBars)

    // 在 composition 入口处读取窗口尺寸并用 remember 缓存，之后不再变化
    // D-34：LocalWindowInfo.current.containerSize 替代 Configuration.screenWidthDp/screenHeightDp
    // containerSize 单位是像素，需经 LocalDensity 换算为 dp（D-34 回归修复：勿直接 .dp 使用像素值）
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val dialogWidth = remember { with(density) { (windowSize.width / 2).toDp() } }
    val dialogHeight = remember { with(density) { (windowSize.height * 0.55f).toDp() } }
    // 宿主（GameOverlayHost）已画单例遮罩时强制禁用自画遮罩，防多窗口遮罩 α 叠加变黑
    val scrimActuallyEnabled = scrimEnabled && !LocalDialogScrimHosted.current

    if (dismissOnBackPress) {
        BackHandler { onDismissRequest() }
    }

    // 覆盖层销毁前清除焦点并隐藏软键盘，防止文本选择 FloatingActionMode
    // 在窗口 token 失效后尝试弹出 PopupWindow 导致 BadTokenException（Bugly #3026）
    DialogFocusGuard()

    // 2026-09 IME 状态机根治：全窗口统一 insets 管线（manifest/DialogSoftInputGuard
    // 均 ADJUST_RESIZE + decorFitsSystemWindows=false），键盘避让统一走 Compose
    // imePadding（官方标准组合，单一应用层避让）——删除历史"渲染模式感知双路径"
    // （shouldUsePanAvoidance/PanAvoidanceGuard/hardwareAccelerated）与"isInsideDialogWindow
    // 二选一"分支；`insideDialogWindow` 仅保留用于下方嵌套冻结传导。
    val dialogView = LocalView.current
    val insideDialogWindow = remember { isInsideDialogWindow(dialogView) }

    // 嵌套传导（2026-08 第四根因根治）：内联输入框渲染于平台 Dialog 窗口内时，
    // 冻结外层 Dialog 窗口的系统栏操作——DialogSystemBarGuard 据此恢复导航栏显示、
    // 不再隐藏导航栏（切断 HIDE_NAVIGATION×IME 冲突面）。freezeSystemBars 语义
    // 从宿主 Activity 自动传导到外层 Dialog 窗口，调用方无需传参
    // （如仓库出售：SmallScreenDialog 平台窗口 overlay 槽位内嵌 SellConfirmDialog）。
    if (freezeSystemBars && insideDialogWindow) {
        val outerWindow = remember(dialogView) {
            generateSequence(dialogView) { it.parent as? View }
                .filterIsInstance<DialogWindowProvider>()
                .firstOrNull()
                ?.window
        }
        if (outerWindow != null) {
            DisposableEffect(outerWindow) {
                DialogSystemBarFreezeScope.enterFreeze(outerWindow)
                onDispose { DialogSystemBarFreezeScope.exitFreeze(outerWindow) }
            }
        }
    }

    // 键盘避让（2026-09 IME 状态机根治）：内联覆盖层统一走官方标准组合
    // imePadding（manifest/DialogSoftInputGuard 均 ADJUST_RESIZE + edge-to-edge 已满足
    // 前置条件；单一应用层避让，无 pan 无双重位移）。外层 Box 挂 imePadding 后，
    // 键盘弹出时覆盖层可用区域收缩到键盘上方，对话框整体上移。
    InlineImePaddingWrapper {
        PromptDialogScrim(
            onDismissRequest = onDismissRequest,
            scrimEnabled = scrimActuallyEnabled,
            dismissOnClickOutside = dismissOnClickOutside
        ) {
            PromptDialogFrame(
                dialogWidth = dialogWidth,
                dialogHeight = dialogHeight,
                dialogBackgroundRes = dialogBackgroundRes,
                config = PromptDialogContent(
                    title = title,
                    titleColor = titleColor,
                    showCloseButton = showCloseButton,
                    closeButtonRes = closeButtonRes,
                    text = text,
                    onDismissRequest = onDismissRequest,
                    confirmLabel = confirmLabel,
                    onConfirm = onConfirm,
                    dismissLabel = dismissLabel,
                    onDismiss = onDismiss,
                    customButtons = customButtons,
                    buttonBackgroundRes = buttonBackgroundRes
                ),
                content = content
            )
        }
    }
}

/**
 * 内联输入框的官方标准 imePadding 避让包装（InlineStandardPromptDialog 拆分防
 * detekt LongMethod）：键盘弹出时覆盖层可用区域收缩到键盘上方（单一应用层避让）。
 */
@Composable
private fun InlineImePaddingWrapper(
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/**
 * 提示框内容配置（StandardPromptDialog/InlineStandardPromptDialog 拆分共享）：
 * 两个公共函数平铺参数中与 frame 渲染相关的部分聚合于此，避免 16+ 参数的
 * 私有组件触发 detekt LongParameterList（data class 不计入）。
 */
private data class PromptDialogContent(
    val title: String,
    val titleColor: Color,
    val showCloseButton: Boolean,
    @DrawableRes val closeButtonRes: Int,
    val text: String?,
    val onDismissRequest: () -> Unit,
    val confirmLabel: String,
    val onConfirm: () -> Unit,
    val dismissLabel: String?,
    val onDismiss: (() -> Unit)?,
    val customButtons: (@Composable RowScope.() -> Unit)?,
    @DrawableRes val buttonBackgroundRes: Int
)

/**
 * 提示框遮罩层（StandardPromptDialog/InlineStandardPromptDialog 拆分共享）：
 * scrim 背景 + 点击外部关闭。**本层不做键盘避让**——避让由调用方容器决定：
 * - [InlineStandardPromptDialog]（Activity 层）：外层挂官方标准 `imePadding`
 * - [StandardPromptDialog]（平台 Dialog 窗口）：内容区挂 [ImeAwareContainer]
 *   （事件驱动一次性位移，不依赖 Dialog 窗口 imePadding 的历史可靠性）
 */
@Composable
private fun PromptDialogScrim(
    onDismissRequest: () -> Unit,
    scrimEnabled: Boolean,
    dismissOnClickOutside: Boolean,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (scrimEnabled) Modifier.background(Color(0x99000000)).testTag("scrim")
                else Modifier
            )
            .then(
                if (dismissOnClickOutside) {
                    Modifier.clickableWithSound(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** 提示框内容框（StandardPromptDialog/InlineStandardPromptDialog 拆分共享）：背景图 + 标题/文本 + 内容槽 + 底部按钮 */
@Composable
private fun PromptDialogFrame(
    dialogWidth: Dp,
    dialogHeight: Dp,
    @DrawableRes dialogBackgroundRes: Int,
    config: PromptDialogContent,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .width(dialogWidth)
            .height(dialogHeight)
            .testTag("prompt_frame") // 尺寸回归守卫测试锚点（D-34 px/dp 换算）
            .clip(RoundedCornerShape(12.dp))
            .clickableWithSound(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {} // 阻止点击穿透到外层
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = dialogBackgroundRes),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PromptDialogHeader(
                showCloseButton = config.showCloseButton,
                title = config.title,
                titleColor = config.titleColor,
                closeButtonRes = config.closeButtonRes,
                onDismissRequest = config.onDismissRequest
            )
            Spacer(modifier = Modifier.height(8.dp))
            PromptDialogText(
                text = config.text,
                customButtons = config.customButtons,
                showCloseButton = config.showCloseButton
            )
            // Content area: 输入框优先，按钮在底部且空间不足时折叠
            Column(modifier = Modifier.weight(1f)) {
                content()
                // 弹性空间：有富余空间时把按钮推到底部，空间不足时率先折叠
                if (!config.showCloseButton) {
                    PromptDialogButtons(
                        customButtons = config.customButtons,
                        dismissLabel = config.dismissLabel,
                        onDismiss = config.onDismiss,
                        onDismissRequest = config.onDismissRequest,
                        confirmLabel = config.confirmLabel,
                        onConfirm = config.onConfirm,
                        buttonBackgroundRes = config.buttonBackgroundRes
                    )
                }
            }
        }
    }
}

/** 标题行（StandardPromptDialog/InlineStandardPromptDialog 拆分共享）：有关闭按钮时右对齐关闭，否则居中标题 */
@Composable
private fun PromptDialogHeader(
    showCloseButton: Boolean,
    title: String,
    titleColor: Color,
    closeButtonRes: Int,
    onDismissRequest: () -> Unit
) {
    if (showCloseButton) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = titleColor
            )
            Spacer(modifier = Modifier.weight(1f))
            CloseButton(onClick = onDismissRequest, closeButtonRes = closeButtonRes)
        }
    } else {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = titleColor,
            textAlign = TextAlign.Center
        )
    }
}

/** 文本块（StandardPromptDialog/InlineStandardPromptDialog 拆分共享）：正文 + 与按钮区之间的条件间距 */
@Composable
private fun PromptDialogText(
    text: String?,
    customButtons: (@Composable RowScope.() -> Unit)?,
    showCloseButton: Boolean
) {
    if (text != null) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = Color.Black,
            textAlign = TextAlign.Center
        )
    }

    if (text != null && (customButtons != null || !showCloseButton)) {
        Spacer(modifier = Modifier.height(12.dp))
    }
}

/** 底部按钮区（StandardPromptDialog/InlineStandardPromptDialog 拆分共享）：customButtons / 取消+确认 / 仅确认 三态 */
@Composable
private fun ColumnScope.PromptDialogButtons(
    customButtons: (@Composable RowScope.() -> Unit)?,
    dismissLabel: String?,
    onDismiss: (() -> Unit)?,
    onDismissRequest: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    @DrawableRes buttonBackgroundRes: Int
) {
    if (customButtons != null) {
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            customButtons()
        }
    } else if (dismissLabel != null) {
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            GameButton(
                text = dismissLabel,
                onClick = { (onDismiss ?: onDismissRequest)() },
                buttonBackgroundRes = buttonBackgroundRes
            )
            Spacer(modifier = Modifier.width(16.dp))
            GameButton(
                text = confirmLabel,
                onClick = onConfirm,
                buttonBackgroundRes = buttonBackgroundRes
            )
        }
    } else {
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            GameButton(
                text = confirmLabel,
                onClick = onConfirm,
                buttonBackgroundRes = buttonBackgroundRes
            )
        }
    }
}
