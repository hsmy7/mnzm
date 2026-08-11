package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.system.TimeSource
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 墙钟抽象（玉符跨天判定专用，2026-08-07 注入化）。
 *
 * 生产使用 [SystemWallClock]（System.currentTimeMillis）；
 * 测试注入可变 fake 验证跨天/回拨/快进。
 */
fun interface WallClock {
    fun currentTimeMillis(): Long
}

/** 生产实现：系统墙钟。 */
object SystemWallClock : WallClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

/** WallClock Hilt 绑定（生产恒为 [SystemWallClock]；测试直接构造 JadeSymbolService(Fake)）。 */
@Module
@InstallIn(SingletonComponent::class)
object WallClockModule {
    @Provides
    @Singleton
    fun provideWallClock(): WallClock = SystemWallClock
}

/**
 * 玉符运行时状态（1Hz 节流发布，驱动 UI 徽章与倒计时）。
 *
 * @param total 累计持有数量
 * @param today 今日已获得数量
 * @param remainingMs 距离下次获得玉符的剩余 ms（拿满后为 0）
 * @param capped 今日是否已达上限（30 枚）
 */
data class JadeSymbolRuntimeState(
    val total: Int,
    val today: Int,
    val remainingMs: Long,
    val capped: Boolean
)

/**
 * 玉符（氪金货币）服务 — 墙钟概念货币，未来商店消耗源。
 *
 * 玉符与游戏内进度完全解耦：不占仓库、无品阶、不走 InventorySystem、
 * 不参与游戏时间结算。获取通道仅为"真实前台运行时长"：
 * 每满 [GameConfig.Jade.INTERVAL_MS]（20 分钟）得 1 枚，
 * 单日最多 [GameConfig.Jade.DAILY_CAP]（30 枚），墙钟午夜重置。
 *
 * ## 时钟语义（防作弊第一性基础）
 * - **发放只由单调时钟**（[TimeSource]，SystemClock.elapsedRealtime()）驱动——
 *   修改墙钟无法加速获得，每枚仍需 20 分钟真实前台时间；
 * - 墙钟（[wallClock]）仅用于跨天重置判定，1s 节流采样；
 * - 单 tick 差分上限 [GameConfig.Jade.MAX_TICK_DELTA_MS]（10s）：
 *   OEM 挂起恢复不补记（镜像引擎 MAX_PHASES_PER_TICK 语义）；
 * - 跨天重置判据 `todayMidnight > [jadeDayAnchorMs]`；回拨（`<=`）不重置；
 * - 拿满 [GameConfig.Jade.DAILY_CAP] 冻结累计，次日 0 点恢复。
 *
 * ## 高频写权衡
 * 不采用每 tick 写 GameData（全量 COW 不可行）：运行时 [@Volatile] 字段
 * 纯算术累计 + 1Hz 节流 UI 流 + 事件/存档 checkpoint——
 * GameData 写入仅发生在发放/跨天/循环停止/存档时（正常约 1 次/20 分钟）。
 * 代价：闪退损失当次周期最多 20 分钟累计，checkpointNow 已把窗口
 * 压到"上次存档后"。
 *
 * @param timeSource 单调时钟（与 GameTimeClock 同源，Hilt 注入）
 * @param stateStore 游戏状态唯一真相源
 * @param wallClock 墙钟（生产 [SystemWallClock]；测试注入可变 fake）
 */
@Singleton
@GameService("JadeSymbolService")
class JadeSymbolService @Inject constructor(
    private val timeSource: TimeSource,
    private val stateStore: GameStateStore,
    private val wallClock: WallClock
) {

    /** 单调时钟上次采样（tick 差分基准）。 */
    @Volatile
    private var lastSampleMs = 0L

    /** 当前 20 分钟周期已累计前台时长 ms（发放后保留余量）。 */
    @Volatile
    private var accumMs = 0L

    /** 今日已获得玉符数。 */
    @Volatile
    private var todayCount = 0

    /** 累计持有玉符数。 */
    @Volatile
    private var totalCount = 0

    /** 今日午夜锚点 epoch ms（跨天判定基准，回拨防御）。 */
    @Volatile
    private var dayAnchorMs = 0L

    /** 上次 UI 发布时刻（1Hz 节流）。 */
    @Volatile
    private var lastUiPublishMs = 0L

    /** 上次墙钟采样时刻（跨天判定 1s 节流）。 */
    @Volatile
    private var lastWallCheckMs = 0L

    /** 启动后首帧强制跨天检查标记（onLoopStart 只读快照，写入延迟到引擎线程首帧）。 */
    @Volatile
    private var pendingDayResetCheck = false

    private val _runtimeState = MutableStateFlow(
        JadeSymbolRuntimeState(
            total = 0, today = 0,
            remainingMs = GameConfig.Jade.INTERVAL_MS, capped = false
        )
    )

    /** 玉符运行时状态流（源已 1Hz 节流，订阅方无需再 sample）。 */
    val runtimeState: StateFlow<JadeSymbolRuntimeState> = _runtimeState.asStateFlow()

    /**
     * 游戏循环启动钩子：从 GameData 快照恢复运行时字段（读档/切档/重启天然正确），
     * 并立即执行一次跨天检查（启动时若已跨天，今日计数直接归零）。
     */
    fun onLoopStart() {
        val gd = stateStore.gameDataSnapshot
        totalCount = gd.jadeSymbols
        todayCount = gd.jadeSymbolsToday
        // 防御纵深：即使未来有绕过存档校验的写路径，恢复值也不可 ≥ 发放阈值
        // （否则每次读档首帧即免费 +1 玉符，见对抗性审查 F1）
        accumMs = gd.jadeAccumMs.coerceAtMost(GameConfig.Jade.INTERVAL_MS - 1)
        dayAnchorMs = gd.jadeDayAnchorMs
        lastSampleMs = timeSource.elapsedRealtime()
        lastUiPublishMs = 0L
        lastWallCheckMs = 0L
        // 跨天检查/首次锚定的 GameData 写入延迟到引擎线程首帧 tick：
        // startGameLoop 可能在主线程被调（onResume 后台切换链），
        // maybeDayReset 的 update 会命中 stateStore.update 主线程运行时守卫
        pendingDayResetCheck = true
        publishUi()
    }

    /**
     * 游戏循环每帧钩子（挂机/暂停照常累计——循环在暂停分支后仍执行到此处；
     * 切后台循环整体停止 → 自然不累计）。
     *
     * 流程：单调时钟差分（10s 裁剪）→ 跨天检查（优先于发放，同一事务互斥）→
     * 满上限冻结 → 满足 20 分钟发放 → 1Hz 发布 UI 状态。
     */
    fun onLoopTick() {
        val now = timeSource.elapsedRealtime()
        var delta = now - lastSampleMs
        lastSampleMs = now
        // 启动后首帧强制跨天检查（引擎线程执行 GameData 写入）；
        // 置于 delta<=0 判断之前——首帧 delta=0 也须完成锚定
        if (pendingDayResetCheck) {
            pendingDayResetCheck = false
            maybeDayReset(force = true)
        } else {
            maybeDayReset()
        }
        // 单调回拨防御
        if (delta <= 0) return
        // OEM 挂起恢复不补记
        if (delta > GameConfig.Jade.MAX_TICK_DELTA_MS) {
            delta = GameConfig.Jade.MAX_TICK_DELTA_MS
        }
        // 今日拿满 → 冻结累计（accumMs 归零，次日 0 点恢复）
        if (todayCount >= GameConfig.Jade.DAILY_CAP) {
            if (accumMs > 0) {
                accumMs = 0
                stateStore.update {
                    gameData = gameData.copy(jadeAccumMs = 0L)
                }
            }
            publishUi()
            return
        }
        accumMs += delta
        settleGrants()
        publishUi()
    }

    /**
     * 游戏循环停止钩子：把运行时累计 checkpoint 入 GameData
     * （切后台/退出不丢失当前周期进度）。
     */
    fun onLoopStop() {
        checkpointNow()
    }

    /**
     * 幂等 checkpoint：把运行时字段全量写入 GameData。
     * 存档/云存档/后台快照前调用，保证快照含最新玉符值。
     */
    fun checkpointNow() {
        // 未 onLoopStart 过（lastSampleMs 未初始化）不写：
        // 防止启动前的存档/后台快照用运行时零值覆盖已持久化的玉符
        if (lastSampleMs == 0L) return
        stateStore.update {
            gameData = gameData.copy(
                jadeSymbols = totalCount,
                jadeSymbolsToday = todayCount,
                jadeAccumMs = accumMs,
                jadeDayAnchorMs = dayAnchorMs
            )
        }
        publishUi()
    }

    /**
     * 在已有事务内扣除玉符（洗炼灵根等消耗路径）。必须在引擎线程、
     * 调用方 `stateStore.update` 事务闭包内调用（仿灵石 Wallet 的 deduct 模式）。
     *
     * 必须同步递减运行时 [totalCount]——否则后续 [checkpointNow]/[settleGrants]
     * 用未扣减的绝对值覆盖写 GameData.jadeSymbols，导致玉符回涨。
     *
     * ⚠️ 事务回滚契约：totalCount 递减是立即生效的外部可变状态，不随事务回滚。
     * 若同事务内 deduct 之后的代码抛出异常导致 update 回滚（GameData 恢复），
     * totalCount 不会自动回滚，余额将与 GameData 不一致（少扣的部分会在
     * checkpoint 时被绝对值覆盖，玩家实际损失该枚玉符）。
     * 调用方必须保证 deduct 之后的事务代码无异常路径（洗炼在 deduct 后仅执行
     * 纯函数抽卡 rollSpiritRootWash，不抛异常，满足契约）。
     *
     * @return 是否成功（余额不足或金额非正返回 false，状态不变）
     */
    fun deduct(state: MutableGameState, amount: Int): Boolean {
        if (amount <= 0 || totalCount < amount) return false
        totalCount -= amount
        state.gameData = state.gameData.copy(jadeSymbols = totalCount)
        return true
    }

    /**
     * 广告玉符发放（观看激励视频奖励，用户决策：不计入每日 30 上限）。
     *
     * 必须在引擎线程调用（调用方负责 launchOnEngine 派发，stateStore.update
     * 有主线程运行时守卫）。
     *
     * 与 [settleGrants] 同款幂等语义：先更新运行时 [totalCount] 再绝对值写
     * GameData——否则 checkpointNow/settleGrants 用旧绝对值写回导致玉符回涨。
     * **不写入 [todayCount]**：广告玉符独立于时间渠道的每日上限（单日时间 30 +
     * 广告 60 合计上限）。
     *
     * @param amount 发放数量（必须为正）
     * @return 是否成功（amount 非正返回 false，状态不变）
     */
    fun grantFromAd(amount: Int): Boolean {
        if (amount <= 0) return false
        totalCount += amount
        stateStore.update {
            gameData = gameData.copy(jadeSymbols = totalCount)
        }
        publishJadeSymbolStateNow()
        return true
    }

    /**
     * 立即发布玉符 UI 状态（清 1Hz 节流标记强制刷新）——玉符消耗后调用，
     * 徽章/详情对话框即时反映最新余额，无需等下一 tick。
     */
    fun publishJadeSymbolStateNow() {
        lastUiPublishMs = 0L
        publishUi()
    }

    /**
     * 结算发放：按累计时长整除 [GameConfig.Jade.INTERVAL_MS] 发放，
     * 封顶 [GameConfig.Jade.DAILY_CAP]，余量保留；拿满后余量丢弃（冻结）。
     */
    private fun settleGrants() {
        val grants = accumMs / GameConfig.Jade.INTERVAL_MS
        if (grants <= 0) return
        val remainder = accumMs % GameConfig.Jade.INTERVAL_MS
        val headroom = GameConfig.Jade.DAILY_CAP - todayCount
        if (headroom <= 0) {
            // 拿满冻结：余量丢弃
            accumMs = 0
            stateStore.update {
                gameData = gameData.copy(jadeAccumMs = 0L)
            }
            return
        }
        val toGrant = minOf(grants.toInt(), headroom)
        val keepRemainder = toGrant == grants.toInt()
        accumMs = if (keepRemainder) remainder else 0L
        totalCount += toGrant
        todayCount += toGrant
        // 绝对值写入：与 checkpointNow 并发交错时幂等
        // （增量式 gameData.x + toGrant 在"volatile 自增后、update 前"被抢占时会双加）
        stateStore.update {
            gameData = gameData.copy(
                jadeSymbols = totalCount,
                jadeSymbolsToday = todayCount,
                jadeAccumMs = accumMs
            )
        }
    }

    /**
     * 跨天重置检查：墙钟 1s 节流采样 → 计算"nowWall 所在日"午夜；
     * `午夜 <= 锚点`（同一天或墙钟回拨）→ 不重置；`午夜 > 锚点` →
     * 跨天（含快进 N 天）只重置一次并锚定目标日午夜。
     * 旧档锚点 0（未初始化）→ 首次直接锚定，无追溯发放。
     *
     * @param force 跳过节流（onLoopStart 时调用）
     */
    private fun maybeDayReset(force: Boolean = false) {
        val nowWall = wallClock.currentTimeMillis()
        // 1s 节流；墙钟回拨（nowWall < lastWallCheckMs）时跳过节流直接采样——
        // 否则回拨后差值恒为负，跨天判定被无限期抑制（对抗性审查 F3）
        if (!force && nowWall - lastWallCheckMs < WALL_CLOCK_CHECK_INTERVAL_MS &&
            nowWall >= lastWallCheckMs
        ) return
        lastWallCheckMs = nowWall
        val todayMidnight = getTodayStartMs(nowWall)
        // 同一天或墙钟回拨（防御）→ 不重置
        if (dayAnchorMs != 0L && todayMidnight <= dayAnchorMs) return
        val crossedDay = dayAnchorMs != 0L
        dayAnchorMs = todayMidnight
        if (crossedDay) {
            // 真实跨天：今日计数归零
            todayCount = 0
            stateStore.update {
                gameData = gameData.copy(
                    jadeSymbolsToday = 0,
                    jadeDayAnchorMs = todayMidnight
                )
            }
        } else {
            // 旧档首次锚定（today 必为 0，无需重置计数）
            stateStore.update {
                gameData = gameData.copy(jadeDayAnchorMs = todayMidnight)
            }
        }
    }

    /** 计算 [nowWall] 所在自然日的午夜 epoch ms（本地时区）。 */
    private fun getTodayStartMs(nowWall: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = nowWall }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** 1Hz 节流发布 UI 状态（发放/重置/启动/checkpoint 后刷新倒计时）。 */
    private fun publishUi() {
        val now = timeSource.elapsedRealtime()
        if (now - lastUiPublishMs < UI_PUBLISH_INTERVAL_MS) return
        lastUiPublishMs = now
        val capped = todayCount >= GameConfig.Jade.DAILY_CAP
        val remainingMs = if (capped) 0L else {
            GameConfig.Jade.INTERVAL_MS - (accumMs % GameConfig.Jade.INTERVAL_MS)
        }
        _runtimeState.value = JadeSymbolRuntimeState(
            total = totalCount,
            today = todayCount,
            remainingMs = remainingMs,
            capped = capped
        )
    }

    private companion object {
        /** UI 状态发布节流：1Hz（倒计时 mm:ss 精度足够，避免高频全屏派发）。 */
        const val UI_PUBLISH_INTERVAL_MS = 1_000L

        /** 跨天判定墙钟采样节流：1s。 */
        const val WALL_CLOCK_CHECK_INTERVAL_MS = 1_000L
    }
}
