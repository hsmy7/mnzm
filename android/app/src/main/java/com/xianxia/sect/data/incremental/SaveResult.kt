package com.xianxia.sect.data.incremental

sealed class SaveResult {
    object NoChanges : SaveResult()
    data class Success(val count: Int) : SaveResult()
    data class Error(val message: String, val exception: Throwable? = null) : SaveResult()

    val isSuccess: Boolean get() = this is Success
    val isNoChanges: Boolean get() = this is NoChanges
    val isError: Boolean get() = this is Error
}
