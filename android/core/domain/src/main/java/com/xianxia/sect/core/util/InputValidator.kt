package com.xianxia.sect.core.util

object InputValidator {
    
    const val MIN_SECT_NAME_LENGTH = 2
    const val MAX_SECT_NAME_LENGTH = 6
    const val MIN_DISCIPLE_NAME_LENGTH = 2
    const val MAX_DISCIPLE_NAME_LENGTH = 10
    const val MAX_REDEEM_CODE_LENGTH = 64
    const val MIN_SAVE_NAME_LENGTH = 1
    const val MAX_SAVE_NAME_LENGTH = 30
    
    private val INVALID_CHARS = Regex("[<>\"'&\\\\/]")
    private val VALID_SECT_NAME_PATTERN = Regex("^[\\u4e00-\\u9fa5a-zA-Z0-9]+$")
    private val VALID_DISCIPLE_NAME_PATTERN = Regex("^[\\u4e00-\\u9fa5a-zA-Z]+$")
    private val VALID_REDEEM_CODE_PATTERN = Regex("^[\\u4e00-\\u9fa5A-Za-z0-9\\-_]+$")
    private val VALID_SAVE_NAME_PATTERN = Regex("^[\\u4e00-\\u9fa5a-zA-Z0-9\\s\\-_]+$")
    
    fun validateSectName(name: String): String? {
        val trimmed = name.trim()
        return firstFailure(
            { requiredCheck(trimmed, "宗门名称") },
            { lengthRangeCheck(trimmed, "宗门名称", MIN_SECT_NAME_LENGTH, MAX_SECT_NAME_LENGTH) },
            { invalidCharsCheck(trimmed, "宗门名称") },
            { patternCheck(trimmed, VALID_SECT_NAME_PATTERN, "宗门名称只能包含中文、英文和数字") },
            { bannedWordCheck(trimmed, "宗门名称") },
        )
    }

    fun validateDiscipleName(name: String): String? {
        val trimmed = name.trim()
        return firstFailure(
            { requiredCheck(trimmed, "弟子名称") },
            { lengthRangeCheck(trimmed, "弟子名称", MIN_DISCIPLE_NAME_LENGTH, MAX_DISCIPLE_NAME_LENGTH) },
            { invalidCharsCheck(trimmed, "弟子名称") },
            { patternCheck(trimmed, VALID_DISCIPLE_NAME_PATTERN, "弟子名称只能包含中文和英文") },
            { bannedWordCheck(trimmed, "弟子名称") },
        )
    }

    fun validateRedeemCode(code: String): String? {
        val trimmed = code.trim()

        if (trimmed.isEmpty()) {
            return "兑换码不能为空"
        }

        if (trimmed.length > MAX_REDEEM_CODE_LENGTH) {
            return "兑换码过长"
        }

        if (!VALID_REDEEM_CODE_PATTERN.matches(trimmed)) {
            return "兑换码包含非法字符"
        }

        return null
    }

    fun validateSaveName(name: String): String? {
        val trimmed = name.trim()
        return firstFailure(
            { requiredCheck(trimmed, "存档名称") },
            {
                lengthRangeCheck(
                    trimmed, "存档名称", MIN_SAVE_NAME_LENGTH, MAX_SAVE_NAME_LENGTH, "存档名称过长"
                )
            },
            { invalidCharsCheck(trimmed, "存档名称") },
            { patternCheck(trimmed, VALID_SAVE_NAME_PATTERN, "存档名称只能包含中文、英文、数字和常见符号") },
        )
    }

    fun validateSpiritStones(amount: Long, minRequired: Long = 0): String? {
        if (amount < 0) {
            return "灵石数量不能为负数"
        }

        if (amount < minRequired) {
            return "灵石不足，需要${minRequired}灵石"
        }

        return null
    }

    fun validateQuantity(quantity: Int, min: Int = 1, max: Int = Int.MAX_VALUE): String? {
        if (quantity < min) {
            return "数量不能小于${min}"
        }

        if (quantity > max) {
            return "数量不能超过${max}"
        }

        return null
    }

    fun validateTeamName(name: String): String? {
        val trimmed = name.trim()

        if (trimmed.isEmpty()) {
            return "队伍名称不能为空"
        }

        if (trimmed.length > 15) {
            return "队伍名称不能超过15个字符"
        }

        if (INVALID_CHARS.containsMatchIn(trimmed)) {
            return "队伍名称包含非法字符"
        }

        return null
    }
    
    fun sanitizeInput(input: String): String {
        return input.trim()
            .replace(INVALID_CHARS, "")
            .replace(Regex("\\s+"), " ")
    }
}

// ==================== 有序规则表引擎（错误提示按规则优先级取首个失败） ====================
// 文件级私有（object 函数数受 TooManyFunctions thresholdInObjects=12 约束）——
// 规则引擎为纯函数，无需驻留对象。

/** 非法字符正则（与 [InputValidator] 清洗用同一字符集） */
private val INPUT_INVALID_CHARS = Regex("[<>\"'&\\\\/]")

/** 依序执行校验规则，返回首个失败消息；全部通过返回 null */
private fun firstFailure(vararg checks: () -> String?): String? {
    for (check in checks) {
        val failure = check()
        if (failure != null) return failure
    }
    return null
}

private fun requiredCheck(value: String, label: String): String? =
    if (value.isEmpty()) "${label}不能为空" else null

private fun lengthRangeCheck(
    value: String,
    label: String,
    min: Int,
    max: Int,
    overflowMessage: String = "${label}不能超过${max}个字符"
): String? = when {
    value.length < min -> "${label}至少需要${min}个字符"
    value.length > max -> overflowMessage
    else -> null
}

private fun invalidCharsCheck(value: String, label: String): String? =
    if (INPUT_INVALID_CHARS.containsMatchIn(value)) "${label}包含非法字符" else null

private fun patternCheck(value: String, pattern: Regex, failureMessage: String): String? =
    if (!pattern.matches(value)) failureMessage else null

private fun bannedWordCheck(value: String, label: String): String? {
    if (!BannedWords.containsBannedWord(value)) return null
    val found = BannedWords.findFirstBannedWord(value)
    return "${label}包含违禁词（$found）"
}
