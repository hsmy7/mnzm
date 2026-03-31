package com.xianxia.sect.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApplicationScopeProvider @Inject constructor() {
    private val supervisorJob = SupervisorJob()
    val scope: CoroutineScope = CoroutineScope(supervisorJob + Dispatchers.Default)
    
    val ioScope: CoroutineScope = CoroutineScope(supervisorJob + Dispatchers.IO)
    
    fun cancel() {
        supervisorJob.cancel()
    }
}
