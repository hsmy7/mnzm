# 配方数据热更新系统设计文档

## 概述

当前生产子系统使用硬编码的配方数据（`PillRecipeDatabase` 和 `ForgeRecipeDatabase`），无法在不更新应用的情况下修改配方。本文档描述配方数据热更新系统的设计方案。

## 当前问题分析

### 现有实现

```
PillRecipeDatabase.kt (1457行) - 硬编码丹方数据
ForgeRecipeDatabase.kt (142行) - 硬编码锻造配方数据
```

### 问题

1. **无法热更新** - 修改配方需要发布新版应用
2. **难以维护** - 大量硬编码数据混在代码中
3. **无法动态调整** - 无法根据游戏平衡性实时调整配方
4. **测试困难** - 无法快速测试新配方

## 设计方案

### 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                      GameEngine                              │
│                            │                                 │
│                            ▼                                 │
│                  ProductionCoordinator                       │
│                            │                                 │
│                            ▼                                 │
│                   RecipeRepository                           │
│                            │                                 │
│              ┌─────────────┼─────────────┐                   │
│              ▼             ▼             ▼                   │
│        LocalRecipe   RemoteRecipe   FallbackRecipe           │
│          Source        Source         Source                 │
│              │             │             │                   │
│              ▼             ▼             ▼                   │
│         Room DB      Remote API    Hardcoded Data            │
└─────────────────────────────────────────────────────────────┘
```

### 核心接口设计

#### 1. RecipeRepository

```kotlin
package com.xianxia.sect.core.data.recipe

interface RecipeRepository {
    suspend fun getPillRecipe(id: String): PillRecipe?
    suspend fun getAllPillRecipes(): List<PillRecipe>
    suspend fun getForgeRecipe(id: String): ForgeRecipe?
    suspend fun getAllForgeRecipes(): List<ForgeRecipe>
    
    suspend fun refreshRecipes(): Result<Unit>
    suspend fun getPillRecipesByTier(tier: Int): List<PillRecipe>
    suspend fun getForgeRecipesByTier(tier: Int): List<ForgeRecipe>
    
    fun observePillRecipes(): Flow<List<PillRecipe>>
    fun observeForgeRecipes(): Flow<List<ForgeRecipe>>
}
```

#### 2. RecipeSource 接口

```kotlin
interface RecipeSource {
    suspend fun loadPillRecipes(): Result<List<PillRecipe>>
    suspend fun loadForgeRecipes(): Result<List<ForgeRecipe>>
}

class LocalRecipeSource(
    private val recipeDao: RecipeDao
) : RecipeSource {
    override suspend fun loadPillRecipes(): Result<List<PillRecipe>> = 
        runCatching { recipeDao.getAllPillRecipes().map { it.toDomain() } }
    
    override suspend fun loadForgeRecipes(): Result<List<ForgeRecipe>> = 
        runCatching { recipeDao.getAllForgeRecipes().map { it.toDomain() } }
}

class RemoteRecipeSource(
    private val api: RecipeApi,
    private val versionCache: RecipeVersionCache
) : RecipeSource {
    
    private var cachedVersion: RecipeVersion? = null
    
    suspend fun checkVersion(): RecipeVersion? {
        return runCatching { api.getRecipeVersion() }.getOrNull()
            ?.also { cachedVersion = it }
    }
    
    fun hasUpdate(): Boolean {
        return cachedVersion?.version != versionCache.getCachedVersion()
    }
    
    override suspend fun loadPillRecipes(): Result<List<PillRecipe>> = 
        runCatching { 
            if (hasUpdate()) {
                api.getPillRecipes().also { 
                    cachedVersion?.version?.let { versionCache.updateVersion(it) }
                }
            } else {
                emptyList()
            }
        }
    
    override suspend fun loadForgeRecipes(): Result<List<ForgeRecipe>> = 
        runCatching { 
            if (hasUpdate()) {
                api.getForgeRecipes()
            } else {
                emptyList()
            }
        }
}

class FallbackRecipeSource : RecipeSource {
    override suspend fun loadPillRecipes(): Result<List<PillRecipe>> = 
        Result.success(PillRecipeDatabase.getStaticRecipes())
    
    override suspend fun loadForgeRecipes(): Result<List<ForgeRecipe>> = 
        Result.success(ForgeRecipeDatabase.getStaticRecipes())
}
```

#### 3. Room TypeConverter

```kotlin
class RecipeTypeConverters {
    private val gson = Gson()
    
    @TypeConverter
    fun fromStringMap(value: Map<String, Int>?): String? {
        return value?.let { gson.toJson(it) }
    }
    
    @TypeConverter
    fun toStringMap(value: String?): Map<String, Int>? {
        return value?.let { 
            val type = object : TypeToken<Map<String, Int>>() {}.type
            gson.fromJson(it, type) 
        }
    }
}
```

#### 4. 数据模型

@Entity(tableName = "pill_recipes")
data class PillRecipeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val pillId: String,
    val tier: Int,
    val category: String,
    val description: String,
    val duration: Int,
    val successRate: Double,
    val materials: Map<String, Int>,
    val rarity: Int,
    val breakthroughChance: Double,
    val targetRealm: Int,
    val cultivationSpeed: Double,
    val effectDuration: Int,
    val cultivationPercent: Int,
    val skillExpPercent: Int,
    val physicalAttackPercent: Int,
    val physicalDefensePercent: Int,
    val magicAttackPercent: Int,
    val magicDefensePercent: Int,
    val hpPercent: Int,
    val mpPercent: Int,
    val speedPercent: Int,
    val healPercent: Int,
    val healMaxHpPercent: Int,
    val heal: Int,
    val battleCount: Int,
    val extendLife: Int,
    val mpRecoverMaxMpPercent: Int,
    val version: Long,
    val updatedAt: Long
)

@Entity(tableName = "forge_recipes")
data class ForgeRecipeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val equipmentId: String,
    val tier: Int,
    val equipmentSlot: String,
    val description: String,
    val materials: Map<String, Int>,
    val duration: Int,
    val successRate: Double,
    val rarity: Int,
    val version: Long,
    val updatedAt: Long
)
```

#### 5. RecipeDao

```kotlin
@Dao
interface RecipeDao {
    @Query("SELECT * FROM pill_recipes")
    suspend fun getAllPillRecipes(): List<PillRecipeEntity>
    
    @Query("SELECT * FROM pill_recipes WHERE id = :id")
    suspend fun getPillRecipeById(id: String): PillRecipeEntity?
    
    @Query("SELECT * FROM pill_recipes WHERE tier = :tier")
    suspend fun getPillRecipesByTier(tier: Int): List<PillRecipeEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPillRecipes(recipes: List<PillRecipeEntity>)
    
    @Query("DELETE FROM pill_recipes")
    suspend fun deleteAllPillRecipes()
    
    @Transaction
    suspend fun replacePillRecipes(recipes: List<PillRecipeEntity>) {
        deleteAllPillRecipes()
        insertPillRecipes(recipes)
    }
    
    @Query("SELECT * FROM forge_recipes")
    suspend fun getAllForgeRecipes(): List<ForgeRecipeEntity>
    
    @Query("SELECT * FROM forge_recipes WHERE id = :id")
    suspend fun getForgeRecipeById(id: String): ForgeRecipeEntity?
    
    @Query("SELECT * FROM forge_recipes WHERE tier = :tier")
    suspend fun getForgeRecipesByTier(tier: Int): List<ForgeRecipeEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertForgeRecipes(recipes: List<ForgeRecipeEntity>)
    
    @Query("DELETE FROM forge_recipes")
    suspend fun deleteAllForgeRecipes()
    
    @Transaction
    suspend fun replaceForgeRecipes(recipes: List<ForgeRecipeEntity>) {
        deleteAllForgeRecipes()
        insertForgeRecipes(recipes)
    }
}
```

#### 6. Entity 与 Domain 模型转换

```kotlin
fun PillRecipeEntity.toDomain(): PillRecipe = PillRecipe(
    id = id,
    name = name,
    pillId = pillId,
    tier = tier,
    category = PillCategory.entries.find { it.name == category } ?: PillCategory.CULTIVATION,
    description = description,
    duration = duration,
    successRate = successRate,
    materials = materials,
    rarity = rarity,
    breakthroughChance = breakthroughChance,
    targetRealm = targetRealm,
    cultivationSpeed = cultivationSpeed,
    effectDuration = effectDuration,
    cultivationPercent = cultivationPercent,
    skillExpPercent = skillExpPercent,
    physicalAttackPercent = physicalAttackPercent,
    physicalDefensePercent = physicalDefensePercent,
    magicAttackPercent = magicAttackPercent,
    magicDefensePercent = magicDefensePercent,
    hpPercent = hpPercent,
    mpPercent = mpPercent,
    speedPercent = speedPercent,
    healPercent = healPercent,
    healMaxHpPercent = healMaxHpPercent,
    heal = heal,
    battleCount = battleCount,
    extendLife = extendLife,
    mpRecoverMaxMpPercent = mpRecoverMaxMpPercent
)

fun PillRecipe.toEntity(version: Long = System.currentTimeMillis()): PillRecipeEntity = PillRecipeEntity(
    id = id,
    name = name,
    pillId = pillId,
    tier = tier,
    category = category.name,
    description = description,
    duration = duration,
    successRate = successRate,
    materials = materials,
    rarity = rarity,
    breakthroughChance = breakthroughChance,
    targetRealm = targetRealm,
    cultivationSpeed = cultivationSpeed,
    effectDuration = effectDuration,
    cultivationPercent = cultivationPercent,
    skillExpPercent = skillExpPercent,
    physicalAttackPercent = physicalAttackPercent,
    physicalDefensePercent = physicalDefensePercent,
    magicAttackPercent = magicAttackPercent,
    magicDefensePercent = magicDefensePercent,
    hpPercent = hpPercent,
    mpPercent = mpPercent,
    speedPercent = speedPercent,
    healPercent = healPercent,
    healMaxHpPercent = healMaxHpPercent,
    heal = heal,
    battleCount = battleCount,
    extendLife = extendLife,
    mpRecoverMaxMpPercent = mpRecoverMaxMpPercent,
    version = version,
    updatedAt = System.currentTimeMillis()
)

fun ForgeRecipeEntity.toDomain(): ForgeRecipe = ForgeRecipe(
    id = id,
    name = name,
    equipmentId = equipmentId,
    tier = tier,
    equipmentSlot = EquipmentSlot.entries.find { it.name == equipmentSlot } ?: EquipmentSlot.WEAPON,
    description = description,
    materials = materials,
    duration = duration,
    successRate = successRate,
    rarity = rarity
)

fun ForgeRecipe.toEntity(version: Long = System.currentTimeMillis()): ForgeRecipeEntity = ForgeRecipeEntity(
    id = id,
    name = name,
    equipmentId = equipmentId,
    tier = tier,
    equipmentSlot = equipmentSlot.name,
    description = description,
    materials = materials,
    duration = duration,
    successRate = successRate,
    rarity = rarity,
    version = version,
    updatedAt = System.currentTimeMillis()
)
```

#### 7. RecipeRepositoryImpl

```kotlin
class RecipeRepositoryImpl(
    private val localSource: LocalRecipeSource,
    private val remoteSource: RemoteRecipeSource,
    private val fallbackSource: FallbackRecipeSource,
    private val recipeDao: RecipeDao,
    private val externalScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : RecipeRepository {
    
    private val _pillRecipes = MutableStateFlow<List<PillRecipe>>(emptyList())
    override fun observePillRecipes(): Flow<List<PillRecipe>> = _pillRecipes.asStateFlow()
    
    private val _forgeRecipes = MutableStateFlow<List<ForgeRecipe>>(emptyList())
    override fun observeForgeRecipes(): Flow<List<ForgeRecipe>> = _forgeRecipes.asStateFlow()
    
    private val mutex = Mutex()
    
    init {
        loadInitialRecipes()
    }
    
    private fun loadInitialRecipes() {
        externalScope.launch(ioDispatcher) {
            val recipes = localSource.loadPillRecipes().getOrNull()
                ?: fallbackSource.loadPillRecipes().getOrNull()
                ?: emptyList()
            _pillRecipes.value = recipes
            
            val forgeRecipes = localSource.loadForgeRecipes().getOrNull()
                ?: fallbackSource.loadForgeRecipes().getOrNull()
                ?: emptyList()
            _forgeRecipes.value = forgeRecipes
        }
    }
    
    override suspend fun getPillRecipe(id: String): PillRecipe? {
        return _pillRecipes.value.find { it.id == id }
    }
    
    override suspend fun getAllPillRecipes(): List<PillRecipe> = _pillRecipes.value
    
    override suspend fun getForgeRecipe(id: String): ForgeRecipe? {
        return _forgeRecipes.value.find { it.id == id }
    }
    
    override suspend fun getAllForgeRecipes(): List<ForgeRecipe> = _forgeRecipes.value
    
    override suspend fun refreshRecipes(): Result<Unit> = mutex.withLock {
        runCatching {
            remoteSource.checkVersion() ?: return@runCatching
            
            if (!remoteSource.hasUpdate()) return@runCatching
            
            val remotePillRecipes = remoteSource.loadPillRecipes().getOrThrow()
            val remoteForgeRecipes = remoteSource.loadForgeRecipes().getOrThrow()
            
            if (remotePillRecipes.isNotEmpty()) {
                recipeDao.replacePillRecipes(remotePillRecipes.map { it.toEntity() })
                _pillRecipes.value = remotePillRecipes
            }
            
            if (remoteForgeRecipes.isNotEmpty()) {
                recipeDao.replaceForgeRecipes(remoteForgeRecipes.map { it.toEntity() })
                _forgeRecipes.value = remoteForgeRecipes
            }
        }.onFailure {
            loadInitialRecipes()
        }
    }
    
    override suspend fun getPillRecipesByTier(tier: Int): List<PillRecipe> =
        _pillRecipes.value.filter { it.tier == tier }
    
    override suspend fun getForgeRecipesByTier(tier: Int): List<ForgeRecipe> =
        _forgeRecipes.value.filter { it.tier == tier }
}
```

### 远程 API 设计

```kotlin
interface RecipeApi {
    @GET("/api/v1/recipes/version")
    suspend fun getRecipeVersion(): RecipeVersion
    
    @GET("/api/v1/recipes/pills")
    suspend fun getPillRecipes(): List<PillRecipe>
    
    @GET("/api/v1/recipes/forge")
    suspend fun getForgeRecipes(): List<ForgeRecipe>
}

data class RecipeVersion(
    val version: Long,
    val updatedAt: Long,
    val pillRecipeCount: Int,
    val forgeRecipeCount: Int
)

class RecipeVersionCache(
    private val prefs: SharedPreferences
) {
    fun getCachedVersion(): Long = prefs.getLong(KEY_RECIPE_VERSION, 0)
    
    fun updateVersion(version: Long) {
        prefs.edit().putLong(KEY_RECIPE_VERSION, version).apply()
    }
    
    companion object {
        private const val KEY_RECIPE_VERSION = "recipe_version"
    }
}
```

### JSON 配方格式示例

```json
{
  "version": 2026033001,
  "updatedAt": 1774827311000,
  "pillRecipes": [
    {
      "id": "pill_qi_gathering",
      "name": "聚气丹",
      "pillId": "pill_qi_gathering_1",
      "tier": 1,
      "category": "CULTIVATION",
      "description": "增加修炼速度",
      "duration": 3,
      "successRate": 0.8,
      "materials": {
        "herb_spirit_grass": 2,
        "herb_ginseng": 1
      },
      "rarity": 1,
      "cultivationSpeed": 0.1,
      "effectDuration": 30
    }
  ],
  "forgeRecipes": [
    {
      "id": "forge_iron_sword",
      "name": "玄铁剑",
      "equipmentId": "equip_iron_sword_1",
      "tier": 1,
      "equipmentSlot": "WEAPON",
      "description": "基础武器",
      "materials": {
        "material_iron_ore": 3,
        "material_beast_bone": 1
      },
      "duration": 6,
      "successRate": 0.75,
      "rarity": 1
    }
  ]
}
```

## 迁移计划

### 阶段 1: 数据层准备

1. 创建 Room 数据库表和 DAO
2. 实现数据模型转换器
3. 创建 RecipeRepository 接口和实现

### 阶段 2: 兼容层实现

1. 修改 `PillRecipeDatabase` 和 `ForgeRecipeDatabase` 为数据源之一
2. 实现 `FallbackRecipeSource` 使用现有硬编码数据
3. 确保 GameEngine 和 ProductionCoordinator 使用 RecipeRepository

### 阶段 3: 远程数据源集成

1. 实现远程 API 接口
2. 添加版本检查和增量更新机制
3. 实现离线缓存策略

### 阶段 4: 测试和验证

1. 单元测试 RecipeRepository
2. 集成测试数据加载流程
3. 性能测试配方查询

## 依赖注入配置

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object RecipeModule {
    
    @Provides
    @Singleton
    fun provideRecipeDao(database: GameDatabase): RecipeDao = database.recipeDao()
    
    @Provides
    @Singleton
    fun provideRecipeApi(): RecipeApi = Retrofit.Builder()
        .baseUrl(BuildConfig.RECIPE_API_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(RecipeApi::class.java)
    
    @Provides
    @Singleton
    fun provideRecipeVersionCache(
        @ApplicationContext context: Context
    ): RecipeVersionCache = RecipeVersionCache(
        context.getSharedPreferences("recipe_cache", Context.MODE_PRIVATE)
    )
    
    @Provides
    @Singleton
    fun provideRecipeRepository(
        recipeDao: RecipeDao,
        recipeApi: RecipeApi,
        versionCache: RecipeVersionCache,
        @ApplicationContext applicationScope: CoroutineScope
    ): RecipeRepository = RecipeRepositoryImpl(
        localSource = LocalRecipeSource(recipeDao),
        remoteSource = RemoteRecipeSource(recipeApi, versionCache),
        fallbackSource = FallbackRecipeSource(),
        recipeDao = recipeDao,
        externalScope = applicationScope
    )
}
```

## 数据库迁移

```kotlin
val MIGRATION_RECIPE_TABLES = object : Migration(10, 11) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS pill_recipes (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                pillId TEXT NOT NULL,
                tier INTEGER NOT NULL,
                category TEXT NOT NULL,
                description TEXT NOT NULL,
                duration INTEGER NOT NULL,
                successRate REAL NOT NULL,
                materials TEXT NOT NULL,
                rarity INTEGER NOT NULL,
                breakthroughChance REAL NOT NULL,
                targetRealm INTEGER NOT NULL,
                cultivationSpeed REAL NOT NULL,
                effectDuration INTEGER NOT NULL,
                cultivationPercent INTEGER NOT NULL,
                skillExpPercent INTEGER NOT NULL,
                physicalAttackPercent INTEGER NOT NULL,
                physicalDefensePercent INTEGER NOT NULL,
                magicAttackPercent INTEGER NOT NULL,
                magicDefensePercent INTEGER NOT NULL,
                hpPercent INTEGER NOT NULL,
                mpPercent INTEGER NOT NULL,
                speedPercent INTEGER NOT NULL,
                healPercent INTEGER NOT NULL,
                healMaxHpPercent INTEGER NOT NULL,
                heal INTEGER NOT NULL,
                battleCount INTEGER NOT NULL,
                extendLife INTEGER NOT NULL,
                mpRecoverMaxMpPercent INTEGER NOT NULL,
                version INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """)
        
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS forge_recipes (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                equipmentId TEXT NOT NULL,
                tier INTEGER NOT NULL,
                equipmentSlot TEXT NOT NULL,
                description TEXT NOT NULL,
                materials TEXT NOT NULL,
                duration INTEGER NOT NULL,
                successRate REAL NOT NULL,
                rarity INTEGER NOT NULL,
                version INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """)
        
        database.execSQL("CREATE INDEX IF NOT EXISTS index_pill_recipes_tier ON pill_recipes(tier)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_forge_recipes_tier ON forge_recipes(tier)")
    }
}
```

## 风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 远程服务不可用 | 无法获取最新配方 | Fallback 到本地硬编码数据 |
| 数据格式变更 | 解析失败 | 版本控制和格式校验 |
| 网络延迟 | 用户体验下降 | 异步加载 + 缓存策略 |
| 数据一致性 | 游戏平衡问题 | 服务端数据校验 + 客户端缓存 |

## 预期收益

1. **运营灵活性** - 可随时调整配方平衡性
2. **维护效率** - 无需发版即可更新配方
3. **测试便利** - 可快速部署测试配方
4. **数据驱动** - 支持基于玩家行为的配方调整

## 后续扩展

1. **A/B 测试支持** - 不同玩家组使用不同配方
2. **个性化配方** - 根据玩家进度推荐配方
3. **配方解锁系统** - 动态控制配方可见性
4. **配方评分系统** - 收集玩家反馈优化配方
