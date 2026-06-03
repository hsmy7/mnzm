package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

// TickSystem: "MailSystem"
@Singleton
@SystemPriority(order = 960)
class MailSystem @Inject constructor(
    private val mailService: MailService
) : GameSystem {
    override val systemName: String = "MailSystem"

    override fun initialize() {
        mailService.initialize()
    }

    override fun release() {
        mailService.release()
    }

    override suspend fun clearForSlot(slotId: Int) {
        mailService.clearForSlot(slotId)
    }

    override suspend fun onMonthTick(state: MutableGameState) {
        mailService.processMonthlyMails(state)
    }
}
