package com.xianxia.sect.core.util

import org.junit.Assert.*
import org.junit.Test

class BannedWordsTest {

    // ==================== 词条规范 ====================

    @Test
    fun allWords_atLeastTwoChars() {
        BannedWords.words.forEach { word ->
            assertTrue(
                "违禁词 '$word' 不足2字符，可能导致误匹配",
                word.length >= 2
            )
        }
    }

    // ==================== containsBannedWord ====================

    @Test
    fun containsBannedWord_exactMatch_returnsTrue() {
        // "毒品" 在违禁词列表中
        assertTrue(BannedWords.containsBannedWord("毒品"))
    }

    @Test
    fun containsBannedWord_substringMatch_returnsTrue() {
        // "毒品" 作为子串出现在名称中
        assertTrue(BannedWords.containsBannedWord("毒品宗"))
    }

    @Test
    fun containsBannedWord_cleanName_returnsFalse() {
        assertFalse(BannedWords.containsBannedWord("青云宗"))
        assertFalse(BannedWords.containsBannedWord("星月门"))
        assertFalse(BannedWords.containsBannedWord("天剑阁"))
        assertFalse(BannedWords.containsBannedWord("凌霄殿"))
        assertFalse(BannedWords.containsBannedWord("万剑宗"))
    }

    @Test
    fun containsBannedWord_emptyInput_returnsFalse() {
        assertFalse(BannedWords.containsBannedWord(""))
        assertFalse(BannedWords.containsBannedWord("   "))
    }

    @Test
    fun containsBannedWord_singleChar_returnsFalse() {
        // 单字不应匹配任何 ≥2 字符的违禁词
        assertFalse(BannedWords.containsBannedWord("毒"))
        assertFalse(BannedWords.containsBannedWord("魔"))
    }

    // ==================== findFirstBannedWord ====================

    @Test
    fun findFirstBannedWord_match_returnsWord() {
        assertEquals("毒品", BannedWords.findFirstBannedWord("毒品宗"))
    }

    @Test
    fun findFirstBannedWord_noMatch_returnsNull() {
        assertNull(BannedWords.findFirstBannedWord("青云宗"))
    }

    @Test
    fun findFirstBannedWord_emptyInput_returnsNull() {
        assertNull(BannedWords.findFirstBannedWord(""))
    }

    // ==================== validateSectName 集成 ====================

    @Test
    fun validateSectName_withBannedWord_returnsError() {
        val result = InputValidator.validateSectName("毒品宗")
        assertNotNull("含违禁词的名称应返回错误", result)
        assertTrue(
            "错误信息应包含'违禁词'",
            result?.contains("违禁词") == true
        )
    }

    @Test
    fun validateSectName_cleanNames_passBannedCheck() {
        assertNull(InputValidator.validateSectName("青云宗"))
        assertNull(InputValidator.validateSectName("星月门"))
        assertNull(InputValidator.validateSectName("天剑阁"))
        assertNull(InputValidator.validateSectName("万剑归宗"))
    }

    // ==================== 正常修仙词不误匹配 ====================

    @Test
    fun commonXianxiaTerms_notBlocked() {
        val terms = listOf(
            "青云宗", "星月门", "天剑阁", "凌霄殿",
            "万剑宗", "太虚门", "碧落宗", "紫霄宫",
            "天魔教", "血煞门", "幽冥宗", "万妖谷",
            "神剑山庄", "灵虚剑派"
        )
        terms.forEach { name ->
            if (name.length in 2..InputValidator.MAX_SECT_NAME_LENGTH) {
                assertNull(
                    "修仙常用名 '$name' 不应被违禁词误匹配",
                    InputValidator.validateSectName(name)
                )
            }
        }
    }
}
