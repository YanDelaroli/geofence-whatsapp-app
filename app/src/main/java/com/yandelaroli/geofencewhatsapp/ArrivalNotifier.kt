package com.yandelaroli.geofencewhatsapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

object ArrivalNotifier {
    private const val CHANNEL_ID = "geofence_alerts"

    fun show(context: Context, rule: GeofenceRule) {
        val whatsappUri = Uri.parse("https://wa.me/${rule.phone}?text=${Uri.encode(rule.message)}")
        val openWhatsApp = Intent(Intent.ACTION_VIEW, whatsappUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            rule.id.hashCode(),
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

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Você chegou: ${rule.name}")
            .setContentText(
                if (rule.autoSendAuthorized)
                    "Envio automático já autorizado para este local."
                else
                    "Toque para abrir o WhatsApp com a mensagem pronta."
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (rule.autoSendAuthorized)
                        "Você entrou na área '${rule.name}'. O envio automático está autorizado para esta regra."
                    else
                        "Você entrou na área '${rule.name}'. Você pode abrir o WhatsApp agora ou autorizar o envio automático para este local nas próximas vezes."
                )
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)

        if (!rule.autoSendAuthorized) {
            val authorizeIntent = Intent(context, AutoSendAuthorizationReceiver::class.java).apply {
                putExtra(AutoSendAuthorizationReceiver.EXTRA_RULE_ID, rule.id)
            }
            val authorizePendingIntent = PendingIntent.getBroadcast(
                context,
                rule.id.hashCode() xor 0x45A2,
                authorizeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_send,
                "Autorizar automático",
                authorizePendingIntent
            )
        }

        manager.notify(rule.id.hashCode(), builder.build())
    }
}
