package com.xianxia.sect.core.engine.system

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SystemPriority(val order: Int = 0)
