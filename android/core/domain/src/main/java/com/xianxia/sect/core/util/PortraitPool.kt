package com.xianxia.sect.core.util

/**
 * 弟子肖像池。
 *
 * 管理所有弟子头像资源名称，并提供预构建的资源 ID 映射以避免运行时
 * [android.content.res.Resources.getIdentifier] 的字符串查找开销。
 *
 * 使用方式：
 * 1. 应用启动时调用 [initialize(context)] 预构建资源 ID 映射
 * 2. 后续通过 [getResourceId(name)] 直接 Int 查找，零开销
 */
object PortraitPool {
    private val malePortraits = (1..20).map { "male_disciple_$it" }
    private val femalePortraits = (1..17).map { "female_disciple_$it" }

    /** 预构建的资源 ID 映射：肖像名称 → R.drawable.xxx */
    private val resourceIdMap = mutableMapOf<String, Int>()

    /** 是否已初始化 */
    private var initialized = false

    /**
     * 预构建所有肖像的资源 ID 映射。
     * 必须在应用启动时调用一次（[XianxiaApplication.onCreate]）。
     */
    fun initialize(context: android.content.Context) {
        if (initialized) return
        val names = allPortraitNames()
        val pkg = context.packageName
        val res = context.resources
        for (name in names) {
            val id = res.getIdentifier(name, "drawable", pkg)
            if (id != 0) {
                resourceIdMap[name] = id
            }
        }
        initialized = true
    }

    /**
     * 从性别对应肖像池随机选一个（确定性 RNG：随机源由调用方注入，
     * 禁止裸 kotlin.random.Random——需接入分区 PRNG 或 GameRandom）。
     *
     * @param gender 性别（"male"/"female"，未知性别回退女性池）
     * @param nextInt 随机上界函数 `(bound) -> value`，返回 [0, bound)
     * @return 肖像资源名称
     */
    fun getRandomPortrait(gender: String, nextInt: (Int) -> Int): String {
        val pool = if (gender == "male") malePortraits else femalePortraits
        return pool[nextInt(pool.size)]
    }

    /** 返回所有头像资源名称列表（用于预加载） */
    fun allPortraitNames(): List<String> = malePortraits + femalePortraits

    /**
     * 通过预构建映射获取资源 ID。
     * 需要在 [initialize] 之后调用，否则返回 0。
     *
     * @param portraitRes 肖像资源名称
     * @return R.drawable.xxx 的资源 ID，未找到则返回 0
     */
    fun getResourceId(portraitRes: String): Int {
        if (portraitRes.isBlank()) return 0
        return resourceIdMap[portraitRes] ?: 0
    }

}
