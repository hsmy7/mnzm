package com.xianxia.sect.data.compression

/**
 * 压缩算法枚举
 *
 * 定义支持的压缩算法类型，用于 DataCompressor 的配置。
 *
 * 算法选择指南：
 * - LZ4: 速度优先（~500MB/s 压缩，2-3x 比率），适合 auto-save、实时缓存
 * - GZIP: 兼容性优先（legacy 格式），适合跨平台数据交换
 * - ZSTD: 压缩比优先（~100MB/s，3-5x 比率），适合 cloud upload / full save
 */
enum class CompressionAlgorithm {
    /**
     * LZ4 压缩算法
     *
     * - 优点：速度极快，适合实时压缩场景
     * - 缺点：压缩率相对较低
     * - 适用场景：频繁读写的缓存数据、实时同步数据、auto-save
     * - 性能：~500MB/s 压缩速度，压缩比 2-3x
     */
    LZ4,

    /**
     * GZIP 压缩算法
     *
     * - 优点：压缩率高，兼容性好
     * - 缺点：压缩/解压速度较慢
     * - 适用场景：存储空间敏感的归档数据、网络传输数据、legacy 格式兼容
     * - 性能：~30MB/s 压缩速度，压缩比 3-4x
     */
    GZIP,

    /**
     * Zstandard (ZSTD) 压缩算法
     *
     * - 优点：压缩率极高，解压速度快（接近 LZ4）
     * - 缺点：压缩速度中等，Android 不原生支持（需 JNI 库）
     * - 适用场景：云存档上传、完整存档备份、大文件归档
     * - 性能：~100MB/s 压缩速度，~400MB/s 解压速度，压缩比 3-5x
     * - Fallback: 若 zstd-jni 库不可用，自动降级到 GZIP 并记录警告
     */
    ZSTD,

    /**
     * 无压缩（直接存储）
     *
     * - 优点：零开销，最快速度
     * - 缺点：不节省空间
     * - 适用场景：小体积数据、已经压缩过的数据
     */
    NONE;

    companion object {
        /**
         * 根据数据大小推荐最佳算法
         *
         * @param dataSize 数据大小（字节）
         * @return 推荐的压缩算法
         */
        fun recommendForSize(dataSize: Int): CompressionAlgorithm = when {
            dataSize < 1024 -> NONE          // 小于 1KB 不压缩
            dataSize < 100 * 1024 -> LZ4    // 小于 100KB 用 LZ4（速度快）
            else -> GZIP                    // 大文件用 GZIP（压缩率高）
        }

        /**
         * 默认算法（平衡速度和压缩率）
         */
        val DEFAULT = LZ4
    }
}
