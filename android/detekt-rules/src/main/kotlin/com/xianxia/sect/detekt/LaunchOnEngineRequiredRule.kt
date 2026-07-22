package com.xianxia.sect.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile

/**
 * 检查 delegate 文件中是否未经 launchOnEngine 派发直接调用 stateStore.update
 * 或使用 scope.launch/viewModelScope.launch 未指定后台 dispatcher。
 *
 * 所有 UI 触发的引擎状态变更必须通过 GameEngine.launchOnEngine 派发到引擎线程，
 * 以避免主线程阻塞 ReentrantLock 导致 ANR（对应 Bugly #9041/#5068）。
 */
class LaunchOnEngineRequiredRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "LaunchOnEngineRequired",
        severity = Severity.Defect,
        description = "Delegate 中的引擎调用必须通过 GameEngine.launchOnEngine 派发到引擎线程，" +
            "禁止直接调用 scope.launch 或 stateStore.update，否则主线程阻塞 ReentrantLock 将导致 ANR。" +
            "详见 Bugly #9041/#5068",
        debt = Debt.FIVE_MINS
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        // 仅在 delegate 包中触发
        val file = expression.containingKtFile ?: return
        if (!isDelegateFile(file)) return

        val calleeText = expression.calleeExpression?.text ?: return

        // 检测 1: scope.launch { 或 viewModelScope.launch { 未指定 dispatcher
        if ((calleeText == "launch") && !hasDispatcherArgument(expression)) {
            report(
                CodeSmell(
                    issue, Entity.from(expression),
                    message = "scope.launch / viewModelScope.launch 直接调用（缺 dispatcher）。" +
                        "应使用 gameEngine.launchOnEngine { ... } 派发到引擎线程。"
                )
            )
        }

        // 检测 2: stateStore.update 直接调用
        if (calleeText == "update" && isStateStoreUpdate(expression)) {
            report(
                CodeSmell(
                    issue, Entity.from(expression),
                    message = "stateStore.update 直接调用。应通过 GameEngine.launchOnEngine " +
                        "派发到引擎线程后调用 gameEngine.xxx()，而非直接更新状态。"
                )
            )
        }
    }

    private fun isDelegateFile(file: KtFile): Boolean {
        val path = file.virtualFilePath
        if (path.contains("/delegate/") || path.contains("\\delegate\\")) return true
        // 也检查包声明，确保单元测试（compileAndLint 虚拟文件）能匹配
        val pkg = file.packageDirective?.qualifiedName ?: return false
        return pkg.endsWith(".delegate") || pkg.contains(".delegate.")
    }

    private fun hasDispatcherArgument(expression: KtCallExpression): Boolean {
        return expression.valueArguments.any { arg ->
            val text = arg.text
            text.contains("Dispatchers.") || text.contains("CoroutineDispatcher")
        }
    }

    private fun isStateStoreUpdate(expression: KtCallExpression): Boolean {
        // 使用 PSI 树精确匹配：检查是否是 stateStore.update { ... } 模式
        val dotQualified = expression.parent as? KtDotQualifiedExpression ?: return false
        val receiverText = dotQualified.receiverExpression?.text ?: return false
        return receiverText == "stateStore" || receiverText.endsWith(".stateStore")
    }
}
