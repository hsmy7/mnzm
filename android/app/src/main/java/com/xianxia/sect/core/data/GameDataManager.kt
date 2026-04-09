package com.xianxia.sect.core.data

import android.content.Context
import android.util.Log

/**
 * 游戏数据管理中心
 *
 * 统一管理所有静态模板数据的注册表实例，提供：
 * - 集中化的初始化入口
 * - 统一的注册表访问接口
 * - 内存占用监控
 * - 初始化状态检查
 *
 * ## 使用方式
 *
 * ```kotlin
 * // 1. 应用启动时初始化
 * GameDataManager.initialize(context)
 *
 * // 2. 访问各类型数据
 * val equipment = GameDataManager.equipment.getById("ironSword")
 * val pill = GameDataManager.pills.getById("spiritGatheringPill")
 *
 * // 3. 检查初始化状态
 * if (!GameDataManager.isFullyInitialized()) {
 *     // 处理未初始化情况
 * }
 * ```
 */
object GameDataManager {

    private const val TAG = "GameDataManager"

    // ==================== 注册表实例 ====================

    /**
     * 装备模板注册表
     */
    lateinit var equipment: EquipmentRegistry
        private set

    /**
     * 丹药模板注册表（纯效果定义，不含配方）
     */
    lateinit var pills: PillTemplateRegistry
        private set

    /**
     * 丹药配方注册表（引用 pills 中的丹药 ID）
     */
    lateinit var pillRecipes: PillRecipeRegistry
        private set

    /**
     * 功法模板注册表（从 JSON 加载）
     */
    lateinit var manuals: ManualRegistry
        private set

    /**
     * 天赋数据注册表
     */
    lateinit var talents: TalentRegistry
        private set

    /**
     * 草药注册表
     */
    lateinit var herbs: HerbRegistry
        private set

    /**
     * 锻造配方注册表
     */
    lateinit var forgeRecipes: ForgeRecipeRegistry
        private set

    /**
     * 妖兽材料注册表
     */
    lateinit var beastMaterials: BeastMaterialRegistry
        private set

    /**
     * 材料模板注册表（通用材料，含妖兽材料）
     */
    lateinit var materials: MaterialTemplateRegistry
        private set

    // ==================== 内部状态 ====================

    @Volatile
    private var _isInitialized = false

    private val initLock = Any()

    // ==================== 公共 API ====================

    /**
     * 初始化所有数据注册表
     *
     * 此方法应在应用启动时调用（如 Application.onCreate）。
     * 会按顺序初始化所有注册表，对于需要 Context 的注册表会传入 context。
     *
     * @param context Android 上下文（用于加载 Assets 资源）
     * @return 初始化是否成功
     */
    fun initialize(context: Context): Boolean {
        if (_isInitialized) {
            Log.w(TAG, "GameDataManager already initialized, skipping.")
            return true
        }

        return synchronized(initLock) {
            try {
                Log.i(TAG, "Starting GameDataManager initialization...")

                val startTime = System.currentTimeMillis()

                // 1. 创建无依赖的硬编码注册表
                equipment = EquipmentRegistry().also { it.autoInitialize() }
                talents = TalentRegistry().also { it.autoInitialize() }
                herbs = HerbRegistry().also { it.autoInitialize() }
                beastMaterials = BeastMaterialRegistry().also { it.autoInitialize() }

                // 2. 创建依赖其他注册表的注册表
                pills = PillTemplateRegistry().also { it.autoInitialize() }
                materials = MaterialTemplateRegistry(beastMaterials).also { it.autoInitialize() }

                // 3. 创建配方注册表（依赖丹药模板）
                pillRecipes = PillRecipeRegistry(pills).also { it.autoInitialize() }

                // 4. 创建锻造配方注册表（依赖装备模板）
                forgeRecipes = ForgeRecipeRegistry(equipment).also { it.autoInitialize() }

                // 5. 初始化需要 Context 的注册表（JSON 加载）
                manuals = ManualRegistry()
                val manualResult = manuals.initializeSync(context)
                if (manualResult.isFailure) {
                    throw RuntimeException("Failed to initialize ManualRegistry", manualResult.exceptionOrNull())
                }

                _isInitialized = true

                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "GameDataManager initialized successfully in ${elapsed}ms")
                Log.i(TAG, "Memory usage: ${getMemoryUsage()} bytes")

                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize GameDataManager", e)
                false
            }
        }
    }

    /**
     * 检查所有注册表是否已完全初始化
     *
     * @return 如果所有注册表都已初始化返回 true
     */
    fun isFullyInitialized(): Boolean {
        if (!_isInitialized) return false

        return listOf(
            ::equipment, ::pills, ::pillRecipes, ::manuals,
            ::talents, ::herbs, ::forgeRecipes, ::beastMaterials, ::materials
        ).all { registry ->
            try {
                registry.get().isInitialized()
            } catch (e: UninitializedPropertyAccessException) {
                false
            }
        }
    }

    /**
     * 获取内存占用估算（字节）
     *
     * 通过统计所有注册表的模板数量来估算内存占用。
     * 注意：这只是粗略估算，实际内存使用可能因对象头、对齐等原因有所不同。
     *
     * @return 估算的总内存占用（字节）
     */
    fun getMemoryUsage(): Long {
        if (!_isInitialized) return 0L

        return listOf(
            equipment.allTemplates,
            pills.allTemplates,
            pillRecipes.allTemplates,
            manuals.allTemplates,
            talents.allTemplates,
            herbs.allHerbTemplates,
            herbs.allSeedTemplates,
            forgeRecipes.allTemplates,
            beastMaterials.allTemplates,
            materials.allTemplates
        ).sumOf { templates ->
            // 粗略估算：每个 Map 条目约 128 字节（包含对象头、引用等）
            templates.size * 128L
        }
    }

    /**
     * 获取初始化状态报告
     *
     * 用于调试和监控，返回每个注册表的初始化状态和数据量。
     *
     * @return 状态报告字符串
     */
    fun getStatusReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== GameDataManager Status ===")
        sb.appendLine("Global initialized: $_isInitialized")
        sb.appendLine()

        if (_isInitialized) {
            sb.appendLine("Registries:")
            sb.appendLine("  - Equipment: ${equipment.getCount()} items [${equipment.isInitialized()}]")
            sb.appendLine("  - Pills: ${pills.getCount()} items [${pills.isInitialized()}]")
            sb.appendLine("  - Pill Recipes: ${pillRecipes.getCount()} items [${pillRecipes.isInitialized()}]")
            sb.appendLine("  - Manuals: ${manuals.getCount()} items [${manuals.isInitialized()}]")
            sb.appendLine("  - Talents: ${talents.getCount()} items [${talents.isInitialized()}]")
            sb.appendLine("  - Herbs: ${herbs.getHerbCount()} herbs, ${herbs.getSeedCount()} seeds [${herbs.isInitialized()}]")
            sb.appendLine("  - Forge Recipes: ${forgeRecipes.getCount()} items [${forgeRecipes.isInitialized()}]")
            sb.appendLine("  - Beast Materials: ${beastMaterials.getCount()} items [${beastMaterials.isInitialized()}]")
            sb.appendLine("  - Materials: ${materials.getCount()} items [${materials.isInitialized()}]")
            sb.appendLine()
            sb.appendLine("Total memory usage: ${getMemoryUsage()} bytes (~${getMemoryUsage() / 1024}KB)")
        } else {
            sb.appendLine("Not initialized yet.")
        }

        return sb.toString()
    }

    // ==================== 批量查询工具方法 ====================

    /**
     * 根据稀有度获取所有类型的随机物品
     *
     * 便捷方法，用于一次性获取多种类型的随机模板。
     *
     * @param rarity 目标稀有度
     * @return 包含各类型随机模板的数据类，如果某类型无数据则为 null
     */
    fun getRandomSetByRarity(rarity: Int): RandomSetByRarity {
        return RandomSetByRarity(
            equipment = try { equipment.getRandom(rarity, rarity) } catch (e: Exception) { null },
            pill = try { pills.getRandom(rarity, rarity) } catch (e: Exception) { null },
            manual = try { manuals.getRandom(rarity, rarity) } catch (e: Exception) { null },
            herb = try { herbs.getRandomHerb(rarity, rarity) } catch (e: Exception) { null },
            beastMaterial = try { beastMaterials.getRandom(rarity, rarity) } catch (e: Exception) { null }
        )
    }

    /**
     * 按稀有度随机生成的物品集合
     */
    data class RandomSetByRarity(
        val equipment: com.xianxia.sect.core.data.EquipmentDatabase.EquipmentTemplate?,
        val pill: ItemDatabase.PillTemplate?,
        val manual: ManualDatabase.ManualTemplate?,
        val herb: HerbDatabase.Herb?,
        val beastMaterial: BeastMaterialDatabase.BeastMaterial?
    )
}
