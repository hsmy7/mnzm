package com.xianxia.sect.data.concurrent

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class AsyncRWLock {
    private val readMutex = Mutex()
    private val writeMutex = Mutex()
    private val readers = AtomicInteger(0)
    private val writerActive = AtomicBoolean(false)
    private val readerDoneChannel = Channel<Unit>(Channel.UNLIMITED)
    private val writeQueue = Channel<Unit>(Channel.UNLIMITED)
    private val pendingWriters = AtomicInteger(0)
    
    suspend fun <T> read(block: suspend () -> T): T {
        return readMutex.withLock {
            while (writerActive.get() || pendingWriters.get() > 0) {
                writeQueue.receive()
            }
            
            readers.incrementAndGet()
            try {
                block()
            } finally {
                if (readers.decrementAndGet() == 0) {
                    readerDoneChannel.trySend(Unit)
                }
            }
        }
    }
    
    suspend fun <T> write(block: suspend () -> T): T {
        pendingWriters.incrementAndGet()
        
        return writeMutex.withLock {
            pendingWriters.decrementAndGet()
            writerActive.set(true)
            
            try {
                while (readers.get() > 0) {
                    readerDoneChannel.receive()
                }
                block()
            } finally {
                writerActive.set(false)
                
                repeat(readers.get().coerceAtLeast(1)) {
                    writeQueue.trySend(Unit)
                }
            }
        }
    }
    
    fun tryRead(): Boolean {
        if (writerActive.get() || pendingWriters.get() > 0) {
            return false
        }
        readers.incrementAndGet()
        return true
    }
    
    fun endRead() {
        if (readers.decrementAndGet() == 0) {
            readerDoneChannel.trySend(Unit)
        }
    }
    
    fun getReaderCount(): Int = readers.get()
    
    fun isWriterActive(): Boolean = writerActive.get()
    
    fun getPendingWriterCount(): Int = pendingWriters.get()
}

class AsyncRWLockManager {
    private val locks = mutableMapOf<String, AsyncRWLock>()
    private val locksMutex = Mutex()
    
    suspend fun <T> withReadLock(key: String, block: suspend () -> T): T {
        val lock = getOrCreateLock(key)
        return lock.read(block)
    }
    
    suspend fun <T> withWriteLock(key: String, block: suspend () -> T): T {
        val lock = getOrCreateLock(key)
        return lock.write(block)
    }
    
    private suspend fun getOrCreateLock(key: String): AsyncRWLock {
        return locksMutex.withLock {
            locks.getOrPut(key) { AsyncRWLock() }
        }
    }
    
    fun removeLock(key: String) {
        locks.remove(key)
    }
    
    fun clear() {
        locks.clear()
    }
    
    fun getLockKeys(): Set<String> = locks.keys.toSet()
}

class SegmentedAsyncRWLock(
    private val segmentCount: Int = 16
) {
    private val segments = Array(segmentCount) { AsyncRWLock() }
    
    private fun getSegmentIndex(key: String): Int {
        return Math.abs(key.hashCode() % segmentCount)
    }
    
    suspend fun <T> withReadLock(key: String, block: suspend () -> T): T {
        return segments[getSegmentIndex(key)].read(block)
    }
    
    suspend fun <T> withWriteLock(key: String, block: suspend () -> T): T {
        return segments[getSegmentIndex(key)].write(block)
    }
    
    fun getSegmentStats(): List<SegmentStats> {
        return segments.mapIndexed { index, lock ->
            SegmentStats(
                segmentIndex = index,
                readerCount = lock.getReaderCount(),
                writerActive = lock.isWriterActive(),
                pendingWriters = lock.getPendingWriterCount()
            )
        }
    }
    
    data class SegmentStats(
        val segmentIndex: Int,
        val readerCount: Int,
        val writerActive: Boolean,
        val pendingWriters: Int
    )
}

class PriorityAsyncRWLock {
    private val readMutex = Mutex()
    private val writeMutex = Mutex()
    private val highPriorityWriteMutex = Mutex()
    private val readers = AtomicInteger(0)
    private val writerActive = AtomicBoolean(false)
    private val readerDoneChannel = Channel<Unit>(Channel.UNLIMITED)
    
    suspend fun <T> read(block: suspend () -> T): T {
        return readMutex.withLock {
            while (writerActive.get()) {
                readerDoneChannel.receive()
            }
            
            readers.incrementAndGet()
            try {
                block()
            } finally {
                if (readers.decrementAndGet() == 0) {
                    readerDoneChannel.trySend(Unit)
                }
            }
        }
    }
    
    suspend fun <T> write(block: suspend () -> T): T {
        return writeMutex.withLock {
            writerActive.set(true)
            
            try {
                while (readers.get() > 0) {
                    readerDoneChannel.receive()
                }
                block()
            } finally {
                writerActive.set(false)
                readerDoneChannel.trySend(Unit)
            }
        }
    }
    
    suspend fun <T> highPriorityWrite(block: suspend () -> T): T {
        return highPriorityWriteMutex.withLock {
            writerActive.set(true)
            
            try {
                while (readers.get() > 0) {
                    readerDoneChannel.receive()
                }
                block()
            } finally {
                writerActive.set(false)
                readerDoneChannel.trySend(Unit)
            }
        }
    }
}

class ReentrantAsyncRWLock {
    private val lock = AsyncRWLock()
    private val readOwners = mutableMapOf<Long, Int>()
    private val writeOwner = mutableMapOf<Long, Int>()
    private val ownersMutex = Mutex()
    
    suspend fun <T> read(block: suspend () -> T): T {
        val threadId = Thread.currentThread().id
        
        return ownersMutex.withLock {
            val currentCount = readOwners[threadId] ?: 0
            if (currentCount > 0 || writeOwner.containsKey(threadId)) {
                readOwners[threadId] = currentCount + 1
                return@withLock null
            }
            null
        }?.let { 
            @Suppress("UNCHECKED_CAST")
            it as T 
        } ?: lock.read {
            ownersMutex.withLock {
                readOwners[threadId] = (readOwners[threadId] ?: 0) + 1
            }
            
            try {
                block()
            } finally {
                ownersMutex.withLock {
                    val count = readOwners[threadId] ?: 1
                    if (count <= 1) {
                        readOwners.remove(threadId)
                    } else {
                        readOwners[threadId] = count - 1
                    }
                }
            }
        }
    }
    
    suspend fun <T> write(block: suspend () -> T): T {
        val threadId = Thread.currentThread().id
        
        return ownersMutex.withLock {
            val currentCount = writeOwner[threadId] ?: 0
            if (currentCount > 0) {
                writeOwner[threadId] = currentCount + 1
                return@withLock null
            }
            null
        }?.let {
            @Suppress("UNCHECKED_CAST")
            it as T
        } ?: lock.write {
            ownersMutex.withLock {
                writeOwner[threadId] = (writeOwner[threadId] ?: 0) + 1
            }
            
            try {
                block()
            } finally {
                ownersMutex.withLock {
                    val count = writeOwner[threadId] ?: 1
                    if (count <= 1) {
                        writeOwner.remove(threadId)
                    } else {
                        writeOwner[threadId] = count - 1
                    }
                }
            }
        }
    }
}
