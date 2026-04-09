package com.xianxia.sect.data.validation

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.model.SaveData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class StorageValidatorTest {

    // ========== validateSlotRange 测试 ==========

    @Test
    fun `validateSlotRange - slot等于0 maxSlots等于5 返回 valid`() {
        val result = StorageValidator.validateSlotRange(0, 5)

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `validateSlotRange - slot等于maxSlots 返回 valid`() {
        val result = StorageValidator.validateSlotRange(5, 5)

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `validateSlotRange - slot为负数返回 SLOT_NEGATIVE 错误`() {
        val result = StorageValidator.validateSlotRange(-1, 5)

        assertFalse(result.isValid)
        assertEquals(1, result.errors.size)
        assertEquals("SLOT_NEGATIVE", result.errors[0].code)
    }

    @Test
    fun `validateSlotRange - slot超过maxSlots返回 SLOT_OUT_OF_RANGE 错误`() {
        val result = StorageValidator.validateSlotRange(6, 5)

        assertFalse(result.isValid)
        assertEquals(1, result.errors.size)
        assertEquals("SLOT_OUT_OF_RANGE", result.errors[0].code)
    }

    @Test
    fun `validateSlotRange - 正常范围内的slot返回 valid`() {
        val result = StorageValidator.validateSlotRange(3, 10)

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    // ========== validateSaveData 测试 ==========

    private fun createValidSaveData(
        version: String = "1.6.00",
        timestamp: Long = System.currentTimeMillis()
    ): SaveData {
        return SaveData(
            version = version,
            timestamp = timestamp,
            gameData = GameData(),
            disciples = emptyList(),
            equipment = emptyList(),
            manuals = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )
    }

    @Test
    fun `validateSaveData - version为空返回 INVALID`() {
        val data = createValidSaveData(version = "")

        val result = StorageValidator.validateSaveData(data)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "EMPTY_VERSION" })
    }

    @Test
    fun `validateSaveData - timestamp小于等于0返回 INVALID`() {
        val data = createValidSaveData(timestamp = 0)

        val result = StorageValidator.validateSaveData(data)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "INVALID_TIMESTAMP" })
    }

    @Test
    fun `validateSaveData - timestamp为负数返回 INVALID`() {
        val data = createValidSaveData(timestamp = -100)

        val result = StorageValidator.validateSaveData(data)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "INVALID_TIMESTAMP" })
    }

    @Test
    fun `validateSaveData - 正常数据返回 valid`() {
        val data = createValidSaveData()

        val result = StorageValidator.validateSaveData(data)

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    // ========== ValidationResult 数据类测试 ==========

    @Test
    fun `ValidationResult - valid 的 isValid 为 true`() {
        val result = StorageValidator.ValidationResult.valid()

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `ValidationResult - error 的 isValid 为 false 且 errors size 为 1`() {
        val result = StorageValidator.ValidationResult.error("CODE", "message")

        assertFalse(result.isValid)
        assertEquals(1, result.errors.size)
        assertEquals("CODE", result.errors[0].code)
        assertEquals("message", result.errors[0].message)
    }

    @Test
    fun `ValidationResult - errors 的 errors 等于传入的 list`() {
        val issues = listOf(
            StorageValidator.ValidationIssue("A", "msgA"),
            StorageValidator.ValidationIssue("B", "msgB")
        )
        val result = StorageValidator.ValidationResult.errors(issues)

        assertFalse(result.isValid)
        assertEquals(2, result.errors.size)
        assertSame(issues, result.errors)
    }

    @Test
    fun `ValidationResult - validWithWarnings 的 warnings 非空`() {
        val warning = StorageValidator.ValidationIssue("WARN", "warning msg", StorageValidator.Severity.WARNING)
        val result = StorageValidator.ValidationResult.validWithWarnings(listOf(warning))

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
        assertEquals(1, result.warnings.size)
        assertEquals("WARN", result.warnings[0].code)
    }

    // ========== ValidationIssue 数据类测试 ==========

    @Test
    fun `ValidationIssue - 默认 severity 为 ERROR`() {
        val issue = StorageValidator.ValidationIssue("CODE", "message")

        assertEquals(StorageValidator.Severity.ERROR, issue.severity)
    }

    @Test
    fun `ValidationIssue - 默认 context 为空 map`() {
        val issue = StorageValidator.ValidationIssue("CODE", "message")

        assertTrue(issue.context.isEmpty())
    }

    @Test
    fun `ValidationIssue - 可自定义 severity 和 context`() {
        val context = mapOf("key" to "value")
        val issue = StorageValidator.ValidationIssue(
            code = "CODE",
            message = "message",
            severity = StorageValidator.Severity.WARNING,
            context = context
        )

        assertEquals(StorageValidator.Severity.WARNING, issue.severity)
        assertEquals(context, issue.context)
    }

    // ========== RuleEngine 测试 ==========

    @Test
    fun `RuleEngine - empty engine returns valid`() {
        val engine = StorageValidator.RuleEngine()
        val data = createValidSaveData()
        val result = engine.validate(data)
        assertTrue(result.isValid)
    }

    @Test
    fun `RuleEngine - addRule and validate`() {
        val engine = StorageValidator.RuleEngine()
        engine.addRule(StorageValidator.VersionRule)
        val data = createValidSaveData()
        val result = engine.validate(data)
        assertTrue(result.isValid)
    }

    @Test
    fun `RuleEngine - addRule detects violation`() {
        val engine = StorageValidator.RuleEngine()
        engine.addRule(StorageValidator.VersionRule)
        val data = createValidSaveData(version = "")
        val result = engine.validate(data)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "EMPTY_VERSION" })
    }

    @Test
    fun `RuleEngine - removeRule stops checking that rule`() {
        val engine = StorageValidator.RuleEngine()
        engine.addRule(StorageValidator.VersionRule)
        engine.removeRule("version_check")
        val data = createValidSaveData(version = "")
        val result = engine.validate(data)
        assertTrue(result.isValid)
    }

    @Test
    fun `RuleEngine - addRule replaces existing rule with same ruleId`() {
        val engine = StorageValidator.RuleEngine()
        val customRule = object : StorageValidator.ValidationRule {
            override val ruleId = "version_check"
            override fun validate(data: SaveData) = emptyList<StorageValidator.ValidationIssue>()
        }
        engine.addRule(StorageValidator.VersionRule)
        engine.addRule(customRule)
        val data = createValidSaveData(version = "")
        val result = engine.validate(data)
        assertTrue(result.isValid)
    }

    @Test
    fun `RuleEngine - getRules returns current rules`() {
        val engine = StorageValidator.RuleEngine()
        engine.addRule(StorageValidator.VersionRule)
        engine.addRule(StorageValidator.TimestampRule)
        assertEquals(2, engine.getRules().size)
    }

    @Test
    fun `RuleEngine - multiple rules collect all errors`() {
        val engine = StorageValidator.RuleEngine()
        engine.addRule(StorageValidator.VersionRule)
        engine.addRule(StorageValidator.TimestampRule)
        val data = createValidSaveData(version = "", timestamp = -1)
        val result = engine.validate(data)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "EMPTY_VERSION" })
        assertTrue(result.errors.any { it.code == "INVALID_TIMESTAMP" })
    }

    @Test
    fun `RuleEngine - rule exception produces RULE_EXCEPTION error`() {
        val engine = StorageValidator.RuleEngine()
        val failingRule = object : StorageValidator.ValidationRule {
            override val ruleId = "failing_rule"
            override fun validate(data: SaveData): List<StorageValidator.ValidationIssue> {
                throw RuntimeException("test failure")
            }
        }
        engine.addRule(failingRule)
        val data = createValidSaveData()
        val result = engine.validate(data)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "RULE_EXCEPTION" })
    }

    @Test
    fun `RuleEngine - WARNING severity goes to warnings not errors`() {
        val engine = StorageValidator.RuleEngine()
        val warningRule = object : StorageValidator.ValidationRule {
            override val ruleId = "warning_rule"
            override fun validate(data: SaveData) = listOf(
                StorageValidator.ValidationIssue("WARN_CODE", "warning", StorageValidator.Severity.WARNING)
            )
        }
        engine.addRule(warningRule)
        val data = createValidSaveData()
        val result = engine.validate(data)
        assertTrue(result.isValid)
        assertEquals(1, result.warnings.size)
        assertEquals("WARN_CODE", result.warnings[0].code)
    }

    @Test
    fun `RuleEngine - INFO severity goes to warnings`() {
        val engine = StorageValidator.RuleEngine()
        val infoRule = object : StorageValidator.ValidationRule {
            override val ruleId = "info_rule"
            override fun validate(data: SaveData) = listOf(
                StorageValidator.ValidationIssue("INFO_CODE", "info", StorageValidator.Severity.INFO)
            )
        }
        engine.addRule(infoRule)
        val data = createValidSaveData()
        val result = engine.validate(data)
        assertTrue(result.isValid)
        assertEquals(1, result.warnings.size)
    }

    // ========== 内置规则单独测试 ==========

    @Test
    fun `VersionRule - ruleId is version_check`() {
        assertEquals("version_check", StorageValidator.VersionRule.ruleId)
    }

    @Test
    fun `VersionRule - blank version returns error`() {
        val data = createValidSaveData(version = "   ")
        val issues = StorageValidator.VersionRule.validate(data)
        assertEquals(1, issues.size)
        assertEquals("EMPTY_VERSION", issues[0].code)
    }

    @Test
    fun `TimestampRule - ruleId is timestamp_check`() {
        assertEquals("timestamp_check", StorageValidator.TimestampRule.ruleId)
    }

    @Test
    fun `TimestampRule - zero timestamp returns error`() {
        val data = createValidSaveData(timestamp = 0)
        val issues = StorageValidator.TimestampRule.validate(data)
        assertEquals(1, issues.size)
        assertEquals("INVALID_TIMESTAMP", issues[0].code)
    }

    @Test
    fun `DiscipleCountRule - ruleId is disciple_count_check`() {
        assertEquals("disciple_count_check", StorageValidator.DiscipleCountRule.ruleId)
    }

    @Test
    fun `DiscipleCountRule - excessive count returns warning`() {
        val data = createValidSaveData().copy(disciples = List(10001) { com.xianxia.sect.core.model.Disciple() })
        val issues = StorageValidator.DiscipleCountRule.validate(data)
        assertTrue(issues.any { it.code == "EXCESSIVE_DISCIPLE_COUNT" })
        assertEquals(StorageValidator.Severity.WARNING, issues.first { it.code == "EXCESSIVE_DISCIPLE_COUNT" }.severity)
    }

    @Test
    fun `DiscipleCountRule - normal count returns empty`() {
        val data = createValidSaveData().copy(disciples = List(50) { com.xianxia.sect.core.model.Disciple() })
        val issues = StorageValidator.DiscipleCountRule.validate(data)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `ResourceRule - ruleId is resource_check`() {
        assertEquals("resource_check", StorageValidator.ResourceRule.ruleId)
    }

    @Test
    fun `ResourceRule - negative spiritStones returns warning`() {
        val data = createValidSaveData().copy(gameData = GameData(spiritStones = -100L))
        val issues = StorageValidator.ResourceRule.validate(data)
        assertEquals(1, issues.size)
        assertEquals("NEGATIVE_SPIRIT_STONES", issues[0].code)
        assertEquals(StorageValidator.Severity.WARNING, issues[0].severity)
    }

    @Test
    fun `ResourceRule - zero spiritStones is valid`() {
        val data = createValidSaveData().copy(gameData = GameData(spiritStones = 0L))
        val issues = StorageValidator.ResourceRule.validate(data)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `CrossFieldConsistencyRule - ruleId is cross_field_consistency`() {
        assertEquals("cross_field_consistency", StorageValidator.CrossFieldConsistencyRule.ruleId)
    }

    @Test
    fun `CrossFieldConsistencyRule - blank sectName returns warning`() {
        val data = createValidSaveData().copy(gameData = GameData(sectName = ""))
        val issues = StorageValidator.CrossFieldConsistencyRule.validate(data)
        assertTrue(issues.any { it.code == "EMPTY_SECT_NAME" })
    }

    @Test
    fun `CrossFieldConsistencyRule - non-blank sectName is valid`() {
        val data = createValidSaveData().copy(gameData = GameData(sectName = "青云宗"))
        val issues = StorageValidator.CrossFieldConsistencyRule.validate(data)
        assertTrue(issues.isEmpty())
    }

    // ========== validateSlotRange 边界测试 ==========

    @Test
    fun `validateSlotRange - slot 1 is valid`() {
        assertTrue(StorageValidator.validateSlotRange(1, 5).isValid)
    }

    @Test
    fun `validateSlotRange - maxSlots 0 and slot 0 is valid`() {
        assertTrue(StorageValidator.validateSlotRange(0, 0).isValid)
    }

    @Test
    fun `validateSlotRange - slot 1 with maxSlots 0 is out of range`() {
        assertFalse(StorageValidator.validateSlotRange(1, 0).isValid)
    }

    // ========== ValidationResult 工厂方法 ==========

    @Test
    fun `ValidationResult - errorWithWarnings has both errors and warnings`() {
        val errors = listOf(StorageValidator.ValidationIssue("E1", "error"))
        val warnings = listOf(StorageValidator.ValidationIssue("W1", "warn", StorageValidator.Severity.WARNING))
        val result = StorageValidator.ValidationResult.errorWithWarnings(errors, warnings)
        assertFalse(result.isValid)
        assertEquals(1, result.errors.size)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `ValidationResult - errorWithErrorsAndWarnings has both`() {
        val errors = listOf(StorageValidator.ValidationIssue("E1", "error"))
        val warnings = listOf(StorageValidator.ValidationIssue("W1", "warn", StorageValidator.Severity.WARNING))
        val result = StorageValidator.ValidationResult.errorWithErrorsAndWarnings(errors, warnings)
        assertFalse(result.isValid)
        assertEquals(1, result.errors.size)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `ValidationResult - error with context`() {
        val result = StorageValidator.ValidationResult.error("CODE", "msg", mapOf("key" to "val"))
        assertEquals("val", result.errors[0].context["key"])
    }

    // ========== Severity enum ==========

    @Test
    fun `Severity has 3 values`() {
        assertEquals(3, StorageValidator.Severity.values().size)
    }

    // ========== 内置 engine 实例测试 ==========

    @Test
    fun `engine has 5 default rules`() {
        assertEquals(5, StorageValidator.engine.getRules().size)
    }

    @Test
    fun `engine contains all built-in rules`() {
        val ruleIds = StorageValidator.engine.getRules().map { it.ruleId }.toSet()
        assertTrue(ruleIds.contains("version_check"))
        assertTrue(ruleIds.contains("timestamp_check"))
        assertTrue(ruleIds.contains("disciple_count_check"))
        assertTrue(ruleIds.contains("resource_check"))
        assertTrue(ruleIds.contains("cross_field_consistency"))
    }

    // ========== validateSaveData 综合测试 ==========

    @Test
    fun `validateSaveData - multiple violations returns all errors`() {
        val data = createValidSaveData(version = "", timestamp = -1)
        val result = StorageValidator.validateSaveData(data)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "EMPTY_VERSION" })
        assertTrue(result.errors.any { it.code == "INVALID_TIMESTAMP" })
    }

    @Test
    fun `validateSaveData - valid data with populated fields`() {
        val data = createValidSaveData().copy(
            gameData = GameData(sectName = "测试宗门", spiritStones = 5000L),
            disciples = List(10) { com.xianxia.sect.core.model.Disciple() }
        )
        val result = StorageValidator.validateSaveData(data)
        assertTrue(result.isValid)
    }
}
