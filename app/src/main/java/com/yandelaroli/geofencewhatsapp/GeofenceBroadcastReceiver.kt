package com.yandelaroli.geofencewhatsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return

        val store = RuleStore(context)
        event.triggeringGeofences.orEmpty().forEach { geofence ->
            val rule = store.find(geofence.requestId) ?: return@forEach
            if (!rule.enabled) return@forEach

            val serviceIntent = Intent(context, ProximityTrackingService::class.java).apply {
                putExtra(ProximityTrackingService.EXTRA_RULE_ID, rule.id)
            }
            runCatching {
                ContextCompat.startForegroundService(context, serviceIntent)
            }.onFailure {
                // If Android refuses the foreground service for any reason,
                // fall back to the original arrival notification behavior.
                ArrivalNotifier.show(context, rule)
            }
        }
    }
}
