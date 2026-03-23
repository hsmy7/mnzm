package com.xianxia.sect.core.engine.coordinator

import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.CircularBuffer
import com.xianxia.sect.core.util.ListenerManager
import com.xianxia.sect.core.util.PerformanceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameLoopCoordinator @Inject constructor(
    private val performanceMonitor: PerformanceMonitor
) {
    companion object {
        private const val TAG = "GameLoopCoordinator"
        private const val TICK_INTERVAL_MS = GameConfig.Time.TICK_INTERVAL
        private const val TICKS_PER_SECOND = GameConfig.Time.TICKS_PER_SECOND
        private const val SECONDS_PER_MONTH = GameConfig.Time.SECONDS_PER_REAL_MONTH
        private const val MAX_TICK_SAMPLES = GameConfig.Performance.MAX_TICK_SAMPLES
    }
    
    enum class GameState {
        STOPPED,
        RUNNING,
        PAUSED,
        ERROR
    }
    
    data class GameLoopStats(
        val totalTicks: Long,
        val totalSeconds: Long,
        val totalMonths: Long,
        val averageTickTimeMs: Double,
        val maxTickTimeMs: Long,
        val lastTickTimeMs: Long,
        val state: GameState,
        val currentYear: Int,
        val currentMonth: Int
    )
    
    interface GameLoopListener {
        fun onTick(tickNumber: Long)
        fun onSecondTick(secondNumber: Long)
        fun onMonthTick(year: Int, month: Int)
        fun onYearTick(year: Int)
        fun onStateChanged(oldState: GameState, newState: GameState)
        fun onError(error: Throwable)
    }
    
    private val scope = CoroutineScope(Dispatchers.Default)
    private var gameLoopJob: Job? = null
    private val currentStateRef = AtomicReference(GameState.STOPPED)
    
    private var tickCounter = 0L
    private var secondCounter = 0L
    private var monthCounter = 0L
    
    @Volatile
    private var currentYear = 1
    @Volatile
    private var currentMonth = 1
    
    private val tickTimes = CircularBuffer<Long>(MAX_TICK_SAMPLES)
    @Volatile
    private var maxTickTime = 0L
    @Volatile
    private var lastTickTime = 0L
    
    private val listeners = ListenerManager<GameLoopListener>(TAG)
    
    @Volatile
    private var onTickCallback: (() -> Unit)? = null
    @Volatile
    private var onSecondTickCallback: (() -> Unit)? = null
    @Volatile
    private var onMonthTickCallback: ((Int, Int) -> Unit)? = null
    @Volatile
    private var onYearTickCallback: ((Int) -> Unit)? = null
    
    private val stateLock = Any()
    
    fun setCallbacks(
        onTick: (() -> Unit)? = null,
        onSecondTick: (() -> Unit)? = null,
        onMonthTick: ((Int, Int) -> Unit)? = null,
        onYearTick: ((Int) -> Unit)? = null
    ) {
        onTickCallback = onTick
        onSecondTickCallback = onSecondTick
        onMonthTickCallback = onMonthTick
        onYearTickCallback = onYearTick
    }
    
    fun addListener(listener: GameLoopListener) = listeners.add(listener)
    
    fun removeListener(listener: GameLoopListener) = listeners.remove(listener)
    
    fun start() {
        synchronized(stateLock) {
            if (currentStateRef.get() == GameState.RUNNING) {
                Log.w(TAG, "Game loop already running")
                return
            }
            
            val oldState = currentStateRef.getAndSet(GameState.RUNNING)
            notifyStateChanged(oldState, GameState.RUNNING)
            
            gameLoopJob = scope.launch {
                Log.i(TAG, "Game loop started")
                
                while (isActive && currentStateRef.get() == GameState.RUNNING) {
                    try {
                        val tickStart = System.nanoTime()
                        
                        processTick()
                        
                        val tickDuration = (System.nanoTime() - tickStart) / 1_000_000
                        recordTickTime(tickDuration)
                        
                        if (tickDuration > TICK_INTERVAL_MS) {
                            Log.w(TAG, "Tick took ${tickDuration}ms, longer than interval ${TICK_INTERVAL_MS}ms")
                        }
                        
                        delay(TICK_INTERVAL_MS)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in game loop", e)
                        notifyError(e)
                        
                        if (currentStateRef.get() == GameState.RUNNING) {
                            delay(1000)
                        }
                    }
                }
                
                Log.i(TAG, "Game loop stopped")
            }
        }
    }
    
    fun stop() {
        synchronized(stateLock) {
            if (currentStateRef.get() == GameState.STOPPED) return
            
            val oldState = currentStateRef.getAndSet(GameState.STOPPED)
            
            gameLoopJob?.cancel()
            gameLoopJob = null
            
            notifyStateChanged(oldState, GameState.STOPPED)
        }
    }
    
    fun pause() {
        synchronized(stateLock) {
            if (currentStateRef.get() != GameState.RUNNING) return
            
            val oldState = currentStateRef.getAndSet(GameState.PAUSED)
            notifyStateChanged(oldState, GameState.PAUSED)
        }
    }
    
    fun resume() {
        synchronized(stateLock) {
            if (currentStateRef.get() != GameState.PAUSED) return
            
            val oldState = currentStateRef.getAndSet(GameState.RUNNING)
            notifyStateChanged(oldState, GameState.RUNNING)
            
            if (gameLoopJob == null || gameLoopJob?.isActive == false) {
                gameLoopJob = scope.launch {
                    Log.i(TAG, "Game loop resumed")
                    
                    while (isActive && currentStateRef.get() == GameState.RUNNING) {
                        try {
                            val tickStart = System.nanoTime()
                            
                            processTick()
                            
                            val tickDuration = (System.nanoTime() - tickStart) / 1_000_000
                            recordTickTime(tickDuration)
                            
                            if (tickDuration > TICK_INTERVAL_MS) {
                                Log.w(TAG, "Tick took ${tickDuration}ms, longer than interval ${TICK_INTERVAL_MS}ms")
                            }
                            
                            delay(TICK_INTERVAL_MS)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in game loop", e)
                            notifyError(e)
                            
                            if (currentStateRef.get() == GameState.RUNNING) {
                                delay(1000)
                            }
                        }
                    }
                }
            }
        }
    }
    
    fun setTime(year: Int, month: Int) {
        synchronized(stateLock) {
            currentYear = year
            currentMonth = month
        }
    }
    
    private fun processTick() {
        tickCounter++
        
        onTickCallback?.invoke()
        notifyTick()
        
        if (tickCounter % TICKS_PER_SECOND == 0L) {
            processSecondTick()
        }
    }
    
    private fun processSecondTick() {
        secondCounter++
        
        onSecondTickCallback?.invoke()
        notifySecondTick()
        
        if (secondCounter % SECONDS_PER_MONTH == 0L) {
            processMonthTick()
        }
    }
    
    private fun processMonthTick() {
        monthCounter++
        
        currentMonth++
        if (currentMonth > GameConfig.Time.MONTHS_PER_YEAR) {
            currentMonth = 1
            currentYear++
            
            onYearTickCallback?.invoke(currentYear)
            notifyYearTick(currentYear)
        }
        
        onMonthTickCallback?.invoke(currentYear, currentMonth)
        notifyMonthTick(currentYear, currentMonth)
    }
    
    private fun recordTickTime(tickTimeMs: Long) {
        lastTickTime = tickTimeMs
        maxTickTime = maxOf(maxTickTime, tickTimeMs)
        tickTimes.add(tickTimeMs)
    }
    
    fun getStats(): GameLoopStats {
        val avgTickTime = tickTimes.average()
        
        return GameLoopStats(
            totalTicks = tickCounter,
            totalSeconds = secondCounter,
            totalMonths = monthCounter,
            averageTickTimeMs = avgTickTime,
            maxTickTimeMs = maxTickTime,
            lastTickTimeMs = lastTickTime,
            state = currentStateRef.get(),
            currentYear = currentYear,
            currentMonth = currentMonth
        )
    }
    
    fun getCurrentState(): GameState = currentStateRef.get()
    
    fun getCurrentTime(): Pair<Int, Int> = Pair(currentYear, currentMonth)
    
    fun resetCounters() {
        tickCounter = 0
        secondCounter = 0
        monthCounter = 0
        tickTimes.clear()
        maxTickTime = 0
        lastTickTime = 0
    }
    
    private fun notifyTick() = listeners.notify { it.onTick(tickCounter) }
    
    private fun notifySecondTick() = listeners.notify { it.onSecondTick(secondCounter) }
    
    private fun notifyMonthTick(year: Int, month: Int) = listeners.notify { it.onMonthTick(year, month) }
    
    private fun notifyYearTick(year: Int) = listeners.notify { it.onYearTick(year) }
    
    private fun notifyStateChanged(oldState: GameState, newState: GameState) = 
        listeners.notify { it.onStateChanged(oldState, newState) }
    
    private fun notifyError(error: Throwable) = listeners.notify { it.onError(error) }
    
    fun cleanup() {
        stop()
        listeners.clear()
        onTickCallback = null
        onSecondTickCallback = null
        onMonthTickCallback = null
        onYearTickCallback = null
        tickTimes.clear()
    }
}
