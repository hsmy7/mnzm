package com.xianxia.sect.data.unified

/**
 * 序列化过程中发生的不可恢复异常。
 *
 * 统一抛出此异常替代 kotlinx.serialization.SerializationException，
 * 避免下游依赖序列化框架的具体异常类型。
 */
class SerializationException(message: String, cause: Throwable? = null) : Exception(message, cause)
