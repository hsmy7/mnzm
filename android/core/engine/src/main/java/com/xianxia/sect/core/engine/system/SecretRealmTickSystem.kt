package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.engine.domain.exploration.SecretRealmAIProcessor
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

// TickSystem: "SecretRealmTickSystem" — 月变入口：AI 宗门队伍派遣（纯背景，无行为模拟）
@Singleton
@SystemPriority(order = 250)
class SecretRealmTickSystem @Inject constructor(
    private val secretRealmAIProcessor: SecretRealmAIProcessor
) : GameSystem {
    override val systemName: String = "SecretRealmTickSystem"

    override fun onMonthlyEvent(state: MutableGameState) {
        secretRealmAIProcessor.processMonthlyAiTeams(state)
    }
}
