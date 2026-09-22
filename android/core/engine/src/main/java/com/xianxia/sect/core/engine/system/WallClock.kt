package com.xianxia.sect.core.engine.system

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * epoch 墙钟抽象（SR-5 落地名 = `WallClock`，方案 §4 SR-5 字面写作"TimeSource"）。
 *
 * **命名偏离登记**（P1 拍板）：本仓 [TimeSource]（本包，`elapsedRealtime`）与
 * `com.xianxia.sect.core.animation.TimeSource`（`nanoTime`）**均为单调钟**，
 * 语义与本抽象正交；再建第三个 `TimeSource` 会造成三同名类型互相误读。
 * 本抽象在 SR-5 之前以 `WallClock` 之名存在于 `JadeSymbolService.kt`（玉符跨天判定专用），
 * SR-5 把它提升为全仓唯一的游戏语义墙钟入口。
 *
 * 用途边界（IN2 红线）：本抽象只提供"现在几点"，供**日/周/过期阈值判定**使用；
 * 存档新旧仲裁**永不**消费本抽象（唯一入口 `SaveArbiter`，脏标志/保存序号）。
 */
fun interface WallClock {
    fun currentTimeMillis(): Long
}

/** 未校正实现：直接读系统墙钟（测试直构默认值，也是偏移 0 时的等价锚）。 */
object SystemWallClock : WallClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

/**
 * 云端可校正墙钟（SR-5 生产实现）：`System.currentTimeMillis() + offset`。
 *
 * **偏移的唯一合法样本源**（P3 拍板 + 施工期实测修正，见 `batch-SR5.md` §2 条 6）：
 * 本端**刚**写完云档后读回的服务端 mtime——此刻"服务端记录的时刻"与"现在"最多相差
 * 一次网络往返，差值才是可信的本地钟漂移。
 * 🔴 冷启动云列表的 `modifiedTimeMs` **不是**合法样本：它是"上次归档写入时刻"，
 * 按其校正等于把本地钟往回拨"距上次上传过了多久"（小时/天量级），会直接打穿
 * 玉符日额与邮件过期判据。
 *
 * **约束与诚实局限**（勿当安全/防作弊闭环宣传）：
 * - 进程内至多**接受一次**校正（被丢弃的样本不占额度）——避免运行中反复移动时钟；
 * - |漂移| 超过 [MAX_DRIFT_MS] 即丢弃：该量级已超出一次 RTT 可解释的范围，
 *   无法区分"设备钟不准"与"元数据陈旧"，宁可不校正；
 * - **不跨进程持久化**：重启后回到未校正态，由下一次新鲜写入重新采样（持久化一个
 *   错误偏移的代价高于每次冷启动不校正）；
 * - 玩家大幅改钟的根治要等服务器真源（方案 D4 的 `GameServerSaveBackend`），本批交付
 *   的是接口与算式，不是反作弊能力。
 */
@Singleton
class CalibratedWallClock @Inject constructor() : WallClock {

    @Volatile
    private var offsetMs: Long = 0L

    @Volatile
    private var calibrated: Boolean = false

    override fun currentTimeMillis(): Long =
        if (offsetMs == 0L) System.currentTimeMillis() else System.currentTimeMillis() + offsetMs

    /** 当前生效偏移（0 = 未校正）；仅供观察/测试断言，不参与任何判据 */
    val currentOffsetMs: Long get() = offsetMs

    /** 是否已接受过一次校正（进程内单次约束的可观察面） */
    val isCalibrated: Boolean get() = calibrated

    /**
     * 用"刚写完云档后读回的服务端 mtime"校正本地钟。
     *
     * @param serverMtimeMs 服务端返回的归档 mtime（**必须**来自本次写入之后的读回）
     * @param localMtimeMsAtSample 读回那一刻的本地未校正墙钟（同一调用点取样）
     * @return 生效的漂移毫秒；null = 本次样本被丢弃（已校正过 / 量级不可信 / 参数非法）
     */
    fun applyDriftSample(serverMtimeMs: Long, localMtimeMsAtSample: Long): Long? {
        if (calibrated) return null
        if (serverMtimeMs <= 0L || localMtimeMsAtSample <= 0L) return null
        val drift = serverMtimeMs - localMtimeMsAtSample
        if (abs(drift) > MAX_DRIFT_MS) return null
        offsetMs = drift
        calibrated = true
        return drift
    }

    companion object {
        /**
         * 可信漂移上限：一次"写入 + 元数据读回"往返的服务端时间粒度是**秒级**
         * （TapTap mtime 为 epoch 秒），叠加冷网 RTT 与写入排队，5 分钟足以覆盖
         * 真实漂移，超出即判为陈旧/异常样本。
         */
        const val MAX_DRIFT_MS = 5L * 60L * 1000L
    }
}

/**
 * [WallClock] Hilt 绑定（SR-5）：生产为 [CalibratedWallClock]。
 *
 * 未采样时其偏移恒 0 ⇒ `currentTimeMillis()` 与 [SystemWallClock] **逐位一致**
 * （LEGACY 模式不上传 ⇒ 永不采样 ⇒ 默认模式行为零变化，守卫测试锚定）。
 * 测试直接构造消费方并传 fake（`WallClock { fixed }`）。
 */
@Module
@InstallIn(SingletonComponent::class)
object WallClockModule {
    @Provides
    @Singleton
    fun provideWallClock(impl: CalibratedWallClock): WallClock = impl
}
