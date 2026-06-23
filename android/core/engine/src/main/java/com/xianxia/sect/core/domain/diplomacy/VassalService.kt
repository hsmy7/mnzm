package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * 附庸/主宗体系服务。
 * 附庸（玩家宗门）向主宗（AI宗门）缴纳年贡 = max(上年收入×50%, 1灵石)。
 */
@Singleton
@GameService("VassalService")
class VassalService @Inject constructor(
    private val stateStore: GameStateStore,
    private val scopeProvider: CoroutineScopeProvider
) {
    private val scope get() = scopeProvider.scope

    companion object {
        private const val TAG = "VassalService"
    }

    /** 建立附庸关系 */
    fun establishVassalage(suzerainSectId: String) {
        scope.launch {
            stateStore.update {
                gameData = gameData.copy(suzerainSectId = suzerainSectId)
            }
        }
    }

    /** 是否附庸 */
    fun isVassal(): Boolean = stateStore.gameData.value.suzerainSectId.isNotEmpty()

    /** 获取主宗ID，"" 表示独立 */
    fun getSuzerainSectId(): String = stateStore.gameData.value.suzerainSectId

    /** 处理年贡（每年一月调用） */
    fun processYearlyTribute() {
        val data = stateStore.gameData.value
        val suzerainId = data.suzerainSectId
        if (suzerainId.isEmpty()) return
        val income = data.lastYearSpiritStoneIncome
        val tribute = max(
            (income * GameConfig.AIAttack.VASSAL_TRIBUTE_RATIO).toLong(),
            if (income > 0) GameConfig.AIAttack.VASSAL_TRIBUTE_MIN else 0L
        )
        if (tribute <= 0) return
        scope.launch {
            stateStore.update {
                gameData = gameData.copy(spiritStones = gameData.spiritStones - tribute)
            }
        }
    }

    /** 记录年收入供年贡计算 */
    fun recordYearlyIncome() {
        val stones = stateStore.gameData.value.spiritStones
        scope.launch {
            stateStore.update {
                gameData = gameData.copy(lastYearSpiritStoneIncome = stones)
            }
        }
    }
}
