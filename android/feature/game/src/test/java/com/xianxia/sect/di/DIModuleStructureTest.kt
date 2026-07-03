package com.xianxia.sect.di

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assert
import org.junit.Test

/**
 * Konsist 架构测试 — 验证 DI 模块结构一致性。
 *
 * 检查：
 * - 所有 @Module 类必须使用 object 或 companion object
 * - 所有 @Provides 方法必须返回非空类型
 * - 所有 @Binds 方法必须在 abstract class 或 interface 中
 */
class DIModuleStructureTest {

    @Test
    fun `all Hilt modules are objects or abstract classes`() {
        Konsist.scopeFromProject()
            .classes()
            .withAnnotation("dagger.Module") { it.hasAnnotationOf<dagger.Module>() }
            .assert { it.isObject or it.isAbstract }
    }

    @Test
    fun `all @Provides return non-nullable types`() {
        Konsist.scopeFromProject()
            .functions()
            .withAnnotation("dagger.Provides")
            .assert { !it.returnType?.isNullable ?: false }
    }

    @Test
    fun `all @Binds are in abstract classes or interfaces`() {
        Konsist.scopeFromProject()
            .functions()
            .withAnnotation("dagger.Binds")
            .assert { it.hasParentClass { parent -> parent.isAbstract or parent.isInterface } }
    }

    @Test
    fun `all @Inject constructors have no wildcard imports`() {
        Konsist.scopeFromProject()
            .files
            .assert { !it.hasImports { it.name.contains(".*") } }
    }
}
