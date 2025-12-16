/**
 * Worker Node in Kotlin - Distributed Training System
 *
 * Complete Worker implementation compatible with Python and Go workers.
 * Uses only standard library (java.net sockets).
 *
 * Features:
 * - TCP Server for client requests (TRAIN, PREDICT, LIST_MODELS)
 * - RAFT consensus for replication
 * - HTTP Monitor for status visualization
 * - Calls Java TrainingModule for neural network operations
 */

import java.io.*
import java.net.*
import java.time.Instant
import java.util.*
import java.util.concurrent.*
import kotlin.concurrent.thread

// Global state
lateinit var raftNode: RaftNode
lateinit var storageDir: String
lateinit var modelsDir: String
var javaDir = "java"
var logFile: File? = null
val logLock = Object()

fun main(args: Array<String>) {
    // Parse arguments
    var host = "0.0.0.0"
    var port = 9000
    var monitorPort = 8000
    var raftPort = 10000
    var peers = listOf<String>()
    var storageDirArg: String? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[++i] }
            "--port" -> { port = args[++i].toInt() }
            "--monitor-port" -> { monitorPort = args[++i].toInt() }
            "--raft-port" -> { raftPort = args[++i].toInt() }
            "--peers" -> { peers = args[++i].split(",").filter { it.isNotBlank() } }
            "--storage-dir" -> { storageDirArg = args[++i] }
            "--java-dir" -> { javaDir = args[++i] }
        }
        i++
    }

    // Configure directories
    storageDir = storageDirArg ?: "node${port - 9000}_storage"
    modelsDir = "$storageDir/models"
    File(storageDir).mkdirs()
    File(modelsDir).mkdirs()

    // Setup logging
    logFile = File("$storageDir/worker.log")

    // Parse RAFT peers
    val raftPeers = peers.mapNotNull { p ->
        val parts = p.trim().split(":")
        if (parts.size == 2) {
            val peerPort = parts[1].toInt()
            val raftPeerPort = raftPort + (peerPort - port)
            Peer(parts[0], raftPeerPort, peerPort)
        } else null
    }

    // Initialize RAFT
    raftNode = RaftNode("$host:$port", host, raftPort, raftPeers, port)
    thread { raftNode.start() }

    log("Worker started: host=$host, port=$port, raft_port=$raftPort")
    log("Storage: $storageDir, Models: $modelsDir")
    log("Peers: $raftPeers")

    // Start HTTP monitor
    thread { startHttpMonitor(host, monitorPort) }

    // Start TCP server (blocking)
    startTcpServer(host, port)
}

fun log(msg: String) {
    synchronized(logLock) {
        val timestamp = Instant.now().toString()
        val line = "$timestamp $msg"
        println(line)
        logFile?.appendText("$line\n")
    }
}

// ============================================================================
// TCP Server
// ============================================================================

fun startTcpServer(host: String, port: Int) {
    val server = ServerSocket(port, 50, InetAddress.getByName(host))
    log("Starting TCP server on $host:$port")

    while (true) {
        val client = server.accept()
        thread { handleConnection(client) }
    }
}

fun handleConnection(client: Socket) {
    try {
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val writer = PrintWriter(client.getOutputStream(), true)

        val line = reader.readLine() ?: return
        val msg = parseJson(line)
        val type = msg["type"] as? String ?: "UNKNOWN"

        when (type) {
            "TRAIN" -> handleTrain(msg, writer)
            "PREDICT" -> handlePredict(msg, writer)
            "LIST_MODELS" -> handleListModels(writer)
            else -> sendResponse(writer, mapOf("status" to "ERROR", "message" to "Unknown type: $type"))
        }
    } catch (e: Exception) {
        log("Handler error: ${e.message}")
    } finally {
        client.close()
    }
}

fun sendResponse(writer: PrintWriter, resp: Map<String, Any?>) {
    writer.println(toJson(resp))
}

// ============================================================================
// Message Handlers
// ============================================================================

fun handleTrain(msg: Map<String, Any?>, writer: PrintWriter) {
    @Suppress("UNCHECKED_CAST")
    val inputs = msg["inputs"] as? List<List<Double>> ?: emptyList()
    @Suppress("UNCHECKED_CAST")
    val outputs = msg["outputs"] as? List<Any> ?: emptyList()

    if (inputs.isEmpty() || outputs.isEmpty()) {
        sendResponse(writer, mapOf("status" to "ERROR", "message" to "Missing inputs or outputs"))
        return
    }

    log("TRAIN request: ${inputs.size} samples")

    // Check if leader
    if (!raftNode.isLeader()) {
        val leader = raftNode.leader
        if (leader != null) {
            sendResponse(writer, mapOf(
                "status" to "REDIRECT",
                "leader" to listOf(leader.host, leader.workerPort)
            ))
            return
        }
        sendResponse(writer, mapOf("status" to "ERROR", "message" to "No leader available"))
        return
    }

    // Generate training ID
    val trainId = (System.currentTimeMillis() % 100000000).toString()
    val inputsFile = "$modelsDir/inputs_$trainId.csv"
    val outputsFile = "$modelsDir/outputs_$trainId.csv"
    val modelPath = "$modelsDir/model_$trainId.bin"

    // Write CSV files
    writeCsv(inputsFile, inputs)
    writeCsvAny(outputsFile, outputs)

    log("Training data saved: $inputsFile, $outputsFile")

    // Run Java training
    val modelId = runJavaTraining(inputsFile, outputsFile, modelPath)

    // Cleanup
    File(inputsFile).delete()
    File(outputsFile).delete()

    if (modelId != null) {
        // Replicate via RAFT
        raftNode.replicate(mapOf("action" to "MODEL_TRAINED", "model_id" to modelId))
        sendResponse(writer, mapOf("status" to "OK", "model_id" to modelId))
    } else {
        sendResponse(writer, mapOf("status" to "ERROR", "message" to "Training failed"))
    }
}

fun handlePredict(msg: Map<String, Any?>, writer: PrintWriter) {
    val modelId = msg["model_id"] as? String ?: ""
    @Suppress("UNCHECKED_CAST")
    val input = msg["input"] as? List<Double> ?: emptyList()

    if (modelId.isEmpty()) {
        sendResponse(writer, mapOf("status" to "ERROR", "message" to "Missing model_id"))
        return
    }

    log("PREDICT request: model=$modelId")

    val modelPath = findModel(modelId)
    if (modelPath == null) {
        sendResponse(writer, mapOf("status" to "ERROR", "message" to "Model not found: $modelId"))
        return
    }

    val inputStr = input.joinToString(",")
    val output = runJavaPrediction(modelPath, inputStr)

    if (output != null) {
        sendResponse(writer, mapOf("status" to "OK", "output" to output))
    } else {
        sendResponse(writer, mapOf("status" to "ERROR", "message" to "Prediction failed"))
    }
}

fun handleListModels(writer: PrintWriter) {
    log("LIST_MODELS request")

    val models = File(modelsDir).listFiles { f -> f.extension == "bin" }
        ?.map { it.nameWithoutExtension.removePrefix("model_") }
        ?: emptyList()

    sendResponse(writer, mapOf("status" to "OK", "models" to models))
}

// ============================================================================
// Java Integration
// ============================================================================

fun runJavaTraining(inputsFile: String, outputsFile: String, modelPath: String): String? {
    val cmd = listOf("java", "-cp", javaDir, "TrainingModule", "train", inputsFile, outputsFile, "1000", modelPath)
    log("Running: ${cmd.joinToString(" ")}")

    return try {
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        var modelId: String? = null
        process.inputStream.bufferedReader().forEachLine { line ->
            log("JAVA: $line")
            if (line.startsWith("MODEL_ID:")) {
                modelId = line.substringAfter("MODEL_ID:")
            }
        }

        process.waitFor()
        modelId
    } catch (e: Exception) {
        log("Java training error: ${e.message}")
        null
    }
}

fun runJavaPrediction(modelPath: String, inputStr: String): List<Double>? {
    val cmd = listOf("java", "-cp", javaDir, "TrainingModule", "predict", modelPath, inputStr)
    log("Running: ${cmd.joinToString(" ")}")

    return try {
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        var prediction: List<Double>? = null
        process.inputStream.bufferedReader().forEachLine { line ->
            if (line.startsWith("PREDICTION:")) {
                prediction = line.substringAfter("PREDICTION:")
                    .split(",")
                    .map { it.trim().toDouble() }
            }
        }

        process.waitFor()
        prediction
    } catch (e: Exception) {
        log("Java prediction error: ${e.message}")
        null
    }
}

fun findModel(modelId: String): String? {
    // Try exact match
    val exact = File("$modelsDir/model_$modelId.bin")
    if (exact.exists()) return exact.absolutePath

    // Try partial match
    return File(modelsDir).listFiles { f ->
        f.name.contains(modelId) && f.extension == "bin"
    }?.firstOrNull()?.absolutePath
}

fun writeCsv(path: String, data: List<List<Double>>) {
    File(path).printWriter().use { out ->
        data.forEach { row ->
            out.println(row.joinToString(","))
        }
    }
}

fun writeCsvAny(path: String, data: List<Any>) {
    File(path).printWriter().use { out ->
        data.forEach { row ->
            when (row) {
                is List<*> -> out.println(row.joinToString(","))
                else -> out.println(row)
            }
        }
    }
}

// ============================================================================
// HTTP Monitor
// ============================================================================

fun startHttpMonitor(host: String, port: Int) {
    val server = ServerSocket(port, 50, InetAddress.getByName(host))
    log("Starting HTTP monitor on $host:$port")

    while (true) {
        val client = server.accept()
        thread { handleHttpRequest(client) }
    }
}

fun handleHttpRequest(client: Socket) {
    try {
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val writer = PrintWriter(client.getOutputStream())

        val requestLine = reader.readLine() ?: return
        val path = requestLine.split(" ").getOrNull(1) ?: "/"

        // Read headers (discard)
        while (reader.readLine()?.isNotEmpty() == true) { }

        when (path) {
            "/" -> sendHtml(writer, getDashboardHtml())
            "/status" -> sendJson(writer, getStatusJson())
            "/models" -> sendJson(writer, getModelsJson())
            "/logs" -> sendText(writer, logFile?.readText() ?: "No logs")
            else -> send404(writer)
        }
    } catch (e: Exception) {
        // Ignore
    } finally {
        client.close()
    }
}

fun sendHtml(writer: PrintWriter, html: String) {
    writer.print("HTTP/1.1 200 OK\r\n")
    writer.print("Content-Type: text/html; charset=utf-8\r\n")
    writer.print("Content-Length: ${html.toByteArray().size}\r\n")
    writer.print("\r\n")
    writer.print(html)
    writer.flush()
}

fun sendJson(writer: PrintWriter, json: String) {
    writer.print("HTTP/1.1 200 OK\r\n")
    writer.print("Content-Type: application/json\r\n")
    writer.print("Content-Length: ${json.toByteArray().size}\r\n")
    writer.print("\r\n")
    writer.print(json)
    writer.flush()
}

fun sendText(writer: PrintWriter, text: String) {
    writer.print("HTTP/1.1 200 OK\r\n")
    writer.print("Content-Type: text/plain; charset=utf-8\r\n")
    writer.print("Content-Length: ${text.toByteArray().size}\r\n")
    writer.print("\r\n")
    writer.print(text)
    writer.flush()
}

fun send404(writer: PrintWriter) {
    val body = "Not Found"
    writer.print("HTTP/1.1 404 Not Found\r\n")
    writer.print("Content-Length: ${body.length}\r\n")
    writer.print("\r\n")
    writer.print(body)
    writer.flush()
}

fun getStatusJson(): String {
    return """{"state":"${raftNode.state}","term":${raftNode.currentTerm},"leader":${if (raftNode.leader != null) "\"${raftNode.leader}\"" else "null"},"log_length":${raftNode.log.size}}"""
}

fun getModelsJson(): String {
    val models = File(modelsDir).listFiles { f -> f.extension == "bin" }
        ?.map { "\"${it.name}\"" }
        ?: emptyList()
    return """{"models":[${models.joinToString(",")}]}"""
}

fun getDashboardHtml(): String = """
<!DOCTYPE html>
<html>
<head>
    <title>Worker Monitor (Kotlin)</title>
    <style>
        body { font-family: monospace; background: #1a1a2e; color: #eee; padding: 20px; }
        h1 { color: #B125EA; }
        .card { background: #16213e; padding: 15px; margin: 10px 0; border-radius: 8px; }
        .label { color: #888; }
        .leader { color: #00ff88; }
        .follower { color: #ffaa00; }
        pre { background: #0f0f23; padding: 10px; overflow-x: auto; max-height: 400px; }
        .kotlin-badge { background: #B125EA; color: white; padding: 2px 8px; border-radius: 4px; }
    </style>
</head>
<body>
    <h1>🖥️ Worker Monitor <span class="kotlin-badge">Kotlin</span></h1>
    <div class="card">
        <div class="label">RAFT Status</div>
        <div id="status">Loading...</div>
    </div>
    <div class="card">
        <div class="label">Trained Models</div>
        <div id="models">Loading...</div>
    </div>
    <div class="card">
        <div class="label">Recent Logs</div>
        <pre id="logs">Loading...</pre>
    </div>
    <script>
        async function refresh() {
            try {
                const status = await fetch('/status').then(r => r.json());
                document.getElementById('status').innerHTML = 
                    '<span class="' + status.state + '">' + status.state.toUpperCase() + '</span> | ' +
                    'Term: ' + status.term + ' | Leader: ' + status.leader +
                    ' | Log: ' + status.log_length + ' entries';
            } catch(e) { document.getElementById('status').textContent = 'Error'; }

            try {
                const models = await fetch('/models').then(r => r.json());
                document.getElementById('models').innerHTML = models.models && models.models.length 
                    ? models.models.map(m => '<div>📦 ' + m + '</div>').join('')
                    : '<em>No models yet</em>';
            } catch(e) { document.getElementById('models').textContent = 'Error'; }

            try {
                const logs = await fetch('/logs').then(r => r.text());
                const lines = logs.split('\n').slice(-50).join('\n');
                document.getElementById('logs').textContent = lines || 'No logs';
            } catch(e) { document.getElementById('logs').textContent = 'Error'; }
        }
        refresh();
        setInterval(refresh, 3000);
    </script>
</body>
</html>
"""

// ============================================================================
// Simple JSON Parser/Writer (minimal, no dependencies)
// ============================================================================

fun parseJson(json: String): Map<String, Any?> {
    // Very simple JSON parser for our protocol
    val result = mutableMapOf<String, Any?>()
    val content = json.trim().removeSurrounding("{", "}")

    var depth = 0
    var inString = false
    var currentKey = ""
    var currentValue = StringBuilder()
    var parsingKey = true

    var i = 0
    while (i < content.length) {
        val c = content[i]

        when {
            c == '"' && (i == 0 || content[i - 1] != '\\') -> {
                inString = !inString
                if (!inString && parsingKey) {
                    currentKey = currentValue.toString()
                    currentValue = StringBuilder()
                }
            }
            c == ':' && !inString && depth == 0 -> {
                parsingKey = false
            }
            c == ',' && !inString && depth == 0 -> {
                result[currentKey] = parseValue(currentValue.toString().trim())
                currentKey = ""
                currentValue = StringBuilder()
                parsingKey = true
            }
            c in listOf('[', '{') && !inString -> {
                depth++
                currentValue.append(c)
            }
            c in listOf(']', '}') && !inString -> {
                depth--
                currentValue.append(c)
            }
            !parsingKey || inString -> {
                currentValue.append(c)
            }
        }
        i++
    }

    if (currentKey.isNotEmpty()) {
        result[currentKey] = parseValue(currentValue.toString().trim())
    }

    return result
}

fun parseValue(value: String): Any? {
    return when {
        value == "null" -> null
        value == "true" -> true
        value == "false" -> false
        value.startsWith("\"") -> value.removeSurrounding("\"")
        value.startsWith("[") -> parseArray(value)
        value.startsWith("{") -> parseJson(value)
        value.contains(".") -> value.toDoubleOrNull() ?: value
        else -> value.toLongOrNull() ?: value
    }
}

fun parseArray(json: String): List<Any?> {
    val content = json.trim().removeSurrounding("[", "]")
    if (content.isBlank()) return emptyList()

    val result = mutableListOf<Any?>()
    var depth = 0
    var inString = false
    var current = StringBuilder()

    for ((i, c) in content.withIndex()) {
        when {
            c == '"' && (i == 0 || content[i - 1] != '\\') -> {
                inString = !inString
                current.append(c)
            }
            c == ',' && !inString && depth == 0 -> {
                result.add(parseValue(current.toString().trim()))
                current = StringBuilder()
            }
            c in listOf('[', '{') && !inString -> {
                depth++
                current.append(c)
            }
            c in listOf(']', '}') && !inString -> {
                depth--
                current.append(c)
            }
            else -> current.append(c)
        }
    }

    if (current.isNotBlank()) {
        result.add(parseValue(current.toString().trim()))
    }

    return result
}

fun toJson(map: Map<String, Any?>): String {
    val entries = map.entries.joinToString(",") { (k, v) ->
        "\"$k\":${valueToJson(v)}"
    }
    return "{$entries}"
}

fun valueToJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"$value\""
    is Number -> value.toString()
    is Boolean -> value.toString()
    is List<*> -> "[${value.joinToString(",") { valueToJson(it) }}]"
    is Map<*, *> -> toJson(value.mapKeys { it.key.toString() }.mapValues { it.value })
    else -> "\"$value\""
}
