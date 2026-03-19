package com.xianxia.sect.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "game_data")

            if (!columns.contains("beastMaterials")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN beastMaterials TEXT NOT NULL DEFAULT '[]'")
            }
            if (!columns.contains("herbs")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN herbs TEXT NOT NULL DEFAULT '[]'")
            }
            if (!columns.contains("seeds")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN seeds TEXT NOT NULL DEFAULT '[]'")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    // 版本6到7：空迁移（无数据库结构变更）
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 无数据库结构变更，仅版本号更新
        }
    }

    // 版本7到8：空迁移（原计划移除spiritRootQuality字段，但表结构已重新设计）
    // 注意：由于历史迁移路径问题，此迁移改为空迁移
    // 实际的表结构会在 MIGRATION_9_10 中重新创建
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 无数据库结构变更，表结构会在后续迁移中重建
        }
    }

    // 版本8到9：添加丹药临时属性加成字段
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 添加丹药临时属性加成字段
            db.execSQL("ALTER TABLE disciples ADD COLUMN pillPhysicalAttackBonus REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE disciples ADD COLUMN pillMagicAttackBonus REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE disciples ADD COLUMN pillPhysicalDefenseBonus REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE disciples ADD COLUMN pillMagicDefenseBonus REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE disciples ADD COLUMN pillHpBonus REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE disciples ADD COLUMN pillMpBonus REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE disciples ADD COLUMN pillSpeedBonus REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE disciples ADD COLUMN pillEffectDuration INTEGER NOT NULL DEFAULT 0")
        }
    }

    // 版本9到10：将cultivation字段从INTEGER改为REAL以支持小数修为
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. 创建新表（cultivation字段为REAL类型）
            db.execSQL("""
                CREATE TABLE disciples_new (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    realm INTEGER NOT NULL DEFAULT 9,
                    realmLayer INTEGER NOT NULL DEFAULT 1,
                    cultivation REAL NOT NULL DEFAULT 0.0,
                    spiritRootType TEXT NOT NULL DEFAULT 'metal',
                    age INTEGER NOT NULL DEFAULT 16,
                    lifespan INTEGER NOT NULL DEFAULT 100,
                    isAlive INTEGER NOT NULL DEFAULT 1,
                    gender TEXT NOT NULL DEFAULT 'male',
                    partnerId TEXT,
                    parentId1 TEXT,
                    parentId2 TEXT,
                    lastChildYear INTEGER NOT NULL DEFAULT 0,
                    griefEndYear INTEGER,
                    weaponId TEXT,
                    armorId TEXT,
                    bootsId TEXT,
                    accessoryId TEXT,
                    manualIds TEXT NOT NULL DEFAULT '[]',
                    talentIds TEXT NOT NULL DEFAULT '[]',
                    spiritStones INTEGER NOT NULL DEFAULT 0,
                    soulPower INTEGER NOT NULL DEFAULT 10,
                    storageBagItems TEXT NOT NULL DEFAULT '[]',
                    storageBagSpiritStones INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'IDLE',
                    statusData TEXT NOT NULL DEFAULT '{}',
                    cultivationSpeedBonus REAL NOT NULL DEFAULT 1.0,
                    cultivationSpeedDuration INTEGER NOT NULL DEFAULT 0,
                    pillPhysicalAttackBonus REAL NOT NULL DEFAULT 0.0,
                    pillMagicAttackBonus REAL NOT NULL DEFAULT 0.0,
                    pillPhysicalDefenseBonus REAL NOT NULL DEFAULT 0.0,
                    pillMagicDefenseBonus REAL NOT NULL DEFAULT 0.0,
                    pillHpBonus REAL NOT NULL DEFAULT 0.0,
                    pillMpBonus REAL NOT NULL DEFAULT 0.0,
                    pillSpeedBonus REAL NOT NULL DEFAULT 0.0,
                    pillEffectDuration INTEGER NOT NULL DEFAULT 0,
                    totalCultivation INTEGER NOT NULL DEFAULT 0,
                    breakthroughCount INTEGER NOT NULL DEFAULT 0,
                    battlesWon INTEGER NOT NULL DEFAULT 0
                )
            """)

            // 2. 将数据从旧表迁移到新表（cultivation从INTEGER转为REAL）
            db.execSQL("""
                INSERT INTO disciples_new (
                    id, name, realm, realmLayer, cultivation, spiritRootType, age, lifespan, isAlive,
                    gender, partnerId, parentId1, parentId2, lastChildYear, griefEndYear,
                    weaponId, armorId, bootsId, accessoryId, manualIds, talentIds,
                    spiritStones, soulPower, storageBagItems, storageBagSpiritStones,
                    status, statusData, cultivationSpeedBonus, cultivationSpeedDuration,
                    pillPhysicalAttackBonus, pillMagicAttackBonus, pillPhysicalDefenseBonus,
                    pillMagicDefenseBonus, pillHpBonus, pillMpBonus, pillSpeedBonus, pillEffectDuration,
                    totalCultivation, breakthroughCount, battlesWon
                )
                SELECT 
                    id, name, realm, realmLayer, CAST(cultivation AS REAL), spiritRootType, age, lifespan, isAlive,
                    gender, partnerId, parentId1, parentId2, lastChildYear, griefEndYear,
                    weaponId, armorId, bootsId, accessoryId, manualIds, talentIds,
                    spiritStones, soulPower, storageBagItems, storageBagSpiritStones,
                    status, statusData, cultivationSpeedBonus, cultivationSpeedDuration,
                    pillPhysicalAttackBonus, pillMagicAttackBonus, pillPhysicalDefenseBonus,
                    pillMagicDefenseBonus, pillHpBonus, pillMpBonus, pillSpeedBonus, pillEffectDuration,
                    totalCultivation, breakthroughCount, battlesWon
                FROM disciples
            """)

            // 3. 删除旧表
            db.execSQL("DROP TABLE disciples")

            // 4. 重命名新表
            db.execSQL("ALTER TABLE disciples_new RENAME TO disciples")
        }
    }

    // 版本10到11：添加探查功能相关字段
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. 为 exploration_teams 表添加探查相关字段
            db.execSQL("ALTER TABLE exploration_teams ADD COLUMN scoutTargetSectId TEXT")
            db.execSQL("ALTER TABLE exploration_teams ADD COLUMN scoutTargetSectName TEXT")

            // 2. 为 game_data 表添加探查信息字段
            db.execSQL("ALTER TABLE game_data ADD COLUMN scoutInfo TEXT NOT NULL DEFAULT '{}'")
        }
    }

    // 版本11到12：添加洞府系统相关字段
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 添加洞府系统相关字段
            db.execSQL("ALTER TABLE game_data ADD COLUMN cultivatorCaves TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE game_data ADD COLUMN aiCaveTeams TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE game_data ADD COLUMN caveExplorationTeams TEXT NOT NULL DEFAULT '[]'")
        }
    }

    // 版本12到13：添加招募弟子系统相关字段
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 添加招募弟子列表字段
            db.execSQL("ALTER TABLE game_data ADD COLUMN recruitList TEXT NOT NULL DEFAULT '[]'")
            // 添加上次招募年份字段
            db.execSQL("ALTER TABLE game_data ADD COLUMN lastRecruitYear INTEGER NOT NULL DEFAULT 0")
        }
    }

    // 版本13到14：空迁移（无数据库结构变更）
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 无数据库结构变更，仅版本号更新
        }
    }

    // 版本14到15：移除game_data表中的herbs、seeds、beastMaterials字段
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. 创建新的game_data表（不包含要删除的字段）
            db.execSQL("""
                CREATE TABLE game_data_new (
                    id INTEGER PRIMARY KEY NOT NULL,
                    sectName TEXT NOT NULL DEFAULT '修仙宗门',
                    spiritStones INTEGER NOT NULL DEFAULT 1000,
                    spiritHerbs INTEGER NOT NULL DEFAULT 50,
                    gameYear INTEGER NOT NULL DEFAULT 1,
                    gameMonth INTEGER NOT NULL DEFAULT 1,
                    currentSlot INTEGER NOT NULL DEFAULT 1,
                    lastSaveTime INTEGER NOT NULL DEFAULT 0,
                    totalPlayTime INTEGER NOT NULL DEFAULT 0,
                    unlockedDungeons TEXT NOT NULL DEFAULT '[\"forest\"]',
                    unlockedRecipes TEXT NOT NULL DEFAULT '[]',
                    unlockedManuals TEXT NOT NULL DEFAULT '[]',
                    monthlySalary TEXT NOT NULL DEFAULT '{\"9\":10,\"8\":30,\"7\":50,\"6\":80,\"5\":110,\"4\":180,\"3\":220,\"2\":280,\"1\":360,\"0\":500}',
                    monthlySalaryEnabled TEXT NOT NULL DEFAULT '{\"9\":true,\"8\":true,\"7\":true,\"6\":true,\"5\":true,\"4\":true,\"3\":true,\"2\":true,\"1\":true,\"0\":true}',
                    settings TEXT NOT NULL DEFAULT '{}',
                    travelingMerchantItems TEXT NOT NULL DEFAULT '[]',
                    merchantLastRefreshYear INTEGER NOT NULL DEFAULT 0,
                    merchantRefreshCount INTEGER NOT NULL DEFAULT 0,
                    tournamentLastYear INTEGER NOT NULL DEFAULT 0,
                    tournamentRewards TEXT NOT NULL DEFAULT '{}',
                    tournamentRealmEnabled TEXT NOT NULL DEFAULT '{\"0\":false,\"1\":false,\"2\":false,\"3\":false,\"4\":false,\"5\":false,\"6\":false,\"7\":true,\"8\":true,\"9\":true}',
                    tournamentAutoHold INTEGER NOT NULL DEFAULT 1,
                    spiritMineSlots TEXT NOT NULL DEFAULT '[]',
                    worldMapSects TEXT NOT NULL DEFAULT '[]',
                    exploredSects TEXT NOT NULL DEFAULT '{}',
                    herbGardenPlantSlots TEXT NOT NULL DEFAULT '[]',
                    herbGardenDiscipleSlots TEXT NOT NULL DEFAULT '[]',
                    manualProficiencies TEXT NOT NULL DEFAULT '{}',
                    scoutInfo TEXT NOT NULL DEFAULT '{}',
                    cultivatorCaves TEXT NOT NULL DEFAULT '[]',
                    aiCaveTeams TEXT NOT NULL DEFAULT '[]',
                    caveExplorationTeams TEXT NOT NULL DEFAULT '[]',
                    recruitList TEXT NOT NULL DEFAULT '[]',
                    lastRecruitYear INTEGER NOT NULL DEFAULT 0
                )
            """)

            // 2. 复制数据（排除要删除的字段）
            db.execSQL("""
                INSERT INTO game_data_new (
                    id, sectName, spiritStones, spiritHerbs, gameYear, gameMonth, currentSlot,
                    lastSaveTime, totalPlayTime, unlockedDungeons, unlockedRecipes, unlockedManuals,
                    monthlySalary, settings, travelingMerchantItems, merchantLastRefreshYear,
                    merchantRefreshCount, tournamentLastYear, tournamentRewards, tournamentRealmEnabled,
                    tournamentAutoHold, spiritMineSlots, worldMapSects,
                    exploredSects, herbGardenPlantSlots, herbGardenDiscipleSlots, manualProficiencies,
                    scoutInfo, cultivatorCaves, aiCaveTeams, caveExplorationTeams, recruitList, lastRecruitYear
                )
                SELECT
                    id, sectName, spiritStones, spiritHerbs, gameYear, gameMonth, currentSlot,
                    lastSaveTime, totalPlayTime, unlockedDungeons, unlockedRecipes, unlockedManuals,
                    monthlySalary, settings, travelingMerchantItems, merchantLastRefreshYear,
                    merchantRefreshCount, tournamentLastYear, tournamentRewards, tournamentRealmEnabled,
                    tournamentAutoHold, spiritMineSlots, worldMapSects,
                    exploredSects, herbGardenPlantSlots, herbGardenDiscipleSlots, manualProficiencies,
                    scoutInfo, cultivatorCaves, aiCaveTeams, caveExplorationTeams, recruitList, lastRecruitYear
                FROM game_data
            """)

            // 3. 删除旧表
            db.execSQL("DROP TABLE game_data")

            // 4. 重命名新表
            db.execSQL("ALTER TABLE game_data_new RENAME TO game_data")
        }
    }

    // 版本15到16：移除game_data表中的spiritMineSlots字段
    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. 创建新的game_data表（不包含spiritMineSlots字段）
            db.execSQL("""
                CREATE TABLE game_data_new (
                    id INTEGER PRIMARY KEY NOT NULL,
                    sectName TEXT NOT NULL DEFAULT '修仙宗门',
                    spiritStones INTEGER NOT NULL DEFAULT 1000,
                    spiritHerbs INTEGER NOT NULL DEFAULT 50,
                    gameYear INTEGER NOT NULL DEFAULT 1,
                    gameMonth INTEGER NOT NULL DEFAULT 1,
                    currentSlot INTEGER NOT NULL DEFAULT 1,
                    lastSaveTime INTEGER NOT NULL DEFAULT 0,
                    totalPlayTime INTEGER NOT NULL DEFAULT 0,
                    unlockedDungeons TEXT NOT NULL DEFAULT '["forest"]',
                    unlockedRecipes TEXT NOT NULL DEFAULT '[]',
                    unlockedManuals TEXT NOT NULL DEFAULT '[]',
                    monthlySalary TEXT NOT NULL DEFAULT '{"9":10,"8":30,"7":50,"6":80,"5":110,"4":180,"3":220,"2":280,"1":360,"0":500}',
                    monthlySalaryEnabled TEXT NOT NULL DEFAULT '{"9":true,"8":true,"7":true,"6":true,"5":true,"4":true,"3":true,"2":true,"1":true,"0":true}',
                    settings TEXT NOT NULL DEFAULT '{}',
                    travelingMerchantItems TEXT NOT NULL DEFAULT '[]',
                    merchantLastRefreshYear INTEGER NOT NULL DEFAULT 0,
                    merchantRefreshCount INTEGER NOT NULL DEFAULT 0,
                    tournamentLastYear INTEGER NOT NULL DEFAULT 0,
                    tournamentRewards TEXT NOT NULL DEFAULT '{}',
                    tournamentRealmEnabled TEXT NOT NULL DEFAULT '{"0":false,"1":false,"2":false,"3":false,"4":false,"5":false,"6":false,"7":true,"8":true,"9":true}',
                    tournamentAutoHold INTEGER NOT NULL DEFAULT 1,
                    worldMapSects TEXT NOT NULL DEFAULT '[]',
                    exploredSects TEXT NOT NULL DEFAULT '{}',
                    herbGardenPlantSlots TEXT NOT NULL DEFAULT '[]',
                    herbGardenDiscipleSlots TEXT NOT NULL DEFAULT '[]',
                    manualProficiencies TEXT NOT NULL DEFAULT '{}',
                    scoutInfo TEXT NOT NULL DEFAULT '{}',
                    cultivatorCaves TEXT NOT NULL DEFAULT '[]',
                    aiCaveTeams TEXT NOT NULL DEFAULT '[]',
                    caveExplorationTeams TEXT NOT NULL DEFAULT '[]',
                    recruitList TEXT NOT NULL DEFAULT '[]',
                    lastRecruitYear INTEGER NOT NULL DEFAULT 0
                )
            """)

            // 2. 复制数据（排除 spiritMineSlots 字段）
            db.execSQL("""
                INSERT INTO game_data_new (
                    id, sectName, spiritStones, spiritHerbs, gameYear, gameMonth, currentSlot,
                    lastSaveTime, totalPlayTime, unlockedDungeons, unlockedRecipes, unlockedManuals,
                    monthlySalary, settings, travelingMerchantItems, merchantLastRefreshYear,
                    merchantRefreshCount, tournamentLastYear, tournamentRewards, tournamentRealmEnabled,
                    tournamentAutoHold, worldMapSects,
                    exploredSects, herbGardenPlantSlots, herbGardenDiscipleSlots, manualProficiencies,
                    scoutInfo, cultivatorCaves, aiCaveTeams, caveExplorationTeams, recruitList, lastRecruitYear
                )
                SELECT
                    id, sectName, spiritStones, spiritHerbs, gameYear, gameMonth, currentSlot,
                    lastSaveTime, totalPlayTime, unlockedDungeons, unlockedRecipes, unlockedManuals,
                    monthlySalary, settings, travelingMerchantItems, merchantLastRefreshYear,
                    merchantRefreshCount, tournamentLastYear, tournamentRewards, tournamentRealmEnabled,
                    tournamentAutoHold, worldMapSects,
                    exploredSects, herbGardenPlantSlots, herbGardenDiscipleSlots, manualProficiencies,
                    scoutInfo, cultivatorCaves, aiCaveTeams, caveExplorationTeams, recruitList, lastRecruitYear
                FROM game_data
            """)

            // 3. 删除旧表
            db.execSQL("DROP TABLE game_data")

            // 4. 重命名新表
            db.execSQL("ALTER TABLE game_data_new RENAME TO game_data")
        }
    }

    // 版本16到17：修改GameData中herbGardenPlantSlots的默认值为包含3个空槽位的列表
    // 此迁移不需要修改数据库结构，因为默认值只影响新创建的数据
    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 无需修改数据库结构，默认值变更只影响新游戏创建
            // 现有存档的herbGardenPlantSlots值保持不变
        }
    }

    // 版本17到18：修复PlantSlotData、HerbGardenDiscipleSlot、ManualProficiencyData数据类字段从var改为val
    // 此迁移不需要修改数据库结构，因为只是Kotlin字段可变性的变更，不影响数据库架构
    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 无需修改数据库结构，字段可变性变更不影响SQLite数据库架构
            // 数据类的var/val只影响Kotlin代码层面，不改变数据库表结构
        }
    }

    // 版本18到19：添加宗门征战系统 - war_teams 表
    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS war_teams (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    memberIds TEXT NOT NULL,
                    status TEXT NOT NULL,
                    stationedSectId TEXT,
                    stationedSectName TEXT,
                    targetSectId TEXT,
                    targetSectName TEXT,
                    pathSectIds TEXT NOT NULL,
                    currentPathIndex INTEGER NOT NULL,
                    travelStartTime INTEGER NOT NULL,
                    travelEndTime INTEGER NOT NULL,
                    monthlyCost INTEGER NOT NULL,
                    totalCostAccumulated INTEGER NOT NULL
                )
            """)
        }
    }

    // 版本 19 到 20：添加 game_data.warTeams 字段
    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "game_data")
            if (!columns.contains("warTeams")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN warTeams TEXT NOT NULL DEFAULT '[]'")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    // 版本 20 到 21：空迁移（无数据库结构变更）
    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 无数据库结构变更，仅版本号更新
        }
    }

    // 版本 21 到 22：添加 game_data.playerListedItems 字段
    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "game_data")
            if (!columns.contains("playerListedItems")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN playerListedItems TEXT NOT NULL DEFAULT '[]'")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    // 版本 22 到 23：空迁移（原计划添加spiritRootQuality字段，已取消）
    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 无数据库结构变更
        }
    }

    // 版本 23 到 24：移除 disciples.spiritRootQuality 字段（如果存在）
    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "disciples")
            if (columns.contains("spiritRootQuality")) {
                db.execSQL("""
                    CREATE TABLE disciples_new (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        realm INTEGER NOT NULL,
                        realmLayer INTEGER NOT NULL,
                        cultivation REAL NOT NULL,
                        spiritRootType TEXT NOT NULL,
                        age INTEGER NOT NULL,
                        lifespan INTEGER NOT NULL,
                        isAlive INTEGER NOT NULL,
                        gender TEXT NOT NULL,
                        partnerId TEXT,
                        parentId1 TEXT,
                        parentId2 TEXT,
                        lastChildYear INTEGER NOT NULL,
                        griefEndYear INTEGER,
                        weaponId TEXT,
                        armorId TEXT,
                        bootsId TEXT,
                        accessoryId TEXT,
                        manualIds TEXT NOT NULL,
                        talentIds TEXT NOT NULL,
                        spiritStones INTEGER NOT NULL,
                        soulPower INTEGER NOT NULL,
                        storageBagItems TEXT NOT NULL,
                        storageBagSpiritStones INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        statusData TEXT NOT NULL,
                        cultivationSpeedBonus REAL NOT NULL,
                        cultivationSpeedDuration INTEGER NOT NULL,
                        pillPhysicalAttackBonus REAL NOT NULL,
                        pillMagicAttackBonus REAL NOT NULL,
                        pillPhysicalDefenseBonus REAL NOT NULL,
                        pillMagicDefenseBonus REAL NOT NULL,
                        pillHpBonus REAL NOT NULL,
                        pillMpBonus REAL NOT NULL,
                        pillSpeedBonus REAL NOT NULL,
                        pillEffectDuration INTEGER NOT NULL,
                        totalCultivation INTEGER NOT NULL,
                        breakthroughCount INTEGER NOT NULL,
                        battlesWon INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    INSERT INTO disciples_new SELECT 
                        id, name, realm, realmLayer, cultivation, spiritRootType, age, lifespan, isAlive,
                        gender, partnerId, parentId1, parentId2, lastChildYear, griefEndYear,
                        weaponId, armorId, bootsId, accessoryId, manualIds, talentIds,
                        spiritStones, soulPower, storageBagItems, storageBagSpiritStones,
                        status, statusData, cultivationSpeedBonus, cultivationSpeedDuration,
                        pillPhysicalAttackBonus, pillMagicAttackBonus, pillPhysicalDefenseBonus,
                        pillMagicDefenseBonus, pillHpBonus, pillMpBonus, pillSpeedBonus,
                        pillEffectDuration, totalCultivation, breakthroughCount, battlesWon
                    FROM disciples
                """)
                db.execSQL("DROP TABLE disciples")
                db.execSQL("ALTER TABLE disciples_new RENAME TO disciples")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    // 版本 24 到 25：修复 game_data 表，添加缺失的字段（tradingSellList, tradingBuyList, playerListedItems, warTeams, monthlySalaryEnabled）
    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "game_data")
            val needsMigration = !columns.contains("tradingSellList") || 
                                !columns.contains("tradingBuyList") ||
                                !columns.contains("playerListedItems") ||
                                !columns.contains("warTeams") ||
                                !columns.contains("monthlySalaryEnabled")
            
            if (needsMigration) {
                // 1. 创建新的 game_data 表，包含所有字段
                db.execSQL("""
                    CREATE TABLE game_data_new (
                        id TEXT PRIMARY KEY NOT NULL,
                        sectName TEXT NOT NULL,
                        currentSlot INTEGER NOT NULL,
                        gameYear INTEGER NOT NULL,
                        gameMonth INTEGER NOT NULL,
                        spiritStones INTEGER NOT NULL,
                        spiritHerbs INTEGER NOT NULL,
                        autoSaveIntervalMonths INTEGER NOT NULL,
                        monthlySalary TEXT NOT NULL,
                        monthlySalaryEnabled TEXT NOT NULL,
                        warTeams TEXT NOT NULL,
                        worldMapSects TEXT NOT NULL,
                        exploredSects TEXT NOT NULL,
                        scoutInfo TEXT NOT NULL,
                        herbGardenPlantSlots TEXT NOT NULL,
                        manualProficiencies TEXT NOT NULL,
                        travelingMerchantItems TEXT NOT NULL,
                        merchantLastRefreshYear INTEGER NOT NULL,
                        merchantRefreshCount INTEGER NOT NULL,
                        playerListedItems TEXT NOT NULL,
                        tradingSellList TEXT NOT NULL,
                        tradingBuyList TEXT NOT NULL,
                        recruitList TEXT NOT NULL,
                        lastRecruitYear INTEGER NOT NULL,
                        tournamentLastYear INTEGER NOT NULL,
                        tournamentRewards TEXT NOT NULL,
                        tournamentAutoHold INTEGER NOT NULL,
                        tournamentRealmEnabled TEXT NOT NULL,
                        cultivatorCaves TEXT NOT NULL,
                        caveExplorationTeams TEXT NOT NULL,
                        aiCaveTeams TEXT NOT NULL,
                        unlockedDungeons TEXT NOT NULL,
                        unlockedRecipes TEXT NOT NULL,
                        unlockedManuals TEXT NOT NULL,
                        lastSaveTime INTEGER NOT NULL
                    )
                """)

                // 2. 从旧表复制数据
                db.execSQL("""
                    INSERT INTO game_data_new (
                        id, sectName, currentSlot, gameYear, gameMonth, spiritStones, spiritHerbs,
                        autoSaveIntervalMonths, monthlySalary, monthlySalaryEnabled, warTeams, worldMapSects, exploredSects,
                        scoutInfo, herbGardenPlantSlots, manualProficiencies, travelingMerchantItems,
                        merchantLastRefreshYear, merchantRefreshCount, playerListedItems, tradingSellList,
                        tradingBuyList, recruitList, lastRecruitYear, tournamentLastYear, tournamentRewards,
                        tournamentAutoHold, tournamentRealmEnabled, cultivatorCaves, caveExplorationTeams,
                        aiCaveTeams, unlockedDungeons, unlockedRecipes, unlockedManuals, lastSaveTime
                    )
                    SELECT
                        id, sectName, currentSlot, gameYear, gameMonth, spiritStones, spiritHerbs,
                        autoSaveIntervalMonths, monthlySalary,
                        CASE WHEN monthlySalaryEnabled IS NULL THEN '{"9":true,"8":true,"7":true,"6":true,"5":true,"4":true,"3":true,"2":true,"1":true,"0":true}' ELSE monthlySalaryEnabled END,
                        CASE WHEN warTeams IS NULL THEN '[]' ELSE warTeams END,
                        worldMapSects, exploredSects, scoutInfo, herbGardenPlantSlots, manualProficiencies,
                        travelingMerchantItems, merchantLastRefreshYear, merchantRefreshCount,
                        CASE WHEN playerListedItems IS NULL THEN '[]' ELSE playerListedItems END,
                        CASE WHEN tradingSellList IS NULL THEN '[]' ELSE tradingSellList END,
                        CASE WHEN tradingBuyList IS NULL THEN '[]' ELSE tradingBuyList END,
                        recruitList, lastRecruitYear, tournamentLastYear, tournamentRewards,
                        tournamentAutoHold, tournamentRealmEnabled, cultivatorCaves, caveExplorationTeams,
                        aiCaveTeams, unlockedDungeons, unlockedRecipes, unlockedManuals, lastSaveTime
                    FROM game_data
                """)

                // 3. 删除旧表
                db.execSQL("DROP TABLE game_data")

                // 4. 重命名新表
                db.execSQL("ALTER TABLE game_data_new RENAME TO game_data")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    // 版本 25 到 26：添加 disciples 表的 intelligence、charm、loyalty 字段
    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "disciples")
            if (!columns.contains("intelligence")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN intelligence INTEGER NOT NULL DEFAULT 50")
            }
            if (!columns.contains("charm")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN charm INTEGER NOT NULL DEFAULT 50")
            }
            if (!columns.contains("loyalty")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN loyalty INTEGER NOT NULL DEFAULT 50")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    // 版本 26 到 27：添加 disciples 表的 comprehension、artifactRefining、pillRefining、spiritPlanting、teaching 字段
    val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "disciples")
            if (!columns.contains("comprehension")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN comprehension INTEGER NOT NULL DEFAULT 50")
            }
            if (!columns.contains("artifactRefining")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN artifactRefining INTEGER NOT NULL DEFAULT 50")
            }
            if (!columns.contains("pillRefining")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN pillRefining INTEGER NOT NULL DEFAULT 50")
            }
            if (!columns.contains("spiritPlanting")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN spiritPlanting INTEGER NOT NULL DEFAULT 50")
            }
            if (!columns.contains("teaching")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN teaching INTEGER NOT NULL DEFAULT 50")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    // 版本 27 到 28：添加 battle_logs 表
    val MIGRATION_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS battle_logs (
                    id TEXT PRIMARY KEY NOT NULL,
                    timestamp INTEGER NOT NULL,
                    year INTEGER NOT NULL,
                    month INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    attackerName TEXT NOT NULL,
                    defenderName TEXT NOT NULL,
                    result TEXT NOT NULL,
                    details TEXT NOT NULL,
                    drops TEXT NOT NULL,
                    dungeonName TEXT NOT NULL,
                    teamMembers TEXT NOT NULL,
                    enemies TEXT NOT NULL,
                    rounds TEXT NOT NULL,
                    turns INTEGER NOT NULL,
                    teamCasualties INTEGER NOT NULL,
                    beastsDefeated INTEGER NOT NULL,
                    battleResult TEXT
                )
            """)
        }
    }

    // 版本 28 到 29：添加 disciples 表的 breakthroughFailCount 和 morality 字段，game_data 表的 elderSlots 和 spiritMineSlots 字段
    val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val discipleColumns = getExistingColumns(db, "disciples")
            if (!discipleColumns.contains("breakthroughFailCount")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN breakthroughFailCount INTEGER NOT NULL DEFAULT 0")
            }
            if (!discipleColumns.contains("morality")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN morality INTEGER NOT NULL DEFAULT 50")
            }

            val gameDataColumns = getExistingColumns(db, "game_data")
            if (!gameDataColumns.contains("elderSlots")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN elderSlots TEXT NOT NULL DEFAULT '{\"herbGardenElder\":null,\"alchemyElder\":null,\"forgeElder\":null,\"libraryElder\":null,\"spiritMineElder\":null,\"recruitElder\":null}'")
            }
            if (!gameDataColumns.contains("spiritMineSlots")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN spiritMineSlots TEXT NOT NULL DEFAULT '[]'")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    // 版本 29 到 30：添加弟子新属性字段（已在之前迁移中添加）
    val MIGRATION_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "disciples")
            
 // 添加月俸发放累计次数字段
            if (!columns.contains("salaryPaidCount")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN salaryPaidCount INTEGER NOT NULL DEFAULT 0")
            }
            // 添加月俸少发累计次数字段
            if (!columns.contains("salaryMissedCount")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN salaryMissedCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    // 版本 30 到 31：添加 disciples 表的 recruitedMonth 字段（新弟子保护期）
    val MIGRATION_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "disciples")
            if (!columns.contains("recruitedMonth")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN recruitedMonth INTEGER NOT NULL DEFAULT 0")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    // 版本 31 到 32：添加 game_data 表的 librarySlots 字段（藏经阁弟子槽位）
    val MIGRATION_31_32 = object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "game_data")
            if (!columns.contains("librarySlots")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN librarySlots TEXT NOT NULL DEFAULT '[]'")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    // 版本 32 到 33：添加 forge_slots 表（炼器槽位）
    val MIGRATION_32_33 = object : Migration(32, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS forge_slots (
                    id TEXT PRIMARY KEY NOT NULL,
                    slotIndex INTEGER NOT NULL,
                    recipeId TEXT,
                    recipeName TEXT NOT NULL,
                    equipmentName TEXT NOT NULL,
                    equipmentRarity INTEGER NOT NULL,
                    equipmentSlot TEXT NOT NULL,
                    startYear INTEGER NOT NULL,
                    startMonth INTEGER NOT NULL,
                    duration INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    successRate REAL NOT NULL,
                    requiredMaterials TEXT NOT NULL
                )
            """)
        }
    }

    // 版本 33 到 34：移除 equipment 表的 enhanceLevel 和 maxEnhance 字段
    val MIGRATION_33_34 = object : Migration(33, 34) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "equipment")
            if (columns.contains("enhanceLevel") || columns.contains("maxEnhance")) {
                // 1. 创建新的 equipment 表（不包含 enhanceLevel 和 maxEnhance 字段）
                db.execSQL("""
                    CREATE TABLE equipment_new (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        rarity INTEGER NOT NULL,
                        description TEXT NOT NULL,
                        slot TEXT NOT NULL,
                        physicalAttack INTEGER NOT NULL,
                        magicAttack INTEGER NOT NULL,
                        physicalDefense INTEGER NOT NULL,
                        magicDefense INTEGER NOT NULL,
                        speed INTEGER NOT NULL,
                        hp INTEGER NOT NULL,
                        mp INTEGER NOT NULL,
                        critChance REAL NOT NULL,
                        nurtureLevel INTEGER NOT NULL,
                        nurtureProgress INTEGER NOT NULL,
                        ownerId TEXT,
                        isEquipped INTEGER NOT NULL
                    )
                """)

                // 2. 将数据从旧表迁移到新表
                db.execSQL("""
                    INSERT INTO equipment_new (
                        id, name, rarity, description, slot, physicalAttack, magicAttack,
                        physicalDefense, magicDefense, speed, hp, mp, critChance,
                        nurtureLevel, nurtureProgress, ownerId, isEquipped
                    )
                    SELECT
                        id, name, rarity, description, slot, physicalAttack, magicAttack,
                        physicalDefense, magicDefense, speed, hp, mp, critChance,
                        nurtureLevel, nurtureProgress, ownerId, isEquipped
                    FROM equipment
                """)

                // 3. 删除旧表
                db.execSQL("DROP TABLE equipment")

                // 4. 重命名新表
                db.execSQL("ALTER TABLE equipment_new RENAME TO equipment")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    // 版本 34 到 35：添加缺失的字段迁移
    val MIGRATION_34_35 = object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. 为 game_data 表添加缺失字段
            val gameDataColumns = getExistingColumns(db, "game_data")
            if (!gameDataColumns.contains("alliances")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN alliances TEXT NOT NULL DEFAULT '[]'")
            }
            if (!gameDataColumns.contains("sectRelations")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN sectRelations TEXT NOT NULL DEFAULT '[]'")
            }
            if (!gameDataColumns.contains("playerAllianceSlots")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN playerAllianceSlots INTEGER NOT NULL DEFAULT 3")
            }
            if (!gameDataColumns.contains("supportTeams")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN supportTeams TEXT NOT NULL DEFAULT '[]'")
            }

            // 2. 为 disciples 表添加缺失字段
            val discipleColumns = getExistingColumns(db, "disciples")
            if (!discipleColumns.contains("partnerSectId")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN partnerSectId TEXT")
            }

            // 3. 为 war_teams 表添加缺失字段
            val warTeamColumns = getExistingColumns(db, "war_teams")
            if (!warTeamColumns.contains("leaderId")) {
                db.execSQL("ALTER TABLE war_teams ADD COLUMN leaderId TEXT")
            }
            if (!warTeamColumns.contains("leaderName")) {
                db.execSQL("ALTER TABLE war_teams ADD COLUMN leaderName TEXT NOT NULL DEFAULT ''")
            }
            if (!warTeamColumns.contains("members")) {
                db.execSQL("ALTER TABLE war_teams ADD COLUMN members TEXT NOT NULL DEFAULT '[]'")
            }
            if (!warTeamColumns.contains("deadMemberIds")) {
                db.execSQL("ALTER TABLE war_teams ADD COLUMN deadMemberIds TEXT NOT NULL DEFAULT '[]'")
            }
            if (!warTeamColumns.contains("totalPower")) {
                db.execSQL("ALTER TABLE war_teams ADD COLUMN totalPower INTEGER NOT NULL DEFAULT 0")
            }
            if (!warTeamColumns.contains("missionStartYear")) {
                db.execSQL("ALTER TABLE war_teams ADD COLUMN missionStartYear INTEGER NOT NULL DEFAULT 0")
            }
            if (!warTeamColumns.contains("missionStartMonth")) {
                db.execSQL("ALTER TABLE war_teams ADD COLUMN missionStartMonth INTEGER NOT NULL DEFAULT 0")
            }
            if (!warTeamColumns.contains("missionDuration")) {
                db.execSQL("ALTER TABLE war_teams ADD COLUMN missionDuration INTEGER NOT NULL DEFAULT 0")
            }
            if (!warTeamColumns.contains("battleWins")) {
                db.execSQL("ALTER TABLE war_teams ADD COLUMN battleWins INTEGER NOT NULL DEFAULT 0")
            }
            if (!warTeamColumns.contains("battleLosses")) {
                db.execSQL("ALTER TABLE war_teams ADD COLUMN battleLosses INTEGER NOT NULL DEFAULT 0")
            }
            if (!warTeamColumns.contains("reputation")) {
                db.execSQL("ALTER TABLE war_teams ADD COLUMN reputation INTEGER NOT NULL DEFAULT 0")
            }
            if (!warTeamColumns.contains("occupierTeamId")) {
                db.execSQL("ALTER TABLE war_teams ADD COLUMN occupierTeamId TEXT")
            }
            if (!warTeamColumns.contains("isOccupied")) {
                db.execSQL("ALTER TABLE war_teams ADD COLUMN isOccupied INTEGER NOT NULL DEFAULT 0")
            }
            if (!warTeamColumns.contains("occupierTeamName")) {
                db.execSQL("ALTER TABLE war_teams ADD COLUMN occupierTeamName TEXT NOT NULL DEFAULT ''")
            }

            // 4. 为 battle_logs 表添加缺失字段
            val battleLogColumns = getExistingColumns(db, "battle_logs")
            if (!battleLogColumns.contains("teamId")) {
                db.execSQL("ALTER TABLE battle_logs ADD COLUMN teamId TEXT")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    val ALL_MIGRATIONS: Array<Migration>
        get() = arrayOf(
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24,
            MIGRATION_24_25,
            MIGRATION_25_26,
            MIGRATION_26_27,
            MIGRATION_27_28,
            MIGRATION_28_29,
            MIGRATION_29_30,
            MIGRATION_30_31,
            MIGRATION_31_32,
            MIGRATION_32_33,
            MIGRATION_33_34,
            MIGRATION_34_35,
            MIGRATION_35_36,
            MIGRATION_36_37,
            MIGRATION_37_38,
            MIGRATION_38_39,
            MIGRATION_39_40,
            MIGRATION_40_41,
            MIGRATION_41_42,
            MIGRATION_42_43,
            MIGRATION_43_44,
            MIGRATION_44_45,
            MIGRATION_45_46,
            MIGRATION_46_47,
            MIGRATION_47_48,
            MIGRATION_48_49
        )

    private val MIGRATION_35_36 = object : Migration(35, 36) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val gameDataColumns = getExistingColumns(db, "game_data")
            if (!gameDataColumns.contains("sectPolicies")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN sectPolicies TEXT NOT NULL DEFAULT '{\"spiritMineBoost\":false}'")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    private val MIGRATION_36_37 = object : Migration(36, 37) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val gameDataColumns = getExistingColumns(db, "game_data")
            if (!gameDataColumns.contains("gameDay")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN gameDay INTEGER NOT NULL DEFAULT 1")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    private val MIGRATION_37_38 = object : Migration(37, 38) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "disciples")
            if (!columns.contains("combatStatsVariance")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN combatStatsVariance INTEGER NOT NULL DEFAULT 0")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    private val MIGRATION_38_39 = object : Migration(38, 39) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "disciples")
            if (!columns.contains("monthlyUsedPillIds")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN monthlyUsedPillIds TEXT NOT NULL DEFAULT '[]'")
            }
            if (!columns.contains("hasReviveEffect")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN hasReviveEffect INTEGER NOT NULL DEFAULT 0")
            }
            if (!columns.contains("hasClearAllEffect")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN hasClearAllEffect INTEGER NOT NULL DEFAULT 0")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    private val MIGRATION_39_40 = object : Migration(39, 40) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "disciples")
            if (!columns.contains("discipleType")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN discipleType TEXT NOT NULL DEFAULT 'outer'")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    private val MIGRATION_40_41 = object : Migration(40, 41) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "game_data")
            if (!columns.contains("outerTournamentLastYear")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN outerTournamentLastYear INTEGER NOT NULL DEFAULT 0")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    private val MIGRATION_41_42 = object : Migration(41, 42) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "equipment")
            if (!columns.contains("minRealm")) {
                db.execSQL("ALTER TABLE equipment ADD COLUMN minRealm INTEGER NOT NULL DEFAULT 9")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    private val MIGRATION_42_43 = object : Migration(42, 43) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS war_teams")
            
            val columns = getExistingColumns(db, "game_data")
            if (columns.contains("warTeams")) {
                db.execSQL("""
                    CREATE TABLE game_data_new (
                        id TEXT PRIMARY KEY NOT NULL,
                        sectName TEXT NOT NULL,
                        currentSlot INTEGER NOT NULL,
                        gameYear INTEGER NOT NULL,
                        gameMonth INTEGER NOT NULL,
                        gameDay INTEGER NOT NULL DEFAULT 1,
                        spiritStones INTEGER NOT NULL,
                        spiritHerbs INTEGER NOT NULL,
                        autoSaveIntervalMonths INTEGER NOT NULL,
                        monthlySalary TEXT NOT NULL,
                        monthlySalaryEnabled TEXT NOT NULL,
                        worldMapSects TEXT NOT NULL,
                        exploredSects TEXT NOT NULL,
                        scoutInfo TEXT NOT NULL,
                        herbGardenPlantSlots TEXT NOT NULL,
                        manualProficiencies TEXT NOT NULL,
                        travelingMerchantItems TEXT NOT NULL,
                        merchantLastRefreshYear INTEGER NOT NULL,
                        merchantRefreshCount INTEGER NOT NULL,
                        playerListedItems TEXT NOT NULL,
                        tradingSellList TEXT NOT NULL,
                        tradingBuyList TEXT NOT NULL,
                        recruitList TEXT NOT NULL,
                        lastRecruitYear INTEGER NOT NULL,
                        tournamentLastYear INTEGER NOT NULL,
                        tournamentRewards TEXT NOT NULL,
                        tournamentAutoHold INTEGER NOT NULL,
                        tournamentRealmEnabled TEXT NOT NULL,
                        outerTournamentLastYear INTEGER NOT NULL DEFAULT 0,
                        cultivatorCaves TEXT NOT NULL,
                        caveExplorationTeams TEXT NOT NULL,
                        aiCaveTeams TEXT NOT NULL,
                        unlockedDungeons TEXT NOT NULL,
                        unlockedRecipes TEXT NOT NULL,
                        unlockedManuals TEXT NOT NULL,
                        lastSaveTime INTEGER NOT NULL,
                        alliances TEXT NOT NULL,
                        sectRelations TEXT NOT NULL,
                        playerAllianceSlots INTEGER NOT NULL,
                        supportTeams TEXT NOT NULL,
                        elderSlots TEXT NOT NULL,
                        spiritMineSlots TEXT NOT NULL,
                        librarySlots TEXT NOT NULL,
                        sectPolicies TEXT NOT NULL
                    )
                """)
                
                db.execSQL("""
                    INSERT INTO game_data_new (
                        id, sectName, currentSlot, gameYear, gameMonth, gameDay, spiritStones, spiritHerbs,
                        autoSaveIntervalMonths, monthlySalary, monthlySalaryEnabled, worldMapSects, exploredSects,
                        scoutInfo, herbGardenPlantSlots, manualProficiencies, travelingMerchantItems,
                        merchantLastRefreshYear, merchantRefreshCount, playerListedItems, tradingSellList,
                        tradingBuyList, recruitList, lastRecruitYear, tournamentLastYear, tournamentRewards,
                        tournamentAutoHold, tournamentRealmEnabled, outerTournamentLastYear, cultivatorCaves, 
                        caveExplorationTeams, aiCaveTeams, unlockedDungeons, unlockedRecipes, unlockedManuals, 
                        lastSaveTime, alliances, sectRelations, playerAllianceSlots, supportTeams, elderSlots, 
                        spiritMineSlots, librarySlots, sectPolicies
                    )
                    SELECT
                        id, sectName, currentSlot, gameYear, gameMonth, 
                        COALESCE(gameDay, 1), 
                        spiritStones, spiritHerbs,
                        autoSaveIntervalMonths, monthlySalary, monthlySalaryEnabled, worldMapSects, exploredSects,
                        scoutInfo, herbGardenPlantSlots, manualProficiencies, travelingMerchantItems,
                        merchantLastRefreshYear, merchantRefreshCount, playerListedItems, tradingSellList,
                        tradingBuyList, recruitList, lastRecruitYear, tournamentLastYear, tournamentRewards,
                        tournamentAutoHold, tournamentRealmEnabled, 
                        COALESCE(outerTournamentLastYear, 0),
                        cultivatorCaves, caveExplorationTeams, aiCaveTeams, unlockedDungeons, unlockedRecipes, 
                        unlockedManuals, lastSaveTime, alliances, sectRelations, playerAllianceSlots, supportTeams, 
                        elderSlots, spiritMineSlots, librarySlots, sectPolicies
                    FROM game_data
                """)
                
                db.execSQL("DROP TABLE game_data")
                db.execSQL("ALTER TABLE game_data_new RENAME TO game_data")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    private val MIGRATION_43_44 = object : Migration(43, 44) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "game_data")
            if (!columns.contains("aiBattleTeams")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN aiBattleTeams TEXT NOT NULL DEFAULT '[]'")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    private val MIGRATION_44_45 = object : Migration(44, 45) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "game_data")
            
            val needsMigration = columns.contains("tournamentLastYear") ||
                                columns.contains("tournamentRewards") ||
                                columns.contains("tournamentAutoHold") ||
                                columns.contains("tournamentRealmEnabled") ||
                                columns.contains("outerTournamentLastYear") ||
                                !columns.contains("battleTeam")
            
            if (needsMigration) {
                db.execSQL("""
                    CREATE TABLE game_data_new (
                        id TEXT PRIMARY KEY NOT NULL,
                        sectName TEXT NOT NULL,
                        currentSlot INTEGER NOT NULL,
                        gameYear INTEGER NOT NULL,
                        gameMonth INTEGER NOT NULL,
                        gameDay INTEGER NOT NULL DEFAULT 1,
                        spiritStones INTEGER NOT NULL,
                        spiritHerbs INTEGER NOT NULL,
                        autoSaveIntervalMonths INTEGER NOT NULL,
                        monthlySalary TEXT NOT NULL,
                        monthlySalaryEnabled TEXT NOT NULL,
                        worldMapSects TEXT NOT NULL,
                        exploredSects TEXT NOT NULL,
                        scoutInfo TEXT NOT NULL,
                        herbGardenPlantSlots TEXT NOT NULL,
                        manualProficiencies TEXT NOT NULL,
                        travelingMerchantItems TEXT NOT NULL,
                        merchantLastRefreshYear INTEGER NOT NULL,
                        merchantRefreshCount INTEGER NOT NULL,
                        playerListedItems TEXT NOT NULL,
                        tradingSellList TEXT NOT NULL,
                        tradingBuyList TEXT NOT NULL,
                        recruitList TEXT NOT NULL,
                        lastRecruitYear INTEGER NOT NULL,
                        cultivatorCaves TEXT NOT NULL,
                        caveExplorationTeams TEXT NOT NULL,
                        aiCaveTeams TEXT NOT NULL,
                        unlockedDungeons TEXT NOT NULL,
                        unlockedRecipes TEXT NOT NULL,
                        unlockedManuals TEXT NOT NULL,
                        lastSaveTime INTEGER NOT NULL,
                        alliances TEXT NOT NULL,
                        sectRelations TEXT NOT NULL,
                        playerAllianceSlots INTEGER NOT NULL,
                        supportTeams TEXT NOT NULL,
                        elderSlots TEXT NOT NULL,
                        spiritMineSlots TEXT NOT NULL,
                        librarySlots TEXT NOT NULL,
                        sectPolicies TEXT NOT NULL,
                        battleTeam TEXT,
                        aiBattleTeams TEXT NOT NULL
                    )
                """)
                
                db.execSQL("""
                    INSERT INTO game_data_new (
                        id, sectName, currentSlot, gameYear, gameMonth, gameDay, spiritStones, spiritHerbs,
                        autoSaveIntervalMonths, monthlySalary, monthlySalaryEnabled, worldMapSects, exploredSects,
                        scoutInfo, herbGardenPlantSlots, manualProficiencies, travelingMerchantItems,
                        merchantLastRefreshYear, merchantRefreshCount, playerListedItems, tradingSellList,
                        tradingBuyList, recruitList, lastRecruitYear, cultivatorCaves, 
                        caveExplorationTeams, aiCaveTeams, unlockedDungeons, unlockedRecipes, unlockedManuals, 
                        lastSaveTime, alliances, sectRelations, playerAllianceSlots, supportTeams, elderSlots, 
                        spiritMineSlots, librarySlots, sectPolicies, battleTeam, aiBattleTeams
                    )
                    SELECT
                        id, sectName, currentSlot, gameYear, gameMonth, 
                        COALESCE(gameDay, 1), 
                        spiritStones, spiritHerbs,
                        autoSaveIntervalMonths, monthlySalary, monthlySalaryEnabled, worldMapSects, exploredSects,
                        scoutInfo, herbGardenPlantSlots, manualProficiencies, travelingMerchantItems,
                        merchantLastRefreshYear, merchantRefreshCount, playerListedItems, tradingSellList,
                        tradingBuyList, recruitList, lastRecruitYear, cultivatorCaves, 
                        caveExplorationTeams, aiCaveTeams, unlockedDungeons, unlockedRecipes, 
                        unlockedManuals, lastSaveTime, alliances, sectRelations, playerAllianceSlots, supportTeams, 
                        elderSlots, spiritMineSlots, librarySlots, sectPolicies,
                        NULL,
                        COALESCE(aiBattleTeams, '[]')
                    FROM game_data
                """)
                
                db.execSQL("DROP TABLE game_data")
                db.execSQL("ALTER TABLE game_data_new RENAME TO game_data")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    private val MIGRATION_45_46 = object : Migration(45, 46) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "game_data")
            if (!columns.contains("playerProtectionEnabled")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN playerProtectionEnabled INTEGER NOT NULL DEFAULT 1")
            }
            if (!columns.contains("playerProtectionStartYear")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN playerProtectionStartYear INTEGER NOT NULL DEFAULT 1")
            }
            if (!columns.contains("playerHasAttackedAI")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN playerHasAttackedAI INTEGER NOT NULL DEFAULT 0")
            }
            if (!columns.contains("usedRedeemCodes")) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN usedRedeemCodes TEXT NOT NULL DEFAULT '[]'")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    private val MIGRATION_46_47 = object : Migration(46, 47) {
        override fun migrate(db: SupportSQLiteDatabase) {
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    private val MIGRATION_47_48 = object : Migration(47, 48) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "disciples")
            if (!columns.contains("baseHp")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN baseHp INTEGER NOT NULL DEFAULT 100")
            }
            if (!columns.contains("baseMp")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN baseMp INTEGER NOT NULL DEFAULT 50")
            }
            if (!columns.contains("basePhysicalAttack")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN basePhysicalAttack INTEGER NOT NULL DEFAULT 10")
            }
            if (!columns.contains("baseMagicAttack")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN baseMagicAttack INTEGER NOT NULL DEFAULT 5")
            }
            if (!columns.contains("basePhysicalDefense")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN basePhysicalDefense INTEGER NOT NULL DEFAULT 5")
            }
            if (!columns.contains("baseMagicDefense")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN baseMagicDefense INTEGER NOT NULL DEFAULT 3")
            }
            if (!columns.contains("baseSpeed")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN baseSpeed INTEGER NOT NULL DEFAULT 10")
            }
            
            db.execSQL("""
                UPDATE disciples SET 
                    baseHp = CAST(100.0 * (1.0 + combatStatsVariance / 100.0) AS INTEGER),
                    baseMp = CAST(50.0 * (1.0 + combatStatsVariance / 100.0) AS INTEGER),
                    basePhysicalAttack = CAST(10.0 * (1.0 + combatStatsVariance / 100.0) AS INTEGER),
                    baseMagicAttack = CAST(5.0 * (1.0 + combatStatsVariance / 100.0) AS INTEGER),
                    basePhysicalDefense = CAST(5.0 * (1.0 + combatStatsVariance / 100.0) AS INTEGER),
                    baseMagicDefense = CAST(3.0 * (1.0 + combatStatsVariance / 100.0) AS INTEGER),
                    baseSpeed = CAST(10.0 * (1.0 + combatStatsVariance / 100.0) AS INTEGER)
                WHERE baseHp = 100 AND combatStatsVariance != 0
            """)
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }

    private val MIGRATION_48_49 = object : Migration(48, 49) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = getExistingColumns(db, "disciples")
            if (!columns.contains("manualMasteries")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN manualMasteries TEXT NOT NULL DEFAULT '{}'")
            }
            if (!columns.contains("weaponNurture")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN weaponNurture TEXT")
            }
            if (!columns.contains("armorNurture")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN armorNurture TEXT")
            }
            if (!columns.contains("bootsNurture")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN bootsNurture TEXT")
            }
            if (!columns.contains("accessoryNurture")) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN accessoryNurture TEXT")
            }
        }

        private fun getExistingColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            val cursor = db.query("PRAGMA table_info($tableName)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    columns.add(it.getString(nameIndex))
                }
            }
            return columns
        }
    }
}