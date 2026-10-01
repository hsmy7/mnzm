package com.xianxia.sect.login

import android.content.Context
import android.content.Intent
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.data.account.AccountSpaceManager
import com.xianxia.sect.taptap.ComplianceManager
import com.xianxia.sect.taptap.TapDBManager
import com.xianxia.sect.taptap.TapTapAuthManager
import com.xianxia.sect.ui.MainActivity

/**
 * 登出五件套 —— 全部登出入口的唯一实现（三入口逐字一致）：
 *
 * 1. `clearSession`：清本地会话（账号/实名标记）；
 * 2. `TapTapAuthManager.logout`：清 TapTap SDK 登录态——否则残留会话使下次登录
 *    走"静默登录"，防沉迷验证不触发导致卡在登录界面；
 * 3. `TapDBManager.stopGameDurationTracking`：停时长统计；
 * 4. `ComplianceManager.unregisterCallback`：解绑合规回调；
 * 5. `AccountSpaceManager.closeCurrent`：关数据空间（只清 `.current`，不删空间——
 *    换回同一账号进度仍在）。
 *
 * 消费方（三处登出入口，禁止各自手抄清单）：
 * - `MainActivity.performFullLogout`（状态机 `LogoutRequested` 汇聚点：
 *   实名认证界面"切换账号"与合规限制弹窗经状态机到达此处）；
 * - `GameActivity.onLogout`（游戏内设置退出登录）；
 * - `GameActivity.performComplianceLogout`（游戏内合规限制弹窗）。
 *
 * 禁止只做 `clearSession()` 的残缺登出；新增登出入口必须复用本函数。
 */
internal fun performFullLogout(
    sessionManager: SessionManager,
    accountSpace: AccountSpaceManager
) {
    sessionManager.clearSession()
    TapTapAuthManager.logout()
    TapDBManager.stopGameDurationTracking()
    ComplianceManager.unregisterCallback()
    accountSpace.closeCurrent()
}

/**
 * 登出后进程重启回登录页。
 *
 * 换账号必须换数据空间，而数据空间绑定在进程级单例图上（Room 连接、存储引擎、
 * 游戏引擎、内存缓存均为账号态）——同进程原地切换必然串档，重启是唯一彻底的
 * 换空间方式。先投递重启目标再退出进程：ActivityManager 已收到启动请求，
 * 进程终止后由系统拉起新进程直达登录页。
 */
fun restartToLoginScreen(context: Context) {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}
