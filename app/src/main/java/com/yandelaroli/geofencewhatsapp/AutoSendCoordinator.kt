package com.yandelaroli.geofencewhatsapp

import android.content.Context

object AutoSendCoordinator {
    private const val PREFS = "auto_send_state"
    private const val KEY_RULE_ID = "pending_rule_id"
    private const val KEY_EXPIRES_AT = "pending_expires_at"

    fun arm(context: Context, ruleId: String, ttlMs: Long = 60_000L) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RULE_ID, ruleId)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + ttlMs)
            .apply()
    }

    fun pendingRuleId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt <= System.currentTimeMillis()) {
            clear(context)
            return null
        }
        return prefs.getString(KEY_RULE_ID, null)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_RULE_ID)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }
}
