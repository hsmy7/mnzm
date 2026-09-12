package com.xianxia.sect.data.crypto

import java.util.Arrays

/**
 * 时序安全比较
 *
 * 此方法始终比较所有字节，无论是否发现不匹配，
 * 从而消除时序侧信道风险。
 *
 * 用途：HMAC 签名验证、密码比较等安全敏感场景
 *
 * @param a 第一个字节数组
 * @param b 第二个字节数组
 * @return 如果内容完全相同返回 true，否则返回 false
 */
fun timingSafeEqual(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false

    var result = 0
    for (i in a.indices) {
        result = result or (a[i].toInt() xor b[i].toInt())
    }
    return result == 0
}

/**
 * 安全清除敏感字节数据
 *
 * 将数组每个字节设为 0，然后请求 GC 回收。
 * 注意：JVM 不保证即时 GC，但填充零值可降低内存转储风险。
 *
 * @param data 需要清除的敏感数据
 */
fun securelyClear(data: ByteArray?) {
    if (data == null) return
    Arrays.fill(data, 0.toByte())
}
