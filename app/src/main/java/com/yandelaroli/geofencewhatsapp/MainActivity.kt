package com.yandelaroli.geofencewhatsapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
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
    val locationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var rules by remember { mutableStateOf(store.load()) }
    var addressQuery by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var isSearchingSuggestions by remember { mutableStateOf(false) }
    var selectedAddress by remember { mutableStateOf("") }
    var selectedLatitude by remember { mutableStateOf<Double?>(null) }
    var selectedLongitude by remember { mutableStateOf<Double?>(null) }
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(150f) }
    var phone by remember { mutableStateOf("55") }
    var message by remember { mutableStateOf("Estou chegando.") }
    var status by remember { mutableStateOf("Digite um CEP/endereço ou use sua localização atual.") }

    LaunchedEffect(addressQuery) {
        val query = addressQuery.trim()
        if (query.length < 3 || query == selectedAddress) {
            suggestions = emptyList()
            isSearchingSuggestions = false
            return@LaunchedEffect
        }

        delay(550)
        isSearchingSuggestions = true
        suggestions = runCatching { searchPhotonSuggestions(query) }.getOrElse { emptyList() }
        isSearchingSuggestions = false
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

    fun selectSuggestion(suggestion: AddressSuggestion) {
        addressQuery = suggestion.label
        selectedAddress = suggestion.label
        selectedLatitude = suggestion.latitude
        selectedLongitude = suggestion.longitude
        suggestions = emptyList()
        if (name.isBlank()) name = suggestion.shortName
        status = "Local selecionado: ${suggestion.label}"
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
        Text("Comece a digitar um endereço ou CEP e escolha uma das sugestões.")

        OutlinedTextField(
            value = addressQuery,
            onValueChange = {
                addressQuery = it
                if (it != selectedAddress) {
                    selectedAddress = ""
                    selectedLatitude = null
                    selectedLongitude = null
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("CEP ou endereço") },
            supportingText = {
                Text(
                    when {
                        isSearchingSuggestions -> "Buscando sugestões..."
                        addressQuery.length in 1..2 -> "Digite pelo menos 3 caracteres"
                        else -> "Ex.: Av. Rio Branco, 1, Rio de Janeiro"
                    }
                )
            },
            singleLine = false
        )

        if (suggestions.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    suggestions.forEach { suggestion ->
                        TextButton(
                            onClick = { selectSuggestion(suggestion) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(suggestion.shortName, style = MaterialTheme.typography.titleSmall)
                                Text(suggestion.label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            Text("Resultados de endereço: Photon / OpenStreetMap", style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                if (addressQuery.isBlank()) {
                    status = "Digite um CEP ou endereço."
                } else {
                    status = "Buscando endereço..."
                    geocodeAddress(context, addressQuery) { result ->
                        result.fold(
                            onSuccess = { found ->
                                selectedLatitude = found.latitude
                                selectedLongitude = found.longitude
                                selectedAddress = found.label
                                addressQuery = found.label
                                suggestions = emptyList()
                                status = "Local encontrado: ${found.label}"
                                if (name.isBlank()) name = found.shortName
                            },
                            onFailure = {
                                status = "Não encontrei esse endereço. Escolha uma sugestão ou informe rua, número, cidade e estado."
                            }
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Buscar endereço completo")
        }

        Button(
            onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    status = "Autorize a localização antes de usar sua posição atual."
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                } else {
                    status = "Obtendo sua localização..."
                    val tokenSource = CancellationTokenSource()
                    try {
                        locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
                            .addOnSuccessListener { location ->
                                if (location == null) {
                                    status = "Não foi possível obter sua localização. Ative o GPS e tente novamente."
                                } else {
                                    selectedLatitude = location.latitude
                                    selectedLongitude = location.longitude
                                    reverseGeocode(context, location.latitude, location.longitude) { label ->
                                        selectedAddress = label ?: "Minha localização atual"
                                        addressQuery = selectedAddress
                                        suggestions = emptyList()
                                        if (name.isBlank()) name = "Local atual"
                                        status = "Localização atual selecionada."
                                    }
                                }
                            }
                            .addOnFailureListener {
                                status = "Erro ao obter localização: ${it.message ?: "erro desconhecido"}"
                            }
                    } catch (securityException: SecurityException) {
                        status = "Permissão de localização não concedida."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Usar minha localização atual")
        }

        if (selectedLatitude != null && selectedLongitude != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Local selecionado", style = MaterialTheme.typography.titleMedium)
                    Text(selectedAddress.ifBlank { "Coordenadas obtidas" })
                }
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
                val lat = selectedLatitude
                val lon = selectedLongitude
                val cleanPhone = phone.filter(Char::isDigit)
                when {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> {
                        status = "Autorize a localização primeiro."
                    }
                    lat == null || lon == null -> status = "Escolha uma sugestão, busque o endereço ou use sua localização atual."
                    name.isBlank() -> status = "Dê um nome ao local."
                    cleanPhone.length < 10 -> status = "Informe um WhatsApp válido com DDI e DDD."
                    message.isBlank() -> status = "Digite a mensagem."
                    rules.size >= 100 -> status = "Limite de 100 locais atingido."
                    else -> {
                        val newRule = GeofenceRule(
                            id = UUID.randomUUID().toString(),
                            name = name.trim(),
                            address = selectedAddress.ifBlank { addressQuery.trim() },
                            latitude = lat,
                            longitude = lon,
                            radiusMeters = radius,
                            phone = cleanPhone,
                            message = message.trim()
                        )
                        persistAndRegister(rules + newRule)
                        addressQuery = ""
                        selectedAddress = ""
                        selectedLatitude = null
                        selectedLongitude = null
                        suggestions = emptyList()
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
                    Text(rule.address)
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

private data class FoundAddress(
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val shortName: String
)

private data class AddressSuggestion(
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val shortName: String
)

private suspend fun searchPhotonSuggestions(query: String): List<AddressSuggestion> = withContext(Dispatchers.IO) {
    val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
    val url = URL("https://photon.komoot.io/api/?q=$encoded&limit=5&lang=pt")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 5000
        readTimeout = 5000
        setRequestProperty("User-Agent", "GeofenceWhatsAppApp/0.4")
        setRequestProperty("Accept", "application/json")
    }

    try {
        if (connection.responseCode !in 200..299) return@withContext emptyList()
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val features = JSONObject(body).optJSONArray("features") ?: return@withContext emptyList()

        buildList {
            for (index in 0 until features.length()) {
                val feature = features.optJSONObject(index) ?: continue
                val geometry = feature.optJSONObject("geometry") ?: continue
                val coordinates = geometry.optJSONArray("coordinates") ?: continue
                if (coordinates.length() < 2) continue

                val longitude = coordinates.optDouble(0, Double.NaN)
                val latitude = coordinates.optDouble(1, Double.NaN)
                if (!latitude.isFinite() || !longitude.isFinite()) continue

                val properties = feature.optJSONObject("properties") ?: JSONObject()
                val name = properties.optString("name").takeIf { it.isNotBlank() }
                val street = properties.optString("street").takeIf { it.isNotBlank() }
                val houseNumber = properties.optString("housenumber").takeIf { it.isNotBlank() }
                val postcode = properties.optString("postcode").takeIf { it.isNotBlank() }
                val city = properties.optString("city").takeIf { it.isNotBlank() }
                    ?: properties.optString("district").takeIf { it.isNotBlank() }
                val state = properties.optString("state").takeIf { it.isNotBlank() }
                val country = properties.optString("country").takeIf { it.isNotBlank() }

                val streetLine = listOfNotNull(street ?: name, houseNumber).joinToString(", ")
                val label = listOfNotNull(
                    streetLine.takeIf { it.isNotBlank() },
                    city,
                    state,
                    postcode,
                    country
                ).distinct().joinToString(" - ").ifBlank { name ?: query }

                add(
                    AddressSuggestion(
                        latitude = latitude,
                        longitude = longitude,
                        label = label,
                        shortName = name ?: street ?: city ?: "Local"
                    )
                )
            }
        }.distinctBy { "${it.latitude},${it.longitude}" }
    } finally {
        connection.disconnect()
    }
}

private fun geocodeAddress(context: Context, query: String, callback: (Result<FoundAddress>) -> Unit) {
    val geocoder = Geocoder(context, Locale("pt", "BR"))

    fun deliver(address: android.location.Address?) {
        if (address == null) {
            callback(Result.failure(IllegalArgumentException("Endereço não encontrado")))
            return
        }
        val label = address.getAddressLine(0)
            ?: listOfNotNull(address.thoroughfare, address.subThoroughfare, address.locality, address.adminArea)
                .joinToString(", ")
                .ifBlank { query }
        val shortName = address.thoroughfare ?: address.featureName ?: address.locality ?: "Local"
        callback(Result.success(FoundAddress(address.latitude, address.longitude, label, shortName)))
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        geocoder.getFromLocationName(query, 1) { addresses ->
            deliver(addresses.firstOrNull())
        }
    } else {
        Thread {
            val result = runCatching { geocoder.getFromLocationName(query, 1)?.firstOrNull() }.getOrNull()
            Handler(Looper.getMainLooper()).post { deliver(result) }
        }.start()
    }
}

private fun reverseGeocode(context: Context, latitude: Double, longitude: Double, callback: (String?) -> Unit) {
    val geocoder = Geocoder(context, Locale("pt", "BR"))

    fun deliver(address: android.location.Address?) {
        callback(address?.getAddressLine(0))
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
            deliver(addresses.firstOrNull())
        }
    } else {
        Thread {
            val result = runCatching { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }.getOrNull()
            Handler(Looper.getMainLooper()).post { deliver(result) }
        }.start()
    }
}
