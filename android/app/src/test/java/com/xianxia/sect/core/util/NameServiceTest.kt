package com.xianxia.sect.core.util

import org.junit.Assert.*
import org.junit.Test

class NameServiceTest {

    @Test
    fun generateName_male_returnsNonNullResultWithNonBlankFields() {
        val result = NameService.generateName("male")
        assertNotNull(result)
        assertTrue("surname should not be blank", result.surname.isNotBlank())
        assertTrue("fullName should not be blank", result.fullName.isNotBlank())
    }

    @Test
    fun generateName_female_returnsNonNullResult() {
        val result = NameService.generateName("female")
        assertNotNull(result)
        assertTrue(result.surname.isNotBlank())
        assertTrue(result.fullName.isNotBlank())
    }

    @Test
    fun fullName_startsWitSurname() {
        repeat(50) {
            val result = NameService.generateName("male")
            assertTrue(
                "fullName '${result.fullName}' should start with surname '${result.surname}'",
                result.fullName.startsWith(result.surname)
            )
        }
    }

    @Test
    fun generateName_commonStyle_usesCommonSurnames() {
        val commonSurnames = listOf(
            "李", "王", "张", "刘", "陈", "杨", "赵", "黄", "周", "吴",
            "孙", "郑", "冯", "蒋", "沈", "韩", "朱", "秦", "许", "何",
            "吕", "施", "曹", "袁", "邓", "彭", "苏", "卢", "蔡", "丁",
            "萧", "叶", "顾", "孟", "林", "徐", "方", "程", "谢", "宋"
        )
        repeat(50) {
            val result = NameService.generateName("male", NameService.NameStyle.COMMON)
            assertTrue(
                "surname '${result.surname}' should be in common surnames",
                result.surname in commonSurnames
            )
        }
    }

    @Test
    fun generateName_xianxiaStyle_usesXianxiaSurnames() {
        val xianxiaSurnames = listOf(
            "慕容", "上官", "欧阳", "司徒", "南宫", "诸葛", "东方", "西门",
            "独孤", "令狐", "皇甫", "公孙", "轩辕", "太史", "端木", "百里",
            "楚", "墨", "白", "青", "紫", "玄", "苍", "凌", "寒", "云",
            "风", "萧", "叶", "林"
        )
        repeat(50) {
            val result = NameService.generateName("male", NameService.NameStyle.XIANXIA)
            assertTrue(
                "surname '${result.surname}' should be in xianxia surnames",
                result.surname in xianxiaSurnames
            )
        }
    }

    @Test
    fun generateName_avoidsExistingNames() {
        val existing = mutableSetOf<String>()
        // 生成一个名字加入 existing
        val first = NameService.generateName("male")
        existing.add(first.fullName)
        // 再生成，应该不会重复
        val second = NameService.generateName("male", existingNames = existing)
        assertFalse(
            "Should not generate an existing name",
            second.fullName in existing
        )
    }

    @Test
    fun inheritName_usesProvidedParentSurname() {
        val parentSurname = "慕容"
        val result = NameService.inheritName(parentSurname, "male")
        assertEquals(parentSurname, result.surname)
        assertTrue(result.fullName.startsWith(parentSurname))
    }

    @Test
    fun inheritName_avoidsExistingNames() {
        val parentSurname = "李"
        val existing = mutableSetOf<String>()
        val first = NameService.inheritName(parentSurname, "male")
        existing.add(first.fullName)
        val second = NameService.inheritName(parentSurname, "male", existingNames = existing)
        assertFalse(
            "inheritName should avoid existing names",
            second.fullName in existing
        )
    }

    @Test
    fun extractSurname_extractsCompoundSurnames() {
        assertEquals("慕容", NameService.extractSurname("慕容逍遥"))
        assertEquals("上官", NameService.extractSurname("上官婉清"))
        assertEquals("欧阳", NameService.extractSurname("欧阳锋"))
    }

    @Test
    fun extractSurname_extractsSingleCharSurnames() {
        assertEquals("李", NameService.extractSurname("李逍遥"))
        assertEquals("王", NameService.extractSurname("王重阳"))
    }

    @Test
    fun extractSurname_returnsEmptyForEmptyString() {
        assertEquals("", NameService.extractSurname(""))
    }

    @Test
    fun multipleCalls_generateDifferentNames() {
        val names = mutableSetOf<String>()
        repeat(20) {
            names.add(NameService.generateName("male").fullName)
        }
        // 20 次调用应该产生不止 1 个不同的名字
        assertTrue(
            "Multiple calls should produce different names, got only ${names.size}",
            names.size > 1
        )
    }

    @Test
    fun nameResult_hasCorrectFields() {
        val result = NameService.NameResult("慕容", "慕容逍遥")
        assertEquals("慕容", result.surname)
        assertEquals("慕容逍遥", result.fullName)
    }
}
