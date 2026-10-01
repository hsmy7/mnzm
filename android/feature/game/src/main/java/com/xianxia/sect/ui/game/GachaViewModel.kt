package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.gacha.GachaFacade
import com.xianxia.sect.core.engine.domain.gacha.GachaPoolConfig
import com.xianxia.sect.core.engine.domain.gacha.GachaPoolSpec
import com.xianxia.sect.core.engine.domain.gacha.GachaPullResult
import com.xianxia.sect.core.model.GachaHistoryEntry
import com.xianxia.sect.core.state.CriticalSaveEventBus
import com.xianxia.sect.core.state.CriticalSaveKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 寻访界面的状态持有者（G11）。
 *
 * 只读面来自 [GachaFacade] 的四个账本流（镜像派生，UI 不直写状态存储）；
 * 价格与概率的唯一读面是 [GachaPoolConfig]（`db.gachaPools` 单源，禁在 Compose 内解析 JSON）。
 * 抽卡动作不在本类——写路径走 `GameViewModel.gacha`（[com.xianxia.sect.ui.game.delegate.GachaDelegate]），
 * 由它派发到引擎线程，结果再回填到这里的 [result] / [failureMessage]。
 */
@HiltViewModel
class GachaViewModel @Inject constructor(
    private val gachaFacade: GachaFacade,
    gachaPoolConfig: GachaPoolConfig,
    ioDispatcher: IoDispatcher,
    // SS6：关键事件自动存档总线——出货回执（不可逆随机结果）经此请求落盘；
    // 默认值仅供测试直构（无人订阅 = 事件零副作用），生产由 Hilt 注入单例
    private val criticalSaveEvents: CriticalSaveEventBus = CriticalSaveEventBus()
) : BaseViewModel() {

    /** 保底进度：池 id → 已抽次数（主界面进度条 x 端） */
    val pityCounters: StateFlow<Map<String, Int>> = gachaFacade.pityCounters

    /** 角色碎片进度：模板 id → 当前星级内计数（图鉴「下一星 x/100」） */
    val fragmentCounts: StateFlow<Map<String, Int>> = gachaFacade.fragmentCounts

    /** 星级账本（稀疏：0 星不落键 ⇒ 未解锁判据只能是「无键」） */
    val starMap: StateFlow<Map<String, Int>> = gachaFacade.starMap

    /** 寻访历史环（下标 0 最新，容量 [GachaHistoryEntry] 挂在 GameData 环上） */
    val history: StateFlow<List<GachaHistoryEntry>> = gachaFacade.history

    private val _poolSpec = MutableStateFlow<GachaPoolSpec?>(null)

    /** 常驻池规格（null = 尚未装载完成，或产物里没有这张池 ⇒ 寻访不可用且不得显示内置价） */
    val poolSpec: StateFlow<GachaPoolSpec?> = _poolSpec.asStateFlow()

    private val _showcase = MutableStateFlow<GachaPullShowcase?>(null)

    /**
     * 当前结果页内容（null = 未抽过或已关闭）。
     *
     * 连本次抽前的星级快照一起给（[GachaPullShowcase.anchorStarMap]）：升星层的
     * 「跳变」要用**抽前**账本作差，而抽后的账本由 [starMap] 反应式提供——
     * 在引擎线程回调里直读 `starMap.value` 会读到还没追上来的旧值，故快照在这里成对下发。
     */
    val showcase: StateFlow<GachaPullShowcase?> = _showcase.asStateFlow()

    private val _resultToken = MutableStateFlow(0)

    /**
     * 结果代次：叠下一轮时递增，供结果层 `key(resultToken)` 强制重建。
     * 宿主只在 `DialogType` 变化时重组（`GameOverlayHost` 的 `key(currentDialogType)`），
     * 同一对话框内换内容不会自动重组，流光也就不会重播。
     */
    val resultToken: StateFlow<Int> = _resultToken.asStateFlow()

    private val _failureMessage = MutableStateFlow<String?>(null)

    /** 失败文案（D-4：在寻访界面内联展示，不占用全局通知通道） */
    val failureMessage: StateFlow<String?> = _failureMessage.asStateFlow()

    private val _pulling = MutableStateFlow(false)

    /** 请求进行中 ⇒ 双按钮禁用，避免连点重复扣费 */
    val pulling: StateFlow<Boolean> = _pulling.asStateFlow()

    init {
        // 卡池表是本批唯一一次资产解析（GachaPoolConfig 内部缓存），放到 IO 线程免得
        // 打开寻访的第一帧被 JSON 读取堵住
        viewModelScope.launch(ioDispatcher.dispatcher) {
            _poolSpec.value = gachaPoolConfig.pool(STANDARD_POOL_ID)
        }
    }

    /** 抽卡请求已发出（由界面在调用 `GameViewModel.gacha` 前告知：取星级锚点 + 禁用按钮） */
    fun onPullRequested() {
        anchorStarMap = gachaFacade.starMap.value
        _pulling.value = true
        _failureMessage.value = null
    }

    /** 回收引擎线程的寻访结果：出货则叠新一轮结果，失败则内联文案 */
    fun onPullResult(result: GachaPullResult) {
        _pulling.value = false
        when (result) {
            is GachaPullResult.Success -> {
                _showcase.value = GachaPullShowcase(result, anchorStarMap)
                _resultToken.value += 1
                // 出货 = 不可逆随机结果已入镜像账本（方案 §2.5），请求关键事件落盘
                criticalSaveEvents.notify(CriticalSaveKind.GACHA)
            }

            is GachaPullResult.Failure -> {
                _showcase.value = null
                _failureMessage.value = failureText(result.reason)
            }
        }
    }

    /** 关闭结果层（点框外 / 返回键）；账本已落，结果层只是展示面 */
    fun dismissResult() {
        _showcase.value = null
    }

    private fun failureText(reason: String): String = FAILURE_TEXTS[reason]
        ?: "寻访未能完成，请稍后再试"

    /** 本次抽卡前的星级账本快照（由 [onPullRequested] 在请求发出时取，事务提交前的一致值） */
    private var anchorStarMap: Map<String, Int> = emptyMap()

    private companion object {
        /** UI 侧唯一使用的池 id；真源是产物 `db.gachaPools[].poolId` 与门面默认参数 */
        const val STANDARD_POOL_ID = "standard"

        /** 结果码全集（`GachaPullOutcome` 五码）→ 玩家可读文案 */
        val FAILURE_TEXTS = mapOf(
            "PoolNotFound" to "这个去处暂时寻访不了",
            "PoolDisabled" to "本次寻访暂未开放",
            "PoolMalformed" to "寻访配置有些问题，请稍后再试",
            "InsufficientSpiritStones" to "灵石不足，无法寻访",
            "InvalidPullCount" to "寻访次数不合法",
        )
    }
}

/** 结果页的一份内容：出货明细 + 抽卡前的星级锚点 */
data class GachaPullShowcase(
    val result: GachaPullResult.Success,
    val anchorStarMap: Map<String, Int>,
)
