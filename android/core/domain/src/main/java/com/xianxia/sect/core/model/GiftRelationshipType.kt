package com.xianxia.sect.core.model

/**
 * 亲属赠送关系类型，用于亲属智能赠送机制。
 * 按亲密度降序排列：道侣 > 父母 > 子嗣 > 师父 > 徒弟 > 兄弟姐妹。
 */
enum class GiftRelationshipType {
    PARTNER, MASTER, APPRENTICE, PARENT, CHILD, SIBLING
}
