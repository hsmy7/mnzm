package com.xianxia.sect.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * 代码质量规则集：文件级/函数级长度守卫。
 *
 * 与 game-engine 规则集（运行时行为防护）分离，
 * 此处集中代码规模类约束（CLAUDE.md 3.1/3.3）。
 */
class CodeQualityRuleSetProvider : RuleSetProvider {

    override val ruleSetId: String = "code-quality"

    override fun instance(config: Config): RuleSet {
        return RuleSet(
            ruleSetId,
            listOf(
                FileLengthRule(config)
            )
        )
    }
}
