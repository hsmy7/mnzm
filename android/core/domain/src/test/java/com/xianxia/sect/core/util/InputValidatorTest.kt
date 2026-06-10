package com.xianxia.sect.core.util

import org.junit.Assert.*
import org.junit.Test

class InputValidatorTest {

    // ==================== validateSectName ====================

    @Test
    fun validateSectName_validChineseName_returnsNull() {
        assertNull(InputValidator.validateSectName("青云宗"))
    }

    @Test
    fun validateSectName_validMixedName_returnsNull() {
        assertNull(InputValidator.validateSectName("宗门A1"))
    }

    @Test
    fun validateSectName_emptyString_returnsError() {
        assertNotNull(InputValidator.validateSectName(""))
    }

    @Test
    fun validateSectName_tooShort_returnsError() {
        val result = InputValidator.validateSectName("宗")
        assertNotNull(result)
        assertTrue(result!!.contains(InputValidator.MIN_SECT_NAME_LENGTH.toString()))
    }

    @Test
    fun validateSectName_tooLong_returnsError() {
        val longName = "宗".repeat(InputValidator.MAX_SECT_NAME_LENGTH + 1)
        val result = InputValidator.validateSectName(longName)
        assertNotNull(result)
        assertTrue(result!!.contains(InputValidator.MAX_SECT_NAME_LENGTH.toString()))
    }

    @Test
    fun validateSectName_specialChars_returnsError() {
        for (char in listOf('<', '>', '"', '\'', '&', '\\', '/')) {
            val result = InputValidator.validateSectName("宗门${char}名")
            assertNotNull("Expected error for special char: $char", result)
        }
    }

    @Test
    fun validateSectName_nameWithSpaces_returnsError() {
        assertNotNull(InputValidator.validateSectName("青 云 宗"))
    }

    @Test
    fun validateSectName_nameWithOnlyNumbers_returnsNull() {
        assertNull(InputValidator.validateSectName("12345"))
    }

    // ==================== validateDiscipleName ====================

    @Test
    fun validateDiscipleName_validChineseName_returnsNull() {
        assertNull(InputValidator.validateDiscipleName("李逍遥"))
    }

    @Test
    fun validateDiscipleName_validEnglishName_returnsNull() {
        assertNull(InputValidator.validateDiscipleName("John"))
    }

    @Test
    fun validateDiscipleName_emptyString_returnsError() {
        assertNotNull(InputValidator.validateDiscipleName(""))
    }

    @Test
    fun validateDiscipleName_tooShort_returnsError() {
        val result = InputValidator.validateDiscipleName("李")
        assertNotNull(result)
        assertTrue(result!!.contains(InputValidator.MIN_DISCIPLE_NAME_LENGTH.toString()))
    }

    @Test
    fun validateDiscipleName_tooLong_returnsError() {
        val longName = "逍遥".repeat(InputValidator.MAX_DISCIPLE_NAME_LENGTH)
        val result = InputValidator.validateDiscipleName(longName)
        assertNotNull(result)
        assertTrue(result!!.contains(InputValidator.MAX_DISCIPLE_NAME_LENGTH.toString()))
    }

    @Test
    fun validateDiscipleName_numbersInName_returnsError() {
        assertNotNull(InputValidator.validateDiscipleName("李逍遥1"))
    }

    @Test
    fun validateDiscipleName_specialChars_returnsError() {
        for (char in listOf('<', '>', '"', '\'', '&', '\\', '/')) {
            val result = InputValidator.validateDiscipleName("李${char}逍遥")
            assertNotNull("Expected error for special char: $char", result)
        }
    }

    // ==================== validateRedeemCode ====================

    @Test
    fun validateRedeemCode_validCode_returnsNull() {
        assertNull(InputValidator.validateRedeemCode("ABC123-def"))
    }

    @Test
    fun validateRedeemCode_emptyString_returnsError() {
        assertNotNull(InputValidator.validateRedeemCode(""))
    }

    @Test
    fun validateRedeemCode_tooLong_returnsError() {
        val longCode = "A".repeat(InputValidator.MAX_REDEEM_CODE_LENGTH + 1)
        assertNotNull(InputValidator.validateRedeemCode(longCode))
    }

    @Test
    fun validateRedeemCode_invalidChars_returnsError() {
        assertNotNull(InputValidator.validateRedeemCode("ABC 123"))
        assertNotNull(InputValidator.validateRedeemCode("ABC@123"))
        assertNotNull(InputValidator.validateRedeemCode("ABC#123"))
    }

    @Test
    fun validateRedeemCode_validWithUnderscoresAndHyphens_returnsNull() {
        assertNull(InputValidator.validateRedeemCode("ABC_123-def"))
    }

    // ==================== validateSaveName ====================

    @Test
    fun validateSaveName_validChineseName_returnsNull() {
        assertNull(InputValidator.validateSaveName("修仙存档"))
    }

    @Test
    fun validateSaveName_validNameWithSpaces_returnsNull() {
        assertNull(InputValidator.validateSaveName("修仙 存档"))
    }

    @Test
    fun validateSaveName_emptyString_returnsError() {
        assertNotNull(InputValidator.validateSaveName(""))
    }

    @Test
    fun validateSaveName_tooLong_returnsError() {
        val longName = "档".repeat(InputValidator.MAX_SAVE_NAME_LENGTH + 1)
        assertNotNull(InputValidator.validateSaveName(longName))
    }

    @Test
    fun validateSaveName_specialChars_returnsError() {
        for (char in listOf('<', '>', '"', '\'', '&', '\\', '/')) {
            val result = InputValidator.validateSaveName("存档${char}名")
            assertNotNull("Expected error for special char: $char", result)
        }
    }

    // ==================== validateSpiritStones ====================

    @Test
    fun validateSpiritStones_validAmount_returnsNull() {
        assertNull(InputValidator.validateSpiritStones(100))
    }

    @Test
    fun validateSpiritStones_negativeAmount_returnsError() {
        assertNotNull(InputValidator.validateSpiritStones(-1))
    }

    @Test
    fun validateSpiritStones_lessThanMinRequired_returnsError() {
        val result = InputValidator.validateSpiritStones(50, minRequired = 100)
        assertNotNull(result)
        assertTrue(result!!.contains("100"))
    }

    @Test
    fun validateSpiritStones_amountEqualsMinRequired_returnsNull() {
        assertNull(InputValidator.validateSpiritStones(100, minRequired = 100))
    }

    // ==================== validateQuantity ====================

    @Test
    fun validateQuantity_validQuantity_returnsNull() {
        assertNull(InputValidator.validateQuantity(5, min = 1, max = 10))
    }

    @Test
    fun validateQuantity_lessThanMin_returnsError() {
        val result = InputValidator.validateQuantity(0, min = 1, max = 10)
        assertNotNull(result)
        assertTrue(result!!.contains("1"))
    }

    @Test
    fun validateQuantity_greaterThanMax_returnsError() {
        val result = InputValidator.validateQuantity(11, min = 1, max = 10)
        assertNotNull(result)
        assertTrue(result!!.contains("10"))
    }

    @Test
    fun validateQuantity_atMinBoundary_returnsNull() {
        assertNull(InputValidator.validateQuantity(1, min = 1, max = 10))
    }

    @Test
    fun validateQuantity_atMaxBoundary_returnsNull() {
        assertNull(InputValidator.validateQuantity(10, min = 1, max = 10))
    }

    // ==================== validateTeamName ====================

    @Test
    fun validateTeamName_validName_returnsNull() {
        assertNull(InputValidator.validateTeamName("青云队"))
    }

    @Test
    fun validateTeamName_emptyString_returnsError() {
        assertNotNull(InputValidator.validateTeamName(""))
    }

    @Test
    fun validateTeamName_tooLong_returnsError() {
        val longName = "队".repeat(16)
        assertNotNull(InputValidator.validateTeamName(longName))
    }

    @Test
    fun validateTeamName_specialChars_returnsError() {
        for (char in listOf('<', '>', '"', '\'', '&', '\\', '/')) {
            val result = InputValidator.validateTeamName("队伍${char}名")
            assertNotNull("Expected error for special char: $char", result)
        }
    }

    // ==================== sanitizeInput ====================

    @Test
    fun sanitizeInput_trimsWhitespace() {
        assertEquals("修仙", InputValidator.sanitizeInput("  修仙  "))
    }

    @Test
    fun sanitizeInput_removesInvalidChars() {
        assertEquals("修仙名", InputValidator.sanitizeInput("修<仙>名"))
        assertEquals("修仙", InputValidator.sanitizeInput("修\"仙"))
        assertEquals("修仙", InputValidator.sanitizeInput("修'仙"))
        assertEquals("修仙", InputValidator.sanitizeInput("修&仙"))
        assertEquals("修仙", InputValidator.sanitizeInput("修\\仙"))
        assertEquals("修仙", InputValidator.sanitizeInput("修/仙"))
    }

    @Test
    fun sanitizeInput_collapsesMultipleSpaces() {
        assertEquals("修 仙 记", InputValidator.sanitizeInput("修   仙   记"))
    }

    // ==================== Constants ====================

    @Test
    fun constants_sectNameLengthBounds() {
        assertEquals(2, InputValidator.MIN_SECT_NAME_LENGTH)
        assertEquals(20, InputValidator.MAX_SECT_NAME_LENGTH)
    }

    @Test
    fun constants_discipleNameLengthBounds() {
        assertEquals(2, InputValidator.MIN_DISCIPLE_NAME_LENGTH)
        assertEquals(10, InputValidator.MAX_DISCIPLE_NAME_LENGTH)
    }

    @Test
    fun constants_redeemCodeMaxLength() {
        assertEquals(64, InputValidator.MAX_REDEEM_CODE_LENGTH)
    }

    @Test
    fun constants_saveNameLengthBounds() {
        assertEquals(1, InputValidator.MIN_SAVE_NAME_LENGTH)
        assertEquals(30, InputValidator.MAX_SAVE_NAME_LENGTH)
    }
}
