package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.DiscipleAggregate

fun List<DiscipleAggregate>.sortedByFollowAndRealm(): List<DiscipleAggregate> {
    return this.sortedWith(compareByDescending<DiscipleAggregate> { it.isFollowed }
        .thenBy { it.realm }
        .thenByDescending { it.realmLayer })
}

fun List<DiscipleAggregate>.sortedByFollowAttributeAndRealm(attribute: String? = null): List<DiscipleAggregate> {
    return if (attribute != null) {
        this.sortedWith(compareByDescending<DiscipleAggregate> { it.isFollowed }
            .thenBy { it.realm }
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
        this.sortedWith(compareByDescending<DiscipleAggregate> { it.isFollowed }
            .thenBy { it.realm }
            .thenByDescending { it.realmLayer }
            .thenByDescending { it.comprehension })
    }
}

val DiscipleAggregate.isFollowed: Boolean
    get() = statusData["followed"] == "true"
