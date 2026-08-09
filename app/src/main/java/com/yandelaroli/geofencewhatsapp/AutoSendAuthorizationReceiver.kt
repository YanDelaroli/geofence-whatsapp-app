package com.yandelaroli.geofencewhatsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutoSendAuthorizationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ruleId = intent.getStringExtra(EXTRA_RULE_ID) ?: return
        val store = RuleStore(context)
        val updated = store.load().map { rule ->
            if (rule.id == ruleId) rule.copy(autoSendAuthorized = true) else rule
        }
        store.save(updated)
    }

    companion object {
        const val EXTRA_RULE_ID = "rule_id"
    }
}
