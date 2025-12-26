package com.svtbn.networktools

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private var activeJob: Job? = null

    // UI Elements
    private lateinit var tvLog: TextView
    private lateinit var btnPing: Button
    private lateinit var btnStop: Button
    private lateinit var btnTrace: Button
    private lateinit var btnCheckPort: Button
    private lateinit var btnScan: Button
    private lateinit var btnQuality: Button
    private lateinit var btnScanPorts: Button
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etPackets: EditText

    // Helper map for port names
    private val portNames = mapOf(
        21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP",
        53 to "DNS", 80 to "HTTP", 443 to "HTTPS", 445 to "SMB",
        3306 to "MySQL", 3389 to "RDP", 8080 to "WebProxy", 554 to "RTSP"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Init UI
        tvLog = findViewById(R.id.tvLog)
        tvLog.movementMethod = ScrollingMovementMethod()

        btnPing = findViewById(R.id.btnPing)
        btnStop = findViewById(R.id.btnStop)
        btnTrace = findViewById(R.id.btnTrace)
        btnCheckPort = findViewById(R.id.btnCheckPort)
        btnScan = findViewById(R.id.btnScan)
        btnQuality = findViewById(R.id.btnQuality)
        btnScanPorts = findViewById(R.id.btnScanPorts)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etPackets = findViewById(R.id.etPackets)

        // --- LISTENERS ---

        btnPing.setOnClickListener {
            val host = etHost.text.toString()
            if (host.isNotBlank()) startTask { runInfinitePing(host) }
        }

        btnStop.setOnClickListener {
            stopTask()
            tvLog.append("\n--- STOPPED BY USER ---\n")
            autoScroll()
        }

        btnTrace.setOnClickListener {
            val host = etHost.text.toString()
            if (host.isNotBlank()) startTask { runTraceroute(host) }
        }

        btnCheckPort.setOnClickListener {
            val host = etHost.text.toString()
            val portStr = etPort.text.toString()
            if (host.isNotBlank() && portStr.isNotBlank()) {
                startTask { runPortCheck(host, portStr.toInt()) }
            }
        }

        // IP SCAN (Uses improved logic)
        btnScan.setOnClickListener {
            startTask { runNetworkScan() }
        }

        btnQuality.setOnClickListener {
            val host = etHost.text.toString()
            val packetsStr = etPackets.text.toString()
            val packetsCount = if (packetsStr.isNotBlank()) packetsStr.toInt() else 10
            if (host.isNotBlank()) startTask { runQualityTest(host, packetsCount) }
        }

        btnScanPorts.setOnClickListener {
            val host = etHost.text.toString()
            if (host.isNotBlank()) {
                startTask { runPortScanner(host) }
            }
        }
    }

    // =========================================================================
    // CORE LOGIC FUNCTIONS
    // =========================================================================

    private suspend fun CoroutineScope.runInfinitePing(host: String) {
        logToScreen(">>> START PING: $host\n")
        while (isActive) {
            try {
                val process = Runtime.getRuntime().exec("ping -c 1 -W 2 $host")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.contains("bytes from")) logToScreen(line + "\n")
                }
                process.waitFor()
                delay(1000)
            } catch (e: Exception) {
                logToScreen("Error: ${e.message}\n")
                delay(2000)
            }
        }
    }

    private suspend fun CoroutineScope.runTraceroute(host: String) {
        logToScreen(">>> TRACEROUTE: $host\n(TTL Method)\n")
        var ttl = 1
        var reached = false
        while (ttl <= 30 && isActive && !reached) {
            try {
                val process = Runtime.getRuntime().exec("ping -c 1 -t $ttl -W 2 $host")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                var step = ""
                var found = false
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.contains("From") || line!!.contains("exceeded")) {
                        step = "Hop $ttl: ${extractIp(line!!)}"
                        found = true
                    }
                    if (line!!.contains("bytes from")) {
                        step = "Hop $ttl: $host (DONE! ✅)"
                        reached = true; found = true
                    }
                }
                process.waitFor()
                if (!found) step = "Hop $ttl: * * *"
                logToScreen(step + "\n")
                ttl++
            } catch (e: Exception) { break }
        }
        logToScreen("\n>>> DONE\n")
        withContext(Dispatchers.Main) { stopTask() }
    }

    private suspend fun CoroutineScope.runPortCheck(host: String, port: Int) {
        logToScreen(">>> CHECKING PORT $port on $host...\n")
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 2000)
            logToScreen("Result: Port $port is OPEN ✅\n")
            socket.close()
        } catch (e: Exception) {
            logToScreen("Result: Port $port is CLOSED ❌\n")
        }
        withContext(Dispatchers.Main) { stopTask() }
    }

    private suspend fun CoroutineScope.runNetworkScan() {
        val myIp = getMyIpAddress() // Uses new fixed logic
        if (myIp == null) {
            logToScreen("Error: No IP address found. Check Wi-Fi connection.\n")
            withContext(Dispatchers.Main) { stopTask() }
            return
        }

        // Calculate subnet (e.g., 192.168.0.)
        val subnet = myIp.substring(0, myIp.lastIndexOf(".") + 1)
        logToScreen(">>> IP SCAN: ${subnet}0/24\nYour IP: $myIp\nScanning active devices...\n\n")

        val allIps = (1..254).toList()
        val batchSize = 50
        val chunks = allIps.chunked(batchSize)
        var foundCount = 0

        for (chunk in chunks) {
            if (!isActive) break

            val deferredResults = chunk.map { i ->
                async(Dispatchers.IO) {
                    val hostToCheck = "$subnet$i"
                    if (hostToCheck == myIp) return@async null

                    try {
                        val process = Runtime.getRuntime().exec("ping -c 1 -W 1 $hostToCheck")
                        val exitValue = process.waitFor()

                        if (exitValue == 0) {
                            return@async try {
                                val inetAddr = java.net.InetAddress.getByName(hostToCheck)
                                val hostname = inetAddr.hostName
                                if (hostname != hostToCheck && hostname != null) "$hostToCheck ($hostname)" else hostToCheck
                            } catch (e: Exception) { hostToCheck }
                        }
                    } catch (e: Exception) { }
                    return@async null
                }
            }

            val foundInBatch = deferredResults.awaitAll().filterNotNull()
            foundInBatch.forEach { device ->
                logToScreen("[FOUND] $device\n")
                foundCount++
            }
            delay(50)
        }

        if (foundCount == 0) logToScreen("No other devices found.\n")
        logToScreen("\n>>> SCAN COMPLETED ($foundCount devices)\n")
        withContext(Dispatchers.Main) { stopTask() }
    }

    private suspend fun CoroutineScope.runQualityTest(host: String, count: Int) {
        logToScreen(">>> QUALITY TEST: $host\nPackets: $count\n")
        val totalPackets = count
        var receivedPackets = 0
        val latencies = ArrayList<Double>()

        for (i in 1..totalPackets) {
            if (!isActive) break
            val startTime = System.currentTimeMillis()
            try {
                val process = Runtime.getRuntime().exec("ping -c 1 -W 3 $host")
                val exitValue = process.waitFor()
                val pingTime = (System.currentTimeMillis() - startTime).toDouble()

                if (exitValue == 0) {
                    receivedPackets++
                    latencies.add(pingTime)
                    logToScreen("#$i: ${pingTime.toInt()} ms (OK)\n")
                } else {
                    logToScreen("#$i: LOST ❌\n")
                }
            } catch (e: Exception) { logToScreen("#$i: Error\n") }

            val sleepTime = if (totalPackets > 20) 50L else 200L
            delay(sleepTime)
        }

        logToScreen("\n--- RESULTS ---\n")
        val lossPercent = ((totalPackets - receivedPackets).toDouble() / totalPackets) * 100
        logToScreen("Loss: ${lossPercent.toInt()}%\n")
        if (receivedPackets > 1) {
            val avgPing = latencies.average()
            var jitterSum = 0.0
            for (i in 0 until latencies.size - 1) jitterSum += Math.abs(latencies[i] - latencies[i + 1])
            val jitter = jitterSum / (latencies.size - 1)
            logToScreen(String.format("Avg: %.1f ms\nJitter: %.1f ms\n", avgPing, jitter))
        }
        withContext(Dispatchers.Main) { stopTask() }
    }

    private suspend fun CoroutineScope.runPortScanner(host: String) {
        logToScreen(">>> FULL PORT SCAN: $host\n")
        logToScreen("Scanning ports 1-65535...\n")
        logToScreen("(This might take a minute, please wait)\n\n")

        val portsToScan = (1..65535).toList()
        val batchSize = 500
        val chunks = portsToScan.chunked(batchSize)
        var totalOpen = 0

        for ((index, chunk) in chunks.withIndex()) {
            if (!isActive) break

            val start = chunk.first()
            if (start % 5000 == 1) {
                logToScreen("Scanning > $start...\n")
            }

            val deferredResults = chunk.map { port ->
                async(Dispatchers.IO) {
                    try {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(host, port), 200)
                        socket.close()
                        return@async port
                    } catch (e: Exception) {
                        return@async null
                    }
                }
            }

            val openPortsInBatch = deferredResults.awaitAll().filterNotNull()
            openPortsInBatch.forEach { port ->
                val serviceName = portNames[port] ?: "TCP"
                logToScreen("[OPEN] Port $port ($serviceName) ✅\n")
                totalOpen++
            }
            delay(50)
        }

        logToScreen("\n>>> SCAN COMPLETED (Found $totalOpen open ports)\n")
        withContext(Dispatchers.Main) { stopTask() }
    }

    // =========================================================================
    // HELPER FUNCTIONS
    // =========================================================================

    private fun startTask(block: suspend CoroutineScope.() -> Unit) {
        activeJob?.cancel()
        tvLog.text = ""
        setButtonsState(isRunning = true)
        activeJob = CoroutineScope(Dispatchers.IO).launch { block() }
    }

    private fun stopTask() {
        activeJob?.cancel()
        activeJob = null
        setButtonsState(isRunning = false)
    }

    private suspend fun logToScreen(text: String) {
        withContext(Dispatchers.Main) {
            tvLog.append(text)
            autoScroll()
        }
    }

    private fun autoScroll() {
        val layout = tvLog.layout
        if (layout != null) {
            val scrollAmount = layout.getLineBottom(tvLog.lineCount - 1) - tvLog.height
            if (scrollAmount > 0) tvLog.scrollTo(0, scrollAmount) else tvLog.scrollTo(0, 0)
        }
    }

    private fun setButtonsState(isRunning: Boolean) {
        btnPing.isEnabled = !isRunning
        btnTrace.isEnabled = !isRunning
        btnCheckPort.isEnabled = !isRunning
        btnScan.isEnabled = !isRunning
        btnQuality.isEnabled = !isRunning
        btnScanPorts.isEnabled = !isRunning
        btnStop.isEnabled = isRunning
    }

    // --- UPDATED IP DETECTION ---
    // Now priorities 'wlan0' (Wi-Fi) over other interfaces
    private fun getMyIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            // 1. First pass: Look specifically for "wlan0" (Wi-Fi)
            for (intf in interfaces) {
                if (intf.name.contains("wlan") || intf.displayName.contains("wlan")) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
            }

            // 2. Second pass: Fallback to any other interface (Mobile Data / Ethernet)
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun extractIp(text: String): String {
        val parts = text.split(" ")
        for (part in parts) {
            if (part.contains(".") && part.any { it.isDigit() }) return part.replace(":", "")
        }
        return text
    }
}