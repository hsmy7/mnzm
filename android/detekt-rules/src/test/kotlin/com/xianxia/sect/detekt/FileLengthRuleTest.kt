package com.xianxia.sect.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.Assert.assertEquals
import org.junit.Test

class FileLengthRuleTest {

    private val rule = FileLengthRule()

    @Test
    fun `file under 2000 lines does not trigger violation`() {
        val code = buildString {
            appendLine("package com.xianxia.sect.test")
            appendLine()
            appendLine("class Sample {")
            repeat(1990) { appendLine("    val value$it = $it") }
            appendLine("}")
        }
        val findings = rule.compileAndLint(code)
        assertEquals("2000 行以内不应触发违规", 0, findings.size)
    }

    @Test
    fun `file over 2000 lines triggers violation`() {
        val code = buildString {
            appendLine("package com.xianxia.sect.test")
            appendLine()
            appendLine("class Sample {")
            repeat(2010) { appendLine("    val value$it = $it") }
            appendLine("}")
        }
        val findings = rule.compileAndLint(code)
        assertEquals("超过 2000 行应触发 1 条违规", 1, findings.size)
    }

    @Test
    fun `custom threshold from config is respected`() {
        val config = io.gitlab.arturbosch.detekt.test.TestConfig("threshold" to 100)
        val ruleWithCustomThreshold = FileLengthRule(config)
        val code = """
            package com.xianxia.sect.test

            class Sample {
                val a = 1
                val b = 2
                val c = 3
            }
        """
        val findings = ruleWithCustomThreshold.compileAndLint(code)
        assertEquals("自定义阈值 100 行时小文件仍应无违规", 0, findings.size)
    }
}
