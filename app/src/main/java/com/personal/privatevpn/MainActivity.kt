package com.personal.privatevpn

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.*
import android.util.AtomicFile
import android.view.Gravity
import android.view.View
import android.widget.*
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max

private val countries = listOf("Türkiye", "Brazil", "Ukraine")
private val flags = listOf("🇹🇷", "🇧🇷", "🇺🇦")
private val slots = listOf("tr", "br", "ua")
data class Server(val id: String, val country: Int, val city: String, val config: String) {
    fun host(): String = runCatching { Config.parse(config.byteInputStream()).peers.single().endpoint.get().toString() }.getOrDefault("Invalid profile")
}

class VpnApp : Application() {
    val backend by lazy { GoBackend(this) }
    val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    var listener: (() -> Unit)? = null
    @Volatile var servers: List<Server> = emptyList()
    @Volatile var busy = false
    @Volatile var up = false
    @Volatile var live = false
    @Volatile var message = "Disconnected"
    @Volatile var activeId: String? = null
    @Volatile var download = 0.0
    @Volatile var upload = 0.0
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastSample = 0L
    private var connectedAt = 0L
    private var sampling = false
    private val catalog get() = AtomicFile(File(noBackupFilesDir,"servers.json"))
    val tunnel = object : Tunnel {
        override fun getName() = "privatevpn"
        override fun onStateChange(newState: Tunnel.State) = Unit
    }
    override fun onCreate() {
        super.onCreate()
        worker.execute {
            runCatching {
                if (catalog.baseFile.exists()) {
                    val items = JSONArray(catalog.openRead().bufferedReader().use { it.readText() })
                    servers = (0 until items.length()).map { i ->
                        val s = items.getJSONObject(i)
                        Server(s.getString("id"),s.getInt("country"),s.getString("city"),s.getString("config"))
                    }
                } else {
                    servers = slots.mapIndexedNotNull { index, slot ->
                        val old = AtomicFile(File(noBackupFilesDir,"$slot.conf"))
                        if (old.baseFile.exists()) Server(slot,index,"Imported location",old.openRead().bufferedReader().use { it.readText() }) else null
                    }
                    save(servers)
                }
            }.onFailure { message = "Could not read saved profiles" }
            changed()
        }
    }
    private fun save(items: List<Server>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id",it.id).put("country",it.country).put("city",it.city).put("config",it.config)) }
        val file = catalog
        val stream = file.startWrite()
        try { stream.write(array.toString().toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        servers = items
    }
    private fun changed() { handler.post { listener?.invoke() } }
    fun importProfile(country: Int, city: String, uri: Uri) {
        if (busy || up) return
        busy = true; message = "Importing profile…"; changed()
        worker.execute {
            runCatching {
                val bytes = requireNotNull(contentResolver.openInputStream(uri)).use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var read = input.read(buffer)
                    while (read >= 0) {
                        require(output.size() + read <= 16384)
                        output.write(buffer,0,read); read = input.read(buffer)
                    }
                    output.toByteArray()
                }
                val config = try { Config.parse(bytes.inputStream()) } catch (_: Exception) {
                    throw IllegalArgumentException("Select a valid WireGuard .conf file, not a ZIP, image or OpenVPN profile")
                }
                require(config.peers.size == 1) { "Profile must contain exactly one VPN server" }
                val peer = config.peers.single()
                require(peer.endpoint.isPresent) { "Profile is missing the server endpoint" }
                val routes = peer.allowedIps.map { it.toString() }.toSet()
                require("0.0.0.0/0" in routes && ("::/0" in routes || "0:0:0:0:0:0:0:0/0" in routes)) { "Profile must route both IPv4 and IPv6: AllowedIPs = 0.0.0.0/0, ::/0" }
                require(config.`interface`.includedApplications.isEmpty() && config.`interface`.excludedApplications.isEmpty()) { "Remove per-app routing exclusions from this profile" }
                save(servers + Server(UUID.randomUUID().toString(),country,city,bytes.toString(Charsets.UTF_8)))
                message = "Profile added · connect to verify"
            }.onFailure { message = if (it is IllegalArgumentException) it.message ?: "Invalid profile" else "Import failed · unable to read or save profile" }
            busy = false; changed()
        }
    }
    fun connect(server: Server) {
        if (busy || up) return
        busy = true; message = "Connecting…"; activeId = server.id; changed()
        worker.execute {
            runCatching {
                backend.setState(tunnel,Tunnel.State.UP,Config.parse(server.config.byteInputStream()))
                up = true; connectedAt = SystemClock.elapsedRealtime()
                lastSample = 0; live = false; message = "Connecting · awaiting handshake"
            }.onFailure { message = "Connection failed · check profile and VPN permission"; activeId = null }
            busy = false; changed()
        }
    }
    fun disconnect() {
        if (busy) return
        busy = true; message = "Disconnecting…"; changed()
        worker.execute {
            runCatching { backend.setState(tunnel,Tunnel.State.DOWN,null) }
                .onSuccess { up=false; live=false; activeId=null; download=0.0; upload=0.0; message="Disconnected" }
                .onFailure { message="Could not disconnect · retry" }
            busy=false; changed()
        }
    }
    fun sample() {
        if (sampling || busy) return
        sampling = true
        worker.execute {
            runCatching {
                up = backend.getState(tunnel) == Tunnel.State.UP
                if (up) {
                    val stats = backend.getStatistics(tunnel)
                    val now = SystemClock.elapsedRealtime()
                    val handshake = stats.peers().maxOfOrNull { stats.peer(it)?.latestHandshakeEpochMillis() ?: 0L } ?: 0L
                    live = handshake > 0 && System.currentTimeMillis()-handshake in 0..180000
                    val seconds = (now-lastSample)/1000.0
                    download = if (lastSample > 0 && seconds > 0) max(0L,stats.totalRx()-lastRx)*8/seconds/1e6 else 0.0
                    upload = if (lastSample > 0 && seconds > 0) max(0L,stats.totalTx()-lastTx)*8/seconds/1e6 else 0.0
                    lastRx=stats.totalRx(); lastTx=stats.totalTx(); lastSample=now
                    message = when {
                        live -> "Connected · peer verified"
                        now-connectedAt < 20000 -> "Connecting · awaiting handshake"
                        else -> "No recent handshake · check server"
                    }
                } else if (activeId != null) {
                    activeId=null; live=false; download=0.0; upload=0.0; message="Disconnected"
                }
            }.onFailure { live=false; message="Unable to read tunnel status" }
            handler.post { sampling=false; listener?.invoke() }
        }
    }
}

class MainActivity : Activity() {
    private val app get() = application as VpnApp
    private val handler = Handler(Looper.getMainLooper())
    private val cyan = Color.rgb(71,220,211)
    private var country = 0
    private var selectedId: String? = null
    private var pendingId: String? = null
    private var pendingCountry = 0
    private var pendingCity = ""
    private lateinit var globe: GlobeView
    private lateinit var countryButton: Button
    private lateinit var status: TextView
    private lateinit var rate: TextView
    private lateinit var power: Button
    private lateinit var powerLabel: TextView
    private lateinit var serverList: LinearLayout
    private lateinit var importButton: Button
    private var listKey = ""
    private lateinit var brandTitle: TextView
    private val brandText = "RYZEN / VPN"
    private var brandLength = 0
    private var brandDeleting = false
    private val brandAnimation = object : Runnable {
        override fun run() {
            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
                brandTitle.text = brandText
                return
            }
            brandLength += if (brandDeleting) -1 else 1
            brandLength = brandLength.coerceIn(0, brandText.length)
            brandTitle.text = (brandText.take(brandLength) + "_").padEnd(brandText.length + 1, ' ')
            val delay = when {
                brandLength == brandText.length -> { brandDeleting = true; 2400L }
                brandLength == 0 -> { brandDeleting = false; 650L }
                brandDeleting -> 65L
                else -> 135L
            }
            handler.postDelayed(this, delay)
        }
    }
    private val poll = object : Runnable {
        override fun run() { app.sample(); handler.postDelayed(this,1500) }
    }
    private fun dp(value: Int) = (value*resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        text=value; textSize=size; setTextColor(color); gravity=Gravity.CENTER
    }
    private fun background(color: Int, radius: Float = 18f) = GradientDrawable().apply {
        setColor(color); cornerRadius=dp(radius.toInt()).toFloat()
    }
    private fun button(label: String) = Button(this).apply {
        text=label; isAllCaps=false; setTextColor(cyan); textSize=15f
        background=background(Color.rgb(18,37,52)); minHeight=dp(48)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        country = savedInstanceState?.getInt("country") ?: 0
        selectedId=savedInstanceState?.getString("selected")
        pendingId=savedInstanceState?.getString("pendingId")
        pendingCountry=savedInstanceState?.getInt("pendingCountry") ?: 0
        pendingCity=savedInstanceState?.getString("pendingCity") ?: ""
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.rgb(6,16,27))
            setOnApplyWindowInsetsListener { view,insets ->
                @Suppress("DEPRECATION")
                view.setPadding(dp(22)+insets.systemWindowInsetLeft,dp(12)+insets.systemWindowInsetTop,
                    dp(22)+insets.systemWindowInsetRight,dp(10)+insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(root)
        brandTitle=text(brandText,21f).apply {
            typeface=Typeface.MONOSPACE; letterSpacing=.12f; setSingleLine()
            contentDescription="RYZEN / VPN"
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_NONE
        }
        root.addView(brandTitle,LinearLayout.LayoutParams(-1,dp(32)))
        status=text("Disconnected",13f,Color.LTGRAY)
        root.addView(status,LinearLayout.LayoutParams(-1,dp(32)))
        globe=GlobeView(this).apply { onCountry={ chooseCountry(it) } }
        root.addView(globe,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(text("DRAG TO EXPLORE · TAP A HIGHLIGHTED COUNTRY",10f,Color.rgb(132,151,169)))
        countryButton=button("").apply { setOnClickListener {
            AlertDialog.Builder(this@MainActivity).setTitle("Choose a country")
                .setItems(countries.mapIndexed { i,name -> "${flags[i]}  $name" }.toTypedArray()) { _,i -> chooseCountry(i) }.show()
        } }
        root.addView(countryButton,LinearLayout.LayoutParams(-1,dp(50)).apply { topMargin=dp(12) })
        serverList=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val scroll=ScrollView(this).apply { addView(serverList); isFillViewport=true }
        root.addView(scroll,LinearLayout.LayoutParams(-1,dp(120)).apply { topMargin=dp(8) })
        importButton=button("+ Add city / import WireGuard").apply { setOnClickListener { askCity() } }
        root.addView(importButton,LinearLayout.LayoutParams(-1,dp(46)))
        rate=text("↓ — Mbps    ↑ — Mbps",14f,Color.LTGRAY)
        root.addView(rate,LinearLayout.LayoutParams(-1,dp(36)))
        power=object : Button(this) {
            private val iconPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.rgb(6,16,27); style=android.graphics.Paint.Style.STROKE
                strokeCap=android.graphics.Paint.Cap.ROUND
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val cx=width/2f; val cy=height/2f; val r=width*.21f
                iconPaint.strokeWidth=width*.045f
                canvas.drawArc(cx-r,cy-r,cx+r,cy+r,-45f,270f,false,iconPaint)
                canvas.drawLine(cx,cy-r*1.2f,cx,cy,iconPaint)
            }
        }.apply {
            text=""; textSize=44f; setTextColor(Color.rgb(6,16,27)); setPadding(0,0,0,0)
            background=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(cyan) }
            setOnClickListener {
                if (app.up) app.disconnect() else {
                    val server=app.servers.find { it.id == selectedId } ?: return@setOnClickListener
                    pendingId=server.id
                    val intent=VpnService.prepare(this@MainActivity)
                    if (intent==null) app.connect(server) else startActivityForResult(intent,1)
                }
            }
        }
        root.addView(power,LinearLayout.LayoutParams(dp(84),dp(84)))
        powerLabel=text("CONNECT",11f,cyan)
        root.addView(powerLabel,LinearLayout.LayoutParams(-1,dp(26)))
        globe.focusCountry(country)
        render()
    }
    private fun chooseCountry(index: Int) {
        country=index; selectedId=null; listKey=""; globe.focusCountry(index); render()
    }
    private fun askCity() {
        val city=EditText(this).apply { hint="City name, e.g. Istanbul"; setSingleLine(); filters=arrayOf(android.text.InputFilter.LengthFilter(60)) }
        AlertDialog.Builder(this).setTitle("Add server in ${countries[country]}")
            .setMessage("Use a working WireGuard profile. Enter the actual server city.").setView(city)
            .setNegativeButton("Cancel",null).setPositiveButton("Choose profile") { _,_ ->
                val name=city.text.toString().trim()
                if (name.isBlank()) { Toast.makeText(this,"Enter the server city",Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                pendingCountry=country; pendingCity=name
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="*/*" },2)
            }.show()
    }
    private fun render() {
        status.text=app.message
        status.setTextColor(if(app.live) cyan else Color.LTGRAY)
        countryButton.text="${flags[country]}  ${countries[country]}   ▾"
        val servers=app.servers.filter { it.country==country }
        if (servers.none { it.id==selectedId }) selectedId=servers.firstOrNull()?.id
        val key="$country/$selectedId/${app.activeId}/${app.servers.hashCode()}/${app.download}/${app.upload}"
        if (key!=listKey) {
            listKey=key; serverList.removeAllViews()
            if (servers.isEmpty()) {
                serverList.addView(text("No servers configured\nAdd a working profile to see its city and host.",14f,Color.LTGRAY).apply { setPadding(0,dp(22),0,dp(12)) })
            }
            servers.forEach { server ->
                val speed=if(app.up && app.activeId==server.id) String.format(java.util.Locale.US,"↓ %.2f · ↑ %.2f Mbps",app.download,app.upload) else "Speed — · connect to measure"
                val row=button("${flags[country]}  ${server.city}${if(server.id==selectedId) "  ✓" else ""}\n${server.host()}\n$speed").apply {
                    gravity=Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(dp(12),dp(4),dp(12),dp(4)); textSize=13f
                    setOnClickListener { if (!app.up && !app.busy) { selectedId=server.id; listKey=""; render() } }
                    contentDescription="${countries[country]}, ${server.city}, host ${server.host()}"
                }
                serverList.addView(row,LinearLayout.LayoutParams(-1,dp(80)).apply { bottomMargin=dp(4) })
            }
        }
        rate.text=if(app.up) String.format(java.util.Locale.US,"↓ %.2f Mbps    ↑ %.2f Mbps · live traffic",app.download,app.upload) else "↓ — Mbps    ↑ — Mbps · connect to measure"
        power.isEnabled=!app.busy && (app.up || selectedId!=null)
        power.alpha=if(power.isEnabled) 1f else .35f
        power.contentDescription=if(app.up) "Disconnect VPN" else "Connect VPN"
        powerLabel.text=if(app.up) "DISCONNECT" else "CONNECT"
        importButton.isEnabled=!app.busy && !app.up
        globe.liveCountry=if(app.live) app.servers.find { it.id==app.activeId }?.country ?: -1 else -1
        globe.invalidate()
    }
    override fun onResume() { super.onResume(); app.listener={render()}; render(); handler.post(poll); handler.removeCallbacks(brandAnimation); handler.postDelayed(brandAnimation,500) }
    override fun onPause() { handler.removeCallbacks(poll); handler.removeCallbacks(brandAnimation); app.listener=null; super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("country",country); outState.putString("selected",selectedId)
        outState.putString("pendingId",pendingId); outState.putInt("pendingCountry",pendingCountry); outState.putString("pendingCity",pendingCity)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Platform activity result")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK) return
        when(requestCode) {
            1 -> app.servers.find { it.id==pendingId }?.let { app.connect(it) }
            2 -> data?.data?.let { app.importProfile(pendingCountry,pendingCity,it) }
        }
    }
}
