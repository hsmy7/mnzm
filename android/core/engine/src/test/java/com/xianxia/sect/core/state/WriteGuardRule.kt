package com.xianxia.sect.core.state

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * JUnit Rule: 在测试执行期间禁用 DiscipleTables WriteGuard。
 *
 * 单元测试中需要直接操作组件表（绕过 stateStore.update{}），
 * WriteGuard 在测试环境中应关闭。
 *
 * 使用方式：
 *   @get:Rule val writeGuardRule = WriteGuardRule()
 */
class WriteGuardRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                val wasEnabled = DiscipleTables.writeGuardEnabled
                DiscipleTables.writeGuardEnabled = false
                try {
                    base.evaluate()
                } finally {
                    DiscipleTables.writeGuardEnabled = wasEnabled
                }
            }
        }
    }
}
