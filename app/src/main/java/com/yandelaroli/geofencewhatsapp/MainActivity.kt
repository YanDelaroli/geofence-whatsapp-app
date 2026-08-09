package com.yandelaroli.geofencewhatsapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import java.util.UUID

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
    val store = remember { RuleStore(context) }
    val geofenceManager = remember { GeofenceManager(context) }

    var rules by remember { mutableStateOf(store.load()) }
    var selectedPoint by remember { mutableStateOf<LatLng?>(null) }
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(150f) }
    var phone by remember { mutableStateOf("55") }
    var message by remember { mutableStateOf("Estou chegando.") }
    var status by remember { mutableStateOf("Toque no mapa para escolher um local.") }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(-14.2350, -51.9253), 3.5f)
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        status = if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            "Localização autorizada. Para funcionar com o app fechado, permita também localização 'Sempre'."
        } else {
            "É necessário permitir localização precisa."
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) status = "Ative notificações para receber o atalho do WhatsApp."
    }

    fun persistAndRegister(updated: List<GeofenceRule>) {
        store.save(updated)
        rules = updated
        geofenceManager.registerAll(updated) { result ->
            status = result.fold(
                onSuccess = { "Regras atualizadas e áreas ativadas." },
                onFailure = { "Não foi possível ativar as áreas: ${it.message ?: "erro desconhecido"}" }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Mensagem por localização", style = MaterialTheme.typography.headlineSmall)
        Text("Toque no mapa, escolha o raio e salve quantos locais quiser.")

        GoogleMap(
            modifier = Modifier
                .fillMaxWidth()
                .height(330.dp),
            cameraPositionState = cameraPositionState,
            onMapClick = {
                selectedPoint = it
                status = "Local escolhido. Ajuste os dados e salve."
            }
        ) {
            selectedPoint?.let { point ->
                Marker(
                    state = MarkerState(position = point),
                    title = name.ifBlank { "Novo local" }
                )
                Circle(
                    center = point,
                    radius = radius.toDouble()
                )
            }

            rules.filter { it.enabled }.forEach { rule ->
                val point = LatLng(rule.latitude, rule.longitude)
                Marker(
                    state = MarkerState(position = point),
                    title = rule.name
                )
                Circle(center = point, radius = rule.radiusMeters.toDouble())
            }
        }

        Text("Raio: ${radius.toInt()} m")
        Slider(
            value = radius,
            onValueChange = { radius = it },
            valueRange = 50f..1000f,
            steps = 18
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Nome do local") },
            supportingText = { Text("Ex.: Casa, trabalho, mercado") },
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
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
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
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Permitir localização 'Sempre'")
            }
        }

        Button(
            onClick = {
                val point = selectedPoint
                val cleanPhone = phone.filter(Char::isDigit)
                when {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> {
                        status = "Autorize a localização primeiro."
                    }
                    point == null -> status = "Toque no mapa para escolher o local."
                    name.isBlank() -> status = "Dê um nome ao local."
                    cleanPhone.length < 10 -> status = "Informe um WhatsApp válido com DDI e DDD."
                    message.isBlank() -> status = "Digite a mensagem."
                    rules.size >= 100 -> status = "Limite de 100 locais atingido."
                    else -> {
                        val newRule = GeofenceRule(
                            id = UUID.randomUUID().toString(),
                            name = name.trim(),
                            latitude = point.latitude,
                            longitude = point.longitude,
                            radiusMeters = radius,
                            phone = cleanPhone,
                            message = message.trim()
                        )
                        persistAndRegister(rules + newRule)
                        selectedPoint = null
                        name = ""
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Salvar e ativar este local")
        }

        Text(status, style = MaterialTheme.typography.bodyMedium)

        if (rules.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Locais salvos", style = MaterialTheme.typography.titleLarge)
        }

        rules.forEach { rule ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(rule.name, style = MaterialTheme.typography.titleMedium)
                    Text("Raio: ${rule.radiusMeters.toInt()} m • WhatsApp: ${rule.phone}")
                    Text(rule.message)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (rule.enabled) "Ativo" else "Desativado")
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { enabled ->
                                    persistAndRegister(
                                        rules.map {
                                            if (it.id == rule.id) it.copy(enabled = enabled) else it
                                        }
                                    )
                                }
                            )
                        }
                        Button(
                            onClick = {
                                persistAndRegister(rules.filterNot { it.id == rule.id })
                            }
                        ) {
                            Text("Excluir")
                        }
                    }
                }
            }
        }
    }
}
