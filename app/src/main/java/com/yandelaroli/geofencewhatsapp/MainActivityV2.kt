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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

class MainActivityV2 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { GeofenceScreenV2() } }
    }
}

private data class AddressFieldsV2(
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

private data class AddressSuggestionV2(
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val shortName: String,
    val fields: AddressFieldsV2
)

@Composable
private fun GeofenceScreenV2() {
    val context = LocalContext.current
    val store = remember { RuleStore(context) }
    val geofenceManager = remember { GeofenceManager(context) }
    val locationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val onboardingPrefs = remember { context.getSharedPreferences("onboarding_preferences", Context.MODE_PRIVATE) }

    var rules by remember { mutableStateOf(store.load()) }
    var editingRuleId by remember { mutableStateOf<String?>(null) }

    var state by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var district by remember { mutableStateOf("") }
    var street by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }

    var cities by remember { mutableStateOf<List<String>>(emptyList()) }
    var districts by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingCities by remember { mutableStateOf(false) }
    var loadingDistricts by remember { mutableStateOf(false) }
    var customDistrictMode by remember { mutableStateOf(false) }

    var suggestions by remember { mutableStateOf<List<AddressSuggestionV2>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var suggestionError by remember { mutableStateOf(false) }

    var selectedAddress by remember { mutableStateOf("") }
    var selectedLatitude by remember { mutableStateOf<Double?>(null) }
    var selectedLongitude by remember { mutableStateOf<Double?>(null) }

    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(150f) }
    var phone by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Estou chegando.") }
    var status by remember { mutableStateOf("Selecione Estado, Cidade e Bairro para começar.") }

    var showAlwaysLocationDialog by remember { mutableStateOf(false) }
    var startupPermissionsRequested by remember { mutableStateOf(false) }
    var showFirstRunPermissionDialog by remember {
        mutableStateOf(!onboardingPrefs.getBoolean("permission_intro_shown", false))
    }

    fun fields() = AddressFieldsV2(street.trim(), number.trim(), district.trim(), city.trim(), state.trim())
    fun clearAddressSelection() {
        selectedAddress = ""
        selectedLatitude = null
        selectedLongitude = null
    }
    fun clearForm() {
        editingRuleId = null
        state = ""; city = ""; district = ""; street = ""; number = ""
        cities = emptyList(); districts = emptyList(); customDistrictMode = false
        suggestions = emptyList(); suggestionError = false
        clearAddressSelection()
        name = ""; radius = 150f; phone = ""; message = "Estou chegando."
    }
    fun hasFineLocation() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBackgroundLocation() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun isLocationEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled else
            runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
    }
    fun openAppSettings() {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
    fun persistAndRegister(updated: List<GeofenceRule>, successMessage: String) {
        store.save(updated)
        rules = updated
        geofenceManager.registerAll(updated) { result ->
            status = result.fold({ successMessage }, { "Não foi possível ativar as áreas: ${it.message ?: "erro desconhecido"}" })
        }
    }
    fun applySuggestion(suggestion: AddressSuggestionV2) {
        state = suggestion.fields.state.ifBlank { state }
        city = suggestion.fields.city.ifBlank { city }
        district = suggestion.fields.district.ifBlank { district }
        street = suggestion.fields.street.ifBlank { street }
        number = suggestion.fields.number.ifBlank { number }
        selectedAddress = suggestion.label
        selectedLatitude = suggestion.latitude
        selectedLongitude = suggestion.longitude
        suggestions = emptyList()
        if (name.isBlank()) name = suggestion.shortName
        status = "Endereço selecionado."
    }
    fun loadRuleForEditing(rule: GeofenceRule) {
        val parsed = parseStoredAddressV2(rule.address)
        editingRuleId = rule.id
        state = parsed.state
        city = parsed.city
        district = parsed.district
        street = parsed.street
        number = parsed.number
        selectedAddress = rule.address
        selectedLatitude = rule.latitude
        selectedLongitude = rule.longitude
        name = rule.name
        radius = rule.radiusMeters
        phone = rule.phone.filter(Char::isDigit).removePrefix("55").takeLast(11)
        message = rule.message
        status = "Editando ${rule.name}."
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

    LaunchedEffect(state) {
        if (state.isBlank()) {
            cities = emptyList()
            return@LaunchedEffect
        }
        loadingCities = true
        val result = runCatching { fetchIbgeCities(state) }
        cities = result.getOrElse { emptyList() }
        loadingCities = false
    }

    LaunchedEffect(city, state) {
        if (city.isBlank() || state.isBlank()) {
            districts = emptyList()
            return@LaunchedEffect
        }
        loadingDistricts = true
        val result = runCatching { fetchDistrictSuggestions(city, state) }
        districts = result.getOrElse { emptyList() }
        loadingDistricts = false
    }

    LaunchedEffect(street, number, district, city, state) {
        if (state.isBlank() || city.isBlank() || street.trim().length < 3) {
            suggestions = emptyList(); searching = false; suggestionError = false
            return@LaunchedEffect
        }
        delay(450)
        searching = true; suggestionError = false
        val result = runCatching { searchPhotonAddressesV2(fields().query()) }
        suggestions = result.getOrElse { emptyList() }
        suggestionError = result.isFailure
        searching = false
    }

    if (showFirstRunPermissionDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Permissões de localização") },
            text = { Text("Para detectar sua chegada mesmo com o aplicativo fechado, mantenha a localização do celular ligada e permita acesso à localização o tempo todo. Este aviso aparece somente na primeira vez após instalar o aplicativo.") },
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Mensagem por localização", style = MaterialTheme.typography.headlineSmall)

        if (editingRuleId != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Editando local salvo", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { clearForm(); status = "Edição cancelada." }) { Text("Cancelar edição") }
                }
            }
        }

        Text("Endereço", style = MaterialTheme.typography.titleMedium)

        CompactSelector(
            label = "Estado",
            selected = state,
            options = BRAZIL_STATES_V2.map { "${it.first} - ${it.second}" },
            placeholder = "Escolha o estado"
        ) { choice ->
            val uf = choice.substringBefore(" - ")
            state = uf
            city = ""
            district = ""
            customDistrictMode = false
            clearAddressSelection()
        }

        CompactSelector(
            label = "Cidade",
            selected = city,
            options = cities,
            placeholder = when {
                state.isBlank() -> "Escolha o estado primeiro"
                loadingCities -> "Carregando cidades..."
                else -> "Escolha a cidade"
            },
            enabled = state.isNotBlank() && !loadingCities
        ) { choice ->
            city = choice
            district = ""
            customDistrictMode = false
            clearAddressSelection()
        }

        if (!customDistrictMode) {
            CompactSelector(
                label = "Bairro",
                selected = district,
                options = districts + listOf("Outro bairro...") ,
                placeholder = when {
                    city.isBlank() -> "Escolha a cidade primeiro"
                    loadingDistricts -> "Carregando bairros..."
                    districts.isEmpty() -> "Nenhum bairro listado"
                    else -> "Escolha o bairro"
                },
                enabled = city.isNotBlank() && !loadingDistricts
            ) { choice ->
                if (choice == "Outro bairro...") {
                    district = ""
                    customDistrictMode = true
                } else district = choice
                clearAddressSelection()
            }
        } else {
            OutlinedTextField(
                value = district,
                onValueChange = { district = it; clearAddressSelection() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bairro") },
                supportingText = { Text("Digite apenas se o bairro não apareceu na lista") },
                singleLine = true
            )
            TextButton(onClick = { customDistrictMode = false; district = "" }) { Text("Voltar para lista de bairros") }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = street,
                onValueChange = { street = it; clearAddressSelection() },
                modifier = Modifier.weight(1f),
                label = { Text("Rua") },
                singleLine = true
            )
            OutlinedTextField(
                value = number,
                onValueChange = { number = it.take(10); clearAddressSelection() },
                modifier = Modifier.weight(0.45f),
                label = { Text("Nº") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                singleLine = true
            )
        }

        if (street.trim().length >= 3 && city.isNotBlank() && state.isNotBlank() && selectedLatitude == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Endereços possíveis", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(10.dp))
                    when {
                        searching -> Text("Buscando...", modifier = Modifier.padding(10.dp))
                        suggestionError -> Text("Não foi possível consultar endereços agora.", modifier = Modifier.padding(10.dp))
                        suggestions.isEmpty() -> Text("Nenhum endereço encontrado ainda. Continue digitando a rua ou o número.", modifier = Modifier.padding(10.dp))
                        else -> suggestions.forEachIndexed { index, suggestion ->
                            TextButton(onClick = { applySuggestion(suggestion) }, modifier = Modifier.fillMaxWidth()) {
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
            Text("Endereços: Photon / OpenStreetMap", style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                if (state.isBlank() || city.isBlank() || street.isBlank()) {
                    status = "Selecione Estado e Cidade e informe a Rua."
                } else {
                    status = "Buscando endereço..."
                    geocodeAddressV2(context, fields().query()) { result ->
                        result.fold(
                            onSuccess = { suggestion -> applySuggestion(suggestion); status = "Endereço encontrado." },
                            onFailure = { status = "Não encontrei esse endereço. Confira os campos ou escolha um resultado da lista." }
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
                    !isLocationEnabled() -> context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    else -> {
                        status = "Obtendo sua localização..."
                        val tokenSource = CancellationTokenSource()
                        try {
                            locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
                                .addOnSuccessListener { location ->
                                    if (location == null) status = "Não foi possível obter sua localização."
                                    else reverseGeocodeV2(context, location.latitude, location.longitude) { suggestion ->
                                        if (suggestion != null) applySuggestion(suggestion.copy(latitude = location.latitude, longitude = location.longitude))
                                        else {
                                            selectedLatitude = location.latitude
                                            selectedLongitude = location.longitude
                                            selectedAddress = "Minha localização atual"
                                        }
                                        if (name.isBlank()) name = "Local atual"
                                        status = "Localização atual selecionada."
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
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Local selecionado", style = MaterialTheme.typography.titleMedium)
                    Text(selectedAddress.ifBlank { fields().display() })
                }
            }
        }

        Text("Raio: ${radius.toInt()} m")
        Slider(radius, { radius = it }, valueRange = 50f..1000f, steps = 18)

        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nome do local") }, singleLine = true)
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
                        val updatedRule = GeofenceRule(
                            id = id,
                            name = name.trim(),
                            address = fields().display().ifBlank { selectedAddress.ifBlank { "Endereço não informado" } },
                            latitude = lat,
                            longitude = lon,
                            radiusMeters = radius,
                            phone = "55$localPhone",
                            message = message.trim(),
                            enabled = existing?.enabled ?: true
                        )
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
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(rule.name, style = MaterialTheme.typography.titleMedium)
                    Text(rule.address)
                    Text("Raio: ${rule.radiusMeters.toInt()} m • WhatsApp: $displayedPhone")
                    Text(rule.message)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (rule.enabled) "Ativo" else "Desativado")
                        Switch(rule.enabled, { enabled -> persistAndRegister(rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }, "Regra atualizada.") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun CompactSelector(
    label: String,
    selected: String,
    options: List<String>,
    placeholder: String,
    enabled: Boolean = true,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { if (enabled) expanded = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled
            ) {
                Text(selected.ifBlank { placeholder })
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.distinct().filter { it.isNotBlank() }.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        }
                    )
                }
            }
        }
    }
}

private fun parseStoredAddressV2(address: String): AddressFieldsV2 {
    val parts = address.split(" - ").map { it.trim() }
    val streetParts = parts.getOrNull(0)?.split(",")?.map { it.trim() }.orEmpty()
    return AddressFieldsV2(
        street = streetParts.getOrNull(0).orEmpty(),
        number = streetParts.getOrNull(1).orEmpty(),
        district = parts.getOrNull(1).orEmpty(),
        city = parts.getOrNull(2).orEmpty(),
        state = parts.getOrNull(3).orEmpty().take(2).uppercase(Locale.ROOT)
    )
}

private suspend fun fetchIbgeCities(uf: String): List<String> = withContext(Dispatchers.IO) {
    val url = URL("https://servicodados.ibge.gov.br/api/v1/localidades/estados/${URLEncoder.encode(uf, "UTF-8")}/municipios?orderBy=nome")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 8000; readTimeout = 8000
        setRequestProperty("Accept", "application/json")
    }
    try {
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        buildList {
            for (i in 0 until array.length()) {
                val name = array.optJSONObject(i)?.optString("nome").orEmpty()
                if (name.isNotBlank()) add(name)
            }
        }
    } finally { connection.disconnect() }
}

private suspend fun fetchDistrictSuggestions(city: String, state: String): List<String> = withContext(Dispatchers.IO) {
    val query = URLEncoder.encode("$city, $state, Brasil", "UTF-8")
    val url = URL("https://photon.komoot.io/api/?q=$query&limit=50&lang=pt")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 7000; readTimeout = 7000
        setRequestProperty("User-Agent", "GeofenceWhatsAppApp/0.8")
        setRequestProperty("Accept", "application/json")
    }
    try {
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val features = body.optJSONArray("features") ?: return@withContext emptyList()
        buildSet {
            for (i in 0 until features.length()) {
                val p = features.optJSONObject(i)?.optJSONObject("properties") ?: continue
                val featureCity = p.optString("city").ifBlank { p.optString("county") }
                if (featureCity.isNotBlank() && !featureCity.equals(city, true)) continue
                listOf(p.optString("district"), p.optString("locality"), p.optString("name"))
                    .filter { it.isNotBlank() && !it.equals(city, true) }
                    .forEach { add(it) }
            }
        }.toList().sorted()
    } finally { connection.disconnect() }
}

private suspend fun searchPhotonAddressesV2(query: String): List<AddressSuggestionV2> = withContext(Dispatchers.IO) {
    val encoded = URLEncoder.encode(query, "UTF-8")
    val url = URL("https://photon.komoot.io/api/?q=$encoded&limit=8&lang=pt&lat=-14.235&lon=-51.9253")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 7000; readTimeout = 7000
        setRequestProperty("User-Agent", "GeofenceWhatsAppApp/0.8")
        setRequestProperty("Accept", "application/json")
    }
    try {
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val features = body.optJSONArray("features") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val p = feature.optJSONObject("properties") ?: JSONObject()
                val cc = p.optString("countrycode").lowercase(Locale.ROOT)
                if (cc.isNotBlank() && cc != "br") continue
                val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                val lon = coords.optDouble(0, Double.NaN)
                val lat = coords.optDouble(1, Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) continue
                val featureName = p.optString("name")
                val street = p.optString("street").ifBlank { featureName }
                val number = p.optString("housenumber")
                val district = p.optString("district").ifBlank { p.optString("locality") }
                val city = p.optString("city").ifBlank { p.optString("county") }
                val state = normalizeStateV2(p.optString("state"))
                val f = AddressFieldsV2(street, number, district, city, state)
                add(AddressSuggestionV2(lat, lon, f.display().ifBlank { featureName.ifBlank { query } }, featureName.ifBlank { street.ifBlank { city } }, f))
            }
        }.distinctBy { "${it.latitude},${it.longitude}" }
    } finally { connection.disconnect() }
}

private fun geocodeAddressV2(context: Context, query: String, callback: (Result<AddressSuggestionV2>) -> Unit) {
    val geocoder = Geocoder(context, Locale("pt", "BR"))
    fun deliver(address: Address?) {
        if (address == null) callback(Result.failure(IllegalArgumentException("Endereço não encontrado")))
        else callback(Result.success(address.toSuggestionV2(query)))
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

private fun reverseGeocodeV2(context: Context, latitude: Double, longitude: Double, callback: (AddressSuggestionV2?) -> Unit) {
    val geocoder = Geocoder(context, Locale("pt", "BR"))
    fun deliver(address: Address?) { callback(address?.toSuggestionV2("Minha localização atual")) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        geocoder.getFromLocation(latitude, longitude, 1) { addresses -> deliver(addresses.firstOrNull()) }
    } else {
        Thread {
            val result = runCatching { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }.getOrNull()
            Handler(Looper.getMainLooper()).post { deliver(result) }
        }.start()
    }
}

private fun Address.toSuggestionV2(fallback: String): AddressSuggestionV2 {
    val f = AddressFieldsV2(
        street = thoroughfare ?: featureName.orEmpty(),
        number = subThoroughfare.orEmpty(),
        district = subLocality.orEmpty(),
        city = locality ?: subAdminArea.orEmpty(),
        state = normalizeStateV2(adminArea.orEmpty())
    )
    return AddressSuggestionV2(
        latitude = latitude,
        longitude = longitude,
        label = f.display().ifBlank { getAddressLine(0) ?: fallback },
        shortName = thoroughfare ?: featureName ?: locality ?: "Local",
        fields = f
    )
}

private fun normalizeStateV2(value: String): String {
    val clean = value.trim()
    if (clean.length == 2) return clean.uppercase(Locale.ROOT)
    return BRAZIL_STATES_V2.firstOrNull { it.second.equals(clean, true) }?.first ?: clean.take(2).uppercase(Locale.ROOT)
}

private val BRAZIL_STATES_V2 = listOf(
    "AC" to "Acre", "AL" to "Alagoas", "AP" to "Amapá", "AM" to "Amazonas", "BA" to "Bahia",
    "CE" to "Ceará", "DF" to "Distrito Federal", "ES" to "Espírito Santo", "GO" to "Goiás",
    "MA" to "Maranhão", "MT" to "Mato Grosso", "MS" to "Mato Grosso do Sul", "MG" to "Minas Gerais",
    "PA" to "Pará", "PB" to "Paraíba", "PR" to "Paraná", "PE" to "Pernambuco", "PI" to "Piauí",
    "RJ" to "Rio de Janeiro", "RN" to "Rio Grande do Norte", "RS" to "Rio Grande do Sul",
    "RO" to "Rondônia", "RR" to "Roraima", "SC" to "Santa Catarina", "SP" to "São Paulo",
    "SE" to "Sergipe", "TO" to "Tocantins"
)
