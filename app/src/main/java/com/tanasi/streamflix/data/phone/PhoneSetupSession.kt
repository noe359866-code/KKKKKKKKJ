package com.tanasi.streamflix.data.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import com.tanasi.streamflix.data.remote.Urls
import com.tanasi.streamflix.data.stremio.StremioUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One short-lived, TV-confirmed local session for phone-assisted setup. */
class PhoneSetupSession(
    context: Context,
    private val currentSnapshot: () -> PhoneSetupSnapshot,
) {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(PhoneSetupSessionState())
    val state: StateFlow<PhoneSetupSessionState> = mutableState.asStateFlow()

    private var server: PhoneSetupHttpServer? = null
    private var expiryJob: Job? = null
    @Volatile private var lifecycleId = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun start() {
        stop()
        val thisLifecycle = lifecycleId
        mutableState.value = PhoneSetupSessionState(isStarting = true)
        try {
            val (activeServer, url, bitmap) = withContext(Dispatchers.IO) {
                val ip = deviceIpv4(appContext)
                    ?: throw IllegalStateException("No se encontró una dirección IPv4 de la TV. Comprueba que esté conectada a la red.")
                val token = randomToken()
                var started: PhoneSetupHttpServer? = null
                for (port in FIRST_PORT until FIRST_PORT + PORT_ATTEMPTS) {
                    val candidate = PhoneSetupHttpServer(
                        host = ip,
                        port = port,
                        sessionToken = token,
                        currentSnapshot = currentSnapshot,
                        onProposal = ::onProposal,
                    )
                    try {
                        candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                        started = candidate
                        break
                    } catch (_: Exception) {
                        candidate.stop()
                    }
                }
                val httpServer = started
                    ?: throw IllegalStateException("No se pudo abrir el servidor local. Cierra otras sesiones e inténtalo de nuevo.")
                val sessionUrl = "http://$ip:${httpServer.listeningPort}/s/$token/"
                try {
                    Triple(httpServer, sessionUrl, generateQr(sessionUrl))
                } catch (error: Exception) {
                    httpServer.stop()
                    throw error
                }
            }
            if (thisLifecycle != lifecycleId) {
                activeServer.stop()
                return
            }
            server = activeServer
            mutableState.value = PhoneSetupSessionState(
                isActive = true,
                serverUrl = url,
                qrBitmap = bitmap,
            )
            expiryJob = scope.launch {
                delay(SESSION_TTL_MS)
                expireSession()
            }
        } catch (cancelled: CancellationException) {
            if (thisLifecycle == lifecycleId) stop()
            throw cancelled
        } catch (error: Exception) {
            if (thisLifecycle == lifecycleId) {
                mutableState.value = PhoneSetupSessionState(error = error.message ?: "No se pudo iniciar el modo QR.")
            }
        }
    }

    fun confirmChange(id: String, message: String) {
        server?.setStatus(id, STATUS_APPLIED, message)
        mutableState.update { current ->
            if (current.pendingChange?.id == id) current.copy(pendingChange = null, lastResult = message) else current
        }
    }

    fun rejectChange(id: String) {
        server?.setStatus(id, STATUS_REJECTED, "El cambio fue rechazado en la TV.")
        mutableState.update { current ->
            if (current.pendingChange?.id == id) current.copy(pendingChange = null, lastResult = "Cambio rechazado.") else current
        }
    }

    fun stop() {
        lifecycleId++
        expiryJob?.cancel()
        expiryJob = null
        server?.stop()
        server = null
        mutableState.value = PhoneSetupSessionState()
    }

    private fun expireSession() {
        lifecycleId++
        expiryJob = null
        server?.stop()
        server = null
        mutableState.value = PhoneSetupSessionState(error = "La sesión QR expiró. Cierra esta ventana para generar un nuevo código.")
    }

    private fun onProposal(change: PhoneSetupChange) {
        mutableState.update { current ->
            if (!current.isActive) current
            else {
                current.pendingChange?.let { server?.setStatus(it.id, STATUS_REJECTED, "Reemplazado por un cambio posterior.") }
                current.copy(pendingChange = change, lastResult = null)
            }
        }
    }

    private class PhoneSetupHttpServer(
        host: String,
        port: Int,
        private val sessionToken: String,
        private val currentSnapshot: () -> PhoneSetupSnapshot,
        private val onProposal: (PhoneSetupChange) -> Unit,
    ) : NanoHTTPD(host, port) {
        private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }
        private val statuses = ConcurrentHashMap<String, Pair<String, String?>>()
        @Volatile private var pendingId: String? = null

        fun setStatus(id: String, status: String, message: String? = null) {
            statuses[id] = status to message
            if (pendingId == id) pendingId = null
        }

        override fun serve(session: IHTTPSession): Response {
            val root = "/s/$sessionToken/"
            val path = session.uri
            if (session.method == Method.GET && (path == root || path == root.removeSuffix("/"))) {
                return htmlResponse(PHONE_SETUP_HTML)
            }
            if (!path.startsWith(root)) return textResponse(Response.Status.NOT_FOUND, "No encontrado")
            if (!hasValidSessionToken(session)) return jsonResponse(Response.Status.FORBIDDEN, mapOf("error" to "Sesión no autorizada"))

            val route = path.removePrefix(root)
            return when {
                session.method == Method.GET && route == "api/state" -> {
                    val snapshot = currentSnapshot()
                    if (snapshot.entries.size > MAX_ENTRIES) {
                        jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Hay más de $MAX_ENTRIES integraciones; gestiona esta lista desde la TV."))
                    } else {
                        jsonResponse(Response.Status.OK, PhoneSetupStateResponse(snapshot.entries, snapshot.preferences))
                    }
                }
                session.method == Method.POST && route == "api/proposal" -> handleProposal(session)
                session.method == Method.GET && route.startsWith("api/status/") -> {
                    val id = route.removePrefix("api/status/")
                    val (status, message) = statuses[id] ?: ("not_found" to null)
                    jsonResponse(Response.Status.OK, PhoneSetupStatusResponse(status, message))
                }
                else -> textResponse(Response.Status.NOT_FOUND, "No encontrado")
            }
        }

        private fun hasValidSessionToken(session: IHTTPSession): Boolean {
            val supplied = session.headers[SESSION_HEADER]?.toByteArray(Charsets.UTF_8) ?: return false
            val expected = sessionToken.toByteArray(Charsets.UTF_8)
            return MessageDigest.isEqual(expected, supplied)
        }

        private fun handleProposal(session: IHTTPSession): Response {
            val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
            if (contentLength !in 1..MAX_BODY_BYTES.toLong()) {
                return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "El envío está vacío o es demasiado grande."))
            }
            val body = try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                files["postData"].orEmpty()
            } catch (_: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "No se pudo leer la solicitud."))
            }
            val request = try {
                json.decodeFromString<PhoneSetupRequest>(body)
            } catch (_: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Formato de configuración inválido."))
            }
            val entries = try {
                validateEntries(request.entries)
            } catch (error: IllegalArgumentException) {
                return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to (error.message ?: "URL no válida.")))
            }

            pendingId?.let { previous -> setStatus(previous, STATUS_REJECTED, "Reemplazado por un cambio posterior.") }
            val snapshot = currentSnapshot()
            val preferences = request.preferences
            if (preferences.baseUrl.length > MAX_URL_LENGTH) {
                return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "La URL de servidor supera el tamaño permitido."))
            }
            if (!preferences.autonomous && !Urls.isValidBase(preferences.baseUrl)) {
                return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Configura una URL de servidor válida o selecciona el modo autónomo."))
            }
            val currentByKey = snapshot.entries.associateBy(::entryKey)
            val nextByKey = entries.associateBy(::entryKey)
            val added = entries.filter { entryKey(it) !in currentByKey }
            val removed = snapshot.entries.filter { entryKey(it) !in nextByKey }
            val changedPreferences = preferences.takeIf { it != snapshot.preferences }
            if (added.isEmpty() && removed.isEmpty() && changedPreferences == null) {
                return jsonResponse(Response.Status.OK, PhoneSetupProposalResponse(status = "no_changes"))
            }

            val id = UUID.randomUUID().toString()
            val change = PhoneSetupChange(id = id, added = added, removed = removed, preferences = changedPreferences)
            pendingId = id
            statuses[id] = STATUS_PENDING to null
            onProposal(change)
            return jsonResponse(Response.Status.OK, PhoneSetupProposalResponse(status = STATUS_PENDING, id = id))
        }

        private fun validateEntries(entries: List<PhoneSetupEntry>): List<PhoneSetupEntry> {
            require(entries.size <= MAX_ENTRIES) { "Se permiten como máximo $MAX_ENTRIES integraciones." }
            val result = entries.map { item ->
                val rawUrl = item.url.trim()
                if (item.kind == PhoneSetupKind.STREMIO_ADDON) {
                    val suppliedScheme = runCatching { URI(rawUrl).scheme?.lowercase() }.getOrNull()
                    require(suppliedScheme == null || suppliedScheme in setOf("http", "https", "stremio")) {
                        "Usa una URL HTTP(S) o stremio:// para el addon."
                    }
                }
                val url = if (item.kind == PhoneSetupKind.STREMIO_ADDON) {
                    StremioUrls.normalizeManifestUrl(rawUrl)
                } else {
                    rawUrl
                }
                require(url.length <= MAX_URL_LENGTH) { "Una URL supera el tamaño permitido." }
                require(isValidUrl(item.kind, url)) { "URL no válida para ${kindLabel(item.kind)}: $url" }
                item.copy(url = url, name = item.name.trim().take(MAX_NAME_LENGTH))
            }.distinctBy(::entryKey)
            return result
        }

        private fun isValidUrl(kind: PhoneSetupKind, value: String): Boolean {
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
            val normalizedHost = host.trimEnd('.')
            if (uri.userInfo != null) return false
            val scheme = uri.scheme?.lowercase() ?: return false
            return when (kind) {
                PhoneSetupKind.STREMIO_ADDON -> scheme in setOf("http", "https", "stremio")
                PhoneSetupKind.NUVIO_REPOSITORY, PhoneSetupKind.CLOUDSTREAM_REPOSITORY -> {
                    val ipLiteral = normalizedHost.all { it.isDigit() || it == '.' } || ':' in normalizedHost
                    scheme == "https" && normalizedHost != "localhost" && !normalizedHost.endsWith(".localhost") &&
                        !normalizedHost.endsWith(".local") && !ipLiteral
                }
            }
        }

        private fun kindLabel(kind: PhoneSetupKind): String = when (kind) {
            PhoneSetupKind.STREMIO_ADDON -> "addon Stremio"
            PhoneSetupKind.NUVIO_REPOSITORY -> "índice Nuvio"
            PhoneSetupKind.CLOUDSTREAM_REPOSITORY -> "índice Cloudstream"
        }

        private fun entryKey(entry: PhoneSetupEntry): String = "${entry.kind.name}:${entry.url.trim()}"

        private inline fun <reified T> jsonResponse(status: Response.Status, value: T): Response =
            response(status, "application/json; charset=utf-8", json.encodeToString(value))

        private fun htmlResponse(body: String): Response = response(Response.Status.OK, "text/html; charset=utf-8", body)
        private fun textResponse(status: Response.Status, body: String): Response = response(status, "text/plain; charset=utf-8", body)

        private fun response(status: Response.Status, mime: String, body: String): Response =
            newFixedLengthResponse(status, mime, body).apply {
                addHeader("Cache-Control", "no-store, max-age=0")
                addHeader("X-Content-Type-Options", "nosniff")
                addHeader("Referrer-Policy", "no-referrer")
                addHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")
            }


        companion object {
            private const val SESSION_HEADER = "x-streamflix-session"
            private const val MAX_ENTRIES = 40
            private const val MAX_URL_LENGTH = 2048
            private const val MAX_NAME_LENGTH = 120
            private const val MAX_BODY_BYTES = 64 * 1024
            private const val STATUS_PENDING = "pending_confirmation"
            private const val STATUS_APPLIED = "applied"
            private const val STATUS_REJECTED = "rejected"
        }
    }

    companion object {
        private const val FIRST_PORT = 8765
        private const val PORT_ATTEMPTS = 10
        private const val SESSION_TTL_MS = 10 * 60 * 1000L

        private fun randomToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        private fun generateQr(content: String): Bitmap {
            val matrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                QR_SIZE,
                QR_SIZE,
                mapOf(EncodeHintType.MARGIN to 3),
            )
            val pixels = IntArray(QR_SIZE * QR_SIZE)
            for (y in 0 until QR_SIZE) {
                for (x in 0 until QR_SIZE) {
                    pixels[y * QR_SIZE + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            return Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, QR_SIZE, 0, 0, QR_SIZE, QR_SIZE)
            }
        }

        @Suppress("DEPRECATION")
        private fun deviceIpv4(context: Context): String? {
            val wifiIp = runCatching {
                (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.ipAddress ?: 0
            }.getOrDefault(0)
            if (wifiIp != 0) {
                val wifiAddress = "${wifiIp and 0xff}.${wifiIp shr 8 and 0xff}.${wifiIp shr 16 and 0xff}.${wifiIp shr 24 and 0xff}"
                val addressIsActive = runCatching {
                    NetworkInterface.getByInetAddress(InetAddress.getByName(wifiAddress))
                        ?.let { it.isUp && !it.isLoopback } == true
                }.getOrDefault(false)
                if (addressIsActive) return wifiAddress
            }
            return runCatching {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                    .sortedBy { iface -> if (iface.name.startsWith("wlan") || iface.name.startsWith("eth") || iface.name.startsWith("en")) 0 else 1 }
                interfaces.asSequence()
                    .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                    .flatMap { Collections.list(it.inetAddresses).asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?.hostAddress
            }.getOrNull()
        }

        private const val QR_SIZE = 512
        private const val STATUS_APPLIED = "applied"
        private const val STATUS_REJECTED = "rejected"
        private const val STATUS_PENDING = "pending_confirmation"
    }
}

private const val PHONE_SETUP_HTML = """
<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>Streamflix · Configurar TV</title>
<style>
:root{color-scheme:dark;font-family:system-ui,-apple-system,"Segoe UI",sans-serif;background:#050505;color:#f7f7f8}
*{box-sizing:border-box}body{margin:0;min-height:100vh;background:radial-gradient(ellipse at top,#202024 0,#080809 52%,#000 100%);padding:28px 18px 50px}
main{max-width:620px;margin:0 auto}.brand{font-size:13px;letter-spacing:.22em;font-weight:800;color:#fff}.eyebrow{margin:34px 0 8px;color:#a4a4aa;font-size:12px;letter-spacing:.12em;text-transform:uppercase}h1{font-size:clamp(28px,7vw,40px);line-height:1.1;margin:0 0 12px}p{color:#b7b7bd;line-height:1.55}.notice{padding:14px 16px;border:1px solid #34343a;background:#141417;border-radius:14px;font-size:14px;margin:18px 0 24px}
label{display:block;font-size:13px;color:#c4c4ca;margin:14px 0 7px}select,input{width:100%;height:48px;border:1px solid #37373d;border-radius:12px;background:#131316;color:#fff;padding:0 13px;font:inherit}input:focus,select:focus{outline:2px solid #ddd;outline-offset:2px}.add{display:flex;gap:10px;align-items:end}.add>div{flex:1}.settings{padding:16px;border:1px solid #34343a;background:#111114;border-radius:14px;margin:20px 0}.settings h2{font-size:18px;margin:0 0 4px}.settings p{font-size:13px;margin:4px 0 12px}.settings select,.settings input{height:42px}.add{display:flex;gap:10px;align-items:end}button{min-height:46px;border:0;border-radius:12px;padding:0 18px;background:#fff;color:#111;font-weight:700;font:inherit;cursor:pointer}.add button{height:48px;white-space:nowrap}.save{width:100%;margin-top:18px}.save:disabled{opacity:.5;cursor:wait}
.list{padding:0;margin:16px 0;list-style:none}.item{display:flex;align-items:center;gap:12px;border-top:1px solid #2a2a2e;padding:14px 2px}.info{min-width:0;flex:1}.kind{font-size:11px;letter-spacing:.1em;text-transform:uppercase;color:#9999a0}.url{overflow-wrap:anywhere;font-size:14px;margin-top:4px}.remove{min-height:38px;background:#29292d;color:#eee;padding:0 12px;font-size:13px}.error{color:#ff9b9b;min-height:22px;font-size:14px}.status{display:none;margin-top:18px;padding:14px 16px;border-radius:12px;background:#18181b;border:1px solid #3a3a40}.status.show{display:block}.muted{color:#888890;font-size:12px}.empty{padding:20px 2px;color:#85858d;border-top:1px solid #2a2a2e}
</style>
</head>
<body><main>
<div class="brand">STREAMFLIX</div><div class="eyebrow">Configuración desde el teléfono</div><h1>Gestiona tus addons</h1>
<p>Añade o quita integraciones desde el navegador del teléfono. La TV te mostrará los cambios y pedirá confirmación antes de aplicarlos.</p>
<div class="notice">Mantén el teléfono y Streamflix conectados a la misma red Wi‑Fi. Esta sesión local dura 10 minutos y se cierra al salir de la pantalla QR.</div>
<section class="settings"><h2>Configuración general</h2><p>También puedes ajustar el modo de conexión y las preferencias de la app.</p>
<label for="autonomous">Origen de contenido</label><select id="autonomous"><option value="true">En este dispositivo (autónomo)</option><option value="false">Servidor externo</option></select>
<div id="baseField"><label for="baseUrl">URL del backend</label><input id="baseUrl" type="text" autocomplete="url" placeholder="http://192.168.1.50:7000"></div>
<div id="metadataField"><label for="metadata">Catálogos de Cinemeta en modo autónomo</label><select id="metadata"><option value="true">Activados</option><option value="false">Desactivados</option></select></div>
<label for="autoUpdate">Buscar actualizaciones al iniciar</label><select id="autoUpdate"><option value="true">Activado</option><option value="false">Desactivado</option></select>
</section>
<label for="kind">Tipo de integración</label><select id="kind"><option value="stremio">Addon Stremio</option><option value="nuvio">Repositorio de plugins Nuvio</option><option value="cloudstream">Repositorio Cloudstream</option></select>
<div class="add"><div><label for="url">URL del manifest o índice</label><input id="url" type="url" autocomplete="url" placeholder="https://…/manifest.json"></div><button id="addButton" type="button">Añadir</button></div>
<div id="error" class="error" role="status"></div><ul id="list" class="list"></ul><div id="empty" class="empty" hidden>No hay integraciones añadidas todavía.</div>
<button id="save" class="save" type="button">Enviar cambios a la TV</button><div id="status" class="status" role="status"></div>
<p class="muted">El QR no inicia sesión ni ejecuta código. Instalar un repositorio de plugin sólo guarda y consulta su índice; la ejecución de plugins aún no está disponible en Streamflix.</p>
</main>
<script>
(() => {
  const sessionPath = window.location.pathname;
  const token = sessionPath.split('/')[2] || '';
  const api = sessionPath + 'api/';
  const entries = [];
  const list = document.getElementById('list');
  const empty = document.getElementById('empty');
  const error = document.getElementById('error');
  const status = document.getElementById('status');
  const saveButton = document.getElementById('save');
  const labels = {stremio:'Addon Stremio',nuvio:'Repositorio Nuvio',cloudstream:'Repositorio Cloudstream'};
  const headers = {'X-Streamflix-Session': token};
  let pollTimer = null;
  let pollStarted = 0;

  function updateSettingVisibility() {
    const autonomous = document.getElementById('autonomous').value === 'true';
    document.getElementById('baseField').hidden = autonomous;
    document.getElementById('metadataField').hidden = !autonomous;
  }
  document.getElementById('autonomous').addEventListener('change', updateSettingVisibility);
  updateSettingVisibility();

  async function apiFetch(route, options={}) {
    const response = await fetch(api + route, {...options, headers:{...headers,...(options.headers||{})}, cache:'no-store'});
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || 'No se pudo comunicar con la TV.');
    return data;
  }
  function render() {
    list.replaceChildren();
    entries.forEach((entry,index) => {
      const li=document.createElement('li'); li.className='item';
      const info=document.createElement('div'); info.className='info';
      const kind=document.createElement('div'); kind.className='kind'; kind.textContent=labels[entry.kind]||entry.kind;
      const url=document.createElement('div'); url.className='url'; url.textContent=entry.name ? entry.name+' · '+entry.url : entry.url;
      info.append(kind,url);
      const remove=document.createElement('button'); remove.className='remove'; remove.type='button'; remove.textContent='Quitar'; remove.onclick=()=>{entries.splice(index,1);render();};
      li.append(info,remove); list.append(li);
    });
    empty.hidden=entries.length>0;
  }
  function normalized(value) { return value.trim(); }
  document.getElementById('addButton').onclick=() => {
    error.textContent='';
    const kind=document.getElementById('kind').value;
    const url=normalized(document.getElementById('url').value);
    if(!url){error.textContent='Escribe la URL del manifest o índice.';return;}
    if(entries.some(item=>item.kind===kind&&item.url===url)){error.textContent='Esa URL ya está en la lista.';return;}
    entries.push({kind,url,name:''}); document.getElementById('url').value=''; render();
  };
  document.getElementById('url').addEventListener('keydown',event=>{if(event.key==='Enter'){event.preventDefault();document.getElementById('addButton').click();}});
  document.getElementById('save').onclick=async () => {
    error.textContent=''; status.className='status';
    const save=saveButton; save.disabled=true; save.textContent='Enviando…';
    try {
      const preferences={
        autonomous:document.getElementById('autonomous').value==='true',
        metadataCatalogs:document.getElementById('metadata').value==='true',
        autoCheckUpdates:document.getElementById('autoUpdate').value==='true',
        baseUrl:document.getElementById('baseUrl').value.trim()
      };
      const result=await apiFetch('proposal',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({entries,preferences})});
      if(result.status==='no_changes'){showStatus('No hay cambios que aplicar.');save.disabled=false;save.textContent='Enviar cambios a la TV';return;}
      if(result.id) pollStatus(result.id);
    } catch(e) { error.textContent=e.message; save.disabled=false;save.textContent='Enviar cambios a la TV'; }
  };
  function showStatus(message){status.textContent=message;status.className='status show';}
  function pollStatus(id){
    pollStarted=Date.now();
    const poll=async()=>{
      if(Date.now()-pollStarted>120000){showStatus('La espera terminó. Si la TV sigue abierta, vuelve a enviar los cambios.');saveButton.disabled=false;saveButton.textContent='Enviar cambios a la TV';return;}
      try {
        const result=await apiFetch('status/'+encodeURIComponent(id));
        if(result.status==='applied'){showStatus(result.message||'Cambios aplicados en la TV.');saveButton.disabled=false;saveButton.textContent='Enviar cambios a la TV';await load();return;}
        if(result.status==='rejected'){showStatus(result.message||'La TV rechazó los cambios.');saveButton.disabled=false;saveButton.textContent='Enviar cambios a la TV';return;}
        if(result.status==='not_found'){showStatus('La sesión terminó.');saveButton.disabled=false;saveButton.textContent='Enviar cambios a la TV';return;}
        showStatus('Esperando confirmación en la pantalla de la TV…');
      } catch(e) { showStatus(e.message); }
      pollTimer=setTimeout(poll,1200);
    };
    poll();
  }
  async function load(){
    try {
      const result=await apiFetch('state');
      entries.splice(0,entries.length,...result.entries.map(entry=>({kind:entry.kind,url:entry.url,name:entry.name||''})));
      document.getElementById('autonomous').value=String(result.preferences.autonomous);
      document.getElementById('metadata').value=String(result.preferences.metadataCatalogs);
      document.getElementById('autoUpdate').value=String(result.preferences.autoCheckUpdates);
      document.getElementById('baseUrl').value=result.preferences.baseUrl||'';
      updateSettingVisibility(); render();
    } catch(e) { error.textContent=e.message;document.getElementById('save').disabled=true; }
  }
  window.addEventListener('pagehide',()=>{if(pollTimer)clearTimeout(pollTimer);});
  load();
})();
</script>
</body></html>
"""
