package com.xianxia.sect.data.cache

data class WriteTask(
    val key: String,
    val flag: DirtyFlag,
    val timestamp: Long,
    val data: Any? = null
) {
    companion object {
        fun insert(key: String, data: Any?): WriteTask {
            return WriteTask(key, DirtyFlag.INSERT, System.currentTimeMillis(), data)
        }

        fun update(key: String, data: Any?): WriteTask {
            return WriteTask(key, DirtyFlag.UPDATE, System.currentTimeMillis(), data)
        }

        fun delete(key: String): WriteTask {
            return WriteTask(key, DirtyFlag.DELETE, System.currentTimeMillis())
        }
    }
}
