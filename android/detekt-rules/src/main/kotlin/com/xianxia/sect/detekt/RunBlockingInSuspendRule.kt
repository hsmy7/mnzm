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
 * 检测 suspend 函数中调用 runBlocking。
 *
 * runBlocking 会阻塞当前线程，在挂起函数中使用它会破坏协程的非阻塞特性，
 * 且在 Dispatchers.Main 上使用将直接导致 ANR。
 */
class RunBlockingInSuspendRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "RunBlockingInSuspend",
        severity = Severity.Defect,
        description = "在挂起函数中使用 runBlocking 会阻塞线程并破坏协程的非阻塞特性。" +
            "应使用 withContext 或其他挂起方式替代。",
        debt = Debt.FIVE_MINS
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val calleeText = expression.calleeExpression?.text ?: return
        if (calleeText != "runBlocking") return

        // 检查是否在 suspend 函数内部
        if (isInsideSuspendFunction(expression)) {
            report(
                CodeSmell(
                    issue, Entity.from(expression),
                    message = "挂起函数中使用了 runBlocking。runBlocking 会阻塞当前线程，" +
                        "应使用 withContext(Dispatchers.IO) 或 withContext(Dispatchers.Default) 替代。"
                )
            )
        }
    }

    private fun isInsideSuspendFunction(expression: KtCallExpression): Boolean {
        var current = expression.parent
        while (current != null) {
            if (current is KtNamedFunction && current.hasModifier(KtTokens.SUSPEND_KEYWORD)) {
                return true
            }
            if (current is KtNamedFunction) {
                return false // 到达非 suspend 函数边界，停止搜索
            }
            current = current.parent
        }
        return false
    }
}
