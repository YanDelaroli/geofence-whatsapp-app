package com.yandelaroli.geofencewhatsapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
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
import androidx.compose.material3.AlertDialog
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
    var editingRuleId by remember { mutableStateOf<String?>(null) }
    var addressQuery by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var isSearchingSuggestions by remember { mutableStateOf(false) }
    var suggestionError by remember { mutableStateOf(false) }
    var selectedAddress by remember { mutableStateOf("") }
    var selectedLatitude by remember { mutableStateOf<Double?>(null) }
    var selectedLongitude by remember { mutableStateOf<Double?>(null) }
    var keepCurrentCoordinatesWhileEditing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(150f) }
    var phone by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Estou chegando.") }
    var status by remember { mutableStateOf("Digite um CEP/endereço ou use sua localização atual.") }
    var showAlwaysLocationDialog by remember { mutableStateOf(false) }
    var startupPermissionsRequested by remember { mutableStateOf(false) }

    fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isLocationEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            runCatching {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
        }
    }

    fun clearForm() {
        editingRuleId = null
        addressQuery = ""
        suggestions = emptyList()
        suggestionError = false
        selectedAddress = ""
        selectedLatitude = null
        selectedLongitude = null
        keepCurrentCoordinatesWhileEditing = false
        name = ""
        radius = 150f
        phone = ""
        message = "Estou chegando."
    }

    fun loadRuleForEditing(rule: GeofenceRule) {
        editingRuleId = rule.id
        addressQuery = rule.address
        selectedAddress = rule.address
        selectedLatitude = rule.latitude
        selectedLongitude = rule.longitude
        keepCurrentCoordinatesWhileEditing = true
        name = rule.name
        radius = rule.radiusMeters
        phone = rule.phone.filter(Char::isDigit).removePrefix("55").takeLast(11)
        message = rule.message
        suggestions = emptyList()
        status = "Editando ${rule.name}. Altere os dados e toque em Salvar alterações."
    }

    fun openAppSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    fun persistAndRegister(updated: List<GeofenceRule>, successMessage: String = "Regras atualizadas e áreas ativadas.") {
        store.save(updated)
        rules = updated
        geofenceManager.registerAll(updated) { result ->
            status = result.fold(
                onSuccess = { successMessage },
                onFailure = { "Não foi possível ativar as áreas: ${it.message ?: "erro desconhecido"}" }
            )
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) status = "Ative as notificações para receber o atalho do WhatsApp quando chegar ao local."
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            status = "Localização precisa autorizada. Agora permita localização o tempo todo para o geofence funcionar com o app fechado."
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation()) {
                showAlwaysLocationDialog = true
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            status = "A localização precisa é necessária para detectar corretamente quando você entra na área cadastrada."
        }
    }

    LaunchedEffect(Unit) {
        if (!startupPermissionsRequested) {
            startupPermissionsRequested = true
            when {
                !hasFineLocation() -> locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation() -> showAlwaysLocationDialog = true
                Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(addressQuery, keepCurrentCoordinatesWhileEditing) {
        val query = addressQuery.trim()
        if (keepCurrentCoordinatesWhileEditing || query.length < 3 || query == selectedAddress) {
            suggestions = emptyList()
            isSearchingSuggestions = false
            suggestionError = false
            return@LaunchedEffect
        }
        delay(450)
        isSearchingSuggestions = true
        suggestionError = false
        val result = runCatching { searchPhotonSuggestions(query) }
        suggestions = result.getOrElse { emptyList() }
        suggestionError = result.isFailure
        isSearchingSuggestions = false
    }

    fun selectSuggestion(suggestion: AddressSuggestion) {
        addressQuery = suggestion.label
        selectedAddress = suggestion.label
        selectedLatitude = suggestion.latitude
        selectedLongitude = suggestion.longitude
        keepCurrentCoordinatesWhileEditing = false
        suggestions = emptyList()
        if (name.isBlank()) name = suggestion.shortName
        status = "Local selecionado: ${suggestion.label}"
    }

    if (showAlwaysLocationDialog) {
        AlertDialog(
            onDismissRequest = { showAlwaysLocationDialog = false },
            title = { Text("Localização deve ficar permitida sempre") },
            text = {
                Text(
                    "Este aplicativo usa geofencing para detectar quando você chega a um local mesmo com o app fechado. " +
                        "No Android, abra as permissões do aplicativo e escolha Localização → Permitir o tempo todo. " +
                        "A localização do aparelho também precisa permanecer ligada."
                )
            },
            confirmButton = {
                Button(onClick = { showAlwaysLocationDialog = false; openAppSettings() }) {
                    Text("Abrir configurações")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAlwaysLocationDialog = false }) { Text("Agora não") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Mensagem por localização", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Permissões necessárias", style = MaterialTheme.typography.titleMedium)
                Text("Mantenha a localização do celular ligada e conceda ao app acesso à localização 'o tempo todo'.")
                Text(
                    when {
                        !hasFineLocation() -> "Status: localização ainda não autorizada."
                        !hasBackgroundLocation() -> "Status: falta permitir localização o tempo todo."
                        !isLocationEnabled() -> "Status: a localização do aparelho está desligada."
                        else -> "Status: localização pronta para geofencing."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                if (!isLocationEnabled()) {
                    Button(
                        onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Ligar localização") }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation()) {
                    Button(onClick = { showAlwaysLocationDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Permitir localização o tempo todo")
                    }
                }
            }
        }

        if (editingRuleId != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Editando local salvo", style = MaterialTheme.typography.titleMedium)
                    Text("Você pode alterar qualquer campo abaixo. Para mudar o ponto geográfico, escolha uma sugestão, busque o endereço novamente ou use sua localização atual.")
                    TextButton(onClick = { clearForm(); status = "Edição cancelada." }) {
                        Text("Cancelar edição")
                    }
                }
            }
        }

        Text("Comece a digitar um endereço ou CEP e escolha uma das sugestões.")

        OutlinedTextField(
            value = addressQuery,
            onValueChange = { newValue ->
                addressQuery = newValue
                if (keepCurrentCoordinatesWhileEditing) {
                    selectedAddress = newValue
                } else if (newValue != selectedAddress) {
                    selectedAddress = ""
                    selectedLatitude = null
                    selectedLongitude = null
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (keepCurrentCoordinatesWhileEditing) "Endereço do local (editável)" else "CEP ou endereço") },
            supportingText = {
                Text(
                    when {
                        keepCurrentCoordinatesWhileEditing -> "Você pode editar o texto; para alterar as coordenadas, use Buscar endereço ou escolha uma sugestão."
                        isSearchingSuggestions -> "Buscando sugestões..."
                        suggestionError -> "Não foi possível carregar sugestões. Verifique sua internet."
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
                        TextButton(onClick = { selectSuggestion(suggestion) }, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(suggestion.shortName, style = MaterialTheme.typography.titleSmall)
                                Text(suggestion.label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            Text("Sugestões: Photon / OpenStreetMap", style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                if (addressQuery.isBlank()) {
                    status = "Digite um CEP ou endereço."
                } else {
                    keepCurrentCoordinatesWhileEditing = false
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
        ) { Text("Buscar endereço completo") }

        Button(
            onClick = {
                if (!hasFineLocation()) {
                    status = "Autorize a localização antes de usar sua posição atual."
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                } else if (!isLocationEnabled()) {
                    status = "A localização do aparelho está desligada. Ligue-a e tente novamente."
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } else {
                    status = "Obtendo sua localização..."
                    val tokenSource = CancellationTokenSource()
                    try {
                        locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
                            .addOnSuccessListener { location ->
                                if (location == null) {
                                    status = "Não foi possível obter sua localização. Tente novamente em local com melhor sinal."
                                } else {
                                    selectedLatitude = location.latitude
                                    selectedLongitude = location.longitude
                                    keepCurrentCoordinatesWhileEditing = true
                                    reverseGeocode(context, location.latitude, location.longitude) { label ->
                                        selectedAddress = label ?: "Minha localização atual"
                                        addressQuery = selectedAddress
                                        suggestions = emptyList()
                                        if (name.isBlank()) name = "Local atual"
                                        status = "Localização atual selecionada. Você pode editar o endereço acima sem perder o ponto GPS."
                                    }
                                }
                            }
                            .addOnFailureListener {
                                status = "Erro ao obter localização: ${it.message ?: "erro desconhecido"}"
                            }
                    } catch (_: SecurityException) {
                        status = "Permissão de localização não concedida."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Usar minha localização atual") }

        if (selectedLatitude != null && selectedLongitude != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Local selecionado", style = MaterialTheme.typography.titleMedium)
                    Text(addressQuery.ifBlank { selectedAddress.ifBlank { "Coordenadas obtidas" } })
                }
            }
        }

        Text("Raio: ${radius.toInt()} m")
        Slider(value = radius, onValueChange = { radius = it }, valueRange = 50f..1000f, steps = 18)

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
            onValueChange = { raw ->
                var digits = raw.filter(Char::isDigit).take(13)
                if (digits.startsWith("55") && digits.length > 11) digits = digits.drop(2)
                phone = digits.take(11)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("WhatsApp (DDD + número)") },
            supportingText = { Text("Brasil (+55) é aplicado automaticamente. Ex.: 21999999999") },
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
                val lat = selectedLatitude
                val lon = selectedLongitude
                val localPhone = phone.filter(Char::isDigit)
                when {
                    !hasFineLocation() -> status = "Autorize a localização primeiro."
                    lat == null || lon == null -> status = "Escolha uma sugestão, busque o endereço ou use sua localização atual."
                    name.isBlank() -> status = "Dê um nome ao local."
                    localPhone.length !in 10..11 -> status = "Informe um WhatsApp válido com DDD e número."
                    message.isBlank() -> status = "Digite a mensagem."
                    editingRuleId == null && rules.size >= 100 -> status = "Limite de 100 locais atingido."
                    else -> {
                        val id = editingRuleId ?: UUID.randomUUID().toString()
                        val existing = rules.firstOrNull { it.id == id }
                        val updatedRule = GeofenceRule(
                            id = id,
                            name = name.trim(),
                            address = addressQuery.trim().ifBlank { selectedAddress.ifBlank { "Endereço não informado" } },
                            latitude = lat,
                            longitude = lon,
                            radiusMeters = radius,
                            phone = "55$localPhone",
                            message = message.trim(),
                            enabled = existing?.enabled ?: true
                        )
                        if (editingRuleId == null) {
                            persistAndRegister(rules + updatedRule, "Local salvo e ativado.")
                        } else {
                            persistAndRegister(
                                rules.map { if (it.id == id) updatedRule else it },
                                "Alterações salvas e geofence atualizado."
                            )
                        }
                        clearForm()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (editingRuleId == null) "Salvar e ativar este local" else "Salvar alterações")
        }

        Text(status, style = MaterialTheme.typography.bodyMedium)

        if (rules.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Locais salvos", style = MaterialTheme.typography.titleLarge)
        }

        rules.forEach { rule ->
            val displayedPhone = rule.phone.filter(Char::isDigit).removePrefix("55")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(rule.name, style = MaterialTheme.typography.titleMedium)
                    Text(rule.address)
                    Text("Raio: ${rule.radiusMeters.toInt()} m • WhatsApp: $displayedPhone")
                    Text(rule.message)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (rule.enabled) "Ativo" else "Desativado")
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { enabled ->
                                    persistAndRegister(
                                        rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }
                                    )
                                }
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { loadRuleForEditing(rule) }) { Text("Editar") }
                        Button(
                            onClick = {
                                if (editingRuleId == rule.id) clearForm()
                                persistAndRegister(rules.filterNot { it.id == rule.id }, "Local excluído.")
                            }
                        ) { Text("Excluir") }
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
    val url = URL("https://photon.komoot.io/api/?q=$encoded&limit=7&lang=pt&lat=-14.235&lon=-51.9253")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 6000
        readTimeout = 6000
        setRequestProperty("User-Agent", "GeofenceWhatsAppApp/0.5")
        setRequestProperty("Accept", "application/json")
    }

    try {
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val features = JSONObject(body).optJSONArray("features") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until features.length()) {
                val feature = features.optJSONObject(index) ?: continue
                val properties = feature.optJSONObject("properties") ?: JSONObject()
                val countryCode = properties.optString("countrycode").lowercase(Locale.ROOT)
                val country = properties.optString("country")
                if (countryCode.isNotBlank() && countryCode != "br") continue
                if (countryCode.isBlank() && country.isNotBlank() && !country.contains("Brazil", true) && !country.contains("Brasil", true)) continue

                val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                if (coordinates.length() < 2) continue
                val longitude = coordinates.optDouble(0, Double.NaN)
                val latitude = coordinates.optDouble(1, Double.NaN)
                if (!latitude.isFinite() || !longitude.isFinite()) continue

                val name = properties.optString("name").takeIf { it.isNotBlank() }
                val street = properties.optString("street").takeIf { it.isNotBlank() }
                val houseNumber = properties.optString("housenumber").takeIf { it.isNotBlank() }
                val postcode = properties.optString("postcode").takeIf { it.isNotBlank() }
                val city = properties.optString("city").takeIf { it.isNotBlank() }
                    ?: properties.optString("district").takeIf { it.isNotBlank() }
                val state = properties.optString("state").takeIf { it.isNotBlank() }
                val streetLine = listOfNotNull(street ?: name, houseNumber).joinToString(", ")
                val label = listOfNotNull(
                    streetLine.takeIf { it.isNotBlank() }, city, state, postcode
                ).distinct().joinToString(" - ").ifBlank { name ?: query }

                add(AddressSuggestion(latitude, longitude, label, name ?: street ?: city ?: "Local"))
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
        geocoder.getFromLocationName(query, 1) { addresses -> deliver(addresses.firstOrNull()) }
    } else {
        Thread {
            val result = runCatching { geocoder.getFromLocationName(query, 1)?.firstOrNull() }.getOrNull()
            Handler(Looper.getMainLooper()).post { deliver(result) }
        }.start()
    }
}

private fun reverseGeocode(context: Context, latitude: Double, longitude: Double, callback: (String?) -> Unit) {
    val geocoder = Geocoder(context, Locale("pt", "BR"))
    fun deliver(address: android.location.Address?) { callback(address?.getAddressLine(0)) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        geocoder.getFromLocation(latitude, longitude, 1) { addresses -> deliver(addresses.firstOrNull()) }
    } else {
        Thread {
            val result = runCatching { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }.getOrNull()
            Handler(Looper.getMainLooper()).post { deliver(result) }
        }.start()
    }
}
