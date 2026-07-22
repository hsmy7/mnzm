package com.xianxia.sect.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * 检测协程上下文中可能导致线程阻塞的调用。
 *
 * 在协程的 launch / async / withContext 块中使用 Thread.sleep、
 * runBlocking 等阻塞操作会阻塞线程并可能导致 ANR。
 *
 * 对应行业实践的 BlockingCallInCoroutine 规则。
 */
class BlockingCallInCoroutineRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "BlockingCallInCoroutine",
        severity = Severity.Defect,
        description = "在协程上下文中使用阻塞调用（如 Thread.sleep、runBlocking）会阻塞线程。" +
            "应使用挂起替代：delay() 替代 Thread.sleep()，withContext 替代 runBlocking。",
        debt = Debt.FIVE_MINS
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val calleeText = expression.calleeExpression?.text ?: return
        val fullText = expression.text

        // 仅检查在协程构建器内部的调用
        if (!isInsideCoroutineBuilder(expression)) return

        // 检查方法名是否为已知阻塞调用
        for (blockingCall in BLOCKING_CALLS) {
            if (calleeText == blockingCall || fullText.startsWith(blockingCall)) {
                report(
                    CodeSmell(
                        issue, Entity.from(expression),
                        message = "在协程上下文中调用了阻塞方法 '$calleeText'。" +
                            "应使用挂起替代。"
                    )
                )
                return
            }
        }
    }

    private fun isInsideCoroutineBuilder(expression: KtCallExpression): Boolean {
        var current = expression.parent
        while (current != null) {
            when (current) {
                is KtNamedFunction -> {
                    // 到达函数边界：suspend 函数内部也算协程上下文
                    if (current.hasModifier(KtTokens.SUSPEND_KEYWORD)) {
                        current = current.parent
                        continue
                    }
                    return false
                }
                is KtCallExpression -> {
                    val name = current.calleeExpression?.text ?: ""
                    if (name in COROUTINE_BUILDERS) return true
                }
            }
            current = current.parent
        }
        return false
    }

    companion object {
        private val BLOCKING_CALLS = setOf(
            "Thread.sleep",
            "sleep",
            "runBlocking",
        )

        private val COROUTINE_BUILDERS = setOf(
            "launch", "async", "withContext",
            "coroutineScope", "supervisorScope",
        )
    }
}
