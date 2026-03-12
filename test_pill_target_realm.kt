import com.xianxia.sect.core.data.ItemDatabase

fun main() {
    println("突破类丹药 targetRealm 值测试")
    println("==================================")
    
    val breakthroughPills = ItemDatabase.breakthroughPills
    
    // 预期的 targetRealm 值
    val expectedValues = mapOf(
        "foundationPill" to 8,    // 筑基丹
        "goldenCorePill" to 7,     // 凝金丹
        "nascentSoulPill" to 6,    // 结婴丹
        "spiritSeveringPill" to 5, // 化神丹
        "voidBreakPill" to 4,      // 破虚丹
        "unityPill" to 3,          // 合道丹
        "mahayanaPill" to 2,       // 大乘丹
        "tribulationPill" to 1,    // 渡劫丹
        "ascensionPill" to 0        // 登仙丹
    )
    
    var allCorrect = true
    
    for ((pillId, pillTemplate) in breakthroughPills) {
        val expected = expectedValues[pillId]
        val actual = pillTemplate.targetRealm
        
        if (expected != null) {
            val correct = expected == actual
            allCorrect = allCorrect && correct
            
            println("${pillTemplate.name}: expected=${expected}, actual=${actual} - ${if (correct) "✓" else "✗"}")
        }
    }
    
    println("==================================")
    println("测试结果: ${if (allCorrect) "全部正确" else "存在错误"}")
}
