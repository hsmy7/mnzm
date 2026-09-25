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
 *
 * ## 池成员范围
 * 本池只承载 37 张通用弟子像（`male_disciple_1..20` + `female_disciple_1..17`），
 * 成员清单是被测试锁定的契约。具名角色的立绘/头像键（`portrait_<角色id>` /
 * `avatar_<角色id>`）属 `:core:ui` 侧的 `SpriteCategory.CHARACTER` 注册域，
 * **禁止**并入本池，也禁止加入 `ResourcePreloader` 的肖像预载清单（该清单直接取自
 * [allPortraitNames]，且预载位图上限低于角色立绘源图分辨率，并入会使大图降质预载）。
 *
 * 同时覆盖通用像域与角色键域的解析入口是 `:core:ui` 的 `resolvePortraitResId`。
 * 本池位于 `:core:domain`，不引用 `:core:ui`（依赖方向 domain ← ui）。
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
     * 从性别对应肖像池随机选一个（随机源由调用方注入——
     * 表现类调用方传 `PresentationRandom.boundPicker()`；禁止裸
     * `kotlin.random.Random.Default`）。
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
     * 查询域仅覆盖本池的 37 个通用像名；具名角色立绘键恒返回 0，
     * 由 `:core:ui` 的 `resolvePortraitResId` 接续查 `SpriteResRegistry`。
     *
     * @param portraitRes 肖像资源名称
     * @return R.drawable.xxx 的资源 ID，未找到则返回 0
     */
    fun getResourceId(portraitRes: String): Int {
        if (portraitRes.isBlank()) return 0
        return resourceIdMap[portraitRes] ?: 0
    }

}
