package com.xianxia.sect.data.serialization.unified

data class SchemaVersion(val major: Int, val minor: Int = 0) : Comparable<SchemaVersion> {
    override fun compareTo(other: SchemaVersion): Int {
        val majorCmp = major.compareTo(other.major)
        return if (majorCmp != 0) majorCmp else minor.compareTo(other.minor)
    }

    override fun toString(): String = if (minor == 0) "$major" else "$major.$minor"

    companion object {
        fun parse(value: String): SchemaVersion {
            val parts = value.split(".")
            return if (parts.size >= 2) {
                SchemaVersion(parts[0].toIntOrNull() ?: 1, parts[1].toIntOrNull() ?: 0)
            } else {
                SchemaVersion(value.toIntOrNull() ?: 1)
            }
        }

        val CURRENT = SchemaVersion(1)
    }
}
