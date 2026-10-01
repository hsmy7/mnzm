package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.system.TimeSource
import com.xianxia.sect.core.engine.system.WallClock
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.JadeLedgerEntry
import com.xianxia.sect.core.model.JadeLedgerReasons
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.PersistenceTelemetryPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * 玉符运行时状态（1Hz 节流发布，驱动倒计时）。
 *
 * 余额读数统一走镜像 [GameData.jadeSymbols]（账本派生缓存）；本流只承载
 * 墙钟日闸语义（今日计数/倒计时/封顶态），见 [JadeSymbolService] KDoc。
 *
 * @param today 今日已获得数量
 * @param remainingMs 距离下次获得玉符的剩余 ms（拿满后为 0）
 * @param capped 今日是否已达上限（20 枚）
 */
data class JadeSymbolRuntimeState(
    val today: Int,
    val remainingMs: Long,
    val capped: Boolean
)

/**
 * 玉符（氪金货币）服务 — 墙钟概念货币，未来商店消耗源。
 *
 * 玉符与游戏内进度完全解耦：不占仓库、无品阶、不走 InventorySystem、
 * 不参与游戏时间结算。获取通道仅为"真实前台运行时长"：
 * 每满 [GameConfig.Jade.INTERVAL_MS]（10 分钟）得 1 枚，
 * 单日最多 [GameConfig.Jade.DAILY_CAP]（20 枚），墙钟午夜重置。
 *
 * ## 账本模型（SS9：余额真源在 C++ jadeLedger）
 * 余额 = 账本期初条目 + Σdelta；[GameData.jadeSymbols] 是账本求和的**派生
 * 缓存**，由 C++ 事务（appendLedgerEntry）同事务双写。本服务不持有余额
 * 内存态：消耗/发放经 native 臂落账（C++ 权威），降级回退臂用
 * [appendLedger] 在镜像上同构落账（append 条目 + 缓存跟随），两条路径都
 * 不存在独立于账本的余额写入——「余额回涨」在结构上不可能。
 *
 * ## 时钟语义（防作弊第一性基础）
 * - **发放只由单调时钟**（[TimeSource]，SystemClock.elapsedRealtime()）驱动——
 *   修改墙钟无法加速获得，每枚仍需 10 分钟真实前台时间；
 * - 墙钟（[wallClock]）用于跨天重置判定（1s 节流）与账本落账时间戳；
 * - 单 tick 差分上限 [GameConfig.Jade.MAX_TICK_DELTA_MS]（10s）：
 *   OEM 挂起恢复不补记（镜像引擎 MAX_PHASES_PER_TICK 语义）；
 * - 跨天重置判据 `todayMidnight > [dayAnchorMs]`；回拨（`<=`）不重置；
 * - 拿满 [GameConfig.Jade.DAILY_CAP] 冻结累计；次日 0 点重置（今日计数与
 *   周期累计时长均清零，新的一天从 0 重新累计）。
 *
 * ## 高频写权衡
 * 不采用每 tick 写 GameData（全量 COW 不可行）：运行时 [@Volatile] 字段
 * 纯算术累计 + 1Hz 节流 UI 流 + 事件/存档 checkpoint——
 * GameData 写入仅发生在发放/跨天/循环停止/存档时（正常约 1 次/10 分钟）。
 * 代价：闪退损失当次周期最多 10 分钟累计，checkpointNow 已把窗口
 * 压到"上次存档后"。
 *
 * @param timeSource 单调时钟（与 GameTimeClock 同源，Hilt 注入）
 * @param stateStore 游戏状态唯一真相源
 * @param wallClock 墙钟（SR-5 起生产绑定
 *   [com.xianxia.sect.core.engine.system.CalibratedWallClock] = 系统钟 + 云 mtime 校正偏移，
 *   未采样时偏移恒 0 与 [com.xianxia.sect.core.engine.system.SystemWallClock] 逐位等价；
 *   测试注入可变 fake 验证跨天/回拨/快进）
 */
@Singleton
@GameService("JadeSymbolService")
class JadeSymbolService @Inject constructor(
    private val timeSource: TimeSource,
    private val stateStore: GameStateStore,
    private val wallClock: WallClock,
    // W4-B/B2（w3-04）：玉符稳态写下沉 C++（1766–1769），native 臂
    // 经 [Provider] 惰性取镜像服务——GameEngineCore 构造链持有本服务，Provider
    // 为惰性边（Dagger 官方破环手段，与 DiplomacyService.gameEngineCoreProvider
    // 同构）；null（测试直构）⇒ 恒走 Kotlin 回退臂。
    private val gameEngineCoreProvider: Provider<GameEngineCore>? = null,
    // SS3：账本↔派生缓存不一致计数接持久化遥测（StorageMetrics 实现，经 domain
    // 端口反向接线）；null（测试直构）⇒ 只保留 Log 通道。
    private val persistenceTelemetry: PersistenceTelemetryPort? = null
) {

    // ── W4-B/B2 native 臂（平台效应回执化）────────────────────────────

    /**
     * 玉符运行时事务统一转发（1766–1769）。
     *
     * 降级契约（与 PatrolNativeForward 等既有转发器同构）：flag 非
     * AUTHORITATIVE / 无 Provider（测试直构）/ 镜像服务缺失（findings 13
     * 可空判空）/ native 失败信封 → null → 调用点回退 Kotlin 原路径。
     */
    private fun tryNativeJade(
        actionId: Int,
        paramsBuilder: JsonObjectBuilder.() -> Unit
    ): JsonObject? {
        if (!NativeEngineFlag.authoritative) return null
        val core = gameEngineCoreProvider?.get() ?: return null
        val sync: StateSyncService? = core.stateSyncServiceRef
        if (sync == null) return null
        return GameEngineNativeOps.tryExecuteNative(
            stateSyncService = sync,
            actionId = actionId,
            paramsJson = params(paramsBuilder)
        ) as? JsonObject
    }

    private fun JsonObject?.intOrZero(name: String): Int =
        this?.get(name)?.jsonPrimitive?.intOrNull ?: 0

    private fun JsonObject?.longOrZero(name: String): Long =
        this?.get(name)?.jsonPrimitive?.longOrNull ?: 0L

    private fun JsonObject?.boolOrFalse(name: String): Boolean =
        this?.get(name)?.jsonPrimitive?.booleanOrNull ?: false

    /** 回执 drift 上报（派生缓存与账本基准不一致，C++ 已以账本为准重锚）。 */
    private fun logNativeDrift(reply: JsonObject?, op: String) {
        if (reply?.boolOrFalse("drift") == true) {
            persistenceTelemetry?.recordJadeLedgerDrift(op)
            DomainLog.w(
                TAG, "$op: 派生缓存与账本基准不一致（C++ 已以账本为准重锚）"
            )
        }
    }

    /** 单调时钟上次采样（tick 差分基准）。 */
    @Volatile
    private var lastSampleMs = 0L

    /** 当前 10 分钟周期已累计前台时长 ms（发放后保留余量）。 */
    @Volatile
    private var accumMs = 0L

    /** 今日已获得玉符数。 */
    @Volatile
    private var todayCount = 0

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
            today = 0,
            remainingMs = GameConfig.Jade.INTERVAL_MS, capped = false
        )
    )

    /** 玉符运行时状态流（源已 1Hz 节流，订阅方无需再 sample）。 */
    val runtimeState: StateFlow<JadeSymbolRuntimeState> = _runtimeState.asStateFlow()

    // ── 账本镜像面（Kotlin 回退臂专用；与 C++ jade_tx.h 同语义）─────────

    /** 账本余额（O(1) 读末条冗余；空账本兜底读派生缓存，同 C++ jadeLedgerBalance）。 */
    private fun ledgerBalance(gd: GameData): Int =
        gd.jadeLedger.lastOrNull()?.balanceAfter ?: gd.jadeSymbols

    /**
     * 账本落账（回退臂唯一写入口）：append 条目 + 派生缓存同事务双写。
     * 缓存偏离账本基准时以账本为准重锚并 Log（计数通道，StorageMetrics
     * getter 归 SS3）。
     */
    private fun appendLedger(gd: GameData, delta: Int, reason: String): GameData {
        val ledgerBase = ledgerBalance(gd)
        if (gd.jadeLedger.isNotEmpty() && gd.jadeSymbols != ledgerBase) {
            DomainLog.w(
                TAG, "派生缓存与账本基准不一致（已以账本为准重锚）: " +
                    "cache=${gd.jadeSymbols} ledger=$ledgerBase"
            )
        }
        val newBalance = ledgerBase + delta
        val entry = JadeLedgerEntry(
            atEpochMs = wallClock.currentTimeMillis(),
            delta = delta,
            reason = reason,
            balanceAfter = newBalance
        )
        return gd.copy(jadeSymbols = newBalance, jadeLedger = gd.jadeLedger + entry)
    }

    /**
     * 游戏循环启动钩子：从 GameData 快照恢复运行时字段（读档/切档/重启天然正确），
     * 并立即执行一次跨天检查（启动时若已跨天，今日计数直接归零）。
     * 余额真源在账本，无内存态需恢复。
     *
     * 可重复调用（幂等重锚）：仅 volatile 内存写 + UI 发布，不写 store
     * （跨天/锚定写入经 [pendingDayResetCheck] 延迟到引擎线程首帧 tick），
     * 任意线程调用安全；循环已被第三方（前台服务/watchdog）抢先启动后
     * 再次调用即以最新快照重锚。
     */
    fun onLoopStart() {
        val gd = stateStore.gameDataSnapshot
        todayCount = gd.jadeSymbolsToday
        // 防御纵深：恢复值不可 ≥ 发放阈值——否则每次读档首帧即免费 +1 玉符
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
     * 满上限冻结 → 满足 10 分钟发放 → 1Hz 发布 UI 状态。
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
                // native 臂（W4-B/B2）：冻结写 = checkpoint（accum=0）等价形
                val reply = tryNativeJade(ActionIds.JADE_RUNTIME_CHECKPOINT_TX) {
                    put("today", todayCount)
                    put("accumMs", 0L)
                    put("dayAnchorMs", dayAnchorMs)
                }
                logNativeDrift(reply, "checkpoint")
                if (reply == null) {
                    stateStore.update {
                        gameData = gameData.copy(jadeAccumMs = 0L)
                    }
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
     * 幂等 checkpoint：把运行时今日计数/累计时长/日锚写入 GameData。
     * 存档/云存档/后台快照前调用，保证快照含最新玉符值。
     * 余额真源在账本，checkpoint 不写余额。
     *
     * native 臂（W4-B/B2，JADE_RUNTIME_CHECKPOINT_TX）：三字段写归
     * C++；运行时值即参数（回执幂等）；失败/降级 → Kotlin 原路径（回退臂）。
     */
    fun checkpointNow() {
        // 未 onLoopStart 过（lastSampleMs 未初始化）不写：
        // 防止启动前的存档/后台快照用运行时零值覆盖已持久化的玉符
        if (lastSampleMs == 0L) return
        val reply = tryNativeJade(ActionIds.JADE_RUNTIME_CHECKPOINT_TX) {
            put("today", todayCount)
            put("accumMs", accumMs)
            put("dayAnchorMs", dayAnchorMs)
        }
        logNativeDrift(reply, "checkpoint")
        if (reply == null) {
            stateStore.update {
                gameData = gameData.copy(
                    jadeSymbolsToday = todayCount,
                    jadeAccumMs = accumMs,
                    jadeDayAnchorMs = dayAnchorMs
                )
            }
        }
        publishUi()
    }

    /**
     * 在已有事务内扣除玉符（玉符购买等消耗路径的降级回退臂）。必须在
     * 引擎线程、调用方 `stateStore.update` 事务闭包内调用（仿灵石 Wallet
     * 的 deduct 模式）。
     *
     * 账本语义：余额判定与落账均以账本为准（[appendLedger] append
     * SPEND_* 条目 + 派生缓存同事务双写）；native 臂可用时购买走 C++
     * 落账（GameEngineJadePurchaseOps），本方法只在降级路径执行。
     *
     * @param reason 落账来源（[JadeLedgerReasons.SPEND_*]，按调用玩法传入）
     * @return 是否成功（余额不足或金额非正返回 false，状态不变）
     */
    fun deduct(state: MutableGameState, amount: Int, reason: String): Boolean {
        if (amount <= 0) return false
        if (ledgerBalance(state.gameData) < amount) return false
        state.gameData = appendLedger(state.gameData, -amount, reason)
        return true
    }

    /**
     * 广告玉符发放（观看激励视频奖励，用户决策：不计入每日 20 上限）。
     *
     * 必须在引擎线程调用（调用方负责 launchOnEngine 派发，stateStore.update
     * 有主线程运行时守卫）。
     *
     * 账本语义：native 臂落 GRANT_AD 条目（C++ 权威，回执 total 为落账后
     * 余额）；降级回退臂用 [appendLedger] 同构落账。**不写 [todayCount]**：
     * 广告玉符独立于时间渠道每日上限。白名单用户的免广告直发同走本路径，
     * 账本如实记录来源（特权无上限语义保持）。
     *
     * @param amount 发放数量（必须为正）
     * @return 是否成功（amount 非正返回 false，状态不变）
     */
    fun grantFromAd(amount: Int): Boolean {
        if (amount <= 0) return false
        // 广告 SDK/播放本身 = 平台效应，已由调用方执行；本方法只承接账段
        val reply = tryNativeJade(ActionIds.JADE_RUNTIME_GRANT_AD_TX) {
            put("amount", amount)
            put("nowMs", wallClock.currentTimeMillis())
        }
        logNativeDrift(reply, "grantFromAd")
        if (reply == null) {
            stateStore.update {
                gameData = appendLedger(gameData, amount, JadeLedgerReasons.GRANT_AD)
            }
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
     * 当前墙钟（CalibratedWallClock 语义）：玉符账本落账时间戳与 native 臂
     * `nowMs` 参数的统一取值点（C++ 不取时，平台读数经参数传入）。
     */
    fun wallClockNowMs(): Long = wallClock.currentTimeMillis()

    /**
     * 结算发放：按累计时长整除 [GameConfig.Jade.INTERVAL_MS] 发放，
     * 封顶 [GameConfig.Jade.DAILY_CAP]，余量保留；拿满后余量丢弃（冻结）。
     *
     * native 臂（W4-B/B2，JADE_RUNTIME_SETTLE_TX）：整除/封顶/冻结判定与
     * GRANT_TIME 落账归 C++（回执 total = 账本余额）；失败/降级 → Kotlin
     * 原路径（[appendLedger] 同构落账）。
     */
    private fun settleGrants() {
        val grants = accumMs / GameConfig.Jade.INTERVAL_MS
        if (grants <= 0) return
        val reply = tryNativeJade(ActionIds.JADE_RUNTIME_SETTLE_TX) {
            put("today", todayCount)
            put("accumMs", accumMs)
            put("nowMs", wallClock.currentTimeMillis())
        }
        if (reply != null) {
            logNativeDrift(reply, "settleGrants")
            todayCount = reply.intOrZero("today")
            accumMs = reply.longOrZero("accumMs")
            return
        }
        // Kotlin 原路径（回退臂，语义同 C++ 事务）
        val remainder = accumMs % GameConfig.Jade.INTERVAL_MS
        val headroom = GameConfig.Jade.DAILY_CAP - todayCount
        if (headroom <= 0) {
            // 拿满冻结：余量丢弃（余额零变化不落账）
            accumMs = 0
            stateStore.update {
                gameData = gameData.copy(jadeAccumMs = 0L)
            }
            return
        }
        val toGrant = minOf(grants.toInt(), headroom)
        val keepRemainder = toGrant == grants.toInt()
        accumMs = if (keepRemainder) remainder else 0L
        todayCount += toGrant
        stateStore.update {
            gameData = appendLedger(gameData, toGrant, JadeLedgerReasons.GRANT_TIME)
                .copy(jadeSymbolsToday = todayCount, jadeAccumMs = accumMs)
        }
    }

    /**
     * 跨天重置检查：墙钟 1s 节流采样 → 计算"nowWall 所在日"午夜；
     * `午夜 <= 锚点`（同一天或墙钟回拨）→ 不重置；`午夜 > 锚点` →
     * 跨天（含快进 N 天）只重置一次并锚定目标日午夜。
     * 真实跨天：今日计数归零，**周期累计时长清零**（昨日未领完的进度作废，
     * 新的一天从 0 重新累计）；
     * 旧档锚点 0（未初始化）→ 首次直接锚定，无追溯发放、不清累计。
     *
     * @param force 跳过节流（onLoopStart 时调用）
     */
    private fun maybeDayReset(force: Boolean = false) {
        val nowWall = wallClock.currentTimeMillis()
        // 1s 节流；墙钟回拨（nowWall < lastWallCheckMs）时跳过节流直接采样——
        // 否则回拨后差值恒为负，跨天判定被无限期抑制
        if (!force && nowWall - lastWallCheckMs < WALL_CLOCK_CHECK_INTERVAL_MS &&
            nowWall >= lastWallCheckMs
        ) return
        lastWallCheckMs = nowWall
        val todayMidnight = getTodayStartMs(nowWall)
        // 同一天或墙钟回拨（防御）→ 不重置
        if (dayAnchorMs != 0L && todayMidnight <= dayAnchorMs) return
        // native 臂（W4-B/B2，JADE_RUNTIME_DAY_RESET_TX）：重置/锚定写归 C++。
        // 🔴 平台读数参数化（ADR 盲区 3）：`todayMidnight` 由 Kotlin Calendar
        // 本地时区计算后传入——C++ 不取时、不复刻时区规则；同一 `todayMidnight`
        // 重复调用幂等（事务内 `<= anchor` 零写入），墙钟回退同样零写入。
        val reply = tryNativeJade(ActionIds.JADE_RUNTIME_DAY_RESET_TX) {
            put("todayMidnightMs", todayMidnight)
            put("today", todayCount)
            put("accumMs", accumMs)
        }
        if (reply != null) {
            logNativeDrift(reply, "maybeDayReset")
            if (reply.boolOrFalse("crossedDay")) {
                // 真实跨天：今日计数归零，周期累计时长清零（运行时跟随回执）
                todayCount = 0
                accumMs = 0
            }
            dayAnchorMs = reply.longOrZero("dayAnchorMs").takeIf { it > 0 } ?: todayMidnight
            return
        }
        // Kotlin 原路径（回退臂，语义同 C++ 事务；余额零变化不落账）
        val crossedDay = dayAnchorMs != 0L
        dayAnchorMs = todayMidnight
        if (crossedDay) {
            // 真实跨天：今日计数归零，周期累计时长清零（新的一天重新累计）
            todayCount = 0
            accumMs = 0
            stateStore.update {
                gameData = gameData.copy(
                    jadeSymbolsToday = 0,
                    jadeAccumMs = 0L,
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
            today = todayCount,
            remainingMs = remainingMs,
            capped = capped
        )
    }

    private companion object {
        /** 日志 TAG。 */
        const val TAG = "JadeSymbolService"

        /** UI 状态发布节流：1Hz（倒计时 mm:ss 精度足够，避免高频全屏派发）。 */
        const val UI_PUBLISH_INTERVAL_MS = 1_000L

        /** 跨天判定墙钟采样节流：1s。 */
        const val WALL_CLOCK_CHECK_INTERVAL_MS = 1_000L
    }
}
