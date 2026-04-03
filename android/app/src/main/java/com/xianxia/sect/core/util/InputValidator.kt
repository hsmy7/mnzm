package com.xianxia.sect.core.util

object InputValidator {
    
    const val MIN_SECT_NAME_LENGTH = 2
    const val MAX_SECT_NAME_LENGTH = 20
    const val MIN_DISCIPLE_NAME_LENGTH = 2
    const val MAX_DISCIPLE_NAME_LENGTH = 10
    
    private val INVALID_CHARS = Regex("[<>\"'&\\\\/]")
    private val VALID_SECT_NAME_PATTERN = Regex("^[\\u4e00-\\u9fa5a-zA-Z0-9]+$")
    private val VALID_DISCIPLE_NAME_PATTERN = Regex("^[\\u4e00-\\u9fa5a-zA-Z]+$")
    
    fun validateSectName(name: String): ValidationResult {
        val trimmed = name.trim()
        
        if (trimmed.isEmpty()) {
            return ValidationResult.Error("宗门名称不能为空")
        }
        
        if (trimmed.length < MIN_SECT_NAME_LENGTH) {
            return ValidationResult.Error("宗门名称至少需要${MIN_SECT_NAME_LENGTH}个字符")
        }
        
        if (trimmed.length > MAX_SECT_NAME_LENGTH) {
            return ValidationResult.Error("宗门名称不能超过${MAX_SECT_NAME_LENGTH}个字符")
        }
        
        if (INVALID_CHARS.containsMatchIn(trimmed)) {
            return ValidationResult.Error("宗门名称包含非法字符")
        }
        
        if (!VALID_SECT_NAME_PATTERN.matches(trimmed)) {
            return ValidationResult.Error("宗门名称只能包含中文、英文和数字")
        }
        
        return ValidationResult.Success(trimmed)
    }
    
    fun validateDiscipleName(name: String): ValidationResult {
        val trimmed = name.trim()
        
        if (trimmed.isEmpty()) {
            return ValidationResult.Error("弟子名称不能为空")
        }
        
        if (trimmed.length < MIN_DISCIPLE_NAME_LENGTH) {
            return ValidationResult.Error("弟子名称至少需要${MIN_DISCIPLE_NAME_LENGTH}个字符")
        }
        
        if (trimmed.length > MAX_DISCIPLE_NAME_LENGTH) {
            return ValidationResult.Error("弟子名称不能超过${MAX_DISCIPLE_NAME_LENGTH}个字符")
        }
        
        if (INVALID_CHARS.containsMatchIn(trimmed)) {
            return ValidationResult.Error("弟子名称包含非法字符")
        }
        
        if (!VALID_DISCIPLE_NAME_PATTERN.matches(trimmed)) {
            return ValidationResult.Error("弟子名称只能包含中文和英文")
        }
        
        return ValidationResult.Success(trimmed)
    }
    
    fun validateSpiritStones(amount: Long, minRequired: Long = 0): ValidationResult {
        if (amount < 0) {
            return ValidationResult.Error("灵石数量不能为负数")
        }
        
        if (amount < minRequired) {
            return ValidationResult.Error("灵石不足，需要${minRequired}灵石")
        }
        
        return ValidationResult.SuccessLong(amount)
    }
    
    fun validateQuantity(quantity: Int, min: Int = 1, max: Int = Int.MAX_VALUE): ValidationResult {
        if (quantity < min) {
            return ValidationResult.Error("数量不能小于${min}")
        }
        
        if (quantity > max) {
            return ValidationResult.Error("数量不能超过${max}")
        }
        
        return ValidationResult.SuccessInt(quantity)
    }
    
    fun validateTeamName(name: String): ValidationResult {
        val trimmed = name.trim()
        
        if (trimmed.isEmpty()) {
            return ValidationResult.Error("队伍名称不能为空")
        }
        
        if (trimmed.length > 15) {
            return ValidationResult.Error("队伍名称不能超过15个字符")
        }
        
        if (INVALID_CHARS.containsMatchIn(trimmed)) {
            return ValidationResult.Error("队伍名称包含非法字符")
        }
        
        return ValidationResult.Success(trimmed)
    }
    
    fun sanitizeInput(input: String): String {
        return input.trim()
            .replace(INVALID_CHARS, "")
            .replace(Regex("\\s+"), " ")
    }
}

sealed class ValidationResult {
    data class Success(val value: String) : ValidationResult()
    data class SuccessLong(val value: Long) : ValidationResult()
    data class SuccessInt(val value: Int) : ValidationResult()
    data class Error(val message: String) : ValidationResult()
    
    val isSuccess: Boolean get() = this !is Error
    val isError: Boolean get() = this is Error
}
