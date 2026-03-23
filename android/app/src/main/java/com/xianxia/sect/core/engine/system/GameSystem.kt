package com.xianxia.sect.core.engine.system

interface GameSystem {
    val systemName: String
    fun initialize()
    fun release()
    fun clear()
}
