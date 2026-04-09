package com.xianxia.sect.data.cache

import java.util.BitSet

class CachePenetrationGuard(
    private val expectedInsertions: Int = 10_000,
    private val falsePositiveRate: Double = 0.01
) {
    companion object {
        private const val TAG = "CachePenetrationGuard"

        private fun optimalNumBits(n: Int, p: Double): Int {
            return (-n * Math.log(p) / (Math.log(2.0) * Math.log(2.0))).toInt()
        }

        private fun optimalNumHashFunctions(n: Int, m: Int): Int {
            return Math.max(1, (m.toDouble() / n * Math.log(2.0)).toInt())
        }
    }

    private val bitSet = BitSet(optimalNumBits(expectedInsertions, falsePositiveRate))
    private val numHashFunctions: Int
    private val numBits: Int

    init {
        numBits = optimalNumBits(expectedInsertions, falsePositiveRate)
        numHashFunctions = optimalNumHashFunctions(expectedInsertions, numBits)
    }

    private fun hash(key: String, seed: Int): Int {
        var hash = seed.toLong()
        for (char in key) {
            hash = 31 * hash + char.code
        }
        return ((hash % numBits) + numBits).toInt() % numBits
    }

    fun mightContain(key: String): Boolean {
        for (i in 0 until numHashFunctions) {
            if (!bitSet.get(hash(key, i))) return false
        }
        return true
    }

    fun put(key: String) {
        for (i in 0 until numHashFunctions) {
            bitSet.set(hash(key, i))
        }
    }

    fun remove(key: String) {
        // Standard bloom filter doesn't support removal; use counting variant or ignore
        // For simplicity, we just don't remove (keys will naturally age out)
    }

    fun clear() {
        bitSet.clear()
    }

    val approximateSize: Int get() = bitSet.cardinality()
    val expectedFalsePositiveRate: Double get() = Math.pow(1.0 - Math.exp(-numHashFunctions.toDouble() * expectedInsertions.toDouble() / numBits), numHashFunctions.toDouble())
}
