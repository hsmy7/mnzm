import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.ItemEffect

// 测试突破丹药使用逻辑
fun testBreakthroughPillUsage() {
    // 创建一个大乘境弟子（realm=2）
    val disciple = Disciple(
        id = "test",
        name = "测试弟子",
        realm = 2, // 大乘境
        realmLayer = 9,
        cultivation = 64000.0, // 达到突破要求
        maxCultivation = 64000.0
    )
    
    // 创建存储袋物品：登仙丹（targetRealm=9）和渡劫丹（targetRealm=8）
    val ascensionPillItem = StorageBagItem(
        itemId = "ascensionPill",
        itemType = "pill",
        name = "登仙丹",
        rarity = 6,
        quantity = 1,
        effect = ItemEffect(
            breakthroughChance = 0.30,
            targetRealm = 9 // 对应渡劫→仙人
        )
    )
    
    val tribulationPillItem = StorageBagItem(
        itemId = "tribulationPill",
        itemType = "pill",
        name = "渡劫丹",
        rarity = 6,
        quantity = 1,
        effect = ItemEffect(
            breakthroughChance = 0.30,
            targetRealm = 8 // 对应大乘→渡劫
        )
    )
    
    // 测试场景1：只有登仙丹
    val discipleWithAscensionPill = disciple.copy(
        storageBagItems = listOf(ascensionPillItem)
    )
    
    // 模拟自动使用突破丹药
    val (updatedDisciple1, pillBonus1) = GameEngine().autoUseBreakthroughPills(discipleWithAscensionPill)
    println("测试场景1（只有登仙丹）：")
    println("使用的丹药加成：$pillBonus1")
    println("是否使用了登仙丹：${updatedDisciple1.storageBagItems.size < discipleWithAscensionPill.storageBagItems.size}")
    
    // 测试场景2：只有渡劫丹
    val discipleWithTribulationPill = disciple.copy(
        storageBagItems = listOf(tribulationPillItem)
    )
    
    val (updatedDisciple2, pillBonus2) = GameEngine().autoUseBreakthroughPills(discipleWithTribulationPill)
    println("\n测试场景2（只有渡劫丹）：")
    println("使用的丹药加成：$pillBonus2")
    println("是否使用了渡劫丹：${updatedDisciple2.storageBagItems.size < discipleWithTribulationPill.storageBagItems.size}")
    
    // 测试场景3：两种丹药都有
    val discipleWithBothPills = disciple.copy(
        storageBagItems = listOf(ascensionPillItem, tribulationPillItem)
    )
    
    val (updatedDisciple3, pillBonus3) = GameEngine().autoUseBreakthroughPills(discipleWithBothPills)
    println("\n测试场景3（两种丹药都有）：")
    println("使用的丹药加成：$pillBonus3")
    println("剩余丹药数量：${updatedDisciple3.storageBagItems.size}")
    println("剩余丹药名称：${updatedDisciple3.storageBagItems.map { it.name }}")
}

fun main() {
    testBreakthroughPillUsage()
}
