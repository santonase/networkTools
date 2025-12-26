package com.example.myapplication // ПЕРЕВІРТЕ, ЩОБ ЦЕЙ РЯДОК СПІВПАДАВ З ВАШИМ

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

    // Змінна для керування активним процесом (щоб можна було його зупинити)
    private var activeJob: Job? = null

    // Оголошення елементів інтерфейсу
    private lateinit var tvLog: TextView
    private lateinit var btnPing: Button
    private lateinit var btnStop: Button
    private lateinit var btnTrace: Button
    private lateinit var btnCheckPort: Button
    private lateinit var btnScan: Button
    private lateinit var btnQuality: Button
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Ініціалізація змінних (зв'язуємо з XML)
        tvLog = findViewById(R.id.tvLog)
        tvLog.movementMethod = ScrollingMovementMethod() // Вмикаємо прокрутку тексту

        btnPing = findViewById(R.id.btnPing)
        btnStop = findViewById(R.id.btnStop)
        btnTrace = findViewById(R.id.btnTrace)
        btnCheckPort = findViewById(R.id.btnCheckPort)
        btnScan = findViewById(R.id.btnScan)
        btnQuality = findViewById(R.id.btnQuality)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)

        // --- ОБРОБНИКИ НАТИСКАННЯ КНОПОК ---

        // 1. PING
        btnPing.setOnClickListener {
            val host = etHost.text.toString()
            if (host.isNotBlank()) startTask { runInfinitePing(host) }
        }

        // 2. STOP
        btnStop.setOnClickListener {
            stopTask()
            // Пишемо в лог вручну (безпечно, бо ми в головному потоці)
            tvLog.append("\n--- STOPPED BY USER ---\n")
            autoScroll()
        }

        // 3. TRACE (Трасування)
        btnTrace.setOnClickListener {
            val host = etHost.text.toString()
            if (host.isNotBlank()) startTask { runTraceroute(host) }
        }

        // 4. PORT CHECK
        btnCheckPort.setOnClickListener {
            val host = etHost.text.toString()
            val portStr = etPort.text.toString()
            if (host.isNotBlank() && portStr.isNotBlank()) {
                startTask { runPortCheck(host, portStr.toInt()) }
            }
        }

        // 5. SCAN LAN (Сканер мережі)
        btnScan.setOnClickListener {
            startTask { runNetworkScan() }
        }

        // 6. QUALITY TEST (Тест якості)
        btnQuality.setOnClickListener {
            val host = etHost.text.toString()
            if (host.isNotBlank()) startTask { runQualityTest(host) }
        }
    }

    // =========================================================================
    // ОСНОВНІ ФУНКЦІЇ (ЛОГІКА)
    // =========================================================================

    // --- ФУНКЦІЯ 1: НЕСКІНЧЕННИЙ ПІНГ ---
    private suspend fun CoroutineScope.runInfinitePing(host: String) {
        logToScreen(">>> START PING: $host\n")

        while (isActive) { // Цикл працює, поки активна корутина
            try {
                // Запускаємо 1 пакет пінгу
                val process = Runtime.getRuntime().exec("ping -c 1 -W 2 $host")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?

                // Читаємо відповідь
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.contains("bytes from")) {
                        logToScreen(line + "\n")
                    }
                }
                process.waitFor()
                delay(1000) // Пауза 1 сек між пакетами
            } catch (e: Exception) {
                logToScreen("Error: ${e.message}\n")
                delay(2000) // Якщо помилка, чекаємо довше перед повтором
            }
        }
    }

    // --- ФУНКЦІЯ 2: ТРАСУВАННЯ (Метод TTL) ---
    private suspend fun CoroutineScope.runTraceroute(host: String) {
        logToScreen(">>> START TRACEROUTE: $host\n")
        logToScreen("(Using TTL method)\n\n")

        var ttl = 1
        val maxHops = 30
        var reachedDestination = false

        while (ttl <= maxHops && isActive && !reachedDestination) {
            try {
                // ping -c 1 (один пакет) -t ttl (час життя) -W 2 (тайм-аут)
                val process = Runtime.getRuntime().exec("ping -c 1 -t $ttl -W 2 $host")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                var stepOutput = ""
                var gotReply = false

                while (reader.readLine().also { line = it } != null) {
                    // Якщо відповідь від проміжного вузла
                    if (line!!.contains("From") || line!!.contains("exceeded")) {
                        val ip = extractIp(line!!)
                        stepOutput = "Hop $ttl: $ip"
                        gotReply = true
                    }
                    // Якщо дійшли до цілі
                    if (line!!.contains("bytes from")) {
                        stepOutput = "Hop $ttl: $host (DONE! ✅)"
                        reachedDestination = true
                        gotReply = true
                    }
                }
                process.waitFor()

                if (!gotReply) {
                    stepOutput = "Hop $ttl: * * * (Time out)"
                }
                logToScreen(stepOutput + "\n")
                ttl++

            } catch (e: Exception) {
                logToScreen("Error on hop $ttl: ${e.message}\n")
                break
            }
        }
        logToScreen("\n>>> TRACEROUTE COMPLETED\n")
        withContext(Dispatchers.Main) { stopTask() } // Авто-стоп
    }

    // --- ФУНКЦІЯ 3: ПЕРЕВІРКА ПОРТУ ---
    private suspend fun CoroutineScope.runPortCheck(host: String, port: Int) {
        logToScreen(">>> CHECKING $host:$port...\n")
        try {
            val socket = Socket()
            // Пробуємо підключитися з тайм-аутом 2000 мс
            socket.connect(InetSocketAddress(host, port), 2000)
            logToScreen("Result: Port $port is OPEN ✅\n")
            socket.close()
        } catch (e: Exception) {
            logToScreen("Result: Port $port is CLOSED or filtered ❌\n")
        }
        withContext(Dispatchers.Main) { stopTask() }
    }

    // --- ФУНКЦІЯ 4: СКАНЕР МЕРЕЖІ + ІМЕНА ---
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

        // Запускаємо 254 паралельних задачі
        val deferredResults = (1..254).map { i ->
            async(Dispatchers.IO) {
                val hostToCheck = "$subnet$i"
                if (hostToCheck == myIp) return@async null // Пропускаємо себе

                try {
                    // Швидкий пінг (1 сек тайм-аут)
                    val process = Runtime.getRuntime().exec("ping -c 1 -W 1 $hostToCheck")
                    val exitValue = process.waitFor()

                    if (exitValue == 0) {
                        // Якщо є відповідь, пробуємо дізнатись ім'я (Reverse DNS)
                        return@async try {
                            val inetAddr = java.net.InetAddress.getByName(hostToCheck)
                            val hostname = inetAddr.hostName
                            if (hostname != hostToCheck) "$hostToCheck ($hostname)" else hostToCheck
                        } catch (e: Exception) {
                            hostToCheck
                        }
                    }
                } catch (e: Exception) {
                    // Ignore errors
                }
                return@async null
            }
        }

        // Чекаємо завершення всіх потоків
        val activeHosts = deferredResults.awaitAll().filterNotNull()

        if (activeHosts.isEmpty()) {
            logToScreen("No active devices found (except you).\n")
        } else {
            activeHosts.forEach { logToScreen("[FOUND] $it\n") }
        }

        logToScreen("\n>>> SCAN COMPLETED (${activeHosts.size} devices)\n")
        withContext(Dispatchers.Main) { stopTask() }
    }

    // --- ФУНКЦІЯ 5: TEST QUALITY (Packet Loss & Jitter) ---
    private suspend fun CoroutineScope.runQualityTest(host: String) {
        logToScreen(">>> QUALITY TEST: $host\n")
        logToScreen("Sending 10 packets for analysis...\n")

        val totalPackets = 10
        var receivedPackets = 0
        val latencies = ArrayList<Double>()

        for (i in 1..totalPackets) {
            if (!isActive) break

            val startTime = System.currentTimeMillis()
            try {
                val process = Runtime.getRuntime().exec("ping -c 1 -W 1 $host")
                val exitValue = process.waitFor()
                val endTime = System.currentTimeMillis()
                val pingTime = (endTime - startTime).toDouble()

                if (exitValue == 0) {
                    receivedPackets++
                    latencies.add(pingTime)
                    logToScreen("#$i: ${pingTime.toInt()} ms (OK)\n")
                } else {
                    logToScreen("#$i: LOST ❌\n")
                }
            } catch (e: Exception) {
                logToScreen("#$i: Error\n")
            }
            delay(200) // Інтервал для точності
        }

        logToScreen("\n--- RESULTS ---\n")
        val lossPercent = ((totalPackets - receivedPackets).toDouble() / totalPackets) * 100
        logToScreen("Packet Loss: ${lossPercent.toInt()}%\n")

        if (receivedPackets > 1) {
            val avgPing = latencies.average()
            // Розрахунок джитера (варіація затримки)
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
    // ДОПОМІЖНІ ФУНКЦІЇ
    // =========================================================================

    // Запуск завдання (очищає старе, готує UI)
    private fun startTask(block: suspend CoroutineScope.() -> Unit) {
        activeJob?.cancel() // Зупиняємо попереднє, якщо було
        tvLog.text = "" // Чистимо екран
        setButtonsState(isRunning = true) // Блокуємо кнопки

        // Запускаємо нову корутину в IO потоці
        activeJob = CoroutineScope(Dispatchers.IO).launch {
            block()
        }
    }

    // Зупинка завдання
    private fun stopTask() {
        activeJob?.cancel()
        activeJob = null
        setButtonsState(isRunning = false)
    }

    // Вивід тексту на екран (безпечно для потоків) + Автоскрол
    private suspend fun logToScreen(text: String) {
        withContext(Dispatchers.Main) {
            tvLog.append(text)
            autoScroll()
        }
    }

    // Логіка автопрокрутки вниз
    private fun autoScroll() {
        val layout = tvLog.layout
        if (layout != null) {
            val scrollAmount = layout.getLineBottom(tvLog.lineCount - 1) - tvLog.height
            if (scrollAmount > 0) {
                tvLog.scrollTo(0, scrollAmount)
            } else {
                tvLog.scrollTo(0, 0)
            }
        }
    }

    // Керування станом кнопок (увімк/вимк)
    private fun setButtonsState(isRunning: Boolean) {
        btnPing.isEnabled = !isRunning
        btnTrace.isEnabled = !isRunning
        btnCheckPort.isEnabled = !isRunning
        btnScan.isEnabled = !isRunning
        btnQuality.isEnabled = !isRunning
        btnStop.isEnabled = isRunning
    }

    // Отримання IP адреси пристрою
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    // Витягування IP з рядка пінгу (Regex-style)
    private fun extractIp(text: String): String {
        val parts = text.split(" ")
        for (part in parts) {
            // Шукаємо щось, що має крапки і цифри, і прибираємо двокрапку
            if (part.contains(".") && part.any { it.isDigit() }) {
                return part.replace(":", "")
            }
        }
        return text
    }
}