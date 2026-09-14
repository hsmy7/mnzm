package com.xianxia.sect.core.util

/**
 * 显式随机源下的集合抽取（替代 stdlib `Iterable.random()`）。
 *
 * ## 存在理由
 * stdlib 的 `List.random()` / `Iterable.random()` 使用 `kotlin.random.Random.Default`
 * ——**进程启动随机、不入存档、不随档走**。它对调用方是"无参数"的，因此极易在
 * 决策路径上被顺手使用而不被察觉（随机源治理 ADR 认定的同一漏洞出口）。
 * 本函数把随机源变成**必填形参**，使"用哪个流"成为调用点的显式决定：
 * - 决策类（结果写入 GameData/实体表）→ 传 `分区Rng.asKotlinRandom()`
 * - 表现类（纯文案/立绘）→ 传 `PresentationRandom` 的适配视图
 *
 * 抽取语义与 stdlib `random()` 一致：下界 0、上界 `size`、单次 `nextInt(size)`。
 * 空列表返回 `null`（调用方自行决定"跳过该条目"或抛错）——比抛
 * `NoSuchElementException` 更适合"模板库按品阶过滤后可能为空"的既有调用面。
 *
 * @param rng 显式随机源（`kotlin.random.Random` 子类；分区适配器或表现随机）
 * @return 随机元素；集合为空时 `null`
 */
fun <T> Collection<T>.randomWith(rng: kotlin.random.Random): T? =
    if (isEmpty()) null else elementAt(rng.nextInt(size))
