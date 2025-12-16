package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"math/rand"
	"net"
	"sync"
	"time"
)

// RAFT message types
const (
	REQUEST_VOTE    = "REQUEST_VOTE"
	VOTE_RESPONSE   = "VOTE_RESPONSE"
	APPEND_ENTRIES  = "APPEND_ENTRIES"
	APPEND_RESPONSE = "APPEND_RESPONSE"
)

// Peer represents a RAFT peer
type Peer struct {
	Host       string
	Port       int
	WorkerPort int
}

// Leader info
type LeaderInfo struct {
	Host       string
	WorkerPort int
}

// LogEntry represents a RAFT log entry
type LogEntry struct {
	Term    int                    `json:"term"`
	Command map[string]interface{} `json:"command"`
}

// RaftNode implements the RAFT consensus algorithm
type RaftNode struct {
	// Identity
	id         string
	host       string
	port       int
	workerPort int
	peers      []Peer

	// Persistent state
	currentTerm int
	votedFor    string
	log         []LogEntry

	// Volatile state
	commitIndex int
	lastApplied int

	// Leader state
	nextIndex  map[string]int
	matchIndex map[string]int

	// Current state
	state  string // "follower", "candidate", "leader"
	leader *LeaderInfo

	// Synchronization
	mu            sync.RWMutex
	electionTimer *time.Timer
	stopCh        chan struct{}

	// Configuration
	heartbeatInterval time.Duration
}

// NewRaftNode creates a new RAFT node
func NewRaftNode(id, host string, port int, peers []Peer, workerPort int) *RaftNode {
	return &RaftNode{
		id:                id,
		host:              host,
		port:              port,
		workerPort:        workerPort,
		peers:             peers,
		currentTerm:       0,
		votedFor:          "",
		log:               []LogEntry{},
		commitIndex:       -1,
		lastApplied:       -1,
		nextIndex:         make(map[string]int),
		matchIndex:        make(map[string]int),
		state:             "follower",
		stopCh:            make(chan struct{}),
		heartbeatInterval: 1 * time.Second,
	}
}

// Start begins the RAFT node operation
func (rn *RaftNode) Start() {
	// Start RPC server
	go rn.startRPCServer()

	// Start election timer
	rn.resetElectionTimeout()
}

// Stop halts the RAFT node
func (rn *RaftNode) Stop() {
	close(rn.stopCh)
}

// IsLeader returns true if this node is the leader
func (rn *RaftNode) IsLeader() bool {
	rn.mu.RLock()
	defer rn.mu.RUnlock()
	return rn.state == "leader"
}

// GetLeader returns current leader info
func (rn *RaftNode) GetLeader() *LeaderInfo {
	rn.mu.RLock()
	defer rn.mu.RUnlock()
	return rn.leader
}

// resetElectionTimeout resets the election timer with random timeout
func (rn *RaftNode) resetElectionTimeout() {
	if rn.electionTimer != nil {
		rn.electionTimer.Stop()
	}
	// Random timeout between 3-5 seconds
	timeout := time.Duration(3000+rand.Intn(2000)) * time.Millisecond
	rn.electionTimer = time.AfterFunc(timeout, rn.startElection)
}

// startElection begins a new election
func (rn *RaftNode) startElection() {
	rn.mu.Lock()
	rn.state = "candidate"
	rn.currentTerm++
	rn.votedFor = rn.id
	term := rn.currentTerm
	votes := 1
	rn.mu.Unlock()

	logMsg("Starting election for term %d", term)

	// Request votes from all peers
	var wg sync.WaitGroup
	var votesMu sync.Mutex

	for _, peer := range rn.peers {
		wg.Add(1)
		go func(p Peer) {
			defer wg.Done()

			msg := map[string]interface{}{
				"type":         REQUEST_VOTE,
				"term":         term,
				"candidate_id": rn.id,
			}

			resp := rn.sendRPC(p.Host, p.Port, msg)
			if resp != nil && resp["vote_granted"] == true {
				votesMu.Lock()
				votes++
				votesMu.Unlock()
			}
		}(peer)
	}

	// Wait for responses with timeout
	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
	}

	// Check if we won
	rn.mu.Lock()
	defer rn.mu.Unlock()

	if rn.state != "candidate" {
		return
	}

	total := len(rn.peers) + 1
	majority := total/2 + 1

	if votes >= majority {
		logMsg("Won election with %d/%d votes, becoming leader", votes, total)
		rn.state = "leader"
		rn.leader = &LeaderInfo{Host: rn.host, WorkerPort: rn.workerPort}

		// Initialize leader state
		for _, p := range rn.peers {
			key := fmt.Sprintf("%s:%d", p.Host, p.Port)
			rn.nextIndex[key] = len(rn.log)
			rn.matchIndex[key] = -1
		}

		// Start heartbeat loop
		go rn.leaderLoop()
	} else {
		logMsg("Lost election with %d/%d votes", votes, total)
		rn.resetElectionTimeout()
	}
}

// leaderLoop sends periodic heartbeats
func (rn *RaftNode) leaderLoop() {
	ticker := time.NewTicker(rn.heartbeatInterval)
	defer ticker.Stop()

	for {
		select {
		case <-rn.stopCh:
			return
		case <-ticker.C:
			rn.mu.RLock()
			isLeader := rn.state == "leader"
			rn.mu.RUnlock()

			if !isLeader {
				return
			}

			rn.sendHeartbeats()
		}
	}
}

// sendHeartbeats sends AppendEntries to all peers
func (rn *RaftNode) sendHeartbeats() {
	for _, peer := range rn.peers {
		go func(p Peer) {
			rn.sendAppendEntries(p, []LogEntry{})
		}(peer)
	}
}

// sendAppendEntries sends AppendEntries RPC to a peer
func (rn *RaftNode) sendAppendEntries(peer Peer, entries []LogEntry) bool {
	rn.mu.RLock()
	msg := map[string]interface{}{
		"type":           APPEND_ENTRIES,
		"term":           rn.currentTerm,
		"leader_id":      []interface{}{rn.host, rn.workerPort},
		"entries":        entries,
		"prev_log_index": -1,
		"prev_log_term":  0,
		"leader_commit":  rn.commitIndex,
	}
	rn.mu.RUnlock()

	resp := rn.sendRPC(peer.Host, peer.Port, msg)
	return resp != nil && resp["success"] == true
}

// Replicate appends a command to the log and replicates it
func (rn *RaftNode) Replicate(command map[string]interface{}) bool {
	rn.mu.Lock()
	if rn.state != "leader" {
		rn.mu.Unlock()
		return false
	}

	entry := LogEntry{Term: rn.currentTerm, Command: command}
	rn.log = append(rn.log, entry)
	myIndex := len(rn.log) - 1
	rn.mu.Unlock()

	// Send to all peers
	acks := 1
	var wg sync.WaitGroup
	var acksMu sync.Mutex

	for _, peer := range rn.peers {
		wg.Add(1)
		go func(p Peer) {
			defer wg.Done()
			if rn.sendAppendEntries(p, []LogEntry{entry}) {
				acksMu.Lock()
				acks++
				acksMu.Unlock()
			}
		}(peer)
	}

	// Wait with timeout
	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(5 * time.Second):
	}

	// Check majority
	rn.mu.Lock()
	defer rn.mu.Unlock()

	total := len(rn.peers) + 1
	majority := total/2 + 1

	if acks >= majority {
		rn.commitIndex = myIndex
		return true
	}

	return false
}

// ============================================================================
// RPC Server and Client
// ============================================================================

func (rn *RaftNode) startRPCServer() {
	addr := fmt.Sprintf("%s:%d", rn.host, rn.port)
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		logMsg("RAFT RPC listen error: %v", err)
		return
	}
	defer listener.Close()

	logMsg("RAFT RPC server listening on %s", addr)

	for {
		select {
		case <-rn.stopCh:
			return
		default:
		}

		conn, err := listener.Accept()
		if err != nil {
			continue
		}
		go rn.handleRPC(conn)
	}
}

func (rn *RaftNode) handleRPC(conn net.Conn) {
	defer conn.Close()

	reader := bufio.NewReader(conn)
	line, err := reader.ReadString('\n')
	if err != nil {
		return
	}

	var msg map[string]interface{}
	if err := json.Unmarshal([]byte(line), &msg); err != nil {
		return
	}

	var resp map[string]interface{}
	msgType, _ := msg["type"].(string)

	switch msgType {
	case REQUEST_VOTE:
		resp = rn.handleRequestVote(msg)
	case APPEND_ENTRIES:
		resp = rn.handleAppendEntries(msg)
	default:
		resp = map[string]interface{}{"error": "unknown"}
	}

	data, _ := json.Marshal(resp)
	conn.Write(append(data, '\n'))
}

func (rn *RaftNode) handleRequestVote(msg map[string]interface{}) map[string]interface{} {
	term := int(msg["term"].(float64))
	candidateID, _ := msg["candidate_id"].(string)

	rn.mu.Lock()
	defer rn.mu.Unlock()

	if term > rn.currentTerm {
		rn.currentTerm = term
		rn.votedFor = ""
		rn.state = "follower"
	}

	voteGranted := false
	if (rn.votedFor == "" || rn.votedFor == candidateID) && term >= rn.currentTerm {
		rn.votedFor = candidateID
		voteGranted = true
		logMsg("Voted for %s in term %d", candidateID, term)
	}

	rn.resetElectionTimeout()

	return map[string]interface{}{
		"type":         VOTE_RESPONSE,
		"term":         rn.currentTerm,
		"vote_granted": voteGranted,
	}
}

func (rn *RaftNode) handleAppendEntries(msg map[string]interface{}) map[string]interface{} {
	term := int(msg["term"].(float64))
	leaderID := msg["leader_id"]

	rn.mu.Lock()
	defer rn.mu.Unlock()

	if term >= rn.currentTerm {
		rn.currentTerm = term
		rn.state = "follower"

		// Parse leader info
		if leaderArr, ok := leaderID.([]interface{}); ok && len(leaderArr) == 2 {
			host, _ := leaderArr[0].(string)
			port, _ := leaderArr[1].(float64)
			rn.leader = &LeaderInfo{Host: host, WorkerPort: int(port)}
		}

		rn.resetElectionTimeout()

		return map[string]interface{}{
			"type":    APPEND_RESPONSE,
			"term":    rn.currentTerm,
			"success": true,
		}
	}

	return map[string]interface{}{
		"type":    APPEND_RESPONSE,
		"term":    rn.currentTerm,
		"success": false,
	}
}

func (rn *RaftNode) sendRPC(host string, port int, msg map[string]interface{}) map[string]interface{} {
	addr := fmt.Sprintf("%s:%d", host, port)
	conn, err := net.DialTimeout("tcp", addr, 2*time.Second)
	if err != nil {
		return nil
	}
	defer conn.Close()

	conn.SetDeadline(time.Now().Add(2 * time.Second))

	data, _ := json.Marshal(msg)
	conn.Write(append(data, '\n'))

	reader := bufio.NewReader(conn)
	line, err := reader.ReadString('\n')
	if err != nil {
		return nil
	}

	var resp map[string]interface{}
	if err := json.Unmarshal([]byte(line), &resp); err != nil {
		return nil
	}

	return resp
}
