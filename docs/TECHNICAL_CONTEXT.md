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

## 2. ARQUITECTURA ACTUAL

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

### 3.1 Red Neuronal (Java)

**Archivos**:
- `java/NeuralNetwork.java` - MLP con sigmoid, backpropagation
- `java/TrainingModule.java` - CLI para train/predict/demo

**Características**:
- Perceptrón multicapa (input → hidden → output)
- Activación sigmoid + derivada
- Backpropagation para ajuste de pesos
- **Paralelización con ExecutorService** (usa todos los núcleos)
- Serialización nativa Java (Serializable)
- UUID único por modelo

**Comandos**:
```bash
java -cp java TrainingModule demo                           # XOR demo
java -cp java TrainingModule train inputs.csv outputs.csv   # Entrenar
java -cp java TrainingModule predict model.bin 1,0          # Predecir
```

### 3.2 Worker Python

**Archivo**: `src/worker.py`

**Características**:
- Servidor TCP con protocolo JSON
- Maneja mensajes: `TRAIN`, `PREDICT`, `LIST_MODELS`, `PUT` (legacy)
- Integración con Java TrainingModule vía subprocess
- Monitor HTTP en puerto separado (`/logs`, `/status`, `/models`, `/`)
- Dashboard web con estado RAFT en tiempo real
- Persistencia en directorio por nodo (`node{N}_storage/`)

### 3.3 Módulo RAFT (Python)

**Archivo**: `src/raft.py`

**Características**:
- 3 estados: Follower, Candidate, Leader
- Election timeout aleatorio (3-5 segundos)
- RequestVote RPC
- AppendEntries RPC (heartbeats + replicación)
- Log replication con majority commit
- NotLeader exception para redirect a cliente

### 3.4 Clientes (Python)

**Archivos**:
- `src/train_client.py` - Envía TRAIN con inputs/outputs
- `src/test_client.py` - Envía PREDICT, LIST_MODELS

**Características**:
- Redirect automático al líder RAFT
- Soporte CSV e inline (`"0,0;0,1"`)
- Timeout y reintentos

---

## 4. COMPONENTES PENDIENTES ❌

### 4.1 Worker en Go (ALTA PRIORIDAD)

**Estructura propuesta**:
```
go/
├── cmd/worker/main.go
├── internal/
│   ├── raft/raft.go
│   ├── server/tcp.go
│   ├── server/http.go
│   └── storage/storage.go
└── go.mod
```

**Requisitos**:
- Servidor TCP con `net` package
- RAFT compatible con protocolo JSON existente
- HTTP monitor en puerto separado
- Llamar a Java TrainingModule vía `os/exec`

### 4.2 Worker en Kotlin (ALTA PRIORIDAD)

**Estructura propuesta**:
```
kotlin/
├── src/main/kotlin/
│   ├── Main.kt
│   ├── RaftNode.kt
│   ├── TcpServer.kt
│   └── HttpMonitor.kt
└── build.gradle.kts
```

**Requisitos**:
- Usar `java.net.Socket` (no frameworks)
- Coroutines para concurrencia
- Mismo protocolo JSON

### 4.3 Mejoras Menores

- [ ] Visualizar progreso de entrenamiento en cliente
- [ ] Métricas de performance en monitor web
- [ ] Timeout más robusto en RAFT

---

## 5. PROTOCOLO DE COMUNICACIÓN

### Cliente → Worker (TCP JSON + newline)

```json
// TRAIN
{"type": "TRAIN", "inputs": [[0,0], [0,1], [1,0], [1,1]], "outputs": [[0], [1], [1], [0]]}

// PREDICT
{"type": "PREDICT", "model_id": "abc123", "input": [1, 0]}

// LIST_MODELS
{"type": "LIST_MODELS"}
```

### Worker → Cliente

```json
{"status": "OK", "model_id": "abc123"}
{"status": "OK", "output": [0.95]}
{"status": "REDIRECT", "leader": ["127.0.0.1", 9001]}
{"status": "ERROR", "message": "Model not found"}
```

### RAFT RPCs (Worker ↔ Worker)

```json
// REQUEST_VOTE
{"type": "REQUEST_VOTE", "term": 5, "candidate_id": "node1", "last_log_index": 10, "last_log_term": 4}

// APPEND_ENTRIES
{"type": "APPEND_ENTRIES", "term": 5, "leader_id": ["host", port], "entries": [...], "prev_log_index": 10, "prev_log_term": 4, "leader_commit": 9}
```

---

## 6. CÓMO EJECUTAR

### Iniciar cluster de 3 nodos Python:

```bash
# Terminal 1
python3 -m src.worker --host 127.0.0.1 --port 9000 --monitor-port 8000 --raft-port 10000 --peers 127.0.0.1:9001 127.0.0.1:9002

# Terminal 2
python3 -m src.worker --host 127.0.0.1 --port 9001 --monitor-port 8001 --raft-port 10001 --peers 127.0.0.1:9000 127.0.0.1:9002

# Terminal 3
python3 -m src.worker --host 127.0.0.1 --port 9002 --monitor-port 8002 --raft-port 10002 --peers 127.0.0.1:9000 127.0.0.1:9001
```

### Entrenar modelo (XOR):

```bash
python3 -m src.train_client train-inline "0,0;0,1;1,0;1,1" "0;1;1;0"
```

### Predecir:

```bash
python3 -m src.test_client list
python3 -m src.test_client predict <model_id> 1,0
```

---

## 7. MEJORAS OPCIONALES (SI HAY TIEMPO)

### Alta Prioridad
- **Replicación real de modelos**: Actualmente solo se replica metadata, no el archivo .bin
- **Health checks entre nodos**: Detectar nodos caídos más rápido

### Media Prioridad
- **Snapshot RAFT**: Para recuperación rápida de nuevos nodos
- **Load balancing**: Distribuir entrenamiento entre workers
- **Logging estructurado**: JSON logs para análisis

### Baja Prioridad (nice-to-have)
- **GUI desktop**: JavaFX o Tkinter para clientes
- **Métricas Prometheus**: Exportar métricas de RAFT y training
- **Compresión de datos**: gzip para transferencia de modelos grandes
- **Autenticación básica**: Token simple para seguridad

---

## 8. RESTRICCIONES DEL EXAMEN

| Permitido | Prohibido |
|-----------|-----------|
| Sockets nativos | WebSocket, Socket.IO |
| Threads/concurrencia | Frameworks web (Flask, FastAPI) |
| Librerías estándar | RabbitMQ, Redis, etc. |
| subprocess para Java | Contenedores como dependencia |
| HTTP server básico | Librerías de ML (TensorFlow, PyTorch) |

---

## 9. ARCHIVOS CLAVE

```
fork/Final_Concurrente/
├── java/
│   ├── NeuralNetwork.java    # Red neuronal MLP
│   └── TrainingModule.java   # CLI de entrenamiento
├── src/
│   ├── raft.py               # Implementación RAFT
│   ├── worker.py             # Worker con TCP + HTTP
│   ├── train_client.py       # Cliente de entrenamiento
│   └── test_client.py        # Cliente de testeo
├── tools/
│   └── benchmark.py          # Script de 1000 requests
└── .gitignore
```

---

## 10. PRÓXIMOS PASOS RECOMENDADOS

1. **Implementar Worker en Go** - Prioridad alta, cumple requisito de 4 LP
2. **Implementar Worker en Kotlin** - Prioridad alta
3. **Probar cluster heterogéneo** (Python + Go + Kotlin)
4. **Ejecutar benchmark de 1000 requests**
5. **Documentar y preparar presentación**
