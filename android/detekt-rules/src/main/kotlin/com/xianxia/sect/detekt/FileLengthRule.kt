package com.xianxia.sect.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtFile

/**
 * 单文件最大行数守卫（CLAUDE.md 3.1：单文件最大 2000 行）。
 *
 * detekt 1.23 已移除内置 FileSize 规则，此规则补齐文件级长度检查。
 * 新增文件超过 [threshold] 行直接报错，防止超长文件回归。
 */
class FileLengthRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "FileLength",
        severity = Severity.Defect,
        description = "单文件超过 2000 行（CLAUDE.md 3.1），应将文件拆分为内聚的多个文件。" +
            "例外：生成代码（Room _Impl、ProtoBuf 生成类）。",
        debt = Debt.TWENTY_MINS
    )

    private val threshold: Int = valueOrDefault("threshold", 2000)

    override fun visitFile(file: PsiFile) {
        super.visitFile(file)
        val ktFile = file as? KtFile ?: return
        val lineCount = ktFile.text.lines().size
        if (lineCount > threshold) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(ktFile),
                    message = "文件共 $lineCount 行，超过上限 $threshold 行。请拆分为多个内聚文件。"
                )
            )
        }
    }
}
