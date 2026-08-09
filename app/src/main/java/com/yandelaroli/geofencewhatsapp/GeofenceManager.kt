package com.yandelaroli.geofencewhatsapp

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

class GeofenceManager(private val context: Context) {

    private val client = LocationServices.getGeofencingClient(context)

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    fun registerAll(rules: List<GeofenceRule>, onResult: (Result<Unit>) -> Unit) {
        val enabledRules = rules.filter { it.enabled }
        if (enabledRules.size > 100) {
            onResult(Result.failure(IllegalArgumentException("O Android permite no máximo 100 áreas ativas por app.")))
            return
        }

        val geofences = enabledRules.map { rule ->
            Geofence.Builder()
                .setRequestId(rule.id)
                .setCircularRegion(rule.latitude, rule.longitude, rule.radiusMeters)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()
        }

        try {
            client.removeGeofences(pendingIntent).addOnCompleteListener {
                if (geofences.isEmpty()) {
                    onResult(Result.success(Unit))
                    return@addOnCompleteListener
                }

                val request = GeofencingRequest.Builder()
                    .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                    .addGeofences(geofences)
                    .build()

                try {
                    client.addGeofences(request, pendingIntent)
                        .addOnSuccessListener { onResult(Result.success(Unit)) }
                        .addOnFailureListener { onResult(Result.failure(it)) }
                } catch (securityException: SecurityException) {
                    onResult(Result.failure(securityException))
                }
            }
        } catch (securityException: SecurityException) {
            onResult(Result.failure(securityException))
        }
    }
}
