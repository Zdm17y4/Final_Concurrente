# Worker Kotlin

Worker distribuido implementado en Kotlin, compatible con los workers de Python y Go.

## Requisitos

- JDK 17 o superior
- Gradle 8+ o Kotlin compiler (kotlinc)

### Instalar Kotlin (Arch Linux/Manjaro):
```bash
sudo pacman -S kotlin
```

### Instalar Kotlin (Ubuntu/Debian):
```bash
sudo apt install kotlin
```

### Instalar con SDKMAN (recomendado):
```bash
curl -s "https://get.sdkman.io" | bash
source ~/.sdkman/bin/sdkman-init.sh
sdk install kotlin
sdk install gradle
```

## Compilar

### Opción 1: Con Gradle
```bash
gradle build
java -jar build/libs/worker-kotlin-1.0.jar --help
```

### Opción 2: Con kotlinc directamente
```bash
kotlinc src/main/kotlin/*.kt -include-runtime -d worker.jar
java -jar worker.jar --help
```

## Ejecutar

```bash
java -jar worker.jar \
    --host 127.0.0.1 \
    --port 9002 \
    --monitor-port 8002 \
    --raft-port 10002 \
    --peers "127.0.0.1:9000,127.0.0.1:9001"
```

## Endpoints

- **TCP :9002** - Mensajes TRAIN, PREDICT, LIST_MODELS
- **HTTP :8002** - Dashboard web con status RAFT
  - `/` - Dashboard
  - `/status` - Estado RAFT (JSON)
  - `/models` - Lista de modelos (JSON)
  - `/logs` - Logs del worker

## Arquitectura

```
kotlin/
├── src/main/kotlin/
│   ├── Main.kt    # TCP server, handlers, HTTP monitor
│   └── Raft.kt    # Consenso RAFT
├── build.gradle.kts
└── README.md
```
