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

private data class IbgeState(val id: Int, val uf: String, val name: String)
private data class AddressFieldsV2(
    val street: String = "", val number: String = "", val district: String = "", val city: String = "", val state: String = ""
) {
    fun query() = listOf(street, number, district, city, state, "Brasil").filter { it.isNotBlank() }.joinToString(", ")
    fun display() = listOf(listOf(street, number).filter { it.isNotBlank() }.joinToString(", "), district, city, state)
        .filter { it.isNotBlank() }.joinToString(" - ")
}
private data class AddressSuggestionV2(
    val latitude: Double, val longitude: Double, val label: String, val shortName: String, val fields: AddressFieldsV2
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
    var states by remember { mutableStateOf<List<IbgeState>>(emptyList()) }
    var loadingStates by remember { mutableStateOf(true) }

    var state by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var citySelected by remember { mutableStateOf(false) }
    var citySuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingCities by remember { mutableStateOf(false) }

    var district by remember { mutableStateOf("") }
    var districtSelected by remember { mutableStateOf(false) }
    var districtSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingDistricts by remember { mutableStateOf(false) }

    var street by remember { mutableStateOf("") }
    var streetSelected by remember { mutableStateOf(false) }
    var streetSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingStreets by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }

    var selectedAddress by remember { mutableStateOf("") }
    var selectedLatitude by remember { mutableStateOf<Double?>(null) }
    var selectedLongitude by remember { mutableStateOf<Double?>(null) }
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(150f) }
    var phone by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Estou chegando.") }
    var status by remember { mutableStateOf("Escolha o estado e digite a cidade.") }
    var showAlwaysLocationDialog by remember { mutableStateOf(false) }
    var startupPermissionsRequested by remember { mutableStateOf(false) }
    var showFirstRunPermissionDialog by remember { mutableStateOf(!onboardingPrefs.getBoolean("permission_intro_shown", false)) }

    fun fields() = AddressFieldsV2(street.trim(), number.trim(), district.trim(), city.trim(), state.trim())
    fun clearAddressSelection() { selectedAddress = ""; selectedLatitude = null; selectedLongitude = null }
    fun clearForm() {
        editingRuleId = null
        state = ""; city = ""; citySelected = false; district = ""; districtSelected = false; street = ""; streetSelected = false; number = ""
        citySuggestions = emptyList(); districtSuggestions = emptyList(); streetSuggestions = emptyList()
        clearAddressSelection(); name = ""; radius = 150f; phone = ""; message = "Estou chegando."
    }
    fun hasFineLocation() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBackgroundLocation() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun isLocationEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled else runCatching {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    }
    fun openAppSettings() = context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    fun persistAndRegister(updated: List<GeofenceRule>, successMessage: String) {
        store.save(updated); rules = updated
        geofenceManager.registerAll(updated) { result -> status = result.fold({ successMessage }, { "Não foi possível ativar as áreas: ${it.message ?: "erro desconhecido"}" }) }
    }
    fun applySuggestion(s: AddressSuggestionV2) {
        state = s.fields.state.ifBlank { state }; city = s.fields.city.ifBlank { city }; citySelected = city.isNotBlank()
        district = s.fields.district.ifBlank { district }; districtSelected = district.isNotBlank()
        street = s.fields.street.ifBlank { street }; streetSelected = street.isNotBlank(); number = s.fields.number.ifBlank { number }
        selectedAddress = s.label; selectedLatitude = s.latitude; selectedLongitude = s.longitude
        if (name.isBlank()) name = s.shortName
    }
    fun loadRuleForEditing(rule: GeofenceRule) {
        val p = parseStoredAddressV2(rule.address)
        editingRuleId = rule.id; state = p.state; city = p.city; citySelected = city.isNotBlank(); district = p.district; districtSelected = district.isNotBlank(); street = p.street; streetSelected = street.isNotBlank(); number = p.number
        selectedAddress = rule.address; selectedLatitude = rule.latitude; selectedLongitude = rule.longitude
        name = rule.name; radius = rule.radiusMeters; phone = rule.phone.filter(Char::isDigit).removePrefix("55").takeLast(11); message = rule.message
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (!it) status = "Ative notificações para receber o atalho do WhatsApp." }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation()) showAlwaysLocationDialog = true
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else status = "A localização precisa é necessária para o funcionamento do app."
    }
    fun requestInitialPermissions() {
        when {
            !hasFineLocation() -> locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation() -> showAlwaysLocationDialog = true
            Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        if (!startupPermissionsRequested && !showFirstRunPermissionDialog) { startupPermissionsRequested = true; requestInitialPermissions() }
        loadingStates = true; states = runCatching { fetchIbgeStates() }.getOrElse { emptyList() }; loadingStates = false
    }
    LaunchedEffect(city, state, citySelected) {
        val typed = city.trim()
        if (state.isBlank() || citySelected || typed.length < 3) { citySuggestions = emptyList(); loadingCities = false; return@LaunchedEffect }
        delay(400); loadingCities = true; citySuggestions = runCatching { searchPlaceNames(typed, state, null, null, SearchKind.CITY) }.getOrElse { emptyList() }; loadingCities = false
    }
    LaunchedEffect(district, city, state, citySelected, districtSelected) {
        val typed = district.trim()
        if (!citySelected || districtSelected || typed.length < 3) { districtSuggestions = emptyList(); loadingDistricts = false; return@LaunchedEffect }
        delay(400); loadingDistricts = true; districtSuggestions = runCatching { searchPlaceNames(typed, state, city, null, SearchKind.DISTRICT) }.getOrElse { emptyList() }; loadingDistricts = false
    }
    LaunchedEffect(street, district, city, state, citySelected, streetSelected) {
        val typed = street.trim()
        if (!citySelected || streetSelected || typed.length < 3) { streetSuggestions = emptyList(); loadingStreets = false; return@LaunchedEffect }
        delay(400); loadingStreets = true; streetSuggestions = runCatching { searchPlaceNames(typed, state, city, district.takeIf { districtSelected }, SearchKind.STREET) }.getOrElse { emptyList() }; loadingStreets = false
    }
    LaunchedEffect(number, streetSelected, districtSelected, citySelected) {
        if (number.isBlank() || !streetSelected || !citySelected) return@LaunchedEffect
        delay(450)
        runCatching { searchPhotonAddressesV2(fields().query()) }.getOrNull()?.firstOrNull()?.let { applySuggestion(it) }
    }

    if (showFirstRunPermissionDialog) {
        AlertDialog(onDismissRequest = {}, title = { Text("Permissões de localização") }, text = { Text("Para detectar sua chegada mesmo com o aplicativo fechado, mantenha a localização do celular ligada e permita acesso à localização o tempo todo. Este aviso aparece somente na primeira vez após instalar o aplicativo.") }, confirmButton = {
            Button(onClick = { onboardingPrefs.edit().putBoolean("permission_intro_shown", true).apply(); showFirstRunPermissionDialog = false; startupPermissionsRequested = true; requestInitialPermissions() }) { Text("Continuar") }
        })
    }
    if (showAlwaysLocationDialog) {
        AlertDialog(onDismissRequest = { showAlwaysLocationDialog = false }, title = { Text("Permita localização o tempo todo") }, text = { Text("Nas configurações do Android, escolha Localização → Permitir o tempo todo e mantenha a localização do aparelho ligada.") }, confirmButton = { Button(onClick = { showAlwaysLocationDialog = false; openAppSettings() }) { Text("Abrir configurações") } }, dismissButton = { TextButton(onClick = { showAlwaysLocationDialog = false }) { Text("Agora não") } })
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Mensagem por localização", style = MaterialTheme.typography.headlineSmall)
        if (editingRuleId != null) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text("Editando local salvo", style = MaterialTheme.typography.titleMedium); TextButton(onClick = { clearForm(); status = "Edição cancelada." }) { Text("Cancelar edição") } } }
        Text("Endereço", style = MaterialTheme.typography.titleMedium)

        CompactSelector("Estado", state, states.map { "${it.uf} - ${it.name}" }, if (loadingStates) "Carregando estados do IBGE..." else "Escolha o estado", !loadingStates && states.isNotEmpty()) { choice ->
            state = choice.substringBefore(" - "); city = ""; citySelected = false; district = ""; districtSelected = false; street = ""; streetSelected = false; number = ""; clearAddressSelection()
        }

        SearchField("Cidade", city, state.isNotBlank(), citySelected, loadingCities, citySuggestions, "Cidades possíveis") { text ->
            city = text; citySelected = false; district = ""; districtSelected = false; street = ""; streetSelected = false; clearAddressSelection()
        } onPick@{ pick -> city = pick; citySelected = true; district = ""; districtSelected = false; street = ""; streetSelected = false; clearAddressSelection() }

        SearchField("Bairro", district, citySelected, districtSelected, loadingDistricts, districtSuggestions, "Bairros possíveis") { text ->
            district = text; districtSelected = false; street = ""; streetSelected = false; clearAddressSelection()
        } onPick@{ pick -> district = pick; districtSelected = true; street = ""; streetSelected = false; clearAddressSelection() }

        SearchField("Rua", street, citySelected, streetSelected, loadingStreets, streetSuggestions, "Ruas possíveis") { text ->
            street = text; streetSelected = false; clearAddressSelection()
        } onPick@{ pick -> street = pick; streetSelected = true; clearAddressSelection() }

        OutlinedTextField(number, { number = it.take(10); clearAddressSelection() }, Modifier.fillMaxWidth(), enabled = streetSelected, label = { Text("Número") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)

        Button(onClick = {
            if (state.isBlank() || !citySelected || !streetSelected) status = "Escolha Estado, Cidade e Rua."
            else geocodeAddressV2(context, fields().query()) { result -> result.fold({ applySuggestion(it); status = "Endereço encontrado." }, { status = "Não encontrei esse endereço." }) }
        }, modifier = Modifier.fillMaxWidth()) { Text("Buscar endereço") }

        Button(onClick = {
            when {
                !hasFineLocation() -> locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                !isLocationEnabled() -> context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                else -> {
                    val token = CancellationTokenSource()
                    locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token).addOnSuccessListener { location ->
                        if (location == null) status = "Não foi possível obter sua localização." else reverseGeocodeV2(context, location.latitude, location.longitude) { s ->
                            if (s != null) applySuggestion(s.copy(latitude = location.latitude, longitude = location.longitude)) else { selectedLatitude = location.latitude; selectedLongitude = location.longitude; selectedAddress = "Minha localização atual" }
                            if (name.isBlank()) name = "Local atual"; status = "Localização atual selecionada."
                        }
                    }
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Usar minha localização atual") }

        if (selectedLatitude != null && selectedLongitude != null) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text("Local selecionado", style = MaterialTheme.typography.titleMedium); Text(selectedAddress.ifBlank { fields().display() }) } }

        Text("Raio: ${radius.toInt()} m"); Slider(radius, { radius = it }, valueRange = 50f..1000f, steps = 18)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nome do local") }, singleLine = true)
        OutlinedTextField(phone, { raw -> var d = raw.filter(Char::isDigit).take(13); if (d.startsWith("55") && d.length > 11) d = d.drop(2); phone = d.take(11) }, Modifier.fillMaxWidth(), label = { Text("WhatsApp (DDD + número)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
        OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), label = { Text("Mensagem") }, minLines = 3)

        Button(onClick = {
            val lat = selectedLatitude; val lon = selectedLongitude; val localPhone = phone.filter(Char::isDigit)
            when {
                !hasFineLocation() -> status = "Autorize a localização primeiro."
                lat == null || lon == null -> status = "Busque ou selecione um endereço."
                name.isBlank() -> status = "Dê um nome ao local."
                localPhone.length !in 10..11 -> status = "Informe um WhatsApp válido."
                message.isBlank() -> status = "Digite a mensagem."
                else -> {
                    val id = editingRuleId ?: UUID.randomUUID().toString(); val existing = rules.firstOrNull { it.id == id }
                    val updated = GeofenceRule(id, name.trim(), fields().display().ifBlank { selectedAddress }, lat, lon, radius, "55$localPhone", message.trim(), existing?.enabled ?: true)
                    if (editingRuleId == null) persistAndRegister(rules + updated, "Local salvo e ativado.") else persistAndRegister(rules.map { if (it.id == id) updated else it }, "Alterações salvas.")
                    clearForm()
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (editingRuleId == null) "Salvar e ativar este local" else "Salvar alterações") }

        Text(status)
        if (rules.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text("Locais salvos", style = MaterialTheme.typography.titleLarge) }
        rules.forEach { rule ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(rule.name, style = MaterialTheme.typography.titleMedium); Text(rule.address); Text("Raio: ${rule.radiusMeters.toInt()} m • WhatsApp: ${rule.phone.removePrefix("55")}"); Text(rule.message)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text(if (rule.enabled) "Ativo" else "Desativado"); Switch(rule.enabled, { enabled -> persistAndRegister(rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }, "Regra atualizada.") }) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { loadRuleForEditing(rule) }) { Text("Editar") }; Button(onClick = { if (editingRuleId == rule.id) clearForm(); persistAndRegister(rules.filterNot { it.id == rule.id }, "Local excluído.") }) { Text("Excluir") } }
            } }
        }
    }
}

@Composable
private fun SearchField(label: String, value: String, enabled: Boolean, selected: Boolean, loading: Boolean, options: List<String>, title: String, onChange: (String) -> Unit, onPick: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, label = { Text(label) }, supportingText = { Text(when { !enabled -> "Complete a etapa anterior"; selected -> "$label selecionado"; loading -> "Buscando..."; value.trim().length in 1..2 -> "Digite pelo menos 3 letras"; else -> "Digite as primeiras letras e escolha abaixo" }) }, singleLine = true)
    if (enabled && !selected && value.trim().length >= 3) Card(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(10.dp))
        when { loading -> Text("Buscando...", Modifier.padding(10.dp)); options.isEmpty() -> Text("Nenhum resultado. Digite mais letras.", Modifier.padding(10.dp)); else -> options.forEachIndexed { i, option -> TextButton(onClick = { onPick(option) }, modifier = Modifier.fillMaxWidth()) { Text(option, modifier = Modifier.fillMaxWidth()) }; if (i != options.lastIndex) HorizontalDivider() } }
    } }
}

@Composable
private fun CompactSelector(label: String, selected: String, options: List<String>, placeholder: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(label, style = MaterialTheme.typography.labelMedium); Box(Modifier.fillMaxWidth()) { OutlinedButton(onClick = { if (enabled) expanded = true }, modifier = Modifier.fillMaxWidth(), enabled = enabled) { Text(selected.ifBlank { placeholder }) }; DropdownMenu(expanded, { expanded = false }) { options.distinct().filter { it.isNotBlank() }.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelect(option) }) } } } }
}

private enum class SearchKind { CITY, DISTRICT, STREET }
private fun parseStoredAddressV2(address: String): AddressFieldsV2 {
    val parts = address.split(" - ").map { it.trim() }; val streetParts = parts.getOrNull(0)?.split(",")?.map { it.trim() }.orEmpty()
    return AddressFieldsV2(streetParts.getOrNull(0).orEmpty(), streetParts.getOrNull(1).orEmpty(), parts.getOrNull(1).orEmpty(), parts.getOrNull(2).orEmpty(), parts.getOrNull(3).orEmpty().take(2).uppercase(Locale.ROOT))
}
private suspend fun fetchIbgeStates(): List<IbgeState> = withContext(Dispatchers.IO) {
    val c = (URL("https://servicodados.ibge.gov.br/api/v1/localidades/estados?orderBy=nome").openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
    try { if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}"); val a = JSONArray(c.inputStream.bufferedReader().use { it.readText() }); buildList { for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; val uf = o.optString("sigla"); val n = o.optString("nome"); if (uf.isNotBlank() && n.isNotBlank()) add(IbgeState(o.optInt("id"), uf, n)) } } } finally { c.disconnect() }
}
private suspend fun searchPlaceNames(typed: String, state: String, city: String?, district: String?, kind: SearchKind): List<String> = withContext(Dispatchers.IO) {
    val context = listOfNotNull(typed, district, city, state, "Brasil").filter { it.isNotBlank() }.joinToString(", ")
    val layers = when (kind) { SearchKind.CITY -> "&layer=city&layer=locality"; SearchKind.DISTRICT -> "&layer=district&layer=locality"; SearchKind.STREET -> "&layer=street" }
    val url = URL("https://photon.komoot.io/api/?q=${URLEncoder.encode(context, "UTF-8")}&limit=15&lang=pt&countrycode=BR$layers")
    val c = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 7000; readTimeout = 7000; setRequestProperty("User-Agent", "GeofenceWhatsAppApp/1.0") }
    try {
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        val f = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("features") ?: return@withContext emptyList()
        buildSet {
            for (i in 0 until f.length()) {
                val p = f.optJSONObject(i)?.optJSONObject("properties") ?: continue
                val rs = normalizeStateV2(p.optString("state")); if (rs.isNotBlank() && !rs.equals(state, true)) continue
                val rc = p.optString("city").ifBlank { p.optString("county") }; if (city != null && rc.isNotBlank() && !rc.equals(city, true)) continue
                val candidate = when (kind) { SearchKind.CITY -> p.optString("city").ifBlank { p.optString("name") }.ifBlank { p.optString("locality") }; SearchKind.DISTRICT -> p.optString("district").ifBlank { p.optString("locality") }.ifBlank { p.optString("name") }; SearchKind.STREET -> p.optString("street").ifBlank { p.optString("name") } }
                if (candidate.isNotBlank() && candidate.startsWith(typed, true)) add(candidate)
            }
        }.toList().sorted()
    } finally { c.disconnect() }
}
private suspend fun searchPhotonAddressesV2(query: String): List<AddressSuggestionV2> = withContext(Dispatchers.IO) {
    val c = (URL("https://photon.komoot.io/api/?q=${URLEncoder.encode(query, "UTF-8")}&limit=8&lang=pt&countrycode=BR").openConnection() as HttpURLConnection).apply { connectTimeout = 7000; readTimeout = 7000; setRequestProperty("User-Agent", "GeofenceWhatsAppApp/1.0") }
    try { if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}"); val f = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("features") ?: return@withContext emptyList(); buildList { for (i in 0 until f.length()) { val feature = f.optJSONObject(i) ?: continue; val p = feature.optJSONObject("properties") ?: JSONObject(); val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue; val lon = coords.optDouble(0, Double.NaN); val lat = coords.optDouble(1, Double.NaN); if (!lat.isFinite() || !lon.isFinite()) continue; val fn = p.optString("name"); val fields = AddressFieldsV2(p.optString("street").ifBlank { fn }, p.optString("housenumber"), p.optString("district").ifBlank { p.optString("locality") }, p.optString("city").ifBlank { p.optString("county") }, normalizeStateV2(p.optString("state"))); add(AddressSuggestionV2(lat, lon, fields.display().ifBlank { fn.ifBlank { query } }, fn.ifBlank { fields.street.ifBlank { fields.city } }, fields)) } }.distinctBy { "${it.latitude},${it.longitude}" } } finally { c.disconnect() }
}
private fun geocodeAddressV2(context: Context, query: String, callback: (Result<AddressSuggestionV2>) -> Unit) {
    val g = Geocoder(context, Locale("pt", "BR")); fun deliver(a: Address?) { if (a == null) callback(Result.failure(IllegalArgumentException("Endereço não encontrado"))) else callback(Result.success(a.toSuggestionV2(query))) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) g.getFromLocationName(query, 1) { deliver(it.firstOrNull()) } else Thread { val r = runCatching { g.getFromLocationName(query, 1)?.firstOrNull() }.getOrNull(); Handler(Looper.getMainLooper()).post { deliver(r) } }.start()
}
private fun reverseGeocodeV2(context: Context, latitude: Double, longitude: Double, callback: (AddressSuggestionV2?) -> Unit) {
    val g = Geocoder(context, Locale("pt", "BR")); fun deliver(a: Address?) { callback(a?.toSuggestionV2("Minha localização atual")) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) g.getFromLocation(latitude, longitude, 1) { deliver(it.firstOrNull()) } else Thread { val r = runCatching { g.getFromLocation(latitude, longitude, 1)?.firstOrNull() }.getOrNull(); Handler(Looper.getMainLooper()).post { deliver(r) } }.start()
}
private fun Address.toSuggestionV2(fallback: String): AddressSuggestionV2 {
    val f = AddressFieldsV2(thoroughfare ?: featureName.orEmpty(), subThoroughfare.orEmpty(), subLocality.orEmpty(), locality ?: subAdminArea.orEmpty(), normalizeStateV2(adminArea.orEmpty()))
    return AddressSuggestionV2(latitude, longitude, f.display().ifBlank { getAddressLine(0) ?: fallback }, thoroughfare ?: featureName ?: locality ?: "Local", f)
}
private fun normalizeStateV2(value: String): String { val c = value.trim(); if (c.length == 2) return c.uppercase(Locale.ROOT); return STATE_NAME_TO_UF[c.lowercase(Locale.ROOT)] ?: "" }
private val STATE_NAME_TO_UF = mapOf("acre" to "AC", "alagoas" to "AL", "amapá" to "AP", "amazonas" to "AM", "bahia" to "BA", "ceará" to "CE", "distrito federal" to "DF", "espírito santo" to "ES", "goiás" to "GO", "maranhão" to "MA", "mato grosso" to "MT", "mato grosso do sul" to "MS", "minas gerais" to "MG", "pará" to "PA", "paraíba" to "PB", "paraná" to "PR", "pernambuco" to "PE", "piauí" to "PI", "rio de janeiro" to "RJ", "rio grande do norte" to "RN", "rio grande do sul" to "RS", "rondônia" to "RO", "roraima" to "RR", "santa catarina" to "SC", "são paulo" to "SP", "sergipe" to "SE", "tocantins" to "TO")
