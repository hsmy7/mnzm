package com.xianxia.sect.data.local

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase

object DbPragmas {
    private const val TAG = "DbPragmas"

    fun executeSafely(db: SupportSQLiteDatabase, pragma: String) {
        try {
            db.execSQL(pragma)
        } catch (e: android.database.sqlite.SQLiteException) {
            if (e.message?.contains("query or rawQuery") == true) {
                Log.w(TAG, "execSQL rejected for '$pragma', using rawQuery fallback")
                try {
                    db.query(pragma, emptyArray()).close()
                } catch (_: Exception) {}
            } else {
                Log.w(TAG, "Failed to execute pragma: $pragma", e)
            }
        }
    }
}