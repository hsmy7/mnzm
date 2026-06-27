package com.xianxia.sect.core.util

import kotlin.random.Random

object PortraitPool {
    private val malePortraits = (1..20).map { "male_disciple_$it" }
    private val femalePortraits = (1..17).map { "female_disciple_$it" }

    fun getRandomPortrait(gender: String): String {
        val pool = if (gender == "male") malePortraits else femalePortraits
        return pool[Random.nextInt(pool.size)]
    }

    /** 返回所有头像资源名称列表（用于预加载） */
    fun allPortraitNames(): List<String> = malePortraits + femalePortraits

    fun getResourceId(context: android.content.Context, portraitRes: String): Int {
        if (portraitRes.isBlank()) return 0
        return context.resources.getIdentifier(portraitRes, "drawable", context.packageName)
    }
}
