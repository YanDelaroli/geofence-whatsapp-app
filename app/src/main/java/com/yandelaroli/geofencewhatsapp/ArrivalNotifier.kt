package com.yandelaroli.geofencewhatsapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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

        if (rule.autoSendAuthorized && isAccessibilityEnabled(context)) {
            AutoSendCoordinator.arm(context, rule.id)
            runCatching { context.startActivity(openWhatsApp) }
                .onFailure { AutoSendCoordinator.clear(context) }
        }

        val automaticReady = rule.autoSendAuthorized && isAccessibilityEnabled(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Você chegou: ${rule.name}")
            .setContentText(
                when {
                    automaticReady -> "Envio automático autorizado. Abrindo o WhatsApp..."
                    rule.autoSendAuthorized -> "Envio automático autorizado, mas a Acessibilidade precisa ser ativada."
                    else -> "Toque para abrir o WhatsApp com a mensagem pronta."
                }
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    when {
                        automaticReady -> "Você entrou na área '${rule.name}'. O app abriu o WhatsApp e tentará tocar em Enviar automaticamente usando a autorização salva para esta regra."
                        rule.autoSendAuthorized -> "Você autorizou o envio automático para '${rule.name}', mas o serviço de Acessibilidade do Geofence WhatsApp ainda não está ativo."
                        else -> "Você entrou na área '${rule.name}'. Você pode abrir o WhatsApp agora ou autorizar o envio automático para este local nas próximas vezes."
                    }
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
        } else if (!automaticReady) {
            val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val settingsPendingIntent = PendingIntent.getActivity(
                context,
                rule.id.hashCode() xor 0x25A7,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_manage,
                "Ativar Acessibilidade",
                settingsPendingIntent
            )
        }

        manager.notify(rule.id.hashCode(), builder.build())
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${AutoSendAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
