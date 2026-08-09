package com.yandelaroli.geofencewhatsapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
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
import androidx.compose.material3.HorizontalDivider
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
        setContent { MaterialTheme { GeofenceScreen() } }
    }
}

private data class AddressFields(
    val street: String = "",
    val number: String = "",
    val district: String = "",
    val city: String = "",
    val state: String = ""
) {
    fun query(): String = listOf(street, number, district, city, state, "Brasil")
        .filter { it.isNotBlank() }
        .joinToString(", ")

    fun display(): String = listOf(
        listOf(street, number).filter { it.isNotBlank() }.joinToString(", "),
        district,
        city,
        state
    ).filter { it.isNotBlank() }.joinToString(" - ")
}

@Composable
private fun GeofenceScreen() {
    val context = LocalContext.current
    val store = remember { RuleStore(context) }
    val geofenceManager = remember { GeofenceManager(context) }
    val locationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val onboardingPrefs = remember { context.getSharedPreferences("onboarding_preferences", Context.MODE_PRIVATE) }

    var rules by remember { mutableStateOf(store.load()) }
    var editingRuleId by remember { mutableStateOf<String?>(null) }
    var street by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var district by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var suggestionError by remember { mutableStateOf(false) }
    var selectedAddress by remember { mutableStateOf("") }
    var selectedLatitude by remember { mutableStateOf<Double?>(null) }
    var selectedLongitude by remember { mutableStateOf<Double?>(null) }
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(150f) }
    var phone by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Estou chegando.") }
    var status by remember { mutableStateOf("Preencha os dados do endereço.") }
    var showAlwaysLocationDialog by remember { mutableStateOf(false) }
    var showFirstRunPermissionDialog by remember {
        mutableStateOf(!onboardingPrefs.getBoolean("permission_intro_shown", false))
    }
    var startupPermissionsRequested by remember { mutableStateOf(false) }

    fun fields() = AddressFields(street.trim(), number.trim(), district.trim(), city.trim(), state.trim())
    fun hasFineLocation() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBackgroundLocation() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun isLocationEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled else
            runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
    }

    fun clearAddressSelection() {
        selectedAddress = ""
        selectedLatitude = null
        selectedLongitude = null
    }

    fun clearForm() {
        editingRuleId = null
        street = ""; number = ""; district = ""; city = ""; state = ""
        suggestions = emptyList(); suggestionError = false
        clearAddressSelection()
        name = ""; radius = 150f; phone = ""; message = "Estou chegando."
    }

    fun applyAddress(result: ResolvedAddress) {
        street = result.fields.street
        number = result.fields.number
        district = result.fields.district
        city = result.fields.city
        state = result.fields.state
        selectedAddress = result.label
        selectedLatitude = result.latitude
        selectedLongitude = result.longitude
        suggestions = emptyList()
        if (name.isBlank()) name = result.shortName
    }

    fun loadRuleForEditing(rule: GeofenceRule) {
        editingRuleId = rule.id
        val parsed = parseStoredAddress(rule.address)
        street = parsed.street
        number = parsed.number
        district = parsed.district
        city = parsed.city
        state = parsed.state
        selectedAddress = rule.address
        selectedLatitude = rule.latitude
        selectedLongitude = rule.longitude
        name = rule.name
        radius = rule.radiusMeters
        phone = rule.phone.filter(Char::isDigit).removePrefix("55").takeLast(11)
        message = rule.message
        suggestions = emptyList()
        status = "Editando ${rule.name}."
    }

    fun persistAndRegister(updated: List<GeofenceRule>, successMessage: String) {
        store.save(updated)
        rules = updated
        geofenceManager.registerAll(updated) { result ->
            status = result.fold({ successMessage }, { "Não foi possível ativar as áreas: ${it.message ?: "erro desconhecido"}" })
        }
    }

    fun openAppSettings() {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) status = "Ative notificações para receber o atalho do WhatsApp."
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation()) showAlwaysLocationDialog = true
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else status = "A localização precisa é necessária para o funcionamento do app."
    }

    fun requestInitialPermissions() {
        when {
            !hasFineLocation() -> locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation() -> showAlwaysLocationDialog = true
            Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        if (!startupPermissionsRequested && !showFirstRunPermissionDialog) {
            startupPermissionsRequested = true
            requestInitialPermissions()
        }
    }

    val query = fields().query()
    val typedLength = listOf(street, district, city).joinToString(" ").trim().length
    LaunchedEffect(street, number, district, city, state, selectedAddress) {
        if (typedLength < 3 || fields().display() == selectedAddress) {
            suggestions = emptyList(); searching = false; suggestionError = false
            return@LaunchedEffect
        }
        delay(450)
        searching = true; suggestionError = false
        val result = runCatching { searchPhotonSuggestions(query) }
        suggestions = result.getOrElse { emptyList() }
        suggestionError = result.isFailure
        searching = false
    }

    fun changedAddressField(update: () -> Unit) {
        update()
        clearAddressSelection()
    }

    if (showFirstRunPermissionDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Permissões de localização") },
            text = { Text("Para detectar sua chegada mesmo com o aplicativo fechado, mantenha a localização do celular ligada e permita acesso à localização o tempo todo. O Android também poderá pedir permissão para notificações. Este aviso aparece somente na primeira vez após instalar o aplicativo.") },
            confirmButton = {
                Button(onClick = {
                    onboardingPrefs.edit().putBoolean("permission_intro_shown", true).apply()
                    showFirstRunPermissionDialog = false
                    startupPermissionsRequested = true
                    requestInitialPermissions()
                }) { Text("Continuar") }
            }
        )
    }

    if (showAlwaysLocationDialog) {
        AlertDialog(
            onDismissRequest = { showAlwaysLocationDialog = false },
            title = { Text("Permita localização o tempo todo") },
            text = { Text("Nas configurações do Android, escolha Localização → Permitir o tempo todo e mantenha a localização do aparelho ligada.") },
            confirmButton = { Button(onClick = { showAlwaysLocationDialog = false; openAppSettings() }) { Text("Abrir configurações") } },
            dismissButton = { TextButton(onClick = { showAlwaysLocationDialog = false }) { Text("Agora não") } }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Mensagem por localização", style = MaterialTheme.typography.headlineSmall)

        if (editingRuleId != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Editando local salvo", style = MaterialTheme.typography.titleMedium)
                    Text("Altere os campos e salve. Se mudar o endereço, escolha um resultado da lista ou toque em Buscar endereço.")
                    TextButton(onClick = { clearForm(); status = "Edição cancelada." }) { Text("Cancelar edição") }
                }
            }
        }

        Text("Endereço", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(street, { changedAddressField { street = it } }, Modifier.fillMaxWidth(), label = { Text("Rua") }, singleLine = true)
        OutlinedTextField(
            number,
            { changedAddressField { number = it.filter { c -> c.isDigit() || c.isLetter() || c == '-' }.take(10) } },
            Modifier.fillMaxWidth(),
            label = { Text("Número") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            singleLine = true
        )
        OutlinedTextField(district, { changedAddressField { district = it } }, Modifier.fillMaxWidth(), label = { Text("Bairro") }, singleLine = true)
        OutlinedTextField(city, { changedAddressField { city = it } }, Modifier.fillMaxWidth(), label = { Text("Cidade") }, singleLine = true)
        OutlinedTextField(state, { changedAddressField { state = it.uppercase(Locale.ROOT).take(2) } }, Modifier.fillMaxWidth(), label = { Text("Estado (UF)") }, supportingText = { Text("Ex.: SP, RJ, MG") }, singleLine = true)

        if (typedLength >= 3 && selectedLatitude == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Endereços possíveis", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(12.dp))
                    when {
                        searching -> Text("Buscando...", modifier = Modifier.padding(12.dp))
                        suggestionError -> Text("Não foi possível consultar endereços agora.", modifier = Modifier.padding(12.dp))
                        suggestions.isEmpty() -> Text("Nenhum endereço encontrado. Preencha mais campos para refinar a busca.", modifier = Modifier.padding(12.dp))
                        else -> suggestions.forEachIndexed { index, suggestion ->
                            TextButton(onClick = { applyAddress(suggestion.toResolved()) }, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(suggestion.shortName, style = MaterialTheme.typography.titleSmall)
                                    Text(suggestion.label, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (index != suggestions.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }
            Text("Resultados: Photon / OpenStreetMap", style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                val current = fields()
                if (current.street.isBlank() && current.city.isBlank()) {
                    status = "Preencha pelo menos a rua ou a cidade."
                } else {
                    status = "Buscando endereço..."
                    geocodeAddress(context, current.query()) { result ->
                        result.fold(
                            onSuccess = { found -> applyAddress(found); status = "Endereço encontrado." },
                            onFailure = { status = "Não encontrei esse endereço. Preencha mais detalhes ou escolha um resultado da lista." }
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Buscar endereço") }

        Button(
            onClick = {
                when {
                    !hasFineLocation() -> locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    !isLocationEnabled() -> {
                        status = "Ligue a localização do aparelho."
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                    else -> {
                        status = "Obtendo sua localização..."
                        val tokenSource = CancellationTokenSource()
                        try {
                            locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
                                .addOnSuccessListener { location ->
                                    if (location == null) status = "Não foi possível obter sua localização."
                                    else reverseGeocode(context, location.latitude, location.longitude) { resolved ->
                                        if (resolved != null) applyAddress(resolved.copy(latitude = location.latitude, longitude = location.longitude))
                                        else {
                                            selectedLatitude = location.latitude
                                            selectedLongitude = location.longitude
                                            selectedAddress = "Minha localização atual"
                                        }
                                        if (name.isBlank()) name = "Local atual"
                                        status = "Localização atual selecionada. Os campos do endereço podem ser editados."
                                    }
                                }
                                .addOnFailureListener { status = "Erro ao obter localização: ${it.message ?: "erro desconhecido"}" }
                        } catch (_: SecurityException) { status = "Permissão de localização não concedida." }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Usar minha localização atual") }

        if (selectedLatitude != null && selectedLongitude != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Local selecionado", style = MaterialTheme.typography.titleMedium)
                    Text(selectedAddress.ifBlank { fields().display().ifBlank { "Coordenadas obtidas" } })
                }
            }
        }

        Text("Raio: ${radius.toInt()} m")
        Slider(radius, { radius = it }, valueRange = 50f..1000f, steps = 18)

        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nome do local") }, supportingText = { Text("Ex.: Casa, trabalho, mercado") }, singleLine = true)
        OutlinedTextField(
            phone,
            { raw ->
                var digits = raw.filter(Char::isDigit).take(13)
                if (digits.startsWith("55") && digits.length > 11) digits = digits.drop(2)
                phone = digits.take(11)
            },
            Modifier.fillMaxWidth(),
            label = { Text("WhatsApp (DDD + número)") },
            supportingText = { Text("Ex.: 21999999999") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true
        )
        OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), label = { Text("Mensagem") }, minLines = 3)

        Button(
            onClick = {
                val lat = selectedLatitude
                val lon = selectedLongitude
                val localPhone = phone.filter(Char::isDigit)
                when {
                    !hasFineLocation() -> status = "Autorize a localização primeiro."
                    lat == null || lon == null -> status = "Escolha um endereço da lista, busque o endereço ou use sua localização atual."
                    name.isBlank() -> status = "Dê um nome ao local."
                    localPhone.length !in 10..11 -> status = "Informe um WhatsApp válido com DDD e número."
                    message.isBlank() -> status = "Digite a mensagem."
                    editingRuleId == null && rules.size >= 100 -> status = "Limite de 100 locais atingido."
                    else -> {
                        val id = editingRuleId ?: UUID.randomUUID().toString()
                        val existing = rules.firstOrNull { it.id == id }
                        val addressText = fields().display().ifBlank { selectedAddress.ifBlank { "Endereço não informado" } }
                        val updatedRule = GeofenceRule(id, name.trim(), addressText, lat, lon, radius, "55$localPhone", message.trim(), existing?.enabled ?: true)
                        if (editingRuleId == null) persistAndRegister(rules + updatedRule, "Local salvo e ativado.")
                        else persistAndRegister(rules.map { if (it.id == id) updatedRule else it }, "Alterações salvas e geofence atualizado.")
                        clearForm()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (editingRuleId == null) "Salvar e ativar este local" else "Salvar alterações") }

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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (rule.enabled) "Ativo" else "Desativado")
                        Switch(rule.enabled, { enabled -> persistAndRegister(rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }, "Regra atualizada.") })
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { loadRuleForEditing(rule) }) { Text("Editar") }
                        Button(onClick = {
                            if (editingRuleId == rule.id) clearForm()
                            persistAndRegister(rules.filterNot { it.id == rule.id }, "Local excluído.")
                        }) { Text("Excluir") }
                    }
                }
            }
        }
    }
}

private data class ResolvedAddress(
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val shortName: String,
    val fields: AddressFields
)

private data class AddressSuggestion(
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val shortName: String,
    val fields: AddressFields
) {
    fun toResolved() = ResolvedAddress(latitude, longitude, label, shortName, fields)
}

private fun parseStoredAddress(address: String): AddressFields {
    val parts = address.split(" - ").map { it.trim() }
    val streetParts = parts.getOrNull(0)?.split(",")?.map { it.trim() }.orEmpty()
    return AddressFields(
        street = streetParts.getOrNull(0).orEmpty(),
        number = streetParts.getOrNull(1).orEmpty(),
        district = parts.getOrNull(1).orEmpty(),
        city = parts.getOrNull(2).orEmpty(),
        state = parts.getOrNull(3).orEmpty().take(2)
    )
}

private suspend fun searchPhotonSuggestions(query: String): List<AddressSuggestion> = withContext(Dispatchers.IO) {
    val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
    val url = URL("https://photon.komoot.io/api/?q=$encoded&limit=8&lang=pt&lat=-14.235&lon=-51.9253")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 6000
        readTimeout = 6000
        setRequestProperty("User-Agent", "GeofenceWhatsAppApp/0.7")
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
                val longitude = coordinates.optDouble(0, Double.NaN)
                val latitude = coordinates.optDouble(1, Double.NaN)
                if (!latitude.isFinite() || !longitude.isFinite()) continue
                val featureName = properties.optString("name").takeIf { it.isNotBlank() }
                val street = properties.optString("street").takeIf { it.isNotBlank() } ?: featureName.orEmpty()
                val number = properties.optString("housenumber")
                val district = properties.optString("district").ifBlank { properties.optString("locality") }
                val city = properties.optString("city").ifBlank { properties.optString("county") }
                val state = properties.optString("state").let { normalizeState(it) }
                val fields = AddressFields(street, number, district, city, state)
                add(AddressSuggestion(latitude, longitude, fields.display().ifBlank { featureName ?: query }, featureName ?: street.ifBlank { city.ifBlank { "Local" } }, fields))
            }
        }.distinctBy { "${it.latitude},${it.longitude}" }
    } finally { connection.disconnect() }
}

private fun geocodeAddress(context: Context, query: String, callback: (Result<ResolvedAddress>) -> Unit) {
    val geocoder = Geocoder(context, Locale("pt", "BR"))
    fun deliver(address: Address?) {
        if (address == null) {
            callback(Result.failure(IllegalArgumentException("Endereço não encontrado")))
            return
        }
        callback(Result.success(address.toResolvedAddress(query)))
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

private fun reverseGeocode(context: Context, latitude: Double, longitude: Double, callback: (ResolvedAddress?) -> Unit) {
    val geocoder = Geocoder(context, Locale("pt", "BR"))
    fun deliver(address: Address?) { callback(address?.toResolvedAddress("Minha localização atual")) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        geocoder.getFromLocation(latitude, longitude, 1) { addresses -> deliver(addresses.firstOrNull()) }
    } else {
        Thread {
            val result = runCatching { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }.getOrNull()
            Handler(Looper.getMainLooper()).post { deliver(result) }
        }.start()
    }
}

private fun Address.toResolvedAddress(fallback: String): ResolvedAddress {
    val fields = AddressFields(
        street = thoroughfare ?: featureName.orEmpty(),
        number = subThoroughfare.orEmpty(),
        district = subLocality.orEmpty(),
        city = locality ?: subAdminArea.orEmpty(),
        state = normalizeState(adminArea.orEmpty())
    )
    val label = fields.display().ifBlank { getAddressLine(0) ?: fallback }
    val shortName = thoroughfare ?: featureName ?: locality ?: "Local"
    return ResolvedAddress(latitude, longitude, label, shortName, fields)
}

private fun normalizeState(value: String): String {
    val clean = value.trim()
    if (clean.length == 2) return clean.uppercase(Locale.ROOT)
    return BRAZIL_STATE_CODES[clean.lowercase(Locale.ROOT)] ?: clean.take(2).uppercase(Locale.ROOT)
}

private val BRAZIL_STATE_CODES = mapOf(
    "acre" to "AC", "alagoas" to "AL", "amapá" to "AP", "amazonas" to "AM", "bahia" to "BA",
    "ceará" to "CE", "distrito federal" to "DF", "espírito santo" to "ES", "goiás" to "GO",
    "maranhão" to "MA", "mato grosso" to "MT", "mato grosso do sul" to "MS", "minas gerais" to "MG",
    "pará" to "PA", "paraíba" to "PB", "paraná" to "PR", "pernambuco" to "PE", "piauí" to "PI",
    "rio de janeiro" to "RJ", "rio grande do norte" to "RN", "rio grande do sul" to "RS",
    "rondônia" to "RO", "roraima" to "RR", "santa catarina" to "SC", "são paulo" to "SP",
    "sergipe" to "SE", "tocantins" to "TO"
)
