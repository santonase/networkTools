package com.example.myapplication

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

    // Variable to control the active background job (allows stopping it)
    private var activeJob: Job? = null

    // UI Elements
    private lateinit var tvLog: TextView
    private lateinit var btnPing: Button
    private lateinit var btnStop: Button
    private lateinit var btnTrace: Button
    private lateinit var btnCheckPort: Button
    private lateinit var btnScan: Button
    private lateinit var btnQuality: Button
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etPackets: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        tvLog = findViewById(R.id.tvLog)
        tvLog.movementMethod = ScrollingMovementMethod() // Enable text scrolling

        btnPing = findViewById(R.id.btnPing)
        btnStop = findViewById(R.id.btnStop)
        btnTrace = findViewById(R.id.btnTrace)
        btnCheckPort = findViewById(R.id.btnCheckPort)
        btnScan = findViewById(R.id.btnScan)
        btnQuality = findViewById(R.id.btnQuality)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etPackets = findViewById(R.id.etPackets)

        // --- BUTTON LISTENERS ---

        // 1. PING BUTTON
        btnPing.setOnClickListener {
            val host = etHost.text.toString()
            if (host.isNotBlank()) startTask { runInfinitePing(host) }
        }

        // 2. STOP BUTTON
        btnStop.setOnClickListener {
            stopTask()
            tvLog.append("\n--- STOPPED BY USER ---\n")
            autoScroll()
        }

        // 3. TRACEROUTE BUTTON
        btnTrace.setOnClickListener {
            val host = etHost.text.toString()
            if (host.isNotBlank()) startTask { runTraceroute(host) }
        }

        // 4. PORT CHECK BUTTON
        btnCheckPort.setOnClickListener {
            val host = etHost.text.toString()
            val portStr = etPort.text.toString()
            if (host.isNotBlank() && portStr.isNotBlank()) {
                startTask { runPortCheck(host, portStr.toInt()) }
            }
        }

        // 5. SCAN NETWORK BUTTON
        btnScan.setOnClickListener {
            startTask { runNetworkScan() }
        }

        // 6. QUALITY TEST BUTTON
        btnQuality.setOnClickListener {
            val host = etHost.text.toString()
            val packetsStr = etPackets.text.toString()

            // Default to 10 packets if the field is empty
            val packetsCount = if (packetsStr.isNotBlank()) packetsStr.toInt() else 10

            if (host.isNotBlank()) {
                startTask { runQualityTest(host, packetsCount) }
            }
        }
    }

    // =========================================================================
    // CORE LOGIC FUNCTIONS
    // =========================================================================

    // --- FUNCTION 1: INFINITE PING ---
    private suspend fun CoroutineScope.runInfinitePing(host: String) {
        logToScreen(">>> START PING: $host\n")

        while (isActive) { // Loop runs while the job is active
            try {
                // Execute a single ping packet
                val process = Runtime.getRuntime().exec("ping -c 1 -W 2 $host")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?

                // Read output
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.contains("bytes from")) {
                        logToScreen(line + "\n")
                    }
                }
                process.waitFor()
                delay(1000) // 1-second delay between pings
            } catch (e: Exception) {
                logToScreen("Error: ${e.message}\n")
                delay(2000) // Wait longer on error
            }
        }
    }

    // --- FUNCTION 2: TRACEROUTE (TTL Method) ---
    private suspend fun CoroutineScope.runTraceroute(host: String) {
        logToScreen(">>> START TRACEROUTE: $host\n")
        logToScreen("(Using TTL method)\n\n")

        var ttl = 1
        val maxHops = 30
        var reachedDestination = false

        while (ttl <= maxHops && isActive && !reachedDestination) {
            try {
                // ping -c 1 (count) -t ttl (time to live) -W 2 (timeout)
                val process = Runtime.getRuntime().exec("ping -c 1 -t $ttl -W 2 $host")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                var stepOutput = ""
                var gotReply = false

                while (reader.readLine().also { line = it } != null) {
                    // Check for intermediate hop response
                    if (line!!.contains("From") || line!!.contains("exceeded")) {
                        val ip = extractIp(line!!)
                        stepOutput = "Hop $ttl: $ip"
                        gotReply = true
                    }
                    // Check if destination reached
                    if (line!!.contains("bytes from")) {
                        stepOutput = "Hop $ttl: $host (DONE! ✅)"
                        reachedDestination = true
                        gotReply = true
                    }
                }
                process.waitFor()

                if (!gotReply) {
                    stepOutput = "Hop $ttl: * * * (Request timed out)"
                }
                logToScreen(stepOutput + "\n")
                ttl++

            } catch (e: Exception) {
                logToScreen("Error on hop $ttl: ${e.message}\n")
                break
            }
        }
        logToScreen("\n>>> TRACEROUTE COMPLETED\n")
        withContext(Dispatchers.Main) { stopTask() }
    }

    // --- FUNCTION 3: PORT CHECK ---
    private suspend fun CoroutineScope.runPortCheck(host: String, port: Int) {
        logToScreen(">>> CHECKING $host:$port...\n")
        try {
            val socket = Socket()
            // Try connecting with a 2-second timeout
            socket.connect(InetSocketAddress(host, port), 2000)
            logToScreen("Result: Port $port is OPEN ✅\n")
            socket.close()
        } catch (e: Exception) {
            logToScreen("Result: Port $port is CLOSED or filtered ❌\n")
        }
        withContext(Dispatchers.Main) { stopTask() }
    }

    // --- FUNCTION 4: NETWORK SCANNER + HOSTNAMES ---
    private suspend fun CoroutineScope.runNetworkScan() {
        val myIp = getMyIpAddress()
        if (myIp == null) {
            logToScreen("Error: Could not determine IP address.\nAre you connected to Wi-Fi?\n")
            withContext(Dispatchers.Main) { stopTask() }
            return
        }

        val subnet = myIp.substring(0, myIp.lastIndexOf(".") + 1)
        logToScreen(">>> SCANNING NETWORK: ${subnet}0/24\n")
        logToScreen("Your IP: $myIp\nScanning active devices...\n\n")

        // Launch 254 parallel tasks
        val deferredResults = (1..254).map { i ->
            async(Dispatchers.IO) {
                val hostToCheck = "$subnet$i"
                if (hostToCheck == myIp) return@async null // Skip self

                try {
                    // Fast ping (1 sec timeout)
                    val process = Runtime.getRuntime().exec("ping -c 1 -W 1 $hostToCheck")
                    val exitValue = process.waitFor()

                    if (exitValue == 0) {
                        // If responsive, try Reverse DNS lookup
                        return@async try {
                            val inetAddr = java.net.InetAddress.getByName(hostToCheck)
                            val hostname = inetAddr.hostName
                            if (hostname != hostToCheck) "$hostToCheck ($hostname)" else hostToCheck
                        } catch (e: Exception) {
                            hostToCheck
                        }
                    }
                } catch (e: Exception) {
                    // Ignore ping errors
                }
                return@async null
            }
        }

        // Wait for all tasks to complete
        val activeHosts = deferredResults.awaitAll().filterNotNull()

        if (activeHosts.isEmpty()) {
            logToScreen("No active devices found (except you).\n")
        } else {
            activeHosts.forEach { logToScreen("[FOUND] $it\n") }
        }

        logToScreen("\n>>> SCAN COMPLETED (${activeHosts.size} devices found)\n")
        withContext(Dispatchers.Main) { stopTask() }
    }

    // --- FUNCTION 5: QUALITY TEST (Packet Loss & Jitter) ---
    private suspend fun CoroutineScope.runQualityTest(host: String, count: Int) {
        logToScreen(">>> QUALITY TEST: $host\n")
        logToScreen("Sending $count packets for analysis...\n")

        val totalPackets = count
        var receivedPackets = 0
        val latencies = ArrayList<Double>()

        for (i in 1..totalPackets) {
            if (!isActive) break

            val startTime = System.currentTimeMillis()
            try {
                val process = Runtime.getRuntime().exec("ping -c 1 -W 1 $host")
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

            // Adjust delay based on packet count to save time
            val sleepTime = if (totalPackets > 20) 50L else 200L
            delay(sleepTime)
        }

        logToScreen("\n--- RESULTS ---\n")
        val lossPercent = ((totalPackets - receivedPackets).toDouble() / totalPackets) * 100
        logToScreen("Packet Loss: ${lossPercent.toInt()}%\n")

        if (receivedPackets > 1) {
            val avgPing = latencies.average()
            // Jitter calculation (variation in latency)
            var jitterSum = 0.0
            for (i in 0 until latencies.size - 1) {
                jitterSum += Math.abs(latencies[i] - latencies[i + 1])
            }
            val jitter = jitterSum / (latencies.size - 1)

            logToScreen(String.format("Avg Ping: %.1f ms\n", avgPing))
            logToScreen(String.format("Jitter: %.1f ms\n", jitter))

            if (lossPercent == 0.0 && jitter < 20) logToScreen("Conclusion: EXCELLENT connection! ✅\n")
            else if (lossPercent > 0 || jitter > 100) logToScreen("Conclusion: POOR connection ⚠️\n")
            else logToScreen("Conclusion: Normal connection.\n")
        }

        withContext(Dispatchers.Main) { stopTask() }
    }

    // =========================================================================
    // HELPER FUNCTIONS
    // =========================================================================

    // Starts a task: cancels old one, clears UI, sets buttons
    private fun startTask(block: suspend CoroutineScope.() -> Unit) {
        activeJob?.cancel()
        tvLog.text = ""
        setButtonsState(isRunning = true)
        activeJob = CoroutineScope(Dispatchers.IO).launch { block() }
    }

    // Stops the current task
    private fun stopTask() {
        activeJob?.cancel()
        activeJob = null
        setButtonsState(isRunning = false)
    }

    // Appends text to the screen safely + Auto-scrolling
    private suspend fun logToScreen(text: String) {
        withContext(Dispatchers.Main) {
            tvLog.append(text)
            autoScroll()
        }
    }

    // Handles auto-scrolling to the bottom
    private fun autoScroll() {
        val layout = tvLog.layout
        if (layout != null) {
            val scrollAmount = layout.getLineBottom(tvLog.lineCount - 1) - tvLog.height
            if (scrollAmount > 0) tvLog.scrollTo(0, scrollAmount) else tvLog.scrollTo(0, 0)
        }
    }

    // Toggles button states
    private fun setButtonsState(isRunning: Boolean) {
        btnPing.isEnabled = !isRunning
        btnTrace.isEnabled = !isRunning
        btnCheckPort.isEnabled = !isRunning
        btnScan.isEnabled = !isRunning
        btnQuality.isEnabled = !isRunning
        btnStop.isEnabled = isRunning
    }

    // Gets local IP address
    private fun getMyIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return null
    }

    // Extracts IP from ping output string
    private fun extractIp(text: String): String {
        val parts = text.split(" ")
        for (part in parts) {
            if (part.contains(".") && part.any { it.isDigit() }) return part.replace(":", "")
        }
        return text
    }
}