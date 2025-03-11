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

var (
	players     = make(map[string]*Player)
	playersLock sync.RWMutex
)

// Constants for ports and timeout.
const (
	UDPPositionUpdatePort = 8089
	UDPPlayerListPort     = 8090
	TCPCombatIDPort       = 5000
	TimeoutSeconds        = 15
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
			// Default velocities and color.
			VelocityX: 0,
			VelocityY: 0,
			Color:     "red",
			Timestamp: time.Now().UnixNano() / 1e6,
		}
		log.Printf("Added new player %s with combatTag %s", playerId, combatId)
	}
}

// packPlayersData creates a binary packet with a count, timestamp, and data for each filtered player.
func packPlayersData(excludeId string, combatTagFilter string) ([]byte, int) {
	playersLock.RLock()
	defer playersLock.RUnlock()

	activePlayers := []*Player{}
	for _, p := range players {
		// Simply exclude the player with the specified ID
		if p.PlayerId == excludeId {
			continue
		}
		
		// Filter by combat tag if specified
		if combatTagFilter == "" || p.CombatTag == combatTagFilter {
			activePlayers = append(activePlayers, p)
		}
	}
	log.Printf("Packing %d players", len(activePlayers))

	buf := new(bytes.Buffer)
	// Write the number of players (uint32) and current timestamp (uint64).
	if err := binary.Write(buf, binary.BigEndian, uint32(len(activePlayers))); err != nil {
		log.Printf("Error writing player count: %v", err)
	}
	currentTimestamp := uint64(time.Now().UnixNano() / 1e6)
	if err := binary.Write(buf, binary.BigEndian, currentTimestamp); err != nil {
		log.Printf("Error writing timestamp: %v", err)
	}
	// Write each player's data.
	for _, p := range activePlayers {
		// Write PlayerId as a fixed 36-byte string.
		idPadded := padString(p.PlayerId, 36)
		buf.WriteString(idPadded)
		// Write the combat tag: 1 byte for length then tag bytes.
		tagBytes := []byte(p.CombatTag)
		buf.WriteByte(uint8(len(tagBytes)))
		buf.Write(tagBytes)
		// Write x, y, velocityX, and velocityY (each int32).
		binary.Write(buf, binary.BigEndian, p.X)
		binary.Write(buf, binary.BigEndian, p.Y)
		binary.Write(buf, binary.BigEndian, p.VelocityX)
		binary.Write(buf, binary.BigEndian, p.VelocityY)
		// Write color as one byte (1 for "red", 2 for anything else).
		colorByte := byte(2)
		if strings.ToLower(p.Color) == "red" {
			colorByte = 1
		}
		buf.WriteByte(colorByte)
		// Write the player's last update timestamp (uint64).
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
		// Also update player's UDP address and timestamp.
		p.Addr = addr
		p.Timestamp = time.Now().UnixNano() / 1e6
	}
	playersLock.RUnlock()

	// The problem is here - we're only excluding the player if combatTag is empty
	// Let's fix it to always exclude the requesting player
	excludeId := playerId  // Always exclude the requesting player
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
		// Zero velocity.
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
	go udpPositionUpdateServer()
	go udpPlayerListServer()

	// Block forever.
	select {}
}
