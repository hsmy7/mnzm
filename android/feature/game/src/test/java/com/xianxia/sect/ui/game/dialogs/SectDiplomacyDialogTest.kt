package com.xianxia.sect.ui.game.dialogs

import com.xianxia.sect.core.util.SectRelationLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class SectDiplomacyDialogTest {

    @Test
    fun `dialogueTextForRelation - ally returns ally greeting`() {
        val text = dialogueTextForRelation(SectRelationLevel.NORMAL, isAlly = true)
        assertEquals("盟友亲至，有何要事但说无妨。", text)
    }

    @Test
    fun `dialogueTextForRelation - ally overrides relation level`() {
        val hostileAsAlly = dialogueTextForRelation(SectRelationLevel.HOSTILE, isAlly = true)
        assertEquals("盟友亲至，有何要事但说无妨。", hostileAsAlly)
    }

    @Test
    fun `dialogueTextForRelation - HOSTILE returns hostile greeting`() {
        val text = dialogueTextForRelation(SectRelationLevel.HOSTILE, isAlly = false)
        assertEquals("......阁下竟敢踏足本宗地界？", text)
    }

    @Test
    fun `dialogueTextForRelation - ANTAGONISTIC returns antagonistic greeting`() {
        val text = dialogueTextForRelation(SectRelationLevel.ANTAGONISTIC, isAlly = false)
        assertEquals("哼，有话快说，本宗不欢迎你。", text)
    }

    @Test
    fun `dialogueTextForRelation - NORMAL returns normal greeting`() {
        val text = dialogueTextForRelation(SectRelationLevel.NORMAL, isAlly = false)
        assertEquals("贵宗来访，不知有何贵干？", text)
    }

    @Test
    fun `dialogueTextForRelation - FRIENDLY returns friendly greeting`() {
        val text = dialogueTextForRelation(SectRelationLevel.FRIENDLY, isAlly = false)
        assertEquals("原来是友宗到访，快请一叙。", text)
    }

    @Test
    fun `dialogueTextForRelation - INTIMATE returns intimate greeting`() {
        val text = dialogueTextForRelation(SectRelationLevel.INTIMATE, isAlly = false)
        assertEquals("哈哈，老友来访，真是蓬荜生辉！", text)
    }
}
