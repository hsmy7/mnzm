package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple

sealed interface GameNotification {
    data class DiscipleDesertion(val disciple: Disciple) : GameNotification
    data class DiscipleTheftCaught(val disciple: Disciple) : GameNotification
    data class WarehouseTheft(val stolenAmount: Long) : GameNotification
    data class MarriageRequest(val maleDisciple: Disciple, val femaleDisciple: Disciple) : GameNotification
}
