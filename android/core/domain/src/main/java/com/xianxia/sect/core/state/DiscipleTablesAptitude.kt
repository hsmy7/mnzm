package com.xianxia.sect.core.state

/** 资质自愈确定性散列乘数（仿 applyDeterministicWinAttr 的 id 散列模式，Long 防 Int 溢出） */
private const val APTITUDE_HASH_MULTIPLIER = 527L

/** 资质自愈确定性散列偏移 */
private const val APTITUDE_HASH_OFFSET = 31L

/**
 * 按灵根数阶梯确定性散列资质（id 散列，幂等——同一 id 每次结果稳定），
 * 命中哨兵 [DEFAULT_APTITUDE] 时强制 +1 收敛。自愈与入宗补算共用同一实现，
 * 保证读档自愈与 [allocateAndInsert] 补算结果一致。
 */
internal fun rollHealedAptitude(id: Int, rootCount: Int): Int {
    val (min, span) = when (rootCount) {
        1 -> 80 to 21 // [80,100]
        2 -> 60 to 21 // [60,80]
        3 -> 40 to 21 // [40,60]
        4 -> 20 to 21 // [20,40]
        else -> 1 to 20 // 5 灵根 [1,20]；异常灵根数兜底
    }
    // 确定性散列（Long 运算防 Int 溢出，floorMod 保证非负），span 恰好覆盖 [min, 100]
    val roll = Math.floorMod(
        id.toLong() * APTITUDE_HASH_MULTIPLIER + APTITUDE_HASH_OFFSET,
        span.toLong()
    ).toInt()
    val aptitude = min + roll
    // 收敛：重算命中哨兵值时强制 +1，保证幂等稳定（3 根区间 [40,60] 含 50）
    return if (aptitude == DiscipleTables.DEFAULT_APTITUDE) DiscipleTables.DEFAULT_APTITUDE + 1 else aptitude
}
