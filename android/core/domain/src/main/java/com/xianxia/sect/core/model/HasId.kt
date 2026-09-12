package com.xianxia.sect.core.model

/**
 * 实体 ID 标识接口。
 * 所有需要使用 EntityStore 存储的实体必须实现此接口。
 */
interface HasId {
    val id: String
}
