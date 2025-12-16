/*
Worker Node in Go - Distributed Training System

This is a complete Worker implementation compatible with the Python workers.
Uses only standard library (no external dependencies).

Features:
- TCP Server for client requests (TRAIN, PREDICT, LIST_MODELS)
- RAFT consensus for replication
- HTTP Monitor for status visualization
- Calls Java TrainingModule for neural network operations
*/
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Global state
var (
	raftNode   *RaftNode
	storageDir string
	modelsDir  string
	javaDir    string
	logFile    *os.File
	logMutex   sync.Mutex
)

func main() {
	// Parse command line arguments
	host := flag.String("host", "0.0.0.0", "Host to bind")
	port := flag.Int("port", 9000, "TCP port for client connections")
	monitorPort := flag.Int("monitor-port", 8000, "HTTP port for monitor")
	raftPort := flag.Int("raft-port", 10000, "Port for RAFT RPCs")
	peersStr := flag.String("peers", "", "Comma-separated list of peers (host:port)")
	storageDirFlag := flag.String("storage-dir", "", "Storage directory")
	javaDirFlag := flag.String("java-dir", "java", "Java classes directory")
	flag.Parse()

	// Configure directories
	if *storageDirFlag != "" {
		storageDir = *storageDirFlag
	} else {
		storageDir = fmt.Sprintf("node%d_storage", *port-9000)
	}
	modelsDir = filepath.Join(storageDir, "models")
	javaDir = *javaDirFlag

	// Create directories
	os.MkdirAll(storageDir, 0755)
	os.MkdirAll(modelsDir, 0755)

	// Setup logging
	logPath := filepath.Join(storageDir, "worker.log")
	var err error
	logFile, err = os.OpenFile(logPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err != nil {
		log.Fatal("Failed to open log file:", err)
	}
	defer logFile.Close()

	// Parse peers
	var peers []Peer
	if *peersStr != "" {
		for _, p := range strings.Split(*peersStr, ",") {
			parts := strings.Split(strings.TrimSpace(p), ":")
			if len(parts) == 2 {
				var peerPort int
				fmt.Sscanf(parts[1], "%d", &peerPort)
				// Calculate RAFT port for peer
				raftPeerPort := *raftPort + (peerPort - *port)
				peers = append(peers, Peer{Host: parts[0], Port: raftPeerPort})
			}
		}
	}

	// Initialize RAFT node
	nodeID := fmt.Sprintf("%s:%d", *host, *port)
	raftNode = NewRaftNode(nodeID, *host, *raftPort, peers, *port)
	go raftNode.Start()

	logMsg("Worker started: host=%s, port=%d, raft_port=%d", *host, *port, *raftPort)
	logMsg("Storage: %s, Models: %s", storageDir, modelsDir)
	logMsg("Peers: %v", peers)

	// Start HTTP monitor
	go startHTTPMonitor(*host, *monitorPort)

	// Start TCP server (blocking)
	startTCPServer(*host, *port)
}

func logMsg(format string, args ...interface{}) {
	logMutex.Lock()
	defer logMutex.Unlock()

	msg := fmt.Sprintf(format, args...)
	timestamp := time.Now().UTC().Format(time.RFC3339)
	line := fmt.Sprintf("%s %s\n", timestamp, msg)

	fmt.Print(line)
	if logFile != nil {
		logFile.WriteString(line)
	}
}

// ============================================================================
// TCP Server
// ============================================================================

func startTCPServer(host string, port int) {
	addr := fmt.Sprintf("%s:%d", host, port)
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatal("TCP listen error:", err)
	}
	defer listener.Close()

	logMsg("Starting TCP server on %s", addr)

	for {
		conn, err := listener.Accept()
		if err != nil {
			logMsg("Accept error: %v", err)
			continue
		}
		go handleConnection(conn)
	}
}

func handleConnection(conn net.Conn) {
	defer conn.Close()

	reader := bufio.NewReader(conn)
	line, err := reader.ReadString('\n')
	if err != nil && err != io.EOF {
		logMsg("Read error: %v", err)
		return
	}

	var msg map[string]interface{}
	if err := json.Unmarshal([]byte(line), &msg); err != nil {
		logMsg("JSON parse error: %v", err)
		sendResponse(conn, map[string]interface{}{"status": "ERROR", "message": "Invalid JSON"})
		return
	}

	msgType, _ := msg["type"].(string)
	switch msgType {
	case "TRAIN":
		handleTrain(conn, msg)
	case "PREDICT":
		handlePredict(conn, msg)
	case "LIST_MODELS":
		handleListModels(conn)
	default:
		sendResponse(conn, map[string]interface{}{"status": "ERROR", "message": "Unknown type"})
	}
}

func sendResponse(conn net.Conn, resp map[string]interface{}) {
	data, _ := json.Marshal(resp)
	conn.Write(append(data, '\n'))
}

// ============================================================================
// Message Handlers
// ============================================================================

func handleTrain(conn net.Conn, msg map[string]interface{}) {
	inputsRaw, _ := msg["inputs"].([]interface{})
	outputsRaw, _ := msg["outputs"].([]interface{})

	if len(inputsRaw) == 0 || len(outputsRaw) == 0 {
		sendResponse(conn, map[string]interface{}{"status": "ERROR", "message": "Missing inputs or outputs"})
		return
	}

	logMsg("TRAIN request: %d samples", len(inputsRaw))

	// Check if we are leader
	if !raftNode.IsLeader() {
		leader := raftNode.GetLeader()
		if leader != nil {
			sendResponse(conn, map[string]interface{}{
				"status": "REDIRECT",
				"leader": []interface{}{leader.Host, leader.WorkerPort},
			})
			return
		}
		sendResponse(conn, map[string]interface{}{"status": "ERROR", "message": "No leader available"})
		return
	}

	// Generate training ID
	trainID := fmt.Sprintf("%d", time.Now().UnixNano()%100000000)

	// Write CSV files
	inputsFile := filepath.Join(modelsDir, fmt.Sprintf("inputs_%s.csv", trainID))
	outputsFile := filepath.Join(modelsDir, fmt.Sprintf("outputs_%s.csv", trainID))
	modelPath := filepath.Join(modelsDir, fmt.Sprintf("model_%s.bin", trainID))

	if err := writeCSV(inputsFile, inputsRaw); err != nil {
		sendResponse(conn, map[string]interface{}{"status": "ERROR", "message": err.Error()})
		return
	}
	if err := writeCSV(outputsFile, outputsRaw); err != nil {
		sendResponse(conn, map[string]interface{}{"status": "ERROR", "message": err.Error()})
		return
	}

	logMsg("Training data saved: %s, %s", inputsFile, outputsFile)

	// Run Java training
	modelID := runJavaTraining(inputsFile, outputsFile, modelPath)

	// Cleanup temp files
	os.Remove(inputsFile)
	os.Remove(outputsFile)

	if modelID != "" {
		// Replicate via RAFT
		entry := map[string]interface{}{
			"action":     "MODEL_TRAINED",
			"model_id":   modelID,
			"model_path": modelPath,
		}
		raftNode.Replicate(entry)

		sendResponse(conn, map[string]interface{}{"status": "OK", "model_id": modelID})
	} else {
		sendResponse(conn, map[string]interface{}{"status": "ERROR", "message": "Training failed"})
	}
}

func handlePredict(conn net.Conn, msg map[string]interface{}) {
	modelID, _ := msg["model_id"].(string)
	inputRaw, _ := msg["input"].([]interface{})

	if modelID == "" {
		sendResponse(conn, map[string]interface{}{"status": "ERROR", "message": "Missing model_id"})
		return
	}

	logMsg("PREDICT request: model=%s", modelID)

	// Find model file
	modelPath := findModel(modelID)
	if modelPath == "" {
		sendResponse(conn, map[string]interface{}{"status": "ERROR", "message": "Model not found"})
		return
	}

	// Build input string
	var inputParts []string
	for _, v := range inputRaw {
		inputParts = append(inputParts, fmt.Sprintf("%v", v))
	}
	inputStr := strings.Join(inputParts, ",")

	// Run Java prediction
	output := runJavaPrediction(modelPath, inputStr)
	if output != nil {
		sendResponse(conn, map[string]interface{}{"status": "OK", "output": output})
	} else {
		sendResponse(conn, map[string]interface{}{"status": "ERROR", "message": "Prediction failed"})
	}
}

func handleListModels(conn net.Conn) {
	logMsg("LIST_MODELS request")

	var models []string
	files, _ := filepath.Glob(filepath.Join(modelsDir, "*.bin"))
	for _, f := range files {
		name := filepath.Base(f)
		// Extract model ID from filename
		if strings.HasPrefix(name, "model_") && strings.HasSuffix(name, ".bin") {
			id := strings.TrimSuffix(strings.TrimPrefix(name, "model_"), ".bin")
			models = append(models, id)
		}
	}

	sendResponse(conn, map[string]interface{}{"status": "OK", "models": models})
}

// ============================================================================
// Java Integration
// ============================================================================

func runJavaTraining(inputsFile, outputsFile, modelPath string) string {
	cmd := exec.Command("java", "-cp", javaDir, "TrainingModule",
		"train", inputsFile, outputsFile, "1000", modelPath)

	logMsg("Running: %s", strings.Join(cmd.Args, " "))

	output, err := cmd.CombinedOutput()
	if err != nil {
		logMsg("Java training error: %v", err)
		return ""
	}

	// Parse output for MODEL_ID
	var modelID string
	for _, line := range strings.Split(string(output), "\n") {
		logMsg("JAVA: %s", line)
		if strings.HasPrefix(line, "MODEL_ID:") {
			modelID = strings.TrimPrefix(line, "MODEL_ID:")
		}
	}

	return modelID
}

func runJavaPrediction(modelPath, inputStr string) []float64 {
	cmd := exec.Command("java", "-cp", javaDir, "TrainingModule",
		"predict", modelPath, inputStr)

	logMsg("Running: %s", strings.Join(cmd.Args, " "))

	output, err := cmd.CombinedOutput()
	if err != nil {
		logMsg("Java prediction error: %v", err)
		return nil
	}

	// Parse output for PREDICTION
	for _, line := range strings.Split(string(output), "\n") {
		if strings.HasPrefix(line, "PREDICTION:") {
			predStr := strings.TrimPrefix(line, "PREDICTION:")
			var result []float64
			for _, v := range strings.Split(predStr, ",") {
				var f float64
				fmt.Sscanf(strings.TrimSpace(v), "%f", &f)
				result = append(result, f)
			}
			return result
		}
	}

	return nil
}

func findModel(modelID string) string {
	// Try exact match
	exactPath := filepath.Join(modelsDir, fmt.Sprintf("model_%s.bin", modelID))
	if _, err := os.Stat(exactPath); err == nil {
		return exactPath
	}

	// Try partial match
	files, _ := filepath.Glob(filepath.Join(modelsDir, fmt.Sprintf("*%s*.bin", modelID)))
	if len(files) > 0 {
		return files[0]
	}

	return ""
}

func writeCSV(path string, data []interface{}) error {
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()

	for _, row := range data {
		switch r := row.(type) {
		case []interface{}:
			var parts []string
			for _, v := range r {
				parts = append(parts, fmt.Sprintf("%v", v))
			}
			f.WriteString(strings.Join(parts, ",") + "\n")
		default:
			f.WriteString(fmt.Sprintf("%v\n", r))
		}
	}
	return nil
}

// ============================================================================
// HTTP Monitor
// ============================================================================

func startHTTPMonitor(host string, port int) {
	addr := fmt.Sprintf("%s:%d", host, port)
	logMsg("Starting HTTP monitor on %s", addr)

	http.HandleFunc("/", handleDashboard)
	http.HandleFunc("/status", handleStatus)
	http.HandleFunc("/models", handleModelsAPI)
	http.HandleFunc("/logs", handleLogs)

	if err := http.ListenAndServe(addr, nil); err != nil {
		logMsg("HTTP server error: %v", err)
	}
}

func handleDashboard(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}

	html := `<!DOCTYPE html>
<html>
<head>
    <title>Worker Monitor (Go)</title>
    <style>
        body { font-family: monospace; background: #1a1a2e; color: #eee; padding: 20px; }
        h1 { color: #00ff88; }
        .card { background: #16213e; padding: 15px; margin: 10px 0; border-radius: 8px; }
        .label { color: #888; }
        .leader { color: #00ff88; }
        .follower { color: #ffaa00; }
        .candidate { color: #ff6b6b; }
        pre { background: #0f0f23; padding: 10px; overflow-x: auto; max-height: 400px; }
        .go-badge { background: #00ADD8; color: white; padding: 2px 8px; border-radius: 4px; }
    </style>
</head>
<body>
    <h1>🖥️ Worker Monitor <span class="go-badge">Go</span></h1>
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
                    'Term: ' + status.term + ' | Leader: ' + JSON.stringify(status.leader) +
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
</html>`

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Write([]byte(html))
}

func handleStatus(w http.ResponseWriter, r *http.Request) {
	status := map[string]interface{}{
		"state":      raftNode.state,
		"term":       raftNode.currentTerm,
		"leader":     raftNode.leader,
		"log_length": len(raftNode.log),
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(status)
}

func handleModelsAPI(w http.ResponseWriter, r *http.Request) {
	var models []string
	files, _ := filepath.Glob(filepath.Join(modelsDir, "*.bin"))
	for _, f := range files {
		models = append(models, filepath.Base(f))
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{"models": models})
}

func handleLogs(w http.ResponseWriter, r *http.Request) {
	logPath := filepath.Join(storageDir, "worker.log")
	data, err := os.ReadFile(logPath)
	if err != nil {
		w.Write([]byte("No logs yet"))
		return
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Write(data)
}
