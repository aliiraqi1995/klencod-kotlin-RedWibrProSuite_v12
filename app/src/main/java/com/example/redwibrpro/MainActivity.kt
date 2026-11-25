package com.example.redwibrpro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScannedWifiNetwork(
    val ssid: String,
    val bssid: String,
    val capabilities: String,
    val signalPercent: Int
)

data class SavedNetworkEntry(
    val ssid: String,
    val password: String,
    val savedAt: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createSuccessNotificationChannel(this)
        setContent {
            val darkRedScheme = darkColorScheme(
                primary = Color(0xFFFF3B30),
                onPrimary = Color.White,
                primaryContainer = Color(0xFF4A0E0E),
                onPrimaryContainer = Color.White,
                secondary = Color(0xFFE53935),
                onSecondary = Color.White,
                background = Color(0xFF121316),
                onBackground = Color.White,
                surface = Color(0xFF222428),
                onSurface = Color.White
            )

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = darkRedScheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        RedWibrClassicHomeApp(onExitApp = { finishAndRemoveTask() })
                    }
                }
            }
        }
    }
}

@Composable
fun RedWibrClassicHomeApp(onExitApp: () -> Unit) {
    val context = LocalContext.current

    val redAccent = Color(0xFFFF3B30)
    val darkBg = Color(0xFF121316)
    val barBg = Color(0xFF222428)
    val cardBg = Color(0xFF1B1C20)
    val textPrimary = Color(0xFFF5F5F7)

    var targetSsid by remember { mutableStateOf("") }
    var targetBssid by remember { mutableStateOf("00:00:00:00:00:00") }
    var targetEncryption by remember { mutableStateOf("[WPA2-PSK]") }
    var targetSignal by remember { mutableIntStateOf(0) }
    var hasSelectedNetwork by remember { mutableStateOf(false) }

    var currentPassword by remember { mutableStateOf("12345678") }
    var statusBanner by remember { mutableStateOf("Ready") }
    var networkSummary by remember { mutableStateOf(inspectNetworkState(context)) }
    var lastIpAddress by remember { mutableStateOf("-") }
    var isTrying by remember { mutableStateOf(false) }

    var showScanDialog by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var scannedNetworks by remember { mutableStateOf<List<ScannedWifiNetwork>>(emptyList()) }
    var scanSummary by remember { mutableStateOf("Tap REFRESH") }

    var bingoDialogData by remember { mutableStateOf<Pair<String, String>?>(null) }
    var savedNetworks by remember { mutableStateOf(loadSavedNetworks(context)) }
    var activeCallback by remember { mutableStateOf<ConnectivityManager.NetworkCallback?>(null) }

    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    fun triggerRealWifiScan() {
        val outcome = performRealWifiScan(context)
        scannedNetworks = outcome.networks
        networkSummary = inspectNetworkState(context)
        scanSummary = outcome.summary
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        triggerRealWifiScan()
        statusBanner = "Permissions Ready"
    }

    fun cleanupCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        activeCallback?.let { cb ->
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
        activeCallback = null
    }

    fun saveSuccessfulConnection(ssid: String, password: String) {
        val stamp = SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.getDefault()).format(Date())
        val updated = listOf(SavedNetworkEntry(ssid, password, stamp)) +
            savedNetworks.filterNot { it.ssid == ssid && it.password == password }
        savedNetworks = updated
        persistSavedNetworks(context, updated)
    }

    @Suppress("DEPRECATION")
    fun attemptSingleWifiConnection(
        ssid: String,
        password: String,
        onConnected: () -> Unit,
        onFailed: () -> Unit
    ) {
        cleanupCallback()

        if (ssid.isBlank()) {
            onFailed()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager == null) {
                onFailed()
                return
            }
            runCatching {
                val conf = WifiConfiguration()
                conf.SSID = "\"" + ssid + "\""
                if (password.isNotEmpty()) {
                    conf.preSharedKey = "\"" + password + "\""
                } else {
                    conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
                val netId = wifiManager.addNetwork(conf)
                if (netId != -1) {
                    wifiManager.disconnect()
                    wifiManager.enableNetwork(netId, true)
                    wifiManager.reconnect()
                } else {
                    onFailed()
                }
            }.onFailure {
                onFailed()
            }
            return
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (password.isNotEmpty()) {
            specifierBuilder.setWpa2Passphrase(password)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        var settled = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!settled) {
                    settled = true
                    onConnected()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val ip = linkProperties.linkAddresses.firstOrNull()?.address?.hostAddress
                if (!ip.isNullOrBlank()) {
                    lastIpAddress = ip
                }
            }

            override fun onUnavailable() {
                if (!settled) {
                    settled = true
                    onFailed()
                }
            }

            override fun onLost(network: Network) {
                networkSummary = inspectNetworkState(context)
            }
        }

        activeCallback = callback
        runCatching {
            cm.requestNetwork(request, callback)
        }.onFailure {
            if (!settled) {
                settled = true
                onFailed()
            }
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    val updated = readScanResultsOnly(context)
                    if (updated.isNotEmpty()) {
                        scannedNetworks = updated
                        scanSummary = "Found ${updated.size} APs"
                    }
                }
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        triggerRealWifiScan()

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            cleanupCallback()
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            containerColor = barBg,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.border(2.dp, redAccent, MaterialTheme.shapes.small),
            title = {
                Text("Help - WIBR+ Red Pro", color = redAccent, fontSize = 19.sp, fontWeight = FontWeight.Black)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Tap PERMS to grant Location & Wi-Fi permissions.", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("2. Tap Scan APs to pick a nearby network, or Add Network manually.", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("3. Enter the password and tap Try Now for a single instant connection test.", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showHelpDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White)
                ) {
                    Text("OK", fontWeight = FontWeight.Black)
                }
            }
        )
    }

    bingoDialogData?.let { (winSsid, winPass) ->
        AlertDialog(
            onDismissRequest = { bingoDialogData = null },
            containerColor = barBg,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.border(2.dp, redAccent, MaterialTheme.shapes.small),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .border(2.dp, redAccent, MaterialTheme.shapes.extraSmall)
                            .background(darkBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("W+", color = redAccent, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Bingo!", color = redAccent, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SSID: $winSsid", color = textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Password: $winPass", color = redAccent, fontSize = 17.sp, fontWeight = FontWeight.Black)
                }
            },
            confirmButton = {
                Button(
                    onClick = { bingoDialogData = null },
                    colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White)
                ) {
                    Text("OK", fontWeight = FontWeight.Black)
                }
            }
        )
    }

    if (showManualDialog) {
        var manualSsid by remember { mutableStateOf("") }
        var manualPass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            containerColor = barBg,
            modifier = Modifier.border(2.dp, redAccent, MaterialTheme.shapes.small),
            title = { Text("Add Network", color = redAccent, fontSize = 19.sp, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manualSsid,
                        onValueChange = { manualSsid = it },
                        label = { Text("SSID", color = redAccent, fontWeight = FontWeight.Bold) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = manualPass,
                        onValueChange = { manualPass = it },
                        label = { Text("Password", color = redAccent, fontWeight = FontWeight.Bold) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (manualSsid.isNotBlank()) {
                            targetSsid = manualSsid.trim()
                            targetBssid = "00:00:00:00:00:00"
                            targetEncryption = "[WPA2-PSK]"
                            targetSignal = 85
                            hasSelectedNetwork = true
                            if (manualPass.isNotBlank()) {
                                currentPassword = manualPass.trim()
                            }
                            statusBanner = "Ready"
                        }
                        showManualDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White)
                ) {
                    Text("Set", fontWeight = FontWeight.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) {
                    Text("Cancel", color = Color.LightGray, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showScanDialog) {
        val filtered = remember(scannedNetworks, searchQuery) {
            if (searchQuery.isBlank()) scannedNetworks
            else scannedNetworks.filter {
                it.ssid.contains(searchQuery, ignoreCase = true) ||
                    it.bssid.contains(searchQuery, ignoreCase = true)
            }
        }
        AlertDialog(
            onDismissRequest = { showScanDialog = false },
            containerColor = darkBg,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .border(2.dp, redAccent, MaterialTheme.shapes.small),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = scanSummary,
                        color = textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                    Box(
                        modifier = Modifier
                            .background(barBg)
                            .border(1.5.dp, redAccent, MaterialTheme.shapes.extraSmall)
                            .clickable {
                                val hasPerm = requiredPermissions.all {
                                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                                }
                                if (!hasPerm) permissionLauncher.launch(requiredPermissions)
                                else triggerRealWifiScan()
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "REFRESH",
                            color = redAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search SSID", color = redAccent, fontWeight = FontWeight.Bold) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (filtered.isEmpty()) {
                        Text(
                            text = "Tap PERMS then REFRESH",
                            color = textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filtered) { net ->
                                val securityLabel = formatSecurityShort(net.capabilities)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(barBg)
                                        .border(1.5.dp, Color(0xFF3A3A40))
                                        .clickable {
                                            targetSsid = net.ssid
                                            targetBssid = net.bssid
                                            targetEncryption = net.capabilities
                                            targetSignal = net.signalPercent
                                            hasSelectedNetwork = true
                                            statusBanner = "Ready"
                                            showScanDialog = false
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            text = net.ssid,
                                            color = redAccent,
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Black,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = securityLabel,
                                            color = textPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        WifiSignalBarsIcon(
                                            signalPercent = net.signalPercent,
                                            activeColor = redAccent,
                                            inactiveColor = Color(0xFF43434A),
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Text(
                                            text = "${net.signalPercent}%",
                                            color = textPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScanDialog = false }) {
                    Text("Close", color = redAccent, fontWeight = FontWeight.Black)
                }
            }
        )
    }

    val mainScroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(darkBg)
    ) {
        // Classic WIBR+ Top Bar (Red Edition) with HELP & EXIT
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(barBg)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .border(2.dp, redAccent, MaterialTheme.shapes.extraSmall)
                        .background(darkBg),
                    contentAlignment = Alignment.Center
                ) {
                    Text("W+", color = redAccent, fontSize = 16.sp, fontWeight = FontWeight.Black)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "WIBR+ Red Pro",
                    color = textPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "HELP",
                    color = textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.clickable { showHelpDialog = true }
                )
                Text(
                    text = "EXIT",
                    color = redAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.clickable {
                        cleanupCallback()
                        (context as? Activity)?.finishAndRemoveTask() ?: onExitApp()
                    }
                )
            }
        }

        // Red signature divider line matching classic WIBR+ bar
        HorizontalDivider(thickness = 2.dp, color = redAccent)

        // Classic full-width top Add Network bar (Red theme)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .background(barBg)
                .border(1.5.dp, Color(0xFF34363C), MaterialTheme.shapes.extraSmall)
                .clickable { showManualDialog = true }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Add Network",
                color = redAccent,
                fontSize = 19.sp,
                fontWeight = FontWeight.Black
            )
        }

        // Main content area
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(mainScroll)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasSelectedNetwork) {
                Text(
                    text = statusBanner,
                    color = redAccent,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = targetSsid,
                    onValueChange = { targetSsid = it },
                    label = { Text("SSID", color = redAccent, fontWeight = FontWeight.Bold) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    label = { Text("Password", color = redAccent, fontWeight = FontWeight.Bold) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (isTrying) {
                    CircularProgressIndicator(
                        color = redAccent,
                        trackColor = Color.DarkGray,
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Text("BSSID: $targetBssid", color = redAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "Encryption: $targetEncryption",
                    color = redAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg)
                        .border(1.5.dp, Color(0xFF2D2F36))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatLine("Signal level:", "$targetSignal%", redAccent)
                    StatLine("System status:", networkSummary, redAccent)
                    StatLine("IP Address:", lastIpAddress, redAccent)
                }
            } else {
                Spacer(modifier = Modifier.height(36.dp))
                Text(
                    text = "No Network Selected",
                    color = Color(0xFF8E9098),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Tap Add Network or Scan APs below",
                    color = Color(0xFF6B6D75),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (savedNetworks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Saved Networks (${savedNetworks.size})",
                    color = redAccent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                savedNetworks.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(barBg)
                            .border(1.5.dp, Color(0xFF34363C))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    targetSsid = entry.ssid
                                    currentPassword = entry.password
                                    hasSelectedNetwork = true
                                    statusBanner = "Ready"
                                }
                        ) {
                            Text(entry.ssid, color = redAccent, fontSize = 16.sp, fontWeight = FontWeight.Black)
                            Text("Password: ${entry.password}", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(entry.savedAt, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFF34363C))
                                .clickable {
                                    val updated = savedNetworks.filterNot { it == entry }
                                    savedNetworks = updated
                                    persistSavedNetworks(context, updated)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("DEL", color = redAccent, fontSize = 11.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }

        // Bottom Control Bar: Permissions, Scan APs, and Single Try Now (Start removed)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(barBg)
                .border(1.dp, Color(0xFF2E3036))
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        permissionLauncher.launch(requiredPermissions)
                    },
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = ButtonDefaults.buttonColors(containerColor = darkBg, contentColor = redAccent),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .border(1.5.dp, redAccent, MaterialTheme.shapes.extraSmall)
                ) {
                    Text("PERMS", fontSize = 14.sp, fontWeight = FontWeight.Black)
                }

                Button(
                    onClick = {
                        val hasPerm = requiredPermissions.all {
                            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                        }
                        if (!hasPerm) permissionLauncher.launch(requiredPermissions)
                        else triggerRealWifiScan()
                        showScanDialog = true
                    },
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(48.dp)
                ) {
                    Text("Scan APs", fontSize = 15.sp, fontWeight = FontWeight.Black)
                }

                Button(
                    onClick = {
                        if (targetSsid.isBlank()) {
                            statusBanner = "Select SSID First"
                            return@Button
                        }
                        isTrying = true
                        statusBanner = "Trying..."
                        attemptSingleWifiConnection(
                            ssid = targetSsid,
                            password = currentPassword,
                            onConnected = {
                                isTrying = false
                                statusBanner = "Connected!"
                                saveSuccessfulConnection(targetSsid, currentPassword)
                                sendSuccessNotification(context, targetSsid, currentPassword)
                                bingoDialogData = targetSsid to currentPassword
                            },
                            onFailed = {
                                isTrying = false
                                statusBanner = "Failed"
                            }
                        )
                    },
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF991B1B), contentColor = Color.White),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(48.dp)
                ) {
                    Text("Try Now", fontSize = 15.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun WifiSignalBarsIcon(
    signalPercent: Int,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier
) {
    val activeBars = when {
        signalPercent >= 80 -> 4
        signalPercent >= 55 -> 3
        signalPercent >= 30 -> 2
        else -> 1
    }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val center = Offset(w * 0.5f, h * 0.88f)
        val radii = listOf(h * 0.22f, h * 0.42f, h * 0.62f, h * 0.82f)
        val strokeWidth = w * 0.10f

        radii.forEachIndexed { index, r ->
            val color = if (index < activeBars) activeColor else inactiveColor
            drawArc(
                color = color,
                startAngle = -135f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
    }
}

private fun formatSecurityShort(capabilities: String): String {
    val upper = capabilities.uppercase(Locale.US)
    return when {
        "WPA3" in upper -> "WPA3"
        "WPA2" in upper && "WPA" in upper -> "WPA / WPA2"
        "WPA2" in upper -> "WPA2"
        "WPA" in upper -> "WPA"
        "WEP" in upper -> "WEP"
        else -> "OPEN"
    }
}

@Composable
private fun StatLine(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Black)
    }
}

private data class ScanOutcome(
    val networks: List<ScannedWifiNetwork>,
    val summary: String
)

private const val PREFS_NAME = "red_wibr_prefs"
private const val KEY_SAVED_NETWORKS = "saved_networks_json"
private const val CHANNEL_ID = "wibr_success_channel"

private fun loadSavedNetworks(context: Context): List<SavedNetworkEntry> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_SAVED_NETWORKS, null) ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                add(
                    SavedNetworkEntry(
                        ssid = obj.optString("ssid"),
                        password = obj.optString("password"),
                        savedAt = obj.optString("savedAt")
                    )
                )
            }
        }
    }.getOrElse { emptyList() }
}

private fun persistSavedNetworks(context: Context, list: List<SavedNetworkEntry>) {
    val arr = JSONArray()
    list.forEach { item ->
        arr.put(
            JSONObject().apply {
                put("ssid", item.ssid)
                put("password", item.password)
                put("savedAt", item.savedAt)
            }
        )
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_SAVED_NETWORKS, arr.toString())
        .apply()
}

private fun createSuccessNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wi-Fi Connection Results",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when a Wi-Fi password succeeds"
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}

private fun sendSuccessNotification(context: Context, ssid: String, password: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_wibr_red)
        .setContentTitle("Bingo! Connected to $ssid")
        .setContentText("Password: $password")
        .setColor(0xFFFF3B30.toInt())
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()

    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(ssid.hashCode(), notification)
}

private fun isLocationServiceEnabled(context: Context): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                @Suppress("DEPRECATION")
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }.getOrDefault(true)
}

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
private fun readScanResultsOnly(context: Context): List<ScannedWifiNetwork> {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return emptyList()
    val results = runCatching { wifiManager.scanResults }.getOrNull().orEmpty()
    return results
        .filter { !it.SSID.isNullOrBlank() }
        .distinctBy { "${it.SSID}_${it.BSSID}" }
        .sortedByDescending { it.level }
        .map { sr ->
            val pct = WifiManager.calculateSignalLevel(sr.level, 100).coerceIn(5, 100)
            ScannedWifiNetwork(
                ssid = sr.SSID,
                bssid = sr.BSSID ?: "00:00:00:00:00:00",
                capabilities = sr.capabilities ?: "[WPA2-PSK]",
                signalPercent = pct
            )
        }
}

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
private fun performRealWifiScan(context: Context): ScanOutcome {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return ScanOutcome(emptyList(), "Found 0 APs")

    if (!wifiManager.isWifiEnabled) {
        return ScanOutcome(emptyList(), "Turn Wi-Fi ON")
    }

    if (!isLocationServiceEnabled(context)) {
        return ScanOutcome(emptyList(), "Turn Location ON")
    }

    runCatching { wifiManager.startScan() }
    val list = readScanResultsOnly(context)
    return ScanOutcome(
        networks = list,
        summary = "Found ${list.size} APs"
    )
}

private fun inspectNetworkState(context: Context): String {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val active = cm.activeNetwork ?: return "Disconnected"
    val caps = cm.getNetworkCapabilities(active) ?: return "Limited"
    val transport = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
        else -> "Other"
    }
    val internet = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) "Internet" else "Local"
    return "$transport ($internet)"
}