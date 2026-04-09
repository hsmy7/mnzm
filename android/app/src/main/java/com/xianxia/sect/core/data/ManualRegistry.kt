package com.xianxia.sect.core.data

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Manual
import com.xianxia.sect.core.model.ManualType

/**
 * 功法模板注册表
 *
 * 管理所有功法的静态模板数据。
 * **唯一需要从 JSON Assets 加载的注册表**。
 *
 * ## 与原 ManualDatabase 的差异
 * - 继承 BaseTemplateRegistry，提供统一接口
 * - 保留 JSON 加载和 Protobuf 校验机制
 * - 需要显式调用 [initializeSync] 完成初始化
 * - 增加线程安全的延迟初始化支持
 */
class ManualRegistry : BaseTemplateRegistry<ManualDatabase.ManualTemplate>() {

    private val TAG = "ManualRegistry"

    // ==================== 内部状态 ====================

    @Volatile
    private var _isInitialized = false

    // ==================== BaseTemplateRegistry 实现 ====================

    override fun loadTemplates(): Map<String, ManualDatabase.ManualTemplate> {
        // 注意：ManualDatabase 使用延迟初始化，这里不能直接访问 allManuals
        // 需要通过 initializeSync() 先完成初始化
        if (!_isInitialized) {
            Log.w(TAG, "Accessing templates before initialization. Return empty map.")
            return emptyMap()
        }
        return ManualDatabase.allManuals
    }

    override fun extractRarity(template: ManualDatabase.ManualTemplate): Int = template.rarity

    override fun isInitialized(): Boolean = _isInitialized

    // ==================== 初始化方法 ====================

    /**
     * 同步初始化（从 JSON Assets 加载数据）
     *
     * 此方法必须在首次查询前调用。
     * 内部委托给原 ManualDatabase 的实现，保留 Protobuf 校验逻辑。
     *
     * @param context Android 上下文（用于访问 Assets）
     * @return 初始化结果（成功或失败）
     */
    fun initializeSync(context: Context): Result<Unit> {
        return try {
            synchronized(initLock) {
                if (_isInitialized) return Result.success(Unit)

                val result = ManualDatabase.initializeSync(context)
                if (result.isSuccess) {
                    _isInitialized = true
                    markInitialized()
                    Log.i(TAG, "ManualRegistry initialized successfully with ${getCount()} templates")
                } else {
                    Log.e(TAG, "Failed to initialize ManualDatabase", result.exceptionOrNull())
                }

                result
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 使用预加载数据初始化（用于测试）
     *
     * @param manuals 预加载的功法模板映射
     */
    fun initializeWithManuals(manuals: Map<String, ManualDatabase.ManualTemplate>) {
        synchronized(initLock) {
            ManualDatabase.initializeWithManuals(manuals)
            _isInitialized = true
            markInitialized()
        }
    }

    // ==================== 扩展查询方法 ====================

    /**
     * 根据类型获取功法列表
     *
     * @param type 功法类型
     * @return 对应类型的所有功法
     */
    fun getByType(type: ManualType): List<ManualDatabase.ManualTemplate> {
        checkInitialization()
        return ManualDatabase.getByType(type)
    }

    /**
     * 根据名称查找功法
     *
     * @param name 功法名称
     * @return 匹配的功法模板，不存在则返回 null
     */
    fun getByName(name: String): ManualDatabase.ManualTemplate? {
        checkInitialization()
        return ManualDatabase.getByName(name)
    }

    /**
     * 根据名称和稀有度查找功法
     *
     * @param name 功法名称
     * @param rarity 稀有度
     * @return 匹配的功法模板
     */
    fun getByNameAndRarity(name: String, rarity: Int): ManualDatabase.ManualTemplate? {
        checkInitialization()
        return ManualDatabase.getByNameAndRarity(name, rarity)
    }

    // ==================== 实例创建方法 ====================

    /**
     * 从模板创建功法实例
     *
     * @param template 功法模板
     * @return 新生成的功法实例
     */
    fun createFromTemplate(template: ManualDatabase.ManualTemplate): Manual {
        checkInitialization()
        return ManualDatabase.createFromTemplate(template)
    }

    /**
     * 随机生成一个功法实例
     *
     * @param minRarity 最低稀有度（默认1）
     * @param maxRarity 最高稀有度（默认6）
     * @param type 可选的功法类型限制
     * @return 随机生成的功法实例
     */
    fun generateRandom(
        minRarity: Int = 1,
        maxRarity: Int = 6,
        type: ManualType? = null
    ): Manual {
        checkInitialization()
        return ManualDatabase.generateRandom(minRarity, maxRarity, type)
    }

    // ==================== 校验和导出方法 ====================

    /**
     * 获取上次 Protobuf 校验结果
     */
    fun getLastValidationResult(): ManualDatabase.ValidationResult? =
        ManualDatabase.getLastValidationResult()

    /**
     * 导出所有功法为 Protobuf 格式
     *
     * @return Base64 编码的 Protobuf 数据
     */
    fun exportToProtobuf(): String {
        checkInitialization()
        return ManualDatabase.exportToProtobuf()
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 检查是否已初始化
     */
    private fun checkInitialization() {
        check(_isInitialized) { "ManualRegistry not initialized. Call initializeSync(context) first." }
    }
}
