package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"strings"
	"sync"
	"time"
)

// Player holds state for each connected player.
type Player struct {
	PlayerId   string
	CombatTag  string
	X          int32
	Y          int32
	VelocityX  int32
	VelocityY  int32
	Color      string // "red" or "blue"
	Timestamp  int64  // in milliseconds
	Addr       *net.UDPAddr
}

type LevelCompletion struct {
	CurrentLevel     int
	CompletedPlayers map[string]struct{}
}

var (
	players              = make(map[string]*Player)
	playersLock          sync.RWMutex
	levelCompletions     = make(map[string]*LevelCompletion)
	levelCompletionsLock sync.RWMutex
)

// Constants for ports and timeout.
const (
	UDPPositionUpdatePort  = 8089
	UDPPlayerListPort      = 8090
	TCPCombatIDPort        = 5000
	TCPLevelCompletionPort = 5001
	TimeoutSeconds         = 15
)

// padString returns a string padded with spaces up to the desired length.
func padString(s string, length int) string {
	if len(s) >= length {
		return s[:length]
	}
	return s + strings.Repeat(" ", length-len(s))
}

// tcpCombatIDServer listens on TCP port 5000 for combat ID update messages.
func tcpCombatIDServer() {
	addr := fmt.Sprintf(":%d", TCPCombatIDPort)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatalf("Error starting TCP server on port %d: %v", TCPCombatIDPort, err)
	}
	log.Printf("TCP combat ID server listening on port %d", TCPCombatIDPort)
	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Printf("TCP accept error: %v", err)
			continue
		}
		go handleTCPCombatID(conn)
	}
}

// handleTCPCombatID reads a JSON message from a TCP connection and updates the player's combat tag.
func handleTCPCombatID(conn net.Conn) {
	defer conn.Close()
	buf := make([]byte, 1024)
	n, err := conn.Read(buf)
	if err != nil {
		log.Printf("Error reading from TCP connection: %v", err)
		return
	}
	var msg map[string]string
	err = json.Unmarshal(buf[:n], &msg)
	if err != nil {
		log.Printf("Error parsing JSON: %v", err)
		return
	}
	playerId, ok1 := msg["playerId"]
	combatId, ok2 := msg["combatId"]
	if !ok1 || !ok2 {
		log.Printf("Missing playerId or combatId in message")
		return
	}
	playersLock.Lock()
	defer playersLock.Unlock()
	if p, exists := players[playerId]; exists {
		p.CombatTag = combatId
		log.Printf("Updated combatTag for player %s to %s", playerId, combatId)
	} else {
		players[playerId] = &Player{
			PlayerId:  playerId,
			CombatTag: combatId,
			X:         0,
			Y:         0,
			VelocityX: 0,
			VelocityY: 0,
			Color:     "red",
			Timestamp: time.Now().UnixNano() / 1e6,
		}
		log.Printf("Added new player %s with combatTag %s", playerId, combatId)
	}
}

// tcpLevelCompletionServer listens on TCP port 5001 for level completion messages.
func tcpLevelCompletionServer() {
	addr := fmt.Sprintf(":%d", TCPLevelCompletionPort)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatalf("Error starting TCP server on port %d: %v", TCPLevelCompletionPort, err)
	}
	log.Printf("TCP level completion server listening on port %d", TCPLevelCompletionPort)
	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Printf("TCP accept error: %v", err)
			continue
		}
		go handleTCPLevelCompletion(conn)
	}
}

// handleTCPLevelCompletion reads a JSON message from a TCP connection and processes level completion.
func handleTCPLevelCompletion(conn net.Conn) {
	defer conn.Close()
	buf := make([]byte, 1024)
	n, err := conn.Read(buf)
	if err != nil {
		log.Printf("Error reading from TCP connection: %v", err)
		return
	}
	var msg map[string]interface{}
	err = json.Unmarshal(buf[:n], &msg)
	if err != nil {
		log.Printf("Error parsing JSON: %v", err)
		sendErrorResponse(conn, "Invalid JSON")
		return
	}
	playerId, ok1 := msg["playerId"].(string)
	combatId, ok2 := msg["combatId"].(string)
	levelNumFloat, ok3 := msg["levelNum"].(float64)
	if !ok1 || !ok2 || !ok3 {
		log.Printf("Missing fields in message")
		sendErrorResponse(conn, "Missing fields")
		return
	}
	levelNum := int(levelNumFloat)

	levelCompletionsLock.Lock()
	defer levelCompletionsLock.Unlock()

	if _, exists := levelCompletions[combatId]; !exists {
		levelCompletions[combatId] = &LevelCompletion{
			CurrentLevel:     0,
			CompletedPlayers: make(map[string]struct{}),
		}
	}
	lc := levelCompletions[combatId]

	if levelNum < lc.CurrentLevel {
		response := map[string]interface{}{
			"allCompleted": true,
			"currentLevel": lc.CurrentLevel,
		}
		sendJSONResponse(conn, response)
		return
	}

	if levelNum > lc.CurrentLevel {
		lc.CurrentLevel = levelNum
		lc.CompletedPlayers = make(map[string]struct{})
	}

	lc.CompletedPlayers[playerId] = struct{}{}

	playersLock.RLock()
	var playersInGroup []string
	for pid, p := range players {
		if p.CombatTag == combatId && pid != "dummy-player-id" {
			playersInGroup = append(playersInGroup, pid)
		}
	}
	playersLock.RUnlock()

	allCompleted := true
	for _, pid := range playersInGroup {
		if _, completed := lc.CompletedPlayers[pid]; !completed {
			allCompleted = false
			break
		}
	}

	if allCompleted {
		lc.CurrentLevel++
		lc.CompletedPlayers = make(map[string]struct{})
	}

	response := map[string]interface{}{
		"allCompleted": allCompleted,
		"currentLevel": lc.CurrentLevel,
	}
	sendJSONResponse(conn, response)
}

func sendJSONResponse(conn net.Conn, data map[string]interface{}) {
	jsonData, err := json.Marshal(data)
	if err != nil {
		log.Printf("Error marshaling JSON: %v", err)
		return
	}
	conn.Write(jsonData)
}

func sendErrorResponse(conn net.Conn, errorMsg string) {
	response := map[string]interface{}{
		"allCompleted": false,
		"error":        errorMsg,
	}
	sendJSONResponse(conn, response)
}

// packPlayersData creates a binary packet with a count, timestamp, and data for each filtered player.
func packPlayersData(excludeId string, combatTagFilter string) ([]byte, int) {
	playersLock.RLock()
	defer playersLock.RUnlock()

	activePlayers := []*Player{}
	for _, p := range players {
		if p.PlayerId == excludeId {
			continue
		}
		if combatTagFilter == "" || p.CombatTag == combatTagFilter {
			activePlayers = append(activePlayers, p)
		}
	}
	log.Printf("Packing %d players", len(activePlayers))

	buf := new(bytes.Buffer)
	if err := binary.Write(buf, binary.BigEndian, uint32(len(activePlayers))); err != nil {
		log.Printf("Error writing player count: %v", err)
	}
	currentTimestamp := uint64(time.Now().UnixNano() / 1e6)
	if err := binary.Write(buf, binary.BigEndian, currentTimestamp); err != nil {
		log.Printf("Error writing timestamp: %v", err)
	}
	for _, p := range activePlayers {
		idPadded := padString(p.PlayerId, 36)
		buf.WriteString(idPadded)
		tagBytes := []byte(p.CombatTag)
		buf.WriteByte(uint8(len(tagBytes)))
		buf.Write(tagBytes)
		binary.Write(buf, binary.BigEndian, p.X)
		binary.Write(buf, binary.BigEndian, p.Y)
		binary.Write(buf, binary.BigEndian, p.VelocityX)
		binary.Write(buf, binary.BigEndian, p.VelocityY)
		colorByte := byte(2)
		if strings.ToLower(p.Color) == "red" {
			colorByte = 1
		}
		buf.WriteByte(colorByte)
		binary.Write(buf, binary.BigEndian, uint64(p.Timestamp))
	}
	return buf.Bytes(), len(activePlayers)
}

// udpPositionUpdateServer listens on UDP port 8089 for position updates.
func udpPositionUpdateServer() {
	addr := net.UDPAddr{
		Port: UDPPositionUpdatePort,
		IP:   net.ParseIP("0.0.0.0"),
	}
	conn, err := net.ListenUDP("udp", &addr)
	if err != nil {
		log.Fatalf("Error starting UDP position update server: %v", err)
	}
	defer conn.Close()
	log.Printf("UDP position update server listening on port %d", UDPPositionUpdatePort)
	for {
		buf := make([]byte, 1024)
		n, remoteAddr, err := conn.ReadFromUDP(buf)
		if err != nil {
			log.Printf("Error reading UDP packet: %v", err)
			continue
		}
		go handlePositionUpdate(buf[:n], remoteAddr)
	}
}

// handlePositionUpdate parses a binary packet with position update data and updates the player.
func handlePositionUpdate(data []byte, addr *net.UDPAddr) {
	reader := bytes.NewReader(data)
	var idLength uint16
	if err := binary.Read(reader, binary.BigEndian, &idLength); err != nil {
		log.Printf("Error reading idLength: %v", err)
		return
	}
	idBytes := make([]byte, idLength)
	if _, err := reader.Read(idBytes); err != nil {
		log.Printf("Error reading playerId: %v", err)
		return
	}
	playerId := string(idBytes)
	var timestamp uint64
	if err := binary.Read(reader, binary.BigEndian, &timestamp); err != nil {
		log.Printf("Error reading timestamp: %v", err)
		return
	}
	var x, y, velX, velY int32
	if err := binary.Read(reader, binary.BigEndian, &x); err != nil {
		log.Printf("Error reading x: %v", err)
		return
	}
	if err := binary.Read(reader, binary.BigEndian, &y); err != nil {
		log.Printf("Error reading y: %v", err)
		return
	}
	if err := binary.Read(reader, binary.BigEndian, &velX); err != nil {
		log.Printf("Error reading velocityX: %v", err)
		return
	}
	if err := binary.Read(reader, binary.BigEndian, &velY); err != nil {
		log.Printf("Error reading velocityY: %v", err)
		return
	}
	var colorLength uint16
	if err := binary.Read(reader, binary.BigEndian, &colorLength); err != nil {
		log.Printf("Error reading color length: %v", err)
		return
	}
	colorBytes := make([]byte, colorLength)
	if _, err := reader.Read(colorBytes); err != nil {
		log.Printf("Error reading color: %v", err)
		return
	}
	color := string(colorBytes)
	log.Printf("Position update for player %s: pos=(%d,%d), vel=(%d,%d), color=%s",
		playerId, x, y, velX, velY, color)

	playersLock.Lock()
	defer playersLock.Unlock()
	if p, exists := players[playerId]; exists {
		p.X = x
		p.Y = y
		p.VelocityX = velX
		p.VelocityY = velY
		p.Color = color
		p.Timestamp = int64(timestamp)
		p.Addr = addr
	} else {
		players[playerId] = &Player{
			PlayerId:   playerId,
			CombatTag:  "",
			X:          x,
			Y:          y,
			VelocityX:  velX,
			VelocityY:  velY,
			Color:      color,
			Timestamp:  int64(timestamp),
			Addr:       addr,
		}
		log.Printf("New player %s added via position update", playerId)
	}
}

// udpPlayerListServer listens on UDP port 8090 for player list requests.
func udpPlayerListServer() {
	addr := net.UDPAddr{
		Port: UDPPlayerListPort,
		IP:   net.ParseIP("0.0.0.0"),
	}
	conn, err := net.ListenUDP("udp", &addr)
	if err != nil {
		log.Fatalf("Error starting UDP player list server: %v", err)
	}
	defer conn.Close()
	log.Printf("UDP player list server listening on port %d", UDPPlayerListPort)
	for {
		buf := make([]byte, 1024)
		n, remoteAddr, err := conn.ReadFromUDP(buf)
		if err != nil {
			log.Printf("Error reading UDP packet: %v", err)
			continue
		}
		go handlePlayerListRequest(buf[:n], remoteAddr, conn)
	}
}

// handlePlayerListRequest parses a UDP request (assumed to be a player ID in text),
// then looks up that player's combat tag and sends back a filtered list.
func handlePlayerListRequest(data []byte, addr *net.UDPAddr, conn *net.UDPConn) {
	playerId := strings.TrimSpace(string(data))
	log.Printf("Player list request from %s", playerId)

	var combatTag string
	playersLock.RLock()
	if p, exists := players[playerId]; exists {
		combatTag = p.CombatTag
		p.Addr = addr
		p.Timestamp = time.Now().UnixNano() / 1e6
	}
	playersLock.RUnlock()

	excludeId := playerId
	log.Printf("Excluding player %s", excludeId)
	packet, count := packPlayersData(excludeId, combatTag)
	log.Printf("Sending %d players in response to %s with combat tag '%s'",
		count, playerId, combatTag)
	if _, err := conn.WriteToUDP(packet, addr); err != nil {
		log.Printf("Error sending UDP response: %v", err)
	}
}

// cleanupRoutine periodically removes players that have timed out.
func cleanupRoutine() {
	for {
		time.Sleep(1 * time.Second)
		now := time.Now().UnixNano() / 1e6
		playersLock.Lock()
		for id, p := range players {
			if id == "dummy-player-id" {
				continue
			}
			if now-p.Timestamp > TimeoutSeconds*1000 {
				log.Printf("Cleanup: Removing player %s due to timeout", id)
				delete(players, id)
			}
		}
		playersLock.Unlock()
	}
}

// addDummyPlayer adds a test player.
func addDummyPlayer() {
	dummy := &Player{
		PlayerId:  "dummy-player-id",
		CombatTag: "00000",
		X:         200,
		Y:         200,
		VelocityX: 0,
		VelocityY: 0,
		Color:     "blue",
		Timestamp: time.Now().UnixNano() / 1e6,
	}
	playersLock.Lock()
	players[dummy.PlayerId] = dummy
	playersLock.Unlock()
	log.Printf("Added dummy player for testing")
}

func main() {
	// Set up logging to both file and console.
	f, err := os.OpenFile("hybrid_server.log", os.O_RDWR|os.O_CREATE|os.O_APPEND, 0666)
	if err != nil {
		log.Fatalf("Error opening log file: %v", err)
	}
	defer f.Close()
	mw := io.MultiWriter(os.Stdout, f)
	log.SetOutput(mw)
	log.SetFlags(log.LstdFlags | log.Lshortfile)

	log.Println("Server starting...")

	addDummyPlayer()

	go cleanupRoutine()
	go tcpCombatIDServer()
	go tcpLevelCompletionServer()
	go udpPositionUpdateServer()
	go udpPlayerListServer()

	// Block forever.
	select {}
}