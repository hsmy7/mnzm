package com.xianxia.sect.core.util

import kotlin.random.Random

object PortraitPool {
    private val malePortraits = (1..7).map { "male_disciple_$it" }
    private val femalePortraits = (1..8).map { "female_disciple_$it" }

    fun getRandomPortrait(gender: String): String {
        val pool = if (gender == "male") malePortraits else femalePortraits
        return pool[Random.nextInt(pool.size)]
    }

    fun getResourceId(context: android.content.Context, portraitRes: String): Int {
        if (portraitRes.isBlank()) return 0
        return context.resources.getIdentifier(portraitRes, "drawable", context.packageName)
    }
}
