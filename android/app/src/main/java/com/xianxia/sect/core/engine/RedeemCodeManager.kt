package com.xianxia.sect.core.engine

import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.data.ItemDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.TalentDatabase
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.GameUtils
import kotlin.random.Random

object RedeemCodeManager {

    private const val TAG = "RedeemCodeManager"
    private const val MIN_CODE_LENGTH = 4
    private const val MAX_CODE_LENGTH = 20
    private const val RATE_LIMIT_SECONDS = 3

    private var lastRedeemTime: Long = 0

    private val predefinedCodes = listOf(
        RedeemCode(
            code = "8888",
            rewardType = RedeemRewardType.STARTER_PACK,
            quantity = 1,
            maxUses = 1
        )
    )

    fun getRedeemCode(code: String): RedeemCode? {
        return predefinedCodes.find { it.code.equals(code, ignoreCase = true) }
    }

    fun validateInput(code: String): RedeemResult? {
        val trimmedCode = code.trim()
        
        if (trimmedCode.isEmpty()) {
            return RedeemResult(
                success = false,
                message = "请输入兑换码"
            )
        }

        if (trimmedCode.length < MIN_CODE_LENGTH) {
            Log.w(TAG, "Code too short: ${trimmedCode.length} chars")
            return RedeemResult(
                success = false,
                message = "兑换码长度不能少于${MIN_CODE_LENGTH}个字符"
            )
        }

        if (trimmedCode.length > MAX_CODE_LENGTH) {
            Log.w(TAG, "Code too long: ${trimmedCode.length} chars")
            return RedeemResult(
                success = false,
                message = "兑换码长度不能超过${MAX_CODE_LENGTH}个字符"
            )
        }

        val validPattern = Regex("^[A-Za-z0-9]+$")
        if (!validPattern.matches(trimmedCode)) {
            Log.w(TAG, "Code contains invalid characters: $trimmedCode")
            return RedeemResult(
                success = false,
                message = "兑换码只能包含字母和数字"
            )
        }

        return null
    }

    fun checkRateLimit(): RedeemResult? {
        val currentTime = System.currentTimeMillis()
        val elapsedSeconds = (currentTime - lastRedeemTime) / 1000

        if (elapsedSeconds < RATE_LIMIT_SECONDS) {
            val remainingSeconds = RATE_LIMIT_SECONDS - elapsedSeconds.toInt()
            Log.w(TAG, "Rate limited: $remainingSeconds seconds remaining")
            return RedeemResult(
                success = false,
                message = "操作过于频繁，请${remainingSeconds}秒后再试"
            )
        }

        return null
    }

    fun validateCode(
        code: String,
        usedCodes: List<String>,
        currentYear: Int,
        currentMonth: Int
    ): RedeemResult {
        Log.d(TAG, "Validating code: $code, usedCodes count: ${usedCodes.size}")

        val inputError = validateInput(code)
        if (inputError != null) {
            return inputError
        }

        val rateLimitError = checkRateLimit()
        if (rateLimitError != null) {
            return rateLimitError
        }

        val redeemCode = getRedeemCode(code)

        if (redeemCode == null) {
            Log.w(TAG, "Code not found: $code")
            return RedeemResult(
                success = false,
                message = "兑换码不存在，请检查输入是否正确"
            )
        }

        if (!redeemCode.isEnabled) {
            Log.w(TAG, "Code disabled: ${redeemCode.code}")
            return RedeemResult(
                success = false,
                message = "该兑换码已被禁用"
            )
        }

        if (redeemCode.expireYear != null && redeemCode.expireMonth != null) {
            val totalExpireMonths = redeemCode.expireYear * 12 + redeemCode.expireMonth
            val totalCurrentMonths = currentYear * 12 + currentMonth
            if (totalCurrentMonths > totalExpireMonths) {
                Log.w(TAG, "Code expired: ${redeemCode.code}, expired at ${redeemCode.expireYear}/${redeemCode.expireMonth}")
                return RedeemResult(
                    success = false,
                    message = "该兑换码已于${redeemCode.expireYear}年${redeemCode.expireMonth}月过期"
                )
            }
        }

        if (redeemCode.isExhausted) {
            Log.w(TAG, "Code exhausted: ${redeemCode.code}")
            return RedeemResult(
                success = false,
                message = "该兑换码已被使用完毕"
            )
        }

        if (usedCodes.contains(code.uppercase())) {
            Log.w(TAG, "Code already used by player: $code")
            return RedeemResult(
                success = false,
                message = "您已使用过该兑换码，无法重复使用"
            )
        }

        Log.d(TAG, "Code validation passed: ${redeemCode.code}")
        return RedeemResult(
            success = true,
            message = "验证成功"
        )
    }

    fun generateReward(redeemCode: RedeemCode): RedeemResult {
        Log.d(TAG, "Generating reward for code: ${redeemCode.code}, type: ${redeemCode.rewardType}")
        
        lastRedeemTime = System.currentTimeMillis()
        
        val rewards = mutableListOf<RewardSelectedItem>()
        var disciple: Disciple? = null
        val disciples = mutableListOf<Disciple>()

        when (redeemCode.rewardType) {
            RedeemRewardType.SPIRIT_STONES -> {
                rewards.add(
                    RewardSelectedItem(
                        id = "spiritStones",
                        type = "spiritStones",
                        name = "灵石",
                        rarity = 1,
                        quantity = redeemCode.quantity
                    )
                )
                Log.d(TAG, "Generated spirit stones reward: ${redeemCode.quantity}")
            }
            RedeemRewardType.EQUIPMENT -> {
                repeat(redeemCode.quantity) {
                    val equipment = generateRandomEquipment(redeemCode.rarity)
                    rewards.add(
                        RewardSelectedItem(
                            id = equipment.id,
                            type = "equipment",
                            name = equipment.name,
                            rarity = equipment.rarity,
                            quantity = 1
                        )
                    )
                }
                Log.d(TAG, "Generated ${redeemCode.quantity} equipment(s) with rarity ${redeemCode.rarity}")
            }
            RedeemRewardType.MANUAL -> {
                repeat(redeemCode.quantity) {
                    val manual = ManualDatabase.generateRandom(minRarity = redeemCode.rarity, maxRarity = redeemCode.rarity)
                    rewards.add(
                        RewardSelectedItem(
                            id = manual.id,
                            type = "manual",
                            name = manual.name,
                            rarity = manual.rarity,
                            quantity = 1
                        )
                    )
                }
                Log.d(TAG, "Generated ${redeemCode.quantity} manual(s) with rarity ${redeemCode.rarity}")
            }
            RedeemRewardType.PILL -> {
                repeat(redeemCode.quantity) {
                    val pill = ItemDatabase.generateRandomPill(minRarity = redeemCode.rarity, maxRarity = redeemCode.rarity)
                    rewards.add(
                        RewardSelectedItem(
                            id = pill.id,
                            type = "pill",
                            name = pill.name,
                            rarity = pill.rarity,
                            quantity = 1
                        )
                    )
                }
                Log.d(TAG, "Generated ${redeemCode.quantity} pill(s) with rarity ${redeemCode.rarity}")
            }
            RedeemRewardType.MATERIAL -> {
                repeat(redeemCode.quantity) {
                    val material = ItemDatabase.generateRandomMaterial(minRarity = redeemCode.rarity, maxRarity = redeemCode.rarity)
                    rewards.add(
                        RewardSelectedItem(
                            id = material.id,
                            type = "material",
                            name = material.name,
                            rarity = material.rarity,
                            quantity = 1
                        )
                    )
                }
                Log.d(TAG, "Generated ${redeemCode.quantity} material(s) with rarity ${redeemCode.rarity}")
            }
            RedeemRewardType.HERB -> {
                repeat(redeemCode.quantity) {
                    val herb = HerbDatabase.generateRandomHerb(minRarity = redeemCode.rarity, maxRarity = redeemCode.rarity)
                    rewards.add(
                        RewardSelectedItem(
                            id = herb.id,
                            type = "herb",
                            name = herb.name,
                            rarity = herb.rarity,
                            quantity = 1
                        )
                    )
                }
                Log.d(TAG, "Generated ${redeemCode.quantity} herb(s) with rarity ${redeemCode.rarity}")
            }
            RedeemRewardType.SEED -> {
                repeat(redeemCode.quantity) {
                    val seed = HerbDatabase.generateRandomSeed(minRarity = redeemCode.rarity, maxRarity = redeemCode.rarity)
                    rewards.add(
                        RewardSelectedItem(
                            id = seed.id,
                            type = "seed",
                            name = seed.name,
                            rarity = seed.rarity,
                            quantity = 1
                        )
                    )
                }
                Log.d(TAG, "Generated ${redeemCode.quantity} seed(s) with rarity ${redeemCode.rarity}")
            }
            RedeemRewardType.DISCIPLE -> {
                disciple = generateDisciple(redeemCode.discipleConfig)
                rewards.add(
                    RewardSelectedItem(
                        id = disciple.id,
                        type = "disciple",
                        name = disciple.name,
                        rarity = 1,
                        quantity = 1
                    )
                )
                Log.d(TAG, "Generated disciple: ${disciple.name}, realm: ${disciple.realm}")
            }
            RedeemRewardType.STARTER_PACK -> {
                rewards.add(
                    RewardSelectedItem(
                        id = "spiritStones",
                        type = "spiritStones",
                        name = "灵石",
                        rarity = 1,
                        quantity = 10000000
                    )
                )
                Log.d(TAG, "Generated spirit stones reward: 10000000")
                repeat(5) {
                    val singleRootDisciple = generateDisciple(
                        DiscipleRewardConfig(
                            spiritRootCount = 1,
                            loyalty = 80
                        )
                    )
                    disciples.add(singleRootDisciple)
                    rewards.add(
                        RewardSelectedItem(
                            id = singleRootDisciple.id,
                            type = "disciple",
                            name = singleRootDisciple.name,
                            rarity = 1,
                            quantity = 1
                        )
                    )
                }
                Log.d(TAG, "Generated 5 single spirit root disciples")
            }
        }

        Log.i(TAG, "Redeem successful for code: ${redeemCode.code}, rewards: ${rewards.size}")
        return RedeemResult(
            success = true,
            message = "兑换成功！",
            rewards = rewards,
            disciple = disciple,
            disciples = disciples
        )
    }

    private fun generateRandomEquipment(rarity: Int): Equipment {
        return EquipmentDatabase.generateRandom(minRarity = rarity, maxRarity = rarity)
    }

    fun generateDisciple(config: DiscipleRewardConfig?): Disciple {
        val cfg = config ?: DiscipleRewardConfig()

        val gender = when (cfg.gender) {
            "male" -> "male"
            "female" -> "female"
            else -> if (Random.nextBoolean()) "male" else "female"
        }

        val name = GameUtils.generateRandomName(GameUtils.NameStyle.XIANXIA)

        val spiritRootType = if (cfg.spiritRootType != null && cfg.spiritRootCount != null) {
            val types = listOf("metal", "wood", "water", "fire", "earth")
            val baseType = cfg.spiritRootType
            if (cfg.spiritRootCount == 1) {
                baseType
            } else {
                val additionalTypes = types.filter { it != baseType }.shuffled().take(cfg.spiritRootCount - 1)
                (listOf(baseType) + additionalTypes).joinToString(",")
            }
        } else if (cfg.spiritRootCount != null) {
            val types = listOf("metal", "wood", "water", "fire", "earth")
            types.shuffled().take(cfg.spiritRootCount).joinToString(",")
        } else {
            val types = listOf("metal", "wood", "water", "fire", "earth")
            val rootCount = GameConfig.SpiritRoot.generateRandomSpiritRootCount()
            types.shuffled().take(rootCount).joinToString(",")
        }

        val age = if (cfg.minAge == cfg.maxAge) {
            cfg.minAge
        } else {
            Random.nextInt(cfg.minAge, cfg.maxAge + 1)
        }

        val realmConfig = GameConfig.Realm.get(cfg.realm)
        val baseLifespan = realmConfig.maxAge
        val lifespan = (baseLifespan * (1.0 + Random.nextDouble(-0.1, 0.1))).toInt()

        val talentIds = if (cfg.talentIds.isNotEmpty()) {
            cfg.talentIds
        } else {
            generateRandomTalents()
        }

        val talents = TalentDatabase.getTalentsByIds(talentIds)
        val lifespanBonus = talents.sumOf { it.effects["lifespan"] ?: 0.0 }

        return Disciple(
            name = name,
            realm = cfg.realm,
            realmLayer = cfg.realmLayer,
            spiritRootType = spiritRootType,
            age = age,
            lifespan = (lifespan * (1.0 + lifespanBonus)).toInt(),
            gender = gender,
            talentIds = talentIds,
            intelligence = cfg.intelligence ?: Random.nextInt(30, 81),
            comprehension = cfg.comprehension ?: Random.nextInt(30, 81),
            charm = cfg.charm ?: Random.nextInt(30, 81),
            loyalty = cfg.loyalty ?: Random.nextInt(40, 71),
            artifactRefining = cfg.artifactRefining ?: Random.nextInt(30, 81),
            pillRefining = cfg.pillRefining ?: Random.nextInt(30, 81),
            spiritPlanting = cfg.spiritPlanting ?: Random.nextInt(30, 81),
            teaching = cfg.teaching ?: Random.nextInt(30, 81),
            morality = cfg.morality ?: Random.nextInt(40, 81),
            combatStatsVariance = Random.nextInt(-30, 31)
        )
    }

    private fun generateRandomTalents(): List<String> {
        val talents = mutableListOf<String>()
        val positiveTalents = TalentDatabase.getPositiveTalents()
        val negativeTalents = TalentDatabase.getNegativeTalents()

        val positiveCount = when {
            Random.nextDouble() < 0.3 -> 2
            Random.nextDouble() < 0.6 -> 1
            else -> 0
        }

        repeat(positiveCount) {
            val talent = positiveTalents.random()
            if (!talents.contains(talent.id)) {
                talents.add(talent.id)
            }
        }

        if (Random.nextDouble() < 0.14 && negativeTalents.isNotEmpty()) {
            val talent = negativeTalents.random()
            if (!talents.contains(talent.id)) {
                talents.add(talent.id)
            }
        }

        return talents
    }
}
