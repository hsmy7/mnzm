package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.Disciple

fun List<Disciple>.sortedByFollowAndRealm(): List<Disciple> {
    return this.sortedWith(compareByDescending<Disciple> { it.isFollowed }
        .thenByDescending { it.realm }
        .thenByDescending { it.realmLayer })
}

fun List<Disciple>.sortedByFollowAttributeAndRealm(attribute: String? = null): List<Disciple> {
    return if (attribute != null) {
        this.sortedWith(compareByDescending<Disciple> { it.isFollowed }
            .thenByDescending { it.realm }
            .thenByDescending { it.realmLayer }
            .thenByDescending { disciple ->
                when (attribute) {
                    "comprehension" -> disciple.comprehension
                    "intelligence" -> disciple.intelligence
                    "charm" -> disciple.charm
                    "loyalty" -> disciple.loyalty
                    "artifactRefining" -> disciple.artifactRefining
                    "pillRefining" -> disciple.pillRefining
                    "spiritPlanting" -> disciple.spiritPlanting
                    "teaching" -> disciple.teaching
                    "morality" -> disciple.morality
                    else -> 0
                }
            })
    } else {
        this.sortedWith(compareByDescending<Disciple> { it.isFollowed }
            .thenByDescending { it.realm }
            .thenByDescending { it.realmLayer }
            .thenByDescending { it.comprehension })
    }
}

val Disciple.isFollowed: Boolean
    get() = statusData["followed"] == "true"
