package com.xianxia.sect.detekt

import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class GameEngineRuleSetProvider : RuleSetProvider {

    override val ruleSetId: String = "game-engine"

    override fun instance(config: io.gitlab.arturbosch.detekt.api.Config): RuleSet {
        return RuleSet(
            ruleSetId,
            listOf(
                LaunchOnEngineRequiredRule(config),
                BlockingCallInCoroutineRule(config),
                RunBlockingInSuspendRule(config)
            )
        )
    }
}
