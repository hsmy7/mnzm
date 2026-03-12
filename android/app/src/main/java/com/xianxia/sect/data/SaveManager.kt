package com.xianxia.sect.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SaveManager"
        private const val PREFS_NAME = "xianxia_saves"
        private const val KEY_SAVE_SLOT = "save_slot_"
        private const val KEY_AUTO_SAVE = "autosave"
        private const val MAX_SLOTS = 5
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd HH:mm:ss")
        .serializeNulls()
        .disableHtmlEscaping()
        .create()

    fun getSaveSlots(): List<SaveSlot> = (1..MAX_SLOTS).map(::getSlotInfo)

    fun getSlotInfo(slot: Int): SaveSlot {
        if (!isValidSlot(slot)) return emptySlot(slot)

        val json = prefs.getString(slotKey(slot), null)
        if (json.isNullOrEmpty()) return emptySlot(slot)

        val data = parseSaveData(json, "slot:$slot") ?: return emptySlot(slot)
        return SaveSlot(
            slot = slot,
            name = "Save $slot",
            timestamp = data.timestamp,
            gameYear = data.gameData.gameYear,
            gameMonth = data.gameData.gameMonth,
            sectName = data.gameData.sectName,
            discipleCount = data.disciples.count { it.isAlive },
            spiritStones = data.gameData.spiritStones,
            isEmpty = false
        )
    }

    fun save(slot: Int, data: SaveData): Boolean {
        if (!isValidSlot(slot)) {
            Log.e(TAG, "Invalid save slot: $slot")
            return false
        }

        return try {
            val success = commitEdit { putString(slotKey(slot), gson.toJson(data)) }
            if (success) {
                Log.d(TAG, "Saved slot $slot")
            } else {
                Log.e(TAG, "Failed to save slot $slot")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception while saving slot $slot", e)
            false
        }
    }

    fun load(slot: Int): SaveData? {
        if (!isValidSlot(slot)) {
            Log.e(TAG, "Invalid save slot: $slot")
            return null
        }

        val json = prefs.getString(slotKey(slot), null)
        if (json.isNullOrEmpty()) {
            Log.w(TAG, "Save slot is empty: $slot")
            return null
        }

        return parseSaveData(json, "slot:$slot")
    }

    fun delete(slot: Int): Boolean {
        if (!isValidSlot(slot)) return false

        return try {
            val success = commitEdit { remove(slotKey(slot)) }
            if (success) {
                Log.d(TAG, "Deleted slot $slot")
            } else {
                Log.e(TAG, "Failed to delete slot $slot")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception while deleting slot $slot", e)
            false
        }
    }

    fun autoSave(data: SaveData): Boolean {
        return try {
            val json = gson.toJson(data.copy(timestamp = System.currentTimeMillis()))
            val success = commitEdit { putString(KEY_AUTO_SAVE, json) }
            if (success) {
                Log.d(TAG, "Auto save completed")
            } else {
                Log.e(TAG, "Auto save failed")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception while auto saving", e)
            false
        }
    }

    fun loadAutoSave(): SaveData? {
        val json = prefs.getString(KEY_AUTO_SAVE, null) ?: return null
        return parseSaveData(json, "autosave")
    }

    fun hasAutoSave(): Boolean = prefs.contains(KEY_AUTO_SAVE)

    fun hasSave(slot: Int): Boolean = isValidSlot(slot) && prefs.contains(slotKey(slot))

    private fun parseSaveData(json: String, source: String): SaveData? {
        return try {
            gson.fromJson(json, SaveData::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse save data from $source", e)
            null
        }
    }

    private fun isValidSlot(slot: Int): Boolean = slot in 1..MAX_SLOTS

    private fun slotKey(slot: Int): String = "$KEY_SAVE_SLOT$slot"

    private fun emptySlot(slot: Int): SaveSlot = SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true)

    private inline fun commitEdit(block: SharedPreferences.Editor.() -> Unit): Boolean {
        val editor = prefs.edit()
        editor.block()
        return editor.commit()
    }
}
