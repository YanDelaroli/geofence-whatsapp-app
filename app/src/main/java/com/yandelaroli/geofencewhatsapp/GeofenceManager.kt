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

    fun register(
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        phone: String,
        message: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        val cleanPhone = phone.filter(Char::isDigit)
        if (cleanPhone.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Informe o número com DDI e DDD.")))
            return
        }
        if (message.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Informe a mensagem.")))
            return
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PHONE, cleanPhone)
            .putString(KEY_MESSAGE, message)
            .putString(KEY_LATITUDE, latitude.toString())
            .putString(KEY_LONGITUDE, longitude.toString())
            .putFloat(KEY_RADIUS, radiusMeters)
            .apply()

        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(latitude, longitude, radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        try {
            client.removeGeofences(pendingIntent).addOnCompleteListener {
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

    companion object {
        const val PREFS = "geofence_rule"
        const val KEY_PHONE = "phone"
        const val KEY_MESSAGE = "message"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_RADIUS = "radius"
        const val GEOFENCE_ID = "whatsapp_destination"
    }
}
