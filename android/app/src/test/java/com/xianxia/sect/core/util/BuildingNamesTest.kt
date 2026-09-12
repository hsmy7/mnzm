package com.xianxia.sect.core.util

import org.junit.Assert.*
import org.junit.Test

class BuildingNamesTest {

    @Test
    fun getDisplayName_alchemy_returns炼丹炉() {
        assertEquals("炼丹炉", BuildingNames.getDisplayName("alchemy"))
    }

    @Test
    fun getDisplayName_forge_returns锻造坊() {
        assertEquals("锻造坊", BuildingNames.getDisplayName("forge"))
    }

    @Test
    fun getDisplayName_mining_returns灵矿() {
        assertEquals("灵矿", BuildingNames.getDisplayName("mining"))
    }

    @Test
    fun getDisplayName_spiritMine_returns灵矿() {
        assertEquals("灵矿", BuildingNames.getDisplayName("spiritMine"))
    }

    @Test
    fun getDisplayName_herb_garden_returns灵植阁() {
        assertEquals("灵植阁", BuildingNames.getDisplayName("herb_garden"))
    }

    @Test
    fun getDisplayName_herbGarden_returns灵植阁() {
        assertEquals("灵植阁", BuildingNames.getDisplayName("herbGarden"))
    }

    @Test
    fun getDisplayName_library_returns藏经阁() {
        assertEquals("藏经阁", BuildingNames.getDisplayName("library"))
    }

    @Test
    fun getDisplayName_tianshu_hall_returns天枢殿() {
        assertEquals("天枢殿", BuildingNames.getDisplayName("tianshu_hall"))
    }

    @Test
    fun getDisplayName_tianShuHall_returns天枢殿() {
        assertEquals("天枢殿", BuildingNames.getDisplayName("tianShuHall"))
    }

    @Test
    fun getDisplayName_wenDaoPeak_returns问道塔() {
        assertEquals("问道塔", BuildingNames.getDisplayName("wenDaoPeak"))
    }

    @Test
    fun getDisplayName_qingyunPeak_returns青云塔() {
        assertEquals("青云塔", BuildingNames.getDisplayName("qingyunPeak"))
    }

    @Test
    fun getDisplayName_lawEnforcementHall_returns执法堂() {
        assertEquals("执法堂", BuildingNames.getDisplayName("lawEnforcementHall"))
    }

    @Test
    fun getDisplayName_missionHall_returns任务阁() {
        assertEquals("任务阁", BuildingNames.getDisplayName("missionHall"))
    }

    @Test
    fun getDisplayName_reflectionCliff_returns监牢() {
        assertEquals("监牢", BuildingNames.getDisplayName("reflectionCliff"))
    }

    @Test
    fun getDisplayName_bloodRefiningPool_returns血炼池() {
        assertEquals("血炼池", BuildingNames.getDisplayName("bloodRefiningPool"))
    }

    @Test
    fun getDisplayName_unknown_returns建筑() {
        assertEquals("建筑", BuildingNames.getDisplayName("unknown_building"))
    }

    @Test
    fun getDisplayName_caseInsensitive_ALCHEMY_returns炼丹炉() {
        assertEquals("炼丹炉", BuildingNames.getDisplayName("ALCHEMY"))
    }
}
