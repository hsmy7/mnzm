package com.xianxia.sect.core.engine.system

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForgingSubsystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "ForgingSubsystem"
        const val SYSTEM_NAME = "ForgingSubsystem"
    }
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "ForgingSubsystem initialized")
    }
    
    override fun release() {
        Log.d(TAG, "ForgingSubsystem released")
    }
    
    override suspend fun clear() {
    }
}
