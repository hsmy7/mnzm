package com.xianxia.sect.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockingCallInCoroutineRuleTest {

    private val rule = BlockingCallInCoroutineRule()

    @Test
    fun `Thread sleep in coroutine builder triggers violation`() {
        val code = """
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.Dispatchers

            class Test {
                fun method() {
                    launch(Dispatchers.Default) {
                        Thread.sleep(100)
                    }
                }
            }
        """
        val findings = rule.compileAndLint(code)
        assertEquals("协程构建器中的 Thread.sleep 应触发违规", 1, findings.size)
    }

    @Test
    fun `Thread sleep outside coroutine does not trigger violation`() {
        val code = """
            class Test {
                fun method() {
                    Thread.sleep(100)
                }
            }
        """
        val findings = rule.compileAndLint(code)
        assertEquals("协程外的 Thread.sleep 不应触发违规", 0, findings.size)
    }

    @Test
    fun `runBlocking in coroutine context triggers violation`() {
        val code = """
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.Dispatchers
            import kotlinx.coroutines.runBlocking

            class Test {
                fun method() {
                    launch(Dispatchers.Default) {
                        runBlocking { }
                    }
                }
            }
        """
        val findings = rule.compileAndLint(code)
        assertEquals("协程中的 runBlocking 应触发违规", 1, findings.size)
    }

    @Test
    fun `delay in coroutine does not trigger violation`() {
        val code = """
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.Dispatchers
            import kotlinx.coroutines.delay

            class Test {
                fun method() {
                    launch(Dispatchers.Default) {
                        delay(100)
                    }
                }
            }
        """
        val findings = rule.compileAndLint(code)
        assertEquals("协程中的 delay 不应触发违规", 0, findings.size)
    }
}
