package com.xianxia.sect.taptap

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * 从任意 Context 向上解包查找 Activity。
 * feature:game 不能反向引用 app 模块的 Activity 类型，Compose LocalContext
 * 在对话框场景下即宿主 Activity（GameActivity），此处安全解包取用。
 */
internal fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
