/**
 * RAFT Consensus Implementation for Kotlin Worker
 *
 * Compatible with Python and Go RAFT implementations.
 * Uses standard library sockets for RPC communication.
 */

import java.io.*
import java.net.*
import java.util.concurrent.*
import kotlin.concurrent.thread
import kotlin.random.Random

data class Peer(val host: String, val port: Int, val workerPort: Int = 0)

data class LeaderInfo(val host: String, val workerPort: Int)

data class LogEntry(val term: Int, val command: Map<String, Any?>)

class RaftNode(
    private val id: String,
    private val host: String,
    private val port: Int,
    private val peers: List<Peer>,
    private val workerPort: Int
) {
    // Persistent state
    @Volatile var currentTerm = 0
        private set
    @Volatile var votedFor: String? = null
        private set
    val log = CopyOnWriteArrayList<LogEntry>()

    // Volatile state
    @Volatile var commitIndex = -1
        private set
    @Volatile var lastApplied = -1
        private set

    // Leader state
    private val nextIndex = ConcurrentHashMap<String, Int>()
    private val matchIndex = ConcurrentHashMap<String, Int>()

    // Current state
    @Volatile var state = "follower"
        private set
    @Volatile var leader: LeaderInfo? = null
        private set

    // Synchronization
    private val lock = Object()
    @Volatile private var electionTimer: ScheduledFuture<*>? = null
    private val scheduler = Executors.newScheduledThreadPool(2)
    @Volatile private var stopped = false

    private val heartbeatInterval = 1000L // ms

    fun start() {
        thread { startRpcServer() }
        resetElectionTimeout()
    }

    fun stop() {
        stopped = true
        scheduler.shutdownNow()
    }

    fun isLeader(): Boolean = state == "leader"

    private fun resetElectionTimeout() {
        electionTimer?.cancel(false)
        val timeout = 3000L + Random.nextLong(2000)
        electionTimer = scheduler.schedule({ startElection() }, timeout, TimeUnit.MILLISECONDS)
    }

    private fun startElection() {
        synchronized(lock) {
            state = "candidate"
            currentTerm++
            votedFor = id
        }
        val term = currentTerm

        log("Starting election for term $term")

        var votes = 1
        val voteLock = Object()
        val latch = CountDownLatch(peers.size)

        for (peer in peers) {
            thread {
                try {
                    val msg = mapOf(
                        "type" to "REQUEST_VOTE",
                        "term" to term,
                        "candidate_id" to id
                    )
                    val resp = sendRpc(peer.host, peer.port, msg)
                    if (resp?.get("vote_granted") == true) {
                        synchronized(voteLock) { votes++ }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(2, TimeUnit.SECONDS)

        synchronized(lock) {
            if (state != "candidate") return

            val total = peers.size + 1
            val majority = total / 2 + 1

            if (votes >= majority) {
                log("Won election with $votes/$total votes, becoming leader")
                state = "leader"
                leader = LeaderInfo(host, workerPort)

                // Initialize leader state
                for (p in peers) {
                    val key = "${p.host}:${p.port}"
                    nextIndex[key] = log.size
                    matchIndex[key] = -1
                }

                // Start heartbeat loop
                thread { leaderLoop() }
            } else {
                log("Lost election with $votes/$total votes")
                resetElectionTimeout()
            }
        }
    }

    private fun leaderLoop() {
        while (!stopped && state == "leader") {
            sendHeartbeats()
            Thread.sleep(heartbeatInterval)
        }
    }

    private fun sendHeartbeats() {
        for (peer in peers) {
            thread { sendAppendEntries(peer, emptyList()) }
        }
    }

    private fun sendAppendEntries(peer: Peer, entries: List<LogEntry>): Boolean {
        val msg = mapOf(
            "type" to "APPEND_ENTRIES",
            "term" to currentTerm,
            "leader_id" to listOf(host, workerPort),
            "entries" to entries.map { mapOf("term" to it.term, "command" to it.command) },
            "prev_log_index" to -1,
            "prev_log_term" to 0,
            "leader_commit" to commitIndex
        )

        val resp = sendRpc(peer.host, peer.port, msg)
        return resp?.get("success") == true
    }

    fun replicate(command: Map<String, Any?>): Boolean {
        synchronized(lock) {
            if (state != "leader") return false

            val entry = LogEntry(currentTerm, command)
            log.add(entry)
        }

        val myIndex = log.size - 1
        var acks = 1
        val ackLock = Object()
        val latch = CountDownLatch(peers.size)

        for (peer in peers) {
            thread {
                try {
                    if (sendAppendEntries(peer, listOf(log[myIndex]))) {
                        synchronized(ackLock) { acks++ }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(5, TimeUnit.SECONDS)

        synchronized(lock) {
            val total = peers.size + 1
            val majority = total / 2 + 1

            if (acks >= majority) {
                commitIndex = myIndex
                return true
            }
            return false
        }
    }

    // ========================================================================
    // RPC Server
    // ========================================================================

    private fun startRpcServer() {
        val server = ServerSocket(port, 50, InetAddress.getByName(host))
        log("RAFT RPC server listening on $host:$port")

        while (!stopped) {
            try {
                val client = server.accept()
                thread { handleRpc(client) }
            } catch (e: Exception) {
                if (!stopped) log("RPC accept error: ${e.message}")
            }
        }
    }

    private fun handleRpc(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = PrintWriter(client.getOutputStream(), true)

            val line = reader.readLine() ?: return
            val msg = parseJson(line)
            val type = msg["type"] as? String

            val resp = when (type) {
                "REQUEST_VOTE" -> handleRequestVote(msg)
                "APPEND_ENTRIES" -> handleAppendEntries(msg)
                else -> mapOf("error" to "unknown")
            }

            writer.println(toJson(resp))
        } catch (e: Exception) {
            // Ignore
        } finally {
            client.close()
        }
    }

    private fun handleRequestVote(msg: Map<String, Any?>): Map<String, Any?> {
        val term = (msg["term"] as? Number)?.toInt() ?: 0
        val candidateId = msg["candidate_id"] as? String ?: ""

        synchronized(lock) {
            if (term > currentTerm) {
                currentTerm = term
                votedFor = null
                state = "follower"
            }

            val voteGranted = (votedFor == null || votedFor == candidateId) && term >= currentTerm
            if (voteGranted) {
                votedFor = candidateId
                log("Voted for $candidateId in term $term")
            }

            resetElectionTimeout()

            return mapOf(
                "type" to "VOTE_RESPONSE",
                "term" to currentTerm,
                "vote_granted" to voteGranted
            )
        }
    }

    private fun handleAppendEntries(msg: Map<String, Any?>): Map<String, Any?> {
        val term = (msg["term"] as? Number)?.toInt() ?: 0
        val leaderId = msg["leader_id"]

        synchronized(lock) {
            if (term >= currentTerm) {
                currentTerm = term
                state = "follower"

                // Parse leader info
                if (leaderId is List<*> && leaderId.size == 2) {
                    val lHost = leaderId[0] as? String ?: ""
                    val lPort = (leaderId[1] as? Number)?.toInt() ?: 0
                    leader = LeaderInfo(lHost, lPort)
                }

                resetElectionTimeout()

                return mapOf(
                    "type" to "APPEND_RESPONSE",
                    "term" to currentTerm,
                    "success" to true
                )
            }

            return mapOf(
                "type" to "APPEND_RESPONSE",
                "term" to currentTerm,
                "success" to false
            )
        }
    }

    private fun sendRpc(host: String, port: Int, msg: Map<String, Any?>): Map<String, Any?>? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2000)
                socket.soTimeout = 2000

                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                writer.println(toJson(msg))
                val line = reader.readLine() ?: return null
                parseJson(line)
            }
        } catch (e: Exception) {
            null
        }
    }
}
