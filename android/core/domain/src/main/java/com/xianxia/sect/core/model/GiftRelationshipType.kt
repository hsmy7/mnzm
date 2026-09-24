package com.xianxia.sect.core.model

/**
 * 师徒赠送关系类型，用于师徒智能赠送机制。
 * 按赠送概率降序排列：师父（0.40）> 徒弟（0.30）。
 */
enum class GiftRelationshipType {
    MASTER, APPRENTICE
}
