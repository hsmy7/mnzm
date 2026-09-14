package com.xianxia.sect.core.architecture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 守卫测试：仓库物品添加路径必须走统一合并入口（StackableItemStore）。
 *
 * 历史问题：多条发放路径（邮件/兑换码/外交/灵田等）手写
 * "find 第一个堆叠 + 列表追加 + coerceAtMost 截断"，导致同种物品
 * 分裂为多个堆叠。所有发放路径统一委托 InventorySystem.addXxx。
 *
 * 本测试扫描 engine 源文件，断言以下手写合并反模式数量为 0，
 * 防止未来新增发放路径时重新引入分裂 bug：
 * 1. `coerceAtMost(inventoryConfig.getMaxStackSize` — 溢出静默截断
 * 2. `equipmentStacks = equipmentStacks + item` — 列表直接追加新堆叠
 * 3. `newQty = existing.quantity + X` — 手写"相加合并"（仅匹配仓库堆叠字段）
 *
 * 已知豁免（有意为之，不在此守卫范围）：
 * - `CaptiveGearUtils.materializeCaptiveGear` 直接写 `equipmentInstances/manualInstances`
 *   实例表——这是"俘虏模板 id → 玩家 UUID 实例"的转换落库，非仓库堆叠发放，
 *   无合并/溢出语义；且仅由三条招募入口在事务内调用，空装备弟子天然短路。
 *   若未来实例表引入容量/来源追踪约束，需同步更新本守卫或该落库路径。
 */
class InventoryAddPathGuardTest {

    /**
     * 反模式 1：溢出用 coerceAtMost 截断丢弃（应改走 StackableItemStore 的 Partial 语义）。
     * 覆盖 `inventoryConfig.getMaxStackSize` / `config.getMaxStackSize` / 本地 maxStack 变量别名
     *（如 StorageBagUtils 手写路径的 `coerceAtMost(maxStack)` 本地变量写法）。
     */
    private val truncationPattern = Regex(
        "coerceAtMost\\s*\\(\\s*(?:\\w+\\.)*\\w*(?:[sS]tackSize|[sS]tack)\\s*\\)"
    )

    /**
     * 反模式 2：向仓库堆叠列表直接追加新条目（应改走 addXxx 合并）。
     * 覆盖 `+=`、`state.`/`this.` 前缀、括号、`=` 右侧全限定以及 `.plus(` 变体。
     */
    private val directAppendPattern = Regex(
        "(equipmentStacks|manualStacks|pills|materials|herbs|seeds|storageBags)" +
            "\\s*(\\+=|=)\\s*\\(?\\s*(?:state|this)?\\s*\\.?\\s*\\1\\s*(?:\\+|\\s*\\.\\s*plus\\s*\\()"
    )

    /**
     * 反模式 3：手写"数量相加合并"（find 第一个匹配堆叠 + 数量相加）。
     * 覆盖 newCount 等变量名变体与 `.plus(` 变体。
     */
    private val inlineMergeAddPattern = Regex(
        "new\\w*\\s*=\\s*\\(?\\w+\\.quantity\\s*(?:\\+|\\s*\\.\\s*plus\\s*\\()"
    )

    /**
     * 反模式 5：向实例表（equipmentInstances/manualInstances）直接追加。
     *
     * 仓库 UI 只渲染堆叠轨道（equipmentStacks/manualStacks），
     * 误写实例轨道的装备/功法对玩家不可见。
     *
     * 实例轨道仅允许以下语义（白名单见 [instanceAllowedFileNames]）：
     * - 弟子装备/功法分配（ownerId 绑定，从仓库堆叠扣减后转实例）
     * - 俘虏模板 id → 玩家 UUID 实例转换（CaptiveGearUtils）
     * - 自动装备/学习落库（袋内堆叠 → 实例）
     * - AI 敌人装备生成（非玩家发放）
     * - InventorySystem.addEquipmentInstance/addManualInstance 自身
     *
     * 玩家物品发放必须委托 InventorySystem.addEquipmentStack/addManualStack
     * （堆叠轨道，仓库可见 + 来源追踪 + 溢出兜底）。
     */
    // 发放误写实例轨道的教训：装备/功法奖励对玩家不可见（仓库只渲染堆叠）
    private val instanceAppendPattern = Regex(
        "(equipmentInstances|manualInstances)\\s*(\\+=|=)\\s*\\(?\\s*(?:state|this)?\\s*\\.?" +
            "\\s*\\1\\s*(?:\\+|\\s*\\.\\s*plus\\s*\\()" +
            "|(equipmentInstances|manualInstances)\\s*\\.\\s*add\\s*\\("
    )

    /**
     * 白名单：离线遗留仓库路径（SectWarehouse/WarehouseItem，主流程未使用），
     * 仍保留自己的堆叠实现，不属于统一入口范围。
     * 按相对路径匹配（避免任意包下同名文件被静默豁免），
     * 并有存在性断言（文件被删除/改名时守卫测试失败，防止白名单悬空）。
     */
    private val allowedFiles = setOf(
        "com" + File.separator + "xianxia" + File.separator + "sect" + File.separator +
            "core" + File.separator + "warehouse" + File.separator + "OptimizedWarehouseManager.kt"
    )

    // Gradle 测试工作目录为模块目录（android/core/engine）
    private val engineSourceDir = File(
        "src" + File.separator + "main" + File.separator + "java"
    )

    private fun sourceFiles(): List<File> =
        engineSourceDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun matchesIn(files: List<File>, pattern: Regex): List<Pair<File, String>> =
        files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (pattern.containsMatchIn(line)) file to "${file.name}:${index + 1}: $line" else null
            }
        }

    /** 文件相对 engine 源码根（src/main/java/）的路径，用于白名单匹配 */
    private fun relativePath(file: File): String =
        file.path.substringAfter(engineSourceDir.path + File.separator)

    @Test
    fun `guard whitelist files still exist`() {
        val files = sourceFiles().map { relativePath(it) }.toSet()
        val missing = allowedFiles.filter { it !in files }
        assertEquals(
            "白名单文件不存在或已被改名/删除：$missing\n" +
                "若文件确实已移除，请同时删除对应白名单条目",
            emptyList<String>(), missing
        )
    }

    @Test
    fun `no overflow truncation via coerceAtMost in any add path`() {
        // 排除遗留离线仓库路径（allowedFiles——SectWarehouse/WarehouseItem 主流程未使用，
        // 自带堆叠实现，见守卫文档；其 coerceAtMost(maxStack) 为白名单内合理实现）
        val files = sourceFiles().filter { relativePath(it) !in allowedFiles }
        val matches = matchesIn(files, truncationPattern)
        assertEquals(
            "发现 ${matches.size} 处溢出截断（coerceAtMost + getMaxStackSize）：\n" +
                matches.joinToString("\n") { it.second } +
                "\n应改为委托 InventorySystem.addXxx（StackableItemStore Partial 语义）",
            emptyList<Pair<File, String>>(), matches
        )
    }

    @Test
    fun `no direct list append to warehouse stacks`() {
        val files = sourceFiles()
        val matches = matchesIn(files, directAppendPattern)
        assertEquals(
            "发现 ${matches.size} 处仓库堆叠列表直接追加：\n" +
                matches.joinToString("\n") { it.second } +
                "\n应改为委托 InventorySystem.addXxx（自动合并同种堆叠）",
            emptyList<Pair<File, String>>(), matches
        )
    }

    @Test
    fun `no inline quantity-merge outside allowed legacy file`() {
        val files = sourceFiles().filter { relativePath(it) !in allowedFiles }
        val matches = matchesIn(files, inlineMergeAddPattern)
        assertEquals(
            "发现 ${matches.size} 处手写数量相加合并：\n" +
                matches.joinToString("\n") { it.second } +
                "\n应改为委托 InventorySystem.addXxx（StackableItemStore 遍历所有匹配堆叠）",
            emptyList<Pair<File, String>>(), matches
        )
    }

    /** 反模式 4：手写 StackableItemStore 构造（应统一走 InventorySystem.addXxx） */
    private val handWrittenStorePattern = Regex("StackableItemStore\\s*\\(")

    /**
     * 允许使用 StackableItemStore 的文件：
     * - InventorySystem.kt：统一入口内部（7 个 addXxx 的实现）
     * - ProductionProcessor.kt：灵田收获走"state 参数直传"模式（PlantingSystem
     *   onMonthlyEvent 传入事务缓冲，区别于 stateStore.update 模式），
     *   溢出通过 InventorySystem.sendOverflowMail 转邮件
     */
    private val storeAllowedFiles = setOf(
        "InventorySystem.kt", "ProductionProcessor.kt",
        "CultivationEventAutoWarehouseOps.kt",  // 自动入库域（batch-01 自 CultivationEventProcessor 拆出）
        // batch-01 拆分产物（InventorySystem 域文件，文件名白名单按名称匹配）
        "InventorySystemWithOps1.kt", "InventorySystem丹药Ops2.kt", "InventorySystem校验Ops3.kt",
        "InventorySystem装备Ops4.kt", "InventorySystem丹药Ops5.kt", "InventorySystemHasOps6.kt",
        "InventorySystemHasOps7.kt",
        "ProductionProcessor构筑Ops2.kt"  // 灵田收获域（batch-01 自 ProductionProcessor 拆出）
    )

    /**
     * 允许直接写实例表的文件（分配/转换/落库/AI 语义，非玩家仓库发放）。
     * 白名单内写入点均为合法分配路径；
     * 新增玩家物品发放路径必须走 addEquipmentStack/addManualStack，不得加入此白名单。
     */
    private val instanceAllowedFileNames = setOf(
        "InventorySystem.kt",           // 统一入口 addEquipmentInstance/addManualInstance 自身
        "CaptiveGearUtils.kt",          // 俘虏模板 id → 玩家 UUID 实例转换（守卫文档已豁免）
        "DiscipleFacadeImpl.kt",        // 弟子装备/功法分配（ownerId 绑定）
        "DiscipleEquipmentService.kt",  // 装备实例状态/分配
        "GameEngineManualOps.kt",       // 弟子学功法/替换功法（ownerId 绑定；M3 第九批自 Coordination 拆出）
        "CultivationEventProcessor.kt", // 自动装备/学习袋内堆叠落库
        "EnemyGenerator.kt",            // AI 敌人装备生成（非玩家发放）
        // batch-01 拆分产物（同语义归属，见上两行的原文件）
        "CultivationEventAutoWarehouseOps.kt",  // 自动装备/学习袋内堆叠落库
        "DiscipleFacadeImpl功法Ops1.kt",         // 弟子丹药/功法分配（ownerId 绑定）
        "DiscipleFacadeImpl战斗Ops2.kt"          // 弟子装备/功法分配（ownerId 绑定）
    )

    @Test
    fun `no hand-written StackableItemStore outside unified entry`() {
        val files = sourceFiles().filter { it.name !in storeAllowedFiles }
        val matches = matchesIn(files, handWrittenStorePattern)
        assertEquals(
            "发现 ${matches.size} 处手写 StackableItemStore 构造：\n" +
                matches.joinToString("\n") { it.second } +
                "\n应改为委托 InventorySystem.addXxx（自动获得合并/溢出转邮件/容量通知能力）",
            emptyList<Pair<File, String>>(), matches
        )
    }

    @Test
    fun `instance-append whitelist files still exist`() {
        val files = sourceFiles().map { it.name }.toSet()
        val missing = instanceAllowedFileNames.filter { it !in files }
        assertEquals(
            "白名单文件不存在或已被改名/删除：$missing\n" +
                "若文件确实已移除，请同时删除对应白名单条目",
            emptyList<String>(), missing
        )
    }

    @Test
    fun `no instance-table append outside disciple assignment paths`() {
        val files = sourceFiles().filter { it.name !in instanceAllowedFileNames }
        val matches = matchesIn(files, instanceAppendPattern)
        assertEquals(
            "发现 ${matches.size} 处实例表直接追加：\n" +
                matches.joinToString("\n") { it.second } +
                "\n玩家物品发放误写实例轨道（equipmentInstances/manualInstances）时，" +
                "仓库 UI 不渲染该轨道，奖励对玩家不可见。" +
                "\n发放必须委托 InventorySystem.addEquipmentStack/addManualStack" +
                "（堆叠轨道，仓库可见 + 来源追踪 + 溢出兜底）",
            emptyList<Pair<File, String>>(), matches
        )
    }
}
