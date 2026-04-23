package com.xianxia.sect

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.*
import org.junit.Test

class GameConfigTest {

    // ============================================================
    // Game 对象
    // ============================================================

    @Test
    fun `游戏名称应为模拟宗门`() {
        assertEquals("模拟宗门", GameConfig.Game.NAME)
    }

    @Test
    fun `游戏版本应为非空字符串且格式正确`() {
        val version = GameConfig.Game.VERSION
        assertTrue("版本号不应为空", version.isNotEmpty())
        assertTrue("版本号应符合语义化版本格式 (x.y.z)", version.matches(Regex("\\d+\\.\\d+\\.\\d+")))
    }

    @Test
    fun `自动保存间隔应为60秒`() {
        assertEquals(60L, GameConfig.Game.AUTO_SAVE_INTERVAL_SECONDS)
    }

    @Test
    fun `最大存档槽位应为5`() {
        assertEquals(5, GameConfig.Game.MAX_SAVE_SLOTS)
    }

    // ============================================================
    // Disciple 对象
    // ============================================================

    @Test
    fun `弟子上限应为1000`() {
        assertEquals(1000, GameConfig.Disciple.MAX_DISCIPLES)
    }

    @Test
    fun `招募费用应为1000灵石`() {
        assertEquals(1000L, GameConfig.Disciple.RECRUIT_COST)
    }

    @Test
    fun `忠诚度最小值应为0`() {
        assertEquals(0, GameConfig.Disciple.MIN_LOYALTY)
    }

    @Test
    fun `忠诚度最大值应为100`() {
        assertEquals(100, GameConfig.Disciple.MAX_LOYALTY)
    }

    @Test
    fun `年龄最小值应为5`() {
        assertEquals(5, GameConfig.Disciple.MIN_AGE)
    }

    @Test
    fun `年龄最大值应为100`() {
        assertEquals(100, GameConfig.Disciple.MAX_AGE)
    }

    // ============================================================
    // Rarity 对象 - CONFIGS 基本结构
    // ============================================================

    @Test
    fun `稀有度配置应包含6个条目`() {
        assertEquals(6, GameConfig.Rarity.CONFIGS.size)
    }

    @Test
    fun `稀有度配置的key应为1到6`() {
        val keys = GameConfig.Rarity.CONFIGS.keys
        for (i in 1..6) {
            assertTrue("缺少key $i", keys.contains(i))
        }
    }

    // ============================================================
    // Rarity 对象 - get 方法
    // ============================================================

    @Test
    fun `传入有效稀有度1应返回凡品配置`() {
        val config = GameConfig.Rarity.get(1)
        assertEquals(1, config.level)
        assertEquals("凡品", config.name)
    }

    @Test
    fun `传入有效稀有度2应返回灵品配置`() {
        val config = GameConfig.Rarity.get(2)
        assertEquals(2, config.level)
        assertEquals("灵品", config.name)
    }

    @Test
    fun `传入有效稀有度3应返回宝品配置`() {
        val config = GameConfig.Rarity.get(3)
        assertEquals(3, config.level)
        assertEquals("宝品", config.name)
    }

    @Test
    fun `传入有效稀有度4应返回玄品配置`() {
        val config = GameConfig.Rarity.get(4)
        assertEquals(4, config.level)
        assertEquals("玄品", config.name)
    }

    @Test
    fun `传入有效稀有度5应返回地品配置`() {
        val config = GameConfig.Rarity.get(5)
        assertEquals(5, config.level)
        assertEquals("地品", config.name)
    }

    @Test
    fun `传入有效稀有度6应返回天品配置`() {
        val config = GameConfig.Rarity.get(6)
        assertEquals(6, config.level)
        assertEquals("天品", config.name)
    }

    @Test
    fun `传入无效稀有度99应返回默认凡品配置`() {
        val config = GameConfig.Rarity.get(99)
        assertEquals("凡品", config.name)
        assertEquals(1.0, config.multiplier, 0.001)
    }

    @Test
    fun `传入无效稀有度0应返回默认凡品配置`() {
        val config = GameConfig.Rarity.get(0)
        assertEquals("凡品", config.name)
    }

    @Test
    fun `传入负数稀有度应返回默认凡品配置`() {
        val config = GameConfig.Rarity.get(-1)
        assertEquals("凡品", config.name)
    }

    // ============================================================
    // Rarity 对象 - getName 方法
    // ============================================================

    @Test
    fun `getName传入1应返回凡品`() {
        assertEquals("凡品", GameConfig.Rarity.getName(1))
    }

    @Test
    fun `getName传入2应返回灵品`() {
        assertEquals("灵品", GameConfig.Rarity.getName(2))
    }

    @Test
    fun `getName传入3应返回宝品`() {
        assertEquals("宝品", GameConfig.Rarity.getName(3))
    }

    @Test
    fun `getName传入4应返回玄品`() {
        assertEquals("玄品", GameConfig.Rarity.getName(4))
    }

    @Test
    fun `getName传入5应返回地品`() {
        assertEquals("地品", GameConfig.Rarity.getName(5))
    }

    @Test
    fun `getName传入6应返回天品`() {
        assertEquals("天品", GameConfig.Rarity.getName(6))
    }

    @Test
    fun `getName传入无效值应返回凡品`() {
        assertEquals("凡品", GameConfig.Rarity.getName(99))
    }

    // ============================================================
    // Rarity 对象 - getColor 方法
    // ============================================================

    @Test
    fun `getColor传入1应返回灰色`() {
        assertEquals("#95a5a6", GameConfig.Rarity.getColor(1))
    }

    @Test
    fun `getColor传入2应返回绿色`() {
        assertEquals("#27ae60", GameConfig.Rarity.getColor(2))
    }

    @Test
    fun `getColor传入3应返回蓝色`() {
        assertEquals("#3498db", GameConfig.Rarity.getColor(3))
    }

    @Test
    fun `getColor传入4应返回紫色`() {
        assertEquals("#9b59b6", GameConfig.Rarity.getColor(4))
    }

    @Test
    fun `getColor传入5应返回橙色`() {
        assertEquals("#f39c12", GameConfig.Rarity.getColor(5))
    }

    @Test
    fun `getColor传入6应返回红色`() {
        assertEquals("#e74c3c", GameConfig.Rarity.getColor(6))
    }

    // ============================================================
    // Rarity 对象 - multiplier 值递增验证
    // ============================================================

    @Test
    fun `凡品倍率应为1点0`() {
        assertEquals(1.0, GameConfig.Rarity.get(1).multiplier, 0.001)
    }

    @Test
    fun `灵品倍率应为1点3`() {
        assertEquals(1.3, GameConfig.Rarity.get(2).multiplier, 0.001)
    }

    @Test
    fun `宝品倍率应为1点6`() {
        assertEquals(1.6, GameConfig.Rarity.get(3).multiplier, 0.001)
    }

    @Test
    fun `玄品倍率应为2点0`() {
        assertEquals(2.0, GameConfig.Rarity.get(4).multiplier, 0.001)
    }

    @Test
    fun `地品倍率应为2点5`() {
        assertEquals(2.5, GameConfig.Rarity.get(5).multiplier, 0.001)
    }

    @Test
    fun `天品倍率应为3点2`() {
        assertEquals(3.2, GameConfig.Rarity.get(6).multiplier, 0.001)
    }

    @Test
    fun `稀有度倍率应随等级严格递增`() {
        val multipliers = (1..6).map { GameConfig.Rarity.get(it).multiplier }
        for (i in 0 until multipliers.size - 1) {
            assertTrue(
                "倍率在level ${i + 1}(${multipliers[i]}) 到 ${i + 2}(${multipliers[i + 1]}) 未递增",
                multipliers[i] < multipliers[i + 1]
            )
        }
    }

    // ============================================================
    // Realm 对象 - CONFIGS 基本结构
    // ============================================================

    @Test
    fun `境界配置应包含10个条目`() {
        assertEquals(10, GameConfig.Realm.CONFIGS.size)
    }

    @Test
    fun `境界配置的key应为0到9`() {
        val keys = GameConfig.Realm.CONFIGS.keys
        for (i in 0..9) {
            assertTrue("缺少境界key $i", keys.contains(i))
        }
    }

    // ============================================================
    // Realm 对象 - get 方法
    // ============================================================

    @Test
    fun `传入有效境界9应返回炼气配置`() {
        val config = GameConfig.Realm.get(9)
        assertEquals(9, config.level)
        assertEquals("炼气", config.name)
    }

    @Test
    fun `传入有效境界8应返回筑基配置`() {
        val config = GameConfig.Realm.get(8)
        assertEquals(8, config.level)
        assertEquals("筑基", config.name)
    }

    @Test
    fun `传入有效境界7应返回金丹配置`() {
        val config = GameConfig.Realm.get(7)
        assertEquals(7, config.level)
        assertEquals("金丹", config.name)
    }

    @Test
    fun `传入有效境界6应返回元婴配置`() {
        val config = GameConfig.Realm.get(6)
        assertEquals(6, config.level)
        assertEquals("元婴", config.name)
    }

    @Test
    fun `传入有效境界5应返回化神配置`() {
        val config = GameConfig.Realm.get(5)
        assertEquals(5, config.level)
        assertEquals("化神", config.name)
    }

    @Test
    fun `传入有效境界4应返回炼虚配置`() {
        val config = GameConfig.Realm.get(4)
        assertEquals(4, config.level)
        assertEquals("炼虚", config.name)
    }

    @Test
    fun `传入有效境界3应返回合体配置`() {
        val config = GameConfig.Realm.get(3)
        assertEquals(3, config.level)
        assertEquals("合体", config.name)
    }

    @Test
    fun `传入有效境界2应返回大乘配置`() {
        val config = GameConfig.Realm.get(2)
        assertEquals(2, config.level)
        assertEquals("大乘", config.name)
    }

    @Test
    fun `传入有效境界1应返回渡劫配置`() {
        val config = GameConfig.Realm.get(1)
        assertEquals(1, config.level)
        assertEquals("渡劫", config.name)
    }

    @Test
    fun `传入有效境界0应返回仙人配置`() {
        val config = GameConfig.Realm.get(0)
        assertEquals(0, config.level)
        assertEquals("仙人", config.name)
    }

    @Test
    fun `传入无效境界99应返回默认炼气配置`() {
        val config = GameConfig.Realm.get(99)
        assertEquals("炼气", config.name)
        assertEquals(9, config.level)
    }

    @Test
    fun `传入无效境界负数应返回默认炼气配置`() {
        val config = GameConfig.Realm.get(-1)
        assertEquals("炼气", config.name)
    }

    // ============================================================
    // Realm 对象 - getName 方法
    // ============================================================

    @Test
    fun `getName传入9应返回炼气`() {
        assertEquals("炼气", GameConfig.Realm.getName(9))
    }

    @Test
    fun `getName传入8应返回筑基`() {
        assertEquals("筑基", GameConfig.Realm.getName(8))
    }

    @Test
    fun `getName传入7应返回金丹`() {
        assertEquals("金丹", GameConfig.Realm.getName(7))
    }

    @Test
    fun `getName传入6应返回元婴`() {
        assertEquals("元婴", GameConfig.Realm.getName(6))
    }

    @Test
    fun `getName传入5应返回化神`() {
        assertEquals("化神", GameConfig.Realm.getName(5))
    }

    @Test
    fun `getName传入4应返回炼虚`() {
        assertEquals("炼虚", GameConfig.Realm.getName(4))
    }

    @Test
    fun `getName传入3应返回合体`() {
        assertEquals("合体", GameConfig.Realm.getName(3))
    }

    @Test
    fun `getName传入2应返回大乘`() {
        assertEquals("大乘", GameConfig.Realm.getName(2))
    }

    @Test
    fun `getName传入1应返回渡劫`() {
        assertEquals("渡劫", GameConfig.Realm.getName(1))
    }

    @Test
    fun `getName传入0应返回仙人`() {
        assertEquals("仙人", GameConfig.Realm.getName(0))
    }

    // ============================================================
    // Realm 对象 - cultivationBase 随境界提升而增大
    // ============================================================

    @Test
    fun `炼气的修炼基础值应为225`() {
        assertEquals(225, GameConfig.Realm.getCultivationBase(9))
    }

    @Test
    fun `筑基的修炼基础值应为450`() {
        assertEquals(450, GameConfig.Realm.getCultivationBase(8))
    }

    @Test
    fun `金丹的修炼基础值应为900`() {
        assertEquals(900, GameConfig.Realm.getCultivationBase(7))
    }

    @Test
    fun `元婴的修炼基础值应为1800`() {
        assertEquals(1800, GameConfig.Realm.getCultivationBase(6))
    }

    @Test
    fun `化神的修炼基础值应为3600`() {
        assertEquals(3600, GameConfig.Realm.getCultivationBase(5))
    }

    @Test
    fun `炼虚的修炼基础值应为16000`() {
        assertEquals(16000, GameConfig.Realm.getCultivationBase(4))
    }

    @Test
    fun `合体的修炼基础值应为32000`() {
        assertEquals(32000, GameConfig.Realm.getCultivationBase(3))
    }

    @Test
    fun `大乘的修炼基础值应为64000`() {
        assertEquals(64000, GameConfig.Realm.getCultivationBase(2))
    }

    @Test
    fun `渡劫的修炼基础值应为128000`() {
        assertEquals(128000, GameConfig.Realm.getCultivationBase(1))
    }

    @Test
    fun `仙人的修炼基础值应为256000`() {
        assertEquals(256000, GameConfig.Realm.getCultivationBase(0))
    }

    @Test
    fun `修炼基础值应随境界降低而增大`() {
        val bases = (9 downTo 0).map { GameConfig.Realm.getCultivationBase(it) }
        for (i in 0 until bases.size - 1) {
            assertTrue(
                "cultivationBase 在境界 ${9 - i} 到 ${9 - i - 1} 未增大",
                bases[i] < bases[i + 1]
            )
        }
    }

    // ============================================================
    // Realm 对象 - breakthroughChance 随境界降低
    // ============================================================

    @Test
    fun `炼气的突破概率应为0点80`() {
        assertEquals(0.80, GameConfig.Realm.getBreakthroughChance(9), 0.001)
    }

    @Test
    fun `筑基的突破概率应为0点60`() {
        assertEquals(0.60, GameConfig.Realm.getBreakthroughChance(8), 0.001)
    }

    @Test
    fun `金丹的突破概率应为0点46`() {
        assertEquals(0.46, GameConfig.Realm.getBreakthroughChance(7), 0.001)
    }

    @Test
    fun `元婴的突破概率应为0点32`() {
        assertEquals(0.32, GameConfig.Realm.getBreakthroughChance(6), 0.001)
    }

    @Test
    fun `化神的突破概率应为0点24`() {
        assertEquals(0.24, GameConfig.Realm.getBreakthroughChance(5), 0.001)
    }

    @Test
    fun `炼虚的突破概率应为0点12`() {
        assertEquals(0.12, GameConfig.Realm.getBreakthroughChance(4), 0.001)
    }

    @Test
    fun `合体的突破概率应为0点06`() {
        assertEquals(0.06, GameConfig.Realm.getBreakthroughChance(3), 0.0001)
    }

    @Test
    fun `大乘的突破概率应为0点03`() {
        assertEquals(0.03, GameConfig.Realm.getBreakthroughChance(2), 0.0001)
    }

    @Test
    fun `渡劫的突破概率应为0点01`() {
        assertEquals(0.01, GameConfig.Realm.getBreakthroughChance(1), 0.0001)
    }

    @Test
    fun `仙人的突破概率应为0点0`() {
        assertEquals(0.0, GameConfig.Realm.getBreakthroughChance(0), 0.0001)
    }

    @Test
    fun `突破概率应随境界降低而减小`() {
        val chances = (9 downTo 0).map { GameConfig.Realm.getBreakthroughChance(it) }
        for (i in 0 until chances.size - 1) {
            assertTrue(
                "breakthroughChance 在境界 ${9 - i} 到 ${9 - i - 1} 未减小",
                chances[i] > chances[i + 1]
            )
        }
    }

    // ============================================================
    // Realm 对象 - getMaxRarity 方法
    // ============================================================

    @Test
    fun `炼气境界的最大稀有度应为1`() {
        assertEquals(1, GameConfig.Realm.getMaxRarity(9))
    }

    @Test
    fun `筑基境界的最大稀有度应为1`() {
        assertEquals(1, GameConfig.Realm.getMaxRarity(8))
    }

    @Test
    fun `金丹境界的最大稀有度应为2`() {
        assertEquals(2, GameConfig.Realm.getMaxRarity(7))
    }

    @Test
    fun `元婴境界的最大稀有度应为3`() {
        assertEquals(3, GameConfig.Realm.getMaxRarity(6))
    }

    @Test
    fun `化神境界的最大稀有度应为4`() {
        assertEquals(4, GameConfig.Realm.getMaxRarity(5))
    }

    @Test
    fun `炼虚境界的最大稀有度应为5`() {
        assertEquals(5, GameConfig.Realm.getMaxRarity(4))
    }

    @Test
    fun `合体境界的最大稀有度应为5`() {
        assertEquals(5, GameConfig.Realm.getMaxRarity(3))
    }

    @Test
    fun `大乘境界的最大稀有度应为6`() {
        assertEquals(6, GameConfig.Realm.getMaxRarity(2))
    }

    @Test
    fun `渡劫境界的最大稀有度应为6`() {
        assertEquals(6, GameConfig.Realm.getMaxRarity(1))
    }

    @Test
    fun `仙人境界的最大稀有度应为6`() {
        assertEquals(6, GameConfig.Realm.getMaxRarity(0))
    }

    @Test
    fun `无效境界的最大稀有度应默认为1`() {
        assertEquals(1, GameConfig.Realm.getMaxRarity(99))
    }

    // ============================================================
    // Realm 对象 - getMinRealmForRarity 方法
    // ============================================================

    @Test
    fun `凡品对应的最小境界应为炼气9`() {
        assertEquals(9, GameConfig.Realm.getMinRealmForRarity(1))
    }

    @Test
    fun `灵品对应的最小境界应为金丹7`() {
        assertEquals(7, GameConfig.Realm.getMinRealmForRarity(2))
    }

    @Test
    fun `宝品对应的最小境界应为元婴6`() {
        assertEquals(6, GameConfig.Realm.getMinRealmForRarity(3))
    }

    @Test
    fun `玄品对应的最小境界应为化神5`() {
        assertEquals(5, GameConfig.Realm.getMinRealmForRarity(4))
    }

    @Test
    fun `地品对应的最小境界应为炼虚4`() {
        assertEquals(4, GameConfig.Realm.getMinRealmForRarity(5))
    }

    @Test
    fun `天品对应的最小境界应为大乘2`() {
        assertEquals(2, GameConfig.Realm.getMinRealmForRarity(6))
    }

    @Test
    fun `无效稀有度对应的最小境界应默认为炼气9`() {
        assertEquals(9, GameConfig.Realm.getMinRealmForRarity(99))
    }

    @Test
    fun `零稀有度对应的最小境界应默认为炼气9`() {
        assertEquals(9, GameConfig.Realm.getMinRealmForRarity(0))
    }

    // ============================================================
    // SpiritRoot 对象 - ELEMENTS 列表
    // ============================================================

    @Test
    fun `五行元素列表应有5个元素`() {
        assertEquals(5, GameConfig.SpiritRoot.ELEMENTS.size)
    }

    @Test
    fun `五行元素应包含金木水火土`() {
        val elements = GameConfig.SpiritRoot.ELEMENTS
        assertTrue(elements.contains("金"))
        assertTrue(elements.contains("木"))
        assertTrue(elements.contains("水"))
        assertTrue(elements.contains("火"))
        assertTrue(elements.contains("土"))
    }

    // ============================================================
    // SpiritRoot 对象 - TYPES map
    // ============================================================

    @Test
    fun `灵根类型映射应有5个条目`() {
        assertEquals(5, GameConfig.SpiritRoot.TYPES.size)
    }

    @Test
    fun `灵根类型应包含metal类型`() {
        assertNotNull(GameConfig.SpiritRoot.TYPES["metal"])
    }

    @Test
    fun `灵根类型应包含wood类型`() {
        assertNotNull(GameConfig.SpiritRoot.TYPES["wood"])
    }

    @Test
    fun `灵根类型应包含water类型`() {
        assertNotNull(GameConfig.SpiritRoot.TYPES["water"])
    }

    @Test
    fun `灵根类型应包含fire类型`() {
        assertNotNull(GameConfig.SpiritRoot.TYPES["fire"])
    }

    @Test
    fun `灵根类型应包含earth类型`() {
        assertNotNull(GameConfig.SpiritRoot.TYPES["earth"])
    }

    // ============================================================
    // SpiritRoot 对象 - get 方法
    // ============================================================

    @Test
    fun `get传入metal应返回金属性配置`() {
        val config = GameConfig.SpiritRoot.get("metal")
        assertEquals("metal", config.type)
        assertEquals("金", config.name)
    }

    @Test
    fun `get传入wood应返回木属性配置`() {
        val config = GameConfig.SpiritRoot.get("wood")
        assertEquals("wood", config.type)
        assertEquals("木", config.name)
    }

    @Test
    fun `get传入water应返回水属性配置`() {
        val config = GameConfig.SpiritRoot.get("water")
        assertEquals("water", config.type)
        assertEquals("水", config.name)
    }

    @Test
    fun `get传入fire应返回火属性配置`() {
        val config = GameConfig.SpiritRoot.get("fire")
        assertEquals("fire", config.type)
        assertEquals("火", config.name)
    }

    @Test
    fun `get传入earth应返回土属性配置`() {
        val config = GameConfig.SpiritRoot.get("earth")
        assertEquals("earth", config.type)
        assertEquals("土", config.name)
    }

    @Test
    fun `get传入无效类型应返回默认metal配置`() {
        val config = GameConfig.SpiritRoot.get("unknown_type")
        assertEquals("metal", config.type)
        assertEquals("金", config.name)
    }

    @Test
    fun `get传入空字符串应返回默认metal配置`() {
        val config = GameConfig.SpiritRoot.get("")
        assertEquals("metal", config.type)
    }

    // ============================================================
    // SpiritRoot 对象 - COUNT_WEIGHTS 权重之和
    // ============================================================

    @Test
    fun `灵根数量权重之和应接近1点0`() {
        val totalWeight = GameConfig.SpiritRoot.COUNT_WEIGHTS.values.sum()
        assertEquals(1.0, totalWeight, 0.001)
    }

    @Test
    fun `灵根数量权重应包含1到5的所有键`() {
        val weights = GameConfig.SpiritRoot.COUNT_WEIGHTS
        for (i in 1..5) {
            assertTrue("缺少权重key $i", weights.containsKey(i))
        }
    }

    // ============================================================
    // SpiritRoot 对象 - generateRandomSpiritRootCount 范围验证
    // ============================================================

    @Test
    fun `随机生成灵根数量应在1到5范围内_单次调用`() {
        val count = GameConfig.SpiritRoot.generateRandomSpiritRootCount()
        assertTrue("生成的灵根数量 $count 应 >= 1", count >= 1)
        assertTrue("生成的灵根数量 $count 应 <= 5", count <= 5)
    }

    @Test
    fun `随机生成灵根数量多次调用均应在范围内`() {
        repeat(100) {
            val count = GameConfig.SpiritRoot.generateRandomSpiritRootCount()
            assertTrue("第${it + 1}次生成: $count 超出范围[1,5]", count in 1..5)
        }
    }

    @Test
    fun `随机生成灵根数量多次调用应覆盖所有可能值`() {
        val results = mutableSetOf<Int>()
        repeat(500) {
            results.add(GameConfig.SpiritRoot.generateRandomSpiritRootCount())
        }
        for (expected in 1..5) {
            assertTrue("未覆盖灵根数量 $expected (500次采样)", results.contains(expected))
        }
    }

    // ============================================================
    // Beast 对象 - TYPES 列表大小
    // ============================================================

    @Test
    fun `妖兽类型列表大小应为8`() {
        assertEquals(8, GameConfig.Beast.TYPES.size)
    }

    // ============================================================
    // Beast 对象 - getType 边界测试
    // ============================================================

    @Test
    fun `getType传入0应返回虎妖`() {
        val beast = GameConfig.Beast.getType(0)
        assertEquals("虎妖", beast.name)
    }

    @Test
    fun `getType传入1应返回狼妖`() {
        val beast = GameConfig.Beast.getType(1)
        assertEquals("狼妖", beast.name)
    }

    @Test
    fun `getType传入7应返回龟妖`() {
        val beast = GameConfig.Beast.getType(7)
        assertEquals("龟妖", beast.name)
    }

    @Test
    fun `getType传入负数索引应回退到第一个类型虎妖`() {
        val beast = GameConfig.Beast.getType(-1)
        assertEquals("虎妖", beast.name)
    }

    @Test
    fun `getType传入越界索引8应回退到第一个类型虎妖`() {
        val beast = GameConfig.Beast.getType(8)
        assertEquals("虎妖", beast.name)
    }

    @Test
    fun `getType传入极大越界索引99应回退到第一个类型虎妖`() {
        val beast = GameConfig.Beast.getType(99)
        assertEquals("虎妖", beast.name)
    }

    // ============================================================
    // Dungeons 对象 - CONFIGS 大小
    // ============================================================

    @Test
    fun `秘境配置大小应为8`() {
        assertEquals(8, GameConfig.Dungeons.CONFIGS.size)
    }

    // ============================================================
    // Dungeons 对象 - 每个 dungeon 的 rewards 结构
    // ============================================================

    @Test
    fun `虎啸岭奖励结构正确`() {
        val d = GameConfig.Dungeons.CONFIGS["tigerForest"]!!
        assertEquals("tigerForest", d.id)
        assertEquals("虎啸岭", d.name)
        assertEquals("虎妖", d.beastType)
        assertEquals(listOf(100, 300), d.rewards.spiritStones)
        assertEquals(listOf("虎皮", "虎骨"), d.rewards.materials)
        assertEquals(0.07, d.rewards.equipmentChance, 0.001)
        assertEquals(0.07, d.rewards.manualChance, 0.001)
    }

    @Test
    fun `狼牙谷奖励结构正确`() {
        val d = GameConfig.Dungeons.CONFIGS["wolfValley"]!!
        assertEquals("wolfValley", d.id)
        assertEquals("狼牙谷", d.name)
        assertEquals("狼妖", d.beastType)
        assertEquals(listOf(100, 300), d.rewards.spiritStones)
        assertEquals(listOf("狼皮", "狼骨"), d.rewards.materials)
        assertEquals(0.07, d.rewards.equipmentChance, 0.001)
        assertEquals(0.07, d.rewards.manualChance, 0.001)
    }

    @Test
    fun `幽冥蛇窟奖励结构正确`() {
        val d = GameConfig.Dungeons.CONFIGS["snakeCave"]!!
        assertEquals("snakeCave", d.id)
        assertEquals("幽冥蛇窟", d.name)
        assertEquals("蛇妖", d.beastType)
        assertEquals(listOf(100, 300), d.rewards.spiritStones)
        assertEquals(listOf("蛇皮", "蛇骨"), d.rewards.materials)
        assertEquals(0.07, d.rewards.equipmentChance, 0.001)
        assertEquals(0.07, d.rewards.manualChance, 0.001)
    }

    @Test
    fun `铁甲熊岭奖励结构正确`() {
        val d = GameConfig.Dungeons.CONFIGS["bearMountain"]!!
        assertEquals("bearMountain", d.id)
        assertEquals("铁甲熊岭", d.name)
        assertEquals("熊妖", d.beastType)
        assertEquals(listOf(100, 300), d.rewards.spiritStones)
        assertEquals(listOf("熊皮", "熊骨"), d.rewards.materials)
        assertEquals(0.07, d.rewards.equipmentChance, 0.001)
        assertEquals(0.07, d.rewards.manualChance, 0.001)
    }

    @Test
    fun `神风鹰巢奖励结构正确`() {
        val d = GameConfig.Dungeons.CONFIGS["eaglePeak"]!!
        assertEquals("eaglePeak", d.id)
        assertEquals("神风鹰巢", d.name)
        assertEquals("鹰妖", d.beastType)
        assertEquals(listOf(100, 300), d.rewards.spiritStones)
        assertEquals(listOf("鹰羽", "鹰骨"), d.rewards.materials)
        assertEquals(0.07, d.rewards.equipmentChance, 0.001)
        assertEquals(0.07, d.rewards.manualChance, 0.001)
    }

    @Test
    fun `幻魅狐谷奖励结构正确`() {
        val d = GameConfig.Dungeons.CONFIGS["foxHollow"]!!
        assertEquals("foxHollow", d.id)
        assertEquals("幻魅狐谷", d.name)
        assertEquals("狐妖", d.beastType)
        assertEquals(listOf(100, 300), d.rewards.spiritStones)
        assertEquals(listOf("狐皮", "狐骨"), d.rewards.materials)
        assertEquals(0.07, d.rewards.equipmentChance, 0.001)
        assertEquals(0.07, d.rewards.manualChance, 0.001)
    }

    @Test
    fun `远古龙渊奖励结构正确`() {
        val d = GameConfig.Dungeons.CONFIGS["dragonAbyss"]!!
        assertEquals("dragonAbyss", d.id)
        assertEquals("远古龙渊", d.name)
        assertEquals("龙妖", d.beastType)
        assertEquals(listOf(100, 300), d.rewards.spiritStones)
        assertEquals(listOf("龙鳞", "龙骨"), d.rewards.materials)
        assertEquals(0.07, d.rewards.equipmentChance, 0.001)
        assertEquals(0.07, d.rewards.manualChance, 0.001)
    }

    @Test
    fun `玄甲龟岛奖励结构正确`() {
        val d = GameConfig.Dungeons.CONFIGS["turtleIsland"]!!
        assertEquals("turtleIsland", d.id)
        assertEquals("玄甲龟岛", d.name)
        assertEquals("龟妖", d.beastType)
        assertEquals(listOf(100, 300), d.rewards.spiritStones)
        assertEquals(listOf("龟甲", "龟骨"), d.rewards.materials)
        assertEquals(0.07, d.rewards.equipmentChance, 0.001)
        assertEquals(0.07, d.rewards.manualChance, 0.001)
    }

    // ============================================================
    // Starting 对象 - RESOURCES
    // ============================================================

    @Test
    fun `初始资源灵石应为1000`() {
        assertEquals(1000, GameConfig.Starting.RESOURCES.spiritStones)
    }

    @Test
    fun `初始资源声望应为100`() {
        assertEquals(100, GameConfig.Starting.RESOURCES.reputation)
    }

    @Test
    fun `初始资源灵草应为50`() {
        assertEquals(50, GameConfig.Starting.RESOURCES.spiritHerbs)
    }

    // ============================================================
    // PolicyConfig 对象 - 各种常量值
    // ============================================================

    @Test
    fun `灵矿增产消耗应为0`() {
        assertEquals(0L, GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_COST)
    }

    @Test
    fun `增强治安消耗应为3000`() {
        assertEquals(3000L, GameConfig.PolicyConfig.ENHANCED_SECURITY_COST)
    }

    @Test
    fun `丹道激励消耗应为3000`() {
        assertEquals(3000L, GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_COST)
    }

    @Test
    fun `锻造激励消耗应为3000`() {
        assertEquals(3000L, GameConfig.PolicyConfig.FORGE_INCENTIVE_COST)
    }

    @Test
    fun `灵药培育消耗应为3000`() {
        assertEquals(3000L, GameConfig.PolicyConfig.HERB_CULTIVATION_COST)
    }

    @Test
    fun `修行津贴消耗应为4000`() {
        assertEquals(4000L, GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_COST)
    }

    @Test
    fun `功法研习消耗应为4000`() {
        assertEquals(4000L, GameConfig.PolicyConfig.MANUAL_RESEARCH_COST)
    }

    @Test
    fun `灵矿增产名称应为灵矿增产`() {
        assertEquals("灵矿增产", GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_NAME)
    }

    @Test
    fun `增强治安名称应为增强治安`() {
        assertEquals("增强治安", GameConfig.PolicyConfig.ENHANCED_SECURITY_NAME)
    }

    @Test
    fun `丹道激励名称应为丹道激励`() {
        assertEquals("丹道激励", GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_NAME)
    }

    @Test
    fun `锻造激励名称应为锻造激励`() {
        assertEquals("锻造激励", GameConfig.PolicyConfig.FORGE_INCENTIVE_NAME)
    }

    @Test
    fun `灵药培育名称应为灵药培育`() {
        assertEquals("灵药培育", GameConfig.PolicyConfig.HERB_CULTIVATION_NAME)
    }

    @Test
    fun `修行津贴名称应为修行津贴`() {
        assertEquals("修行津贴", GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_NAME)
    }

    @Test
    fun `功法研习名称应为功法研习`() {
        assertEquals("功法研习", GameConfig.PolicyConfig.MANUAL_RESEARCH_NAME)
    }

    @Test
    fun `灵矿增产效果应为20百分比`() {
        assertEquals(0.20, GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_BASE_EFFECT, 0.001)
    }

    @Test
    fun `增强治安效果应为20百分比`() {
        assertEquals(0.20, GameConfig.PolicyConfig.ENHANCED_SECURITY_BASE_EFFECT, 0.001)
    }

    @Test
    fun `丹道激励效果应为10百分比`() {
        assertEquals(0.10, GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_BASE_EFFECT, 0.001)
    }

    @Test
    fun `锻造激励效果应为10百分比`() {
        assertEquals(0.10, GameConfig.PolicyConfig.FORGE_INCENTIVE_BASE_EFFECT, 0.001)
    }

    @Test
    fun `灵药培育效果应为20百分比`() {
        assertEquals(0.20, GameConfig.PolicyConfig.HERB_CULTIVATION_BASE_EFFECT, 0.001)
    }

    @Test
    fun `修行津贴效果应为15百分比`() {
        assertEquals(0.15, GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_BASE_EFFECT, 0.001)
    }

    @Test
    fun `功法研习效果应为20百分比`() {
        assertEquals(0.20, GameConfig.PolicyConfig.MANUAL_RESEARCH_BASE_EFFECT, 0.001)
    }

    @Test
    fun `副宗主智力基准值应为50`() {
        assertEquals(50, GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BASE)
    }

    @Test
    fun `副宗主智力步长应为5`() {
        assertEquals(5, GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_STEP)
    }

    @Test
    fun `副宗主智力每步加成应为0点01`() {
        assertEquals(0.01, GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BONUS_PER_STEP, 0.001)
    }

    // ============================================================
    // Diplomacy 对象
    // ============================================================

    @Test
    fun `结盟最低好感度应为80`() {
        assertEquals(80, GameConfig.Diplomacy.MIN_ALLIANCE_FAVOR)
    }

    @Test
    fun `好感度最小值应为0`() {
        assertEquals(0, GameConfig.Diplomacy.MIN_FAVOR)
    }

    @Test
    fun `好感度最大值应为100`() {
        assertEquals(100, GameConfig.Diplomacy.MAX_FAVOR)
    }

    @Test
    fun `结盟持续时间应为5年`() {
        assertEquals(5, GameConfig.Diplomacy.ALLIANCE_DURATION_YEARS)
    }

    @Test
    fun `默认最大联盟槽位应为3`() {
        assertEquals(3, GameConfig.Diplomacy.MAX_ALLIANCE_SLOTS_DEFAULT)
    }

    @Test
    fun `外交事件概率应为0点12`() {
        assertEquals(0.12, GameConfig.Diplomacy.DIPLOMATIC_EVENT_CHANCE, 0.001)
    }

    @Test
    fun `好感度衰减无赠礼年限应为1年`() {
        assertEquals(1, GameConfig.Diplomacy.FAVOR_DECAY_NO_GIFT_YEARS)
    }

    @Test
    fun `好感度衰减量应为1`() {
        assertEquals(1, GameConfig.Diplomacy.FAVOR_DECAY_AMOUNT)
    }

    @Test
    fun `好感度衰减阈值应为80`() {
        assertEquals(80, GameConfig.Diplomacy.FAVOR_DECAY_THRESHOLD)
    }

    @Test
    fun `联盟评分阈值应为80`() {
        assertEquals(80, GameConfig.Diplomacy.AllianceScore.THRESHOLD)
    }

    @Test
    fun `联盟评分概率除数应为200点0`() {
        assertEquals(200.0, GameConfig.Diplomacy.AllianceScore.PROBABILITY_DIVISOR, 0.001)
    }

    @Test
    fun `AI最大联盟数应为2`() {
        assertEquals(2, GameConfig.Diplomacy.AllianceScore.MAX_AI_ALLIANCES)
    }

    @Test
    fun `破盟好感度惩罚应为30`() {
        assertEquals(30, GameConfig.Diplomacy.BreakPenalty.FAVOR_PENALTY)
    }

    @Test
    fun `破盟灵石惩罚比例应为0点1`() {
        assertEquals(0.1, GameConfig.Diplomacy.BreakPenalty.SPIRIT_STONE_PENALTY_RATIO, 0.001)
    }
}
