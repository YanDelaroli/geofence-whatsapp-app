package com.yandelaroli.geofencewhatsapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return

        val prefs = context.getSharedPreferences(GeofenceManager.PREFS, Context.MODE_PRIVATE)
        val phone = prefs.getString(GeofenceManager.KEY_PHONE, null) ?: return
        val message = prefs.getString(GeofenceManager.KEY_MESSAGE, null) ?: return

        val whatsappUri = Uri.parse("https://wa.me/$phone?text=${Uri.encode(message)}")
        val openWhatsApp = Intent(Intent.ACTION_VIEW, whatsappUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            2001,
            openWhatsApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Alertas de localização",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Você chegou ao local")
            .setContentText("Toque para abrir o WhatsApp com a mensagem pronta.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Você entrou na área configurada. Toque aqui para abrir o WhatsApp e enviar: $message")
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "geofence_alerts"
        private const val NOTIFICATION_ID = 3001
    }
}
