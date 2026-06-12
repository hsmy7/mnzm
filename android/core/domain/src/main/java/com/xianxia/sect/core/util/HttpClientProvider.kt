package com.xianxia.sect.core.util

interface HttpClientProvider {
    suspend fun get(url: String): String
    suspend fun post(url: String, body: String): String
}
