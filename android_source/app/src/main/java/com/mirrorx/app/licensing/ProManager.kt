package com.mirrorx.app.licensing

import android.content.Context

object ProManager {
    private const val PREFS_NAME = "mirrorx_prefs"
    private const val KEY_PRO = "pro_unlocked"
    private const val UNLOCK_CODE = "MIRRORX-PRO-10"
    private const val MAX_FPS_FREE = 24

    fun isPro(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PRO, false)
    }

    fun unlock(context: Context, code: String): Boolean {
        if (code.trim().uppercase() == UNLOCK_CODE) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PRO, true).apply()
            return true
        }
        return false
    }

    fun lock(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PRO, false).apply()
    }

    fun maxFps(context: Context): Int {
        return if (isPro(context)) 60 else MAX_FPS_FREE
    }
}
