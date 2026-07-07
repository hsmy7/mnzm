package com.xianxia.sect.core.model

import com.xianxia.sect.core.state.DiscipleTables

/**
 * 弟子属性快照 — 加载阶段预计算，UI 层直接读取。
 *
 * 替代 UI 层实时调用 DiscipleStatCalculator.getFinalStats() 的昂贵计算，
 * 在加载阶段批量完成所有存活弟子的属性计算，进入游戏后 O(1) 读取。
 *
 * 设计原则：
 * - 只包含 UI 层实际显示的字段，不包含引擎计算需要的热路径字段
 * - 不可变数据类，一次写入永不改变（变化时重新创建快照）
 * - 跨平台纯 Kotlin，无 Android 依赖
 */
data class DiscipleSnapshot(
    val idInt: Int,
    val name: String,
    val surname: String,
    val realm: Int,
    val realmLayer: Int,
    val realmName: String,
    val age: Int,
    val lifespan: Int,
    val gender: String,
    val portraitRes: String,
    val spiritRootType: String,
    val isAlive: Boolean,
    val isFollowed: Boolean,
    val discipleType: String,
    val cultivationProgress: Double,
    val loyalty: Int,
    val intelligence: Int,
    val charm: Int,
    val comprehension: Int,
    val teaching: Int,
    val morality: Int,
    val mining: Int,
    val maxHp: Int,
    val maxMp: Int,
    val physicalAttack: Int,
    val magicAttack: Int,
    val physicalDefense: Int,
    val magicDefense: Int,
    val speed: Int,
    val soulPower: Int,
    val totalCultivation: Long,
    val breakthroughCount: Int,
    val status: String
) {
    companion object {
        /**
         * 从 DiscipleTables 直接批量构建快照列表。
         * 仅在加载阶段调用（存档读取后 / 新游戏初始化时）。
         * O(n) 遍历后持久化为内存 Map，后续查询 O(1)。
         */
        fun buildAll(tables: DiscipleTables): List<DiscipleSnapshot> {
            return buildList(tables.ids.size) {
                for (idInt in tables.ids) {
                    val alive = tables.isAlive.getOrDefault(idInt, 1)
                    if (alive == 0) continue

                    val rlm = tables.realms.getOrDefault(idInt, 9)
                    val layer = tables.realmLayers.getOrDefault(idInt, 1)
                    val age = tables.ages.getOrDefault(idInt, 16)

                    add(DiscipleSnapshot(
                        idInt = idInt,
                        name = tables.names[idInt] ?: continue,
                        surname = tables.surnames[idInt] ?: "",
                        realm = rlm,
                        realmLayer = layer,
                        realmName = buildRealmName(rlm, layer, age),
                        age = age,
                        lifespan = tables.lifespans.getOrDefault(idInt, 100),
                        gender = tables.genders[idInt] ?: "male",
                        portraitRes = tables.portraitRes[idInt] ?: "",
                        spiritRootType = tables.spiritRootTypes[idInt] ?: "five",
                        isAlive = true,
                        isFollowed = tables.statusData.getOrNull(idInt)
                            ?.get("followed") == "true",
                        discipleType = tables.discipleTypes[idInt] ?: "outer",
                        cultivationProgress = 0.0, // 需从外部公式计算，暂不预计算
                        loyalty = tables.loyalties.getOrDefault(idInt, 50),
                        intelligence = tables.intelligences.getOrDefault(idInt, 50),
                        charm = tables.charms.getOrDefault(idInt, 50),
                        comprehension = tables.comprehensions.getOrDefault(idInt, 50),
                        teaching = tables.teachings.getOrDefault(idInt, 50),
                        morality = tables.moralities.getOrDefault(idInt, 50),
                        mining = tables.minings.getOrDefault(idInt, 50),
                        maxHp = tables.baseHps.getOrDefault(idInt, 100),
                        maxMp = tables.baseMps.getOrDefault(idInt, 50),
                        physicalAttack = tables.basePhysicalAttacks.getOrDefault(idInt, 7),
                        magicAttack = tables.baseMagicAttacks.getOrDefault(idInt, 7),
                        physicalDefense = tables.basePhysicalDefenses.getOrDefault(idInt, 5),
                        magicDefense = tables.baseMagicDefenses.getOrDefault(idInt, 3),
                        speed = tables.baseSpeeds.getOrDefault(idInt, 10),
                        soulPower = tables.soulPowers.getOrDefault(idInt, 0),
                        totalCultivation = tables.totalCultivations.getOrNull(idInt) ?: 0L,
                        breakthroughCount = tables.breakthroughCounts.getOrDefault(idInt, 0),
                        status = tables.statuses.getOrNull(idInt)?.name ?: "IDLE"
                    ))
                }
            }
        }

        private fun buildRealmName(realm: Int, realmLayer: Int, age: Int): String {
            if (age < 5 || realmLayer == 0) return "无境界"
            val name = com.xianxia.sect.core.GameConfig.Realm.getName(realm)
            if (realm == 0) return name
            return "${name}${realmLayer}层"
        }
    }
}
