package com.yandelaroli.geofencewhatsapp

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                GeofenceScreen()
            }
        }
    }
}

@Composable
private fun GeofenceScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(GeofenceManager.PREFS, 0) }
    val geofenceManager = remember { GeofenceManager(context) }

    var latitude by remember { mutableStateOf(prefs.getString(GeofenceManager.KEY_LATITUDE, "") ?: "") }
    var longitude by remember { mutableStateOf(prefs.getString(GeofenceManager.KEY_LONGITUDE, "") ?: "") }
    var radius by remember { mutableStateOf(prefs.getFloat(GeofenceManager.KEY_RADIUS, 150f).toInt().toString()) }
    var phone by remember { mutableStateOf(prefs.getString(GeofenceManager.KEY_PHONE, "55") ?: "55") }
    var message by remember { mutableStateOf(prefs.getString(GeofenceManager.KEY_MESSAGE, "Estou chegando.") ?: "Estou chegando.") }
    var status by remember { mutableStateOf("Configure a regra abaixo.") }

    val foregroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        status = if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            "Localização autorizada. Se necessário, permita também localização 'Sempre'."
        } else {
            "É necessário permitir localização precisa para registrar o local."
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) status = "Sem notificações, o alerta para abrir o WhatsApp pode não aparecer."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Mensagem por localização", style = MaterialTheme.typography.headlineSmall)
        Text("Ao entrar no raio configurado, o app mostra uma notificação. Ao tocar nela, o WhatsApp abre com a mensagem pronta.")

        OutlinedTextField(
            value = latitude,
            onValueChange = { latitude = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Latitude") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true
        )
        OutlinedTextField(
            value = longitude,
            onValueChange = { longitude = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Longitude") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true
        )
        OutlinedTextField(
            value = radius,
            onValueChange = { radius = it.filter(Char::isDigit) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Raio em metros") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("WhatsApp com DDI + DDD") },
            supportingText = { Text("Ex.: 5521999999999") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true
        )
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Mensagem") },
            minLines = 3
        )

        Button(
            onClick = {
                val permissions = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                foregroundPermissionLauncher.launch(permissions.toTypedArray())
                if (Build.VERSION.SDK_INT >= 33) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Autorizar localização e notificações")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Button(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Permitir localização 'Sempre'")
            }
        }

        Button(
            onClick = {
                val lat = latitude.replace(',', '.').toDoubleOrNull()
                val lon = longitude.replace(',', '.').toDoubleOrNull()
                val radiusMeters = radius.toFloatOrNull()

                when {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> {
                        status = "Autorize a localização primeiro."
                    }
                    lat == null || lat !in -90.0..90.0 -> status = "Latitude inválida."
                    lon == null || lon !in -180.0..180.0 -> status = "Longitude inválida."
                    radiusMeters == null || radiusMeters < 50f -> status = "Use um raio de pelo menos 50 metros."
                    else -> {
                        status = "Registrando área..."
                        geofenceManager.register(lat, lon, radiusMeters, phone, message) { result ->
                            status = result.fold(
                                onSuccess = { "Regra ativada com sucesso." },
                                onFailure = { "Não foi possível ativar: ${it.message ?: "erro desconhecido"}" }
                            )
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Salvar e ativar regra")
        }

        Spacer(Modifier.height(4.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}
