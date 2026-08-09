package com.yandelaroli.geofencewhatsapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class ProximityTrackingService : Service() {
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var targetRuleId: String? = null
    private var startedAt: Long = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val id = targetRuleId ?: return
            val rule = RuleStore(this@ProximityTrackingService).find(id)
            if (rule == null || !rule.enabled) {
                stopSelfSafely()
                return
            }

            val distance = distanceMeters(location, rule)
            updateForegroundNotification(rule.name, distance)

            if (distance <= rule.radiusMeters) {
                ArrivalNotifier.show(this@ProximityTrackingService, rule)
                stopSelfSafely()
                return
            }

            if (distance > APPROACH_RADIUS_METERS * 1.5f || System.currentTimeMillis() - startedAt > MAX_TRACKING_MS) {
                stopSelfSafely()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ruleId = intent?.getStringExtra(EXTRA_RULE_ID)
        if (ruleId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val rule = RuleStore(this).find(ruleId)
        if (rule == null || !rule.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        targetRuleId = ruleId
        startedAt = System.currentTimeMillis()
        startForeground(NOTIFICATION_ID, buildTrackingNotification(rule.name, null))
        startHighAccuracyUpdates()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        locationClient.removeLocationUpdates(callback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startHighAccuracyUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelfSafely()
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
            .setMinUpdateIntervalMillis(2000L)
            .setMaxUpdateDelayMillis(5000L)
            .build()

        locationClient.requestLocationUpdates(request, callback, mainLooper)
    }

    private fun updateForegroundNotification(name: String, distance: Float) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildTrackingNotification(name, distance))
    }

    private fun buildTrackingNotification(name: String, distance: Float?) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Aproximando-se de $name")
            .setContentText(
                distance?.let { "Verificando localização com maior precisão • ${it.toInt()} m do destino" }
                    ?: "Ativando localização de alta precisão..."
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    7001,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Acompanhamento de aproximação",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun distanceMeters(location: Location, rule: GeofenceRule): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            location.latitude,
            location.longitude,
            rule.latitude,
            rule.longitude,
            results
        )
        return results[0]
    }

    private fun stopSelfSafely() {
        locationClient.removeLocationUpdates(callback)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val EXTRA_RULE_ID = "rule_id"
        const val APPROACH_RADIUS_METERS = 750f
        private const val MAX_TRACKING_MS = 30 * 60 * 1000L
        private const val CHANNEL_ID = "proximity_tracking"
        private const val NOTIFICATION_ID = 7002
    }
}
