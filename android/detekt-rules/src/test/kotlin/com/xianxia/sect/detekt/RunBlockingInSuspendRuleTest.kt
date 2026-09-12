package com.xianxia.sect.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.Assert.assertEquals
import org.junit.Test

class RunBlockingInSuspendRuleTest {

    private val rule = RunBlockingInSuspendRule()

    @Test
    fun `runBlocking in suspend function triggers violation`() {
        val code = """
            import kotlinx.coroutines.runBlocking

            class Test {
                suspend fun method() {
                    runBlocking { }
                }
            }
        """
        val findings = rule.compileAndLint(code)
        assertEquals("suspend 函数中的 runBlocking 应触发违规", 1, findings.size)
    }

    @Test
    fun `runBlocking in non suspend function does not trigger violation`() {
        val code = """
            import kotlinx.coroutines.runBlocking

            class Test {
                fun method() {
                    runBlocking { }
                }
            }
        """
        val findings = rule.compileAndLint(code)
        assertEquals("非 suspend 函数中的 runBlocking 不应触发违规", 0, findings.size)
    }

    @Test
    fun `suspend function without runBlocking does not trigger violation`() {
        val code = """
            import kotlinx.coroutines.delay

            class Test {
                suspend fun method() {
                    delay(100)
                }
            }
        """
        val findings = rule.compileAndLint(code)
        assertEquals("suspend 函数中无 runBlocking 不应触发违规", 0, findings.size)
    }
}
