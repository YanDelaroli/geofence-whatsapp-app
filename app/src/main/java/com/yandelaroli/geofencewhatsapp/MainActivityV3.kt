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

class MainActivityV3 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AppScreen() } }
    }
}

private data class StateItem(val uf: String, val name: String)
private data class Fields(val street: String="", val number: String="", val district: String="", val city: String="", val state: String="") {
    fun query() = listOf(street, number, district, city, state, "Brasil").filter { it.isNotBlank() }.joinToString(", ")
    fun display() = listOf(listOf(street, number).filter { it.isNotBlank() }.joinToString(", "), district, city, state).filter { it.isNotBlank() }.joinToString(" - ")
}
private data class Resolved(val lat: Double, val lon: Double, val label: String, val shortName: String, val fields: Fields)
private enum class Kind { CITY, DISTRICT, STREET }

@Composable
private fun AppScreen() {
    val context = LocalContext.current
    val store = remember { RuleStore(context) }
    val geofenceManager = remember { GeofenceManager(context) }
    val locationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val prefs = remember { context.getSharedPreferences("onboarding_preferences", Context.MODE_PRIVATE) }

    var rules by remember { mutableStateOf(store.load()) }
    var editId by remember { mutableStateOf<String?>(null) }
    var states by remember { mutableStateOf<List<StateItem>>(emptyList()) }
    var loadingStates by remember { mutableStateOf(true) }
    var state by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }; var citySelected by remember { mutableStateOf(false) }; var cityOptions by remember { mutableStateOf<List<String>>(emptyList()) }; var cityLoading by remember { mutableStateOf(false) }
    var district by remember { mutableStateOf("") }; var districtSelected by remember { mutableStateOf(false) }; var districtOptions by remember { mutableStateOf<List<String>>(emptyList()) }; var districtLoading by remember { mutableStateOf(false) }
    var street by remember { mutableStateOf("") }; var streetSelected by remember { mutableStateOf(false) }; var streetOptions by remember { mutableStateOf<List<String>>(emptyList()) }; var streetLoading by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }
    var selectedAddress by remember { mutableStateOf("") }; var lat by remember { mutableStateOf<Double?>(null) }; var lon by remember { mutableStateOf<Double?>(null) }
    var name by remember { mutableStateOf("") }; var radius by remember { mutableStateOf(150f) }; var phone by remember { mutableStateOf("") }; var message by remember { mutableStateOf("Estou chegando.") }; var status by remember { mutableStateOf("Escolha o estado e digite a cidade.") }
    var showAlways by remember { mutableStateOf(false) }; var firstRun by remember { mutableStateOf(!prefs.getBoolean("permission_intro_shown", false)) }; var permissionsStarted by remember { mutableStateOf(false) }

    fun fields() = Fields(street.trim(), number.trim(), district.trim(), city.trim(), state.trim())
    fun clearCoords() { lat = null; lon = null; selectedAddress = "" }
    fun clearForm() { editId=null; state=""; city=""; citySelected=false; district=""; districtSelected=false; street=""; streetSelected=false; number=""; cityOptions=emptyList(); districtOptions=emptyList(); streetOptions=emptyList(); clearCoords(); name=""; radius=150f; phone=""; message="Estou chegando." }
    fun hasFine() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBackground() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun locationEnabled(): Boolean { val m=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager; return if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.P)m.isLocationEnabled else runCatching{m.isProviderEnabled(LocationManager.GPS_PROVIDER)||m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)}.getOrDefault(false) }
    fun register(updated: List<GeofenceRule>, ok: String) { store.save(updated); rules=updated; geofenceManager.registerAll(updated){r->status=r.fold({ok},{"Não foi possível ativar as áreas: ${it.message ?: "erro"}"})} }
    fun apply(r: Resolved) { state=r.fields.state.ifBlank{state}; city=r.fields.city.ifBlank{city}; citySelected=city.isNotBlank(); district=r.fields.district.ifBlank{district}; districtSelected=district.isNotBlank(); street=r.fields.street.ifBlank{street}; streetSelected=street.isNotBlank(); number=r.fields.number.ifBlank{number}; lat=r.lat; lon=r.lon; selectedAddress=r.label; if(name.isBlank())name=r.shortName }
    fun edit(rule: GeofenceRule) { val p=parse(rule.address); editId=rule.id; state=p.state; city=p.city; citySelected=city.isNotBlank(); district=p.district; districtSelected=district.isNotBlank(); street=p.street; streetSelected=street.isNotBlank(); number=p.number; lat=rule.latitude; lon=rule.longitude; selectedAddress=rule.address; name=rule.name; radius=rule.radiusMeters; phone=rule.phone.filter(Char::isDigit).removePrefix("55").takeLast(11); message=rule.message }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if(!it) status="Ative notificações para receber o atalho do WhatsApp." }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { g -> if(g[Manifest.permission.ACCESS_FINE_LOCATION]==true){ if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q&&!hasBackground())showAlways=true; if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } else status="A localização precisa é necessária." }
    fun requestPermissions(){ when { !hasFine()->locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)); Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q&&!hasBackground()->showAlways=true; Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED->notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } }

    LaunchedEffect(Unit){ if(!permissionsStarted&&!firstRun){permissionsStarted=true;requestPermissions()}; loadingStates=true; states=runCatching{ibgeStates()}.getOrElse{emptyList()}; loadingStates=false }
    LaunchedEffect(city,state,citySelected){ val q=city.trim(); if(state.isBlank()||citySelected||q.length<3){cityOptions=emptyList();cityLoading=false;return@LaunchedEffect};delay(400);cityLoading=true;cityOptions=runCatching{placeNames(q,state,null,null,Kind.CITY)}.getOrElse{emptyList()};cityLoading=false }
    LaunchedEffect(district,state,city,citySelected,districtSelected){ val q=district.trim(); if(!citySelected||districtSelected||q.length<3){districtOptions=emptyList();districtLoading=false;return@LaunchedEffect};delay(400);districtLoading=true;districtOptions=runCatching{placeNames(q,state,city,null,Kind.DISTRICT)}.getOrElse{emptyList()};districtLoading=false }
    LaunchedEffect(street,state,city,district,citySelected,streetSelected){ val q=street.trim(); if(!citySelected||streetSelected||q.length<3){streetOptions=emptyList();streetLoading=false;return@LaunchedEffect};delay(400);streetLoading=true;streetOptions=runCatching{placeNames(q,state,city,district.takeIf{districtSelected},Kind.STREET)}.getOrElse{emptyList()};streetLoading=false }

    if(firstRun) AlertDialog(onDismissRequest={},title={Text("Permissões de localização")},text={Text("Para detectar sua chegada mesmo com o aplicativo fechado, mantenha a localização ligada e permita acesso o tempo todo. Este aviso aparece somente na primeira vez após instalar.")},confirmButton={Button(onClick={prefs.edit().putBoolean("permission_intro_shown",true).apply();firstRun=false;permissionsStarted=true;requestPermissions()}){Text("Continuar")}})
    if(showAlways) AlertDialog(onDismissRequest={showAlways=false},title={Text("Permita localização o tempo todo")},text={Text("Nas configurações do Android, escolha Localização → Permitir o tempo todo.")},confirmButton={Button(onClick={showAlways=false;context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}){Text("Abrir configurações")}},dismissButton={TextButton(onClick={showAlways=false}){Text("Agora não")}})

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Mensagem por localização",style=MaterialTheme.typography.headlineSmall)
        if(editId!=null) Card(Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp)){Text("Editando local salvo");TextButton(onClick={clearForm()}){Text("Cancelar edição")}}}
        CompactSelector("Estado",state,states.map{"${it.uf} - ${it.name}"},if(loadingStates)"Carregando estados do IBGE..." else "Escolha o estado",!loadingStates&&states.isNotEmpty()){choice->state=choice.substringBefore(" - ");city="";citySelected=false;district="";districtSelected=false;street="";streetSelected=false;number="";clearCoords()}
        Typeahead("Cidade",city,state.isNotBlank(),citySelected,cityLoading,cityOptions,"Cidades possíveis",onChange={city=it;citySelected=false;district="";districtSelected=false;street="";streetSelected=false;clearCoords()},onPick={city=it;citySelected=true;district="";districtSelected=false;street="";streetSelected=false;clearCoords()})
        Typeahead("Bairro",district,citySelected,districtSelected,districtLoading,districtOptions,"Bairros possíveis",onChange={district=it;districtSelected=false;street="";streetSelected=false;clearCoords()},onPick={district=it;districtSelected=true;street="";streetSelected=false;clearCoords()})
        Typeahead("Rua",street,citySelected,streetSelected,streetLoading,streetOptions,"Ruas possíveis",onChange={street=it;streetSelected=false;clearCoords()},onPick={street=it;streetSelected=true;clearCoords()})
        OutlinedTextField(number,{number=it.take(10);clearCoords()},Modifier.fillMaxWidth(),enabled=streetSelected,label={Text("Número")},singleLine=true)
        Button(onClick={if(state.isBlank()||!citySelected||!streetSelected)status="Escolha Estado, Cidade e Rua." else geocode(context,fields().query()){r->r.fold({apply(it);status="Endereço encontrado."},{status="Não encontrei esse endereço."})}},modifier=Modifier.fillMaxWidth()){Text("Buscar endereço")}
        Button(onClick={when{!hasFine()->locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION));!locationEnabled()->context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));else->{val token=CancellationTokenSource();locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY,token.token).addOnSuccessListener{l->if(l==null)status="Não foi possível obter sua localização." else reverse(context,l.latitude,l.longitude){r->if(r!=null)apply(r.copy(lat=l.latitude,lon=l.longitude))else{lat=l.latitude;lon=l.longitude;selectedAddress="Minha localização atual"};if(name.isBlank())name="Local atual";status="Localização atual selecionada."}}}}},modifier=Modifier.fillMaxWidth()){Text("Usar minha localização atual")}
        if(lat!=null&&lon!=null)Card(Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp)){Text("Local selecionado");Text(selectedAddress.ifBlank{fields().display()})}}
        Text("Raio: ${radius.toInt()} m");Slider(radius,{radius=it},valueRange=50f..1000f,steps=18)
        OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text("Nome do local")},singleLine=true)
        OutlinedTextField(phone,{raw->var d=raw.filter(Char::isDigit).take(13);if(d.startsWith("55")&&d.length>11)d=d.drop(2);phone=d.take(11)},Modifier.fillMaxWidth(),label={Text("WhatsApp (DDD + número)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),singleLine=true)
        OutlinedTextField(message,{message=it},Modifier.fillMaxWidth(),label={Text("Mensagem")},minLines=3)
        Button(onClick={val la=lat;val lo=lon;val p=phone.filter(Char::isDigit);when{la==null||lo==null->status="Busque ou selecione um endereço.";name.isBlank()->status="Dê um nome ao local.";p.length !in 10..11->status="Informe um WhatsApp válido.";message.isBlank()->status="Digite a mensagem.";else->{val id=editId?:UUID.randomUUID().toString();val old=rules.firstOrNull{it.id==id};val rule=GeofenceRule(id,name.trim(),fields().display().ifBlank{selectedAddress},la,lo,radius,"55$p",message.trim(),old?.enabled?:true);if(editId==null)register(rules+rule,"Local salvo e ativado.")else register(rules.map{if(it.id==id)rule else it},"Alterações salvas.");clearForm()}}},modifier=Modifier.fillMaxWidth()){Text(if(editId==null)"Salvar e ativar este local" else "Salvar alterações")}
        Text(status)
        if(rules.isNotEmpty()){Spacer(Modifier.height(4.dp));Text("Locais salvos",style=MaterialTheme.typography.titleLarge)}
        rules.forEach{r->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(r.name,style=MaterialTheme.typography.titleMedium);Text(r.address);Text("Raio: ${r.radiusMeters.toInt()} m • WhatsApp: ${r.phone.removePrefix("55")}");Text(r.message);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Text(if(r.enabled)"Ativo" else "Desativado");Switch(r.enabled,{e->register(rules.map{if(it.id==r.id)it.copy(enabled=e)else it},"Regra atualizada.")})};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={edit(r)}){Text("Editar")};Button(onClick={if(editId==r.id)clearForm();register(rules.filterNot{it.id==r.id},"Local excluído.")}){Text("Excluir")}}}}}
    }
}

@Composable private fun Typeahead(label:String,value:String,enabled:Boolean,selected:Boolean,loading:Boolean,options:List<String>,title:String,onChange:(String)->Unit,onPick:(String)->Unit){OutlinedTextField(value,onChange,Modifier.fillMaxWidth(),enabled=enabled,label={Text(label)},supportingText={Text(when{!enabled->"Complete a etapa anterior";selected->"$label selecionado";loading->"Buscando...";value.trim().length in 1..2->"Digite pelo menos 3 letras";else->"Digite as primeiras letras e escolha abaixo"})},singleLine=true);if(enabled&&!selected&&value.trim().length>=3)Card(Modifier.fillMaxWidth()){Column{Text(title,style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(10.dp));when{loading->Text("Buscando...",Modifier.padding(10.dp));options.isEmpty()->Text("Nenhum resultado. Digite mais letras.",Modifier.padding(10.dp));else->options.forEachIndexed{i,o->TextButton(onClick={onPick(o)},modifier=Modifier.fillMaxWidth()){Text(o,modifier=Modifier.fillMaxWidth())};if(i!=options.lastIndex)HorizontalDivider()}}}}}
@Composable private fun CompactSelector(label:String,selected:String,options:List<String>,placeholder:String,enabled:Boolean,onSelect:(String)->Unit){var expanded by remember{mutableStateOf(false)};Column{Text(label,style=MaterialTheme.typography.labelMedium);Box(Modifier.fillMaxWidth()){OutlinedButton(onClick={if(enabled)expanded=true},modifier=Modifier.fillMaxWidth(),enabled=enabled){Text(selected.ifBlank{placeholder})};DropdownMenu(expanded,{expanded=false}){options.forEach{o->DropdownMenuItem(text={Text(o)},onClick={expanded=false;onSelect(o)})}}}}}

private fun parse(a:String):Fields{val p=a.split(" - ").map{it.trim()};val s=p.getOrNull(0)?.split(",")?.map{it.trim()}.orEmpty();return Fields(s.getOrNull(0).orEmpty(),s.getOrNull(1).orEmpty(),p.getOrNull(1).orEmpty(),p.getOrNull(2).orEmpty(),p.getOrNull(3).orEmpty().take(2).uppercase(Locale.ROOT))}
private suspend fun ibgeStates(): List<StateItem> = withContext(Dispatchers.IO){val c=URL("https://servicodados.ibge.gov.br/api/v1/localidades/estados?orderBy=nome").openConnection() as HttpURLConnection;try{c.connectTimeout=8000;c.readTimeout=8000;if(c.responseCode !in 200..299)error("HTTP ${c.responseCode}");val a=JSONArray(c.inputStream.bufferedReader().use{it.readText()});buildList{for(i in 0 until a.length()){val o=a.optJSONObject(i)?:continue;val uf=o.optString("sigla");val n=o.optString("nome");if(uf.isNotBlank()&&n.isNotBlank())add(StateItem(uf,n))}}}finally{c.disconnect()}}
private suspend fun placeNames(typed:String,state:String,city:String?,district:String?,kind:Kind): List<String> = withContext(Dispatchers.IO){val q=listOfNotNull(typed,district,city,state,"Brasil").filter{it.isNotBlank()}.joinToString(", ");val layers=when(kind){Kind.CITY->"&layer=city&layer=locality";Kind.DISTRICT->"&layer=district&layer=locality";Kind.STREET->"&layer=street"};val c=URL("https://photon.komoot.io/api/?q=${URLEncoder.encode(q,"UTF-8")}&limit=15&lang=pt&countrycode=BR$layers").openConnection() as HttpURLConnection;try{c.connectTimeout=7000;c.readTimeout=7000;c.setRequestProperty("User-Agent","GeofenceWhatsAppApp/1.1");if(c.responseCode !in 200..299)error("HTTP ${c.responseCode}");val f=JSONObject(c.inputStream.bufferedReader().use{it.readText()}).optJSONArray("features")?:return@withContext emptyList();buildSet{for(i in 0 until f.length()){val p=f.optJSONObject(i)?.optJSONObject("properties")?:continue;val rs=uf(p.optString("state"));if(rs.isNotBlank()&&!rs.equals(state,true))continue;val rc=p.optString("city").ifBlank{p.optString("county")};if(city!=null&&rc.isNotBlank()&&!rc.equals(city,true))continue;val candidate=when(kind){Kind.CITY->p.optString("city").ifBlank{p.optString("name")}.ifBlank{p.optString("locality")};Kind.DISTRICT->p.optString("district").ifBlank{p.optString("locality")}.ifBlank{p.optString("name")};Kind.STREET->p.optString("street").ifBlank{p.optString("name")}};if(candidate.isNotBlank()&&candidate.startsWith(typed,true))add(candidate)}}.toList().sorted()}finally{c.disconnect()}}
private suspend fun addressSearch(q:String): List<Resolved> = withContext(Dispatchers.IO){val c=URL("https://photon.komoot.io/api/?q=${URLEncoder.encode(q,"UTF-8")}&limit=8&lang=pt&countrycode=BR").openConnection() as HttpURLConnection;try{c.connectTimeout=7000;c.readTimeout=7000;c.setRequestProperty("User-Agent","GeofenceWhatsAppApp/1.1");if(c.responseCode !in 200..299)error("HTTP ${c.responseCode}");val f=JSONObject(c.inputStream.bufferedReader().use{it.readText()}).optJSONArray("features")?:return@withContext emptyList();buildList{for(i in 0 until f.length()){val x=f.optJSONObject(i)?:continue;val p=x.optJSONObject("properties")?:JSONObject();val co=x.optJSONObject("geometry")?.optJSONArray("coordinates")?:continue;val lo=co.optDouble(0,Double.NaN);val la=co.optDouble(1,Double.NaN);if(!la.isFinite()||!lo.isFinite())continue;val n=p.optString("name");val fs=Fields(p.optString("street").ifBlank{n},p.optString("housenumber"),p.optString("district").ifBlank{p.optString("locality")},p.optString("city").ifBlank{p.optString("county")},uf(p.optString("state")));add(Resolved(la,lo,fs.display().ifBlank{n.ifBlank{q}},n.ifBlank{fs.street.ifBlank{fs.city}},fs))}}}finally{c.disconnect()}}
private fun geocode(context:Context,q:String,cb:(Result<Resolved>)->Unit){val g=Geocoder(context,Locale("pt","BR"));fun d(a:Address?){if(a==null)cb(Result.failure(IllegalArgumentException("Endereço não encontrado")))else cb(Result.success(a.resolved(q)))};if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU)g.getFromLocationName(q,1){d(it.firstOrNull())}else Thread{val r=runCatching{g.getFromLocationName(q,1)?.firstOrNull()}.getOrNull();Handler(Looper.getMainLooper()).post{d(r)}}.start()}
private fun reverse(context:Context,la:Double,lo:Double,cb:(Resolved?)->Unit){val g=Geocoder(context,Locale("pt","BR"));fun d(a:Address?){cb(a?.resolved("Minha localização atual"))};if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU)g.getFromLocation(la,lo,1){d(it.firstOrNull())}else Thread{val r=runCatching{g.getFromLocation(la,lo,1)?.firstOrNull()}.getOrNull();Handler(Looper.getMainLooper()).post{d(r)}}.start()}
private fun Address.resolved(fallback:String):Resolved{val f=Fields(thoroughfare?:featureName.orEmpty(),subThoroughfare.orEmpty(),subLocality.orEmpty(),locality?:subAdminArea.orEmpty(),uf(adminArea.orEmpty()));return Resolved(latitude,longitude,f.display().ifBlank{getAddressLine(0)?:fallback},thoroughfare?:featureName?:locality?:"Local",f)}
private fun uf(v:String):String{val c=v.trim();if(c.length==2)return c.uppercase(Locale.ROOT);return STATE_MAP[c.lowercase(Locale.ROOT)]?:""}
private val STATE_MAP=mapOf("acre" to "AC","alagoas" to "AL","amapá" to "AP","amazonas" to "AM","bahia" to "BA","ceará" to "CE","distrito federal" to "DF","espírito santo" to "ES","goiás" to "GO","maranhão" to "MA","mato grosso" to "MT","mato grosso do sul" to "MS","minas gerais" to "MG","pará" to "PA","paraíba" to "PB","paraná" to "PR","pernambuco" to "PE","piauí" to "PI","rio de janeiro" to "RJ","rio grande do norte" to "RN","rio grande do sul" to "RS","rondônia" to "RO","roraima" to "RR","santa catarina" to "SC","são paulo" to "SP","sergipe" to "SE","tocantins" to "TO")