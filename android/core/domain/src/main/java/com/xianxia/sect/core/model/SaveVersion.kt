package com.xianxia.sect.core.model

/**
 * 存档数据版本号权威定义（domain 层，engine/data 共用）。
 *
 * 版本号是云档/本地档的版本戳（跨设备识别用）：engine 模块（创建新档/重启）
 * 与 data 模块（保存盖章）都必须引用本常量盖章或比较，禁止硬编码版本号。
 */
object SaveVersion {
    /** 当前存档数据版本号 */
    const val CURRENT = 2
}
