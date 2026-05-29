package com.xianxia.sect.data.serialization.unified

data class DataLimits(
    val maxDiscipleCount: Int = 5000,
    val maxBattleLogCount: Int = 10000
) {
    companion object {
        val DEFAULT = DataLimits()
        val LENIENT = DataLimits(
            maxDiscipleCount = 20000,
            maxBattleLogCount = 50000
        )
    }
}

data class DataLimitViolation(
    val domain: String,
    val actualValue: Long,
    val limit: Long,
    val unit: String = "items"
) {
    override fun toString(): String =
        "DataLimitViolation(domain='$domain', actual=$actualValue$unit, limit=$limit$unit)"
}

data class DataLimitValidationResult(
    val isValid: Boolean,
    val violations: List<DataLimitViolation> = emptyList()
) {
    val hasViolations: Boolean get() = violations.isNotEmpty()
}
