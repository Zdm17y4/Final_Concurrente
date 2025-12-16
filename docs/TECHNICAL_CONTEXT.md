# Proyecto Final - Programación Concurrente

## Contexto Técnico para Continuación por IA

Este documento describe el estado actual del proyecto para facilitar la continuación por otra instancia de IA o desarrollador.

---

## 1. OBJETIVO DEL PROYECTO

Sistema distribuido para entrenamiento y consumo de modelos de IA (redes neuronales) usando:
- **Entrenamiento distribuido, paralelo y concurrente** entre múltiples nodos worker
- **Algoritmo RAFT** para consenso y replicación de modelos
- **4 lenguajes de programación**: Java (obligatorio para IA), Python, Go, Kotlin
- **Solo sockets nativos** (prohibido: frameworks web, WebSocket, RabbitMQ, etc.)

---

## 2. ARQUITECTURA

```
┌─────────────────┐     ┌─────────────────┐
│  train_client   │     │   test_client   │
│    (Python)     │     │    (Python)     │
└────────┬────────┘     └────────┬────────┘
         │ TCP/JSON              │ TCP/JSON
         ▼                       ▼
┌─────────────────────────────────────────┐
│           Worker Cluster (RAFT)         │
│  ┌──────────┐  ┌──────────┐  ┌────────┐ │
│  │Worker Py │◄─┤►Worker Go│◄─┤►Kotlin │ │
│  │ :9000    │  │  :9001   │  │ :9002  │ │
│  │ RAFT     │  │  RAFT    │  │ RAFT   │ │
│  │ HTTP Mon │  │  HTTP Mon│  │HTTP Mon│ │
│  └──────────┘  └──────────┘  └────────┘ │
└─────────────────────────────────────────┘
         │
         ▼ subprocess
┌─────────────────┐
│ Java Training   │
│ Module (MLP)    │
└─────────────────┘
```

---

## 3. COMPONENTES IMPLEMENTADOS ✅

### 3.1 Red Neuronal (Java) ✅
- `java/NeuralNetwork.java` - MLP con sigmoid, backpropagation, paralelización ExecutorService
- `java/TrainingModule.java` - CLI para train/predict/demo
- UUID único por modelo, serialización nativa

### 3.2 Worker Python ✅
- `src/worker.py` - Servidor TCP + HTTP monitor
- `src/raft.py` - Consenso RAFT completo
- Mensajes: TRAIN, PREDICT, LIST_MODELS

### 3.3 Worker Go ✅
- `go/main.go` - Servidor TCP, handlers, HTTP monitor
- `go/raft.go` - Consenso RAFT con goroutines
- Compilar: `cd go && go build -o worker .`

### 3.4 Worker Kotlin ✅
- `kotlin/src/main/kotlin/Main.kt` - Servidor TCP, handlers, HTTP monitor  
- `kotlin/src/main/kotlin/Raft.kt` - Consenso RAFT
- Compilar: `kotlinc src/main/kotlin/*.kt -include-runtime -d worker.jar`

### 3.5 Clientes Python ✅
- `src/train_client.py` - Envía TRAIN con inputs/outputs
- `src/test_client.py` - Envía PREDICT, LIST_MODELS

---

## 4. CÓMO EJECUTAR

### Cluster heterogéneo (Python + Go + Kotlin):
```bash
# Python (Terminal 1)
python3 -m src.worker --host 127.0.0.1 --port 9000 --monitor-port 8000 --raft-port 10000 --peers 127.0.0.1:9001,127.0.0.1:9002

# Go (Terminal 2)
./go/worker --host 127.0.0.1 --port 9001 --monitor-port 8001 --raft-port 10001 --peers 127.0.0.1:9000,127.0.0.1:9002

# Kotlin (Terminal 3)
java -jar kotlin/worker.jar --host 127.0.0.1 --port 9002 --monitor-port 8002 --raft-port 10002 --peers 127.0.0.1:9000,127.0.0.1:9001
```

### Entrenar y predecir:
```bash
python3 -m src.train_client train-inline "0,0;0,1;1,0;1,1" "0;1;1;0"
python3 -m src.test_client list
python3 -m src.test_client predict <model_id> 1,0
```

---

## 5. PROTOCOLO DE COMUNICACIÓN

### Cliente → Worker (TCP JSON + newline)
```json
{"type": "TRAIN", "inputs": [[0,0], [0,1]], "outputs": [[0], [1]]}
{"type": "PREDICT", "model_id": "abc123", "input": [1, 0]}
{"type": "LIST_MODELS"}
```

### RAFT RPCs (Worker ↔ Worker)
```json
{"type": "REQUEST_VOTE", "term": 5, "candidate_id": "node1"}
{"type": "APPEND_ENTRIES", "term": 5, "leader_id": ["host", port], "entries": [...]}
```

---

## 6. ARCHIVOS CLAVE

```
fork/Final_Concurrente/
├── java/
│   ├── NeuralNetwork.java    # Red neuronal MLP
│   └── TrainingModule.java   # CLI de entrenamiento
├── go/
│   ├── main.go               # Worker Go
│   └── raft.go               # RAFT Go
├── kotlin/src/main/kotlin/
│   ├── Main.kt               # Worker Kotlin
│   └── Raft.kt               # RAFT Kotlin
├── src/
│   ├── raft.py               # RAFT Python
│   ├── worker.py             # Worker Python
│   ├── train_client.py       # Cliente entrenamiento
│   └── test_client.py        # Cliente testeo
└── tools/
    └── benchmark.py          # Script de 1000 requests
```

---

## 7. PENDIENTE / MEJORAS OPCIONALES

- [ ] Test cluster heterogéneo (Python + Go + Kotlin juntos)
- [ ] Ejecutar benchmark 1000+ requests
- [ ] Replicación real de archivos .bin entre nodos
- [ ] Documentación final (informe, presentación)

---

## 8. RESTRICCIONES DEL EXAMEN

| Permitido | Prohibido |
|-----------|-----------|
| Sockets nativos | WebSocket, Socket.IO |
| Threads/concurrencia | Frameworks web |
| Librerías estándar | RabbitMQ, Redis |
| subprocess para Java | Contenedores como dependencia |
