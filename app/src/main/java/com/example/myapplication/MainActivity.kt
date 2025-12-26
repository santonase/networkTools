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

    private var activeJob: Job? = null

    private lateinit var tvLog: TextView
    private lateinit var btnPing: Button
    private lateinit var btnStop: Button
    private lateinit var btnTrace: Button
    private lateinit var btnCheckPort: Button
    private lateinit var btnScan: Button // Нова кнопка
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLog = findViewById(R.id.tvLog)
        tvLog.movementMethod = ScrollingMovementMethod()

        btnPing = findViewById(R.id.btnPing)
        btnStop = findViewById(R.id.btnStop)
        btnTrace = findViewById(R.id.btnTrace)
        btnCheckPort = findViewById(R.id.btnCheckPort)
        btnScan = findViewById(R.id.btnScan) // Знаходимо нову кнопку
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)

        btnPing.setOnClickListener {
            val host = etHost.text.toString()
            if (host.isNotBlank()) startTask { runInfinitePing(host) }
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

        // --- НОВА ЛОГІКА ДЛЯ КНОПКИ SCAN ---
        btnScan.setOnClickListener {
            startTask { runNetworkScan() }
        }

        btnStop.setOnClickListener {
            stopTask()
            tvLog.append("\n--- ЗУПИНЕНО КОРИСТУВАЧЕМ ---\n")
        }
    }

    // --- ФУНКЦІЯ 4: СКАНЕР МЕРЕЖІ (НОВА) ---
    // --- ОНОВЛЕНА ФУНКЦІЯ СКАНЕРА З ІМЕНАМИ ---
    private suspend fun CoroutineScope.runNetworkScan() {
        // 1. Дізнаємось свою IP адресу
        val myIp = getMyIpAddress()
        if (myIp == null) {
            logToScreen("Помилка: Не вдалося знайти IP пристрою. Ви підключені до Wi-Fi?\n")
            withContext(Dispatchers.Main) { stopTask() }
            return
        }

        val subnet = myIp.substring(0, myIp.lastIndexOf(".") + 1)

        logToScreen(">>> START SCANNING NETWORK: ${subnet}0/24\n")
        logToScreen("Ваш IP: $myIp\nШукаю пристрої та їх імена...\n\n")

        // 2. Запускаємо 254 потоки
        val deferredResults = (1..254).map { i ->
            async(Dispatchers.IO) {
                val hostToCheck = "$subnet$i"

                // Пропускаємо себе
                if (hostToCheck == myIp) return@async null

                try {
                    // КРОК А: Швидкий пінг (тайм-аут 1с)
                    // Якщо пристрій не пінгується, ми навіть не намагаємось дізнатись ім'я (це економить час)
                    val process = Runtime.getRuntime().exec("ping -c 1 -W 1 $hostToCheck")
                    val exitValue = process.waitFor()

                    if (exitValue == 0) {
                        // КРОК Б: Якщо пінг пройшов успішно, пробуємо дізнатись ім'я
                        return@async try {
                            val inetAddr = java.net.InetAddress.getByName(hostToCheck)
                            val hostname = inetAddr.hostName // Це займає трохи часу

                            if (hostname != hostToCheck) {
                                // Якщо ім'я відрізняється від IP - повертаємо красивий рядок
                                "$hostToCheck ($hostname)"
                            } else {
                                // Якщо імені немає, повертаємо тільки IP
                                hostToCheck
                            }
                        } catch (e: Exception) {
                            hostToCheck // Якщо помилка DNS - просто IP
                        }
                    }
                } catch (e: Exception) {
                    // ігноруємо помилки пінгу
                }
                return@async null
            }
        }

        val activeHosts = deferredResults.awaitAll().filterNotNull()

        if (activeHosts.isEmpty()) {
            logToScreen("Активних пристроїв не знайдено (крім вас).\n")
        } else {
            activeHosts.forEach { device ->
                logToScreen("[ЗНАЙДЕНО] $device\n")
            }
        }

        logToScreen("\n>>> SCAN COMPLETED (${activeHosts.size} devices found)\n")
        withContext(Dispatchers.Main) { stopTask() }
    }

    // Допоміжна функція: отримати IP адресу телефону
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

    // --- ІНШІ ФУНКЦІЇ (ТІ САМІ, ЩО Й РАНІШЕ) ---
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
                logToScreen("Error: ${e.message}\n"); break
            }
        }
    }

    private suspend fun CoroutineScope.runTraceroute(host: String) {
        logToScreen(">>> START TRACEROUTE: $host\n")
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
                        step = "Hop $ttl: $host (DONE!)"
                        reached = true; found = true
                    }
                }
                process.waitFor()
                if (!found) step = "Hop $ttl: * * *"
                logToScreen(step + "\n")
                ttl++
            } catch (e: Exception) { break }
        }
        withContext(Dispatchers.Main) { stopTask() }
    }

    private suspend fun CoroutineScope.runPortCheck(host: String, port: Int) {
        logToScreen(">>> CHECKING $host:$port...\n")
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 2000)
            logToScreen("Port $port is OPEN ✅\n"); socket.close()
        } catch (e: Exception) { logToScreen("Port $port is CLOSED ❌\n") }
        withContext(Dispatchers.Main) { stopTask() }
    }

    private fun extractIp(text: String): String {
        val parts = text.split(" ")
        for (part in parts) {
            if (part.contains(".") && part.any { it.isDigit() }) return part.replace(":", "")
        }
        return text
    }

    private fun startTask(block: suspend CoroutineScope.() -> Unit) {
        activeJob?.cancel()
        tvLog.text = ""
        setButtonsState(isRunning = true)
        activeJob = CoroutineScope(Dispatchers.IO).launch { block() }
    }

    private fun stopTask() {
        activeJob?.cancel(); activeJob = null
        setButtonsState(isRunning = false)
    }

    private suspend fun logToScreen(text: String) {
        withContext(Dispatchers.Main) {
            tvLog.append(text)
            val layout = tvLog.layout
            if (layout != null) {
                val scroll = layout.getLineBottom(tvLog.lineCount - 1) - tvLog.height
                if (scroll > 0) tvLog.scrollTo(0, scroll)
            }
        }
    }

    private fun setButtonsState(isRunning: Boolean) {
        btnPing.isEnabled = !isRunning
        btnTrace.isEnabled = !isRunning
        btnCheckPort.isEnabled = !isRunning
        btnScan.isEnabled = !isRunning // Блокуємо і цю кнопку
        btnStop.isEnabled = isRunning
    }
}