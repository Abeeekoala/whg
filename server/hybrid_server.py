import socket
import struct
import threading
import time
import logging
import random
import json
from collections import defaultdict

# Configure logging
logging.basicConfig(
    level=logging.DEBUG,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler('hybrid_server.log')
    ]
)
logger = logging.getLogger(__name__)
logger.setLevel(logging.WARNING)

UDP_HOST = '0.0.0.0'
POSITION_UPDATE_PORT = 8089
PLAYER_LIST_PORT = 8090
TIMEOUT = 15
players = {}
player_lock = threading.Lock()
level_completions = defaultdict(set)  # Combat ID -> set of player IDs that completed the level
level_numbers = {}  # Combat ID -> current level number
level_lock = threading.Lock()

position_update_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
position_update_socket.bind(('', POSITION_UPDATE_PORT))

player_list_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
player_list_socket.bind(('', PLAYER_LIST_PORT))

# ------------------------
# TCP SERVER FUNCTIONS
# ------------------------

def handle_tcp_combat_id(conn, addr):
    """Handle an incoming TCP connection to update the combat ID."""
    try:
        data = conn.recv(1024)
        if not data:
            return
        # Parse the JSON data sent by the client
        msg = json.loads(data.decode('utf-8'))
        player_id = msg.get("playerId")
        combat_id = msg.get("combatId")
        if player_id and combat_id:
            with player_lock:
                if player_id in players:
                    players[player_id]['combatTag'] = combat_id
                    logger.info(f"Updated combatTag for player {player_id} to {combat_id}")
                else:
                    # Optionally, add a new entry if not already present
                    players[player_id] = {
                        'playerId': player_id,
                        'combatTag': combat_id,
                        'x': 0,
                        'y': 0,
                        'velocityX': 0,
                        'velocityY': 0,
                        'color': 'red',  # default color if not specified
                        'timestamp': time.time_ns() // 1_000_000,
                        'addr': None
                    }
                    logger.info(f"Added new player {player_id} with combatTag {combat_id}")
    except Exception as e:
        logger.error(f"TCP combat ID update error: {e}")
    finally:
        conn.close()

def tcp_combat_id_server():
    """Start a TCP server on port 5000 to handle combat ID binding."""
    tcp_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    tcp_socket.bind(('', 5000))
    tcp_socket.listen(5)
    logger.info("TCP combat ID server listening on port 5000")
    while True:
        conn, addr = tcp_socket.accept()
        threading.Thread(target=handle_tcp_combat_id, args=(conn, addr), daemon=True).start()

def handle_tcp_level_completion(conn, addr):
    """Handle an incoming TCP connection for level completion."""
    try:
        data = conn.recv(1024)
        if not data:
            return

        # Parse the JSON data sent by the client
        msg = json.loads(data.decode('utf-8'))
        player_id = msg.get("playerId")
        combat_id = msg.get("combatId")
        level_num = msg.get("levelNum")
        
        if not all([player_id, combat_id, level_num is not None]):
            logger.warning(f"Invalid level completion data: {msg}")
            conn.send(json.dumps({"allCompleted": False}).encode('utf-8'))
            return
            
        logger.info(f"Level completion: Player {player_id} completed level {level_num} (combat: {combat_id})")
        
        all_completed = False
        with level_lock:
            # Update level number for this combat group if higher
            current_level = level_numbers.get(combat_id, 0)
            if level_num > current_level:
                level_numbers[combat_id] = level_num
                # Clear previous level completions when starting a new level
                level_completions[combat_id] = set()
                
            # Mark this player as having completed the level
            level_completions[combat_id].add(player_id)
            
            # Get all players in this combat group
            players_in_group = []
            with player_lock:
                for pid, pdata in players.items():
                    if pdata.get('combatTag') == combat_id and pid != 'dummy-player-id':
                        players_in_group.append(pid)
            
            # Check if all players in the group have completed
            missing_players = set(players_in_group) - level_completions[combat_id]
            all_completed = len(missing_players) == 0
            
            logger.info(f"Combat {combat_id}: {len(level_completions[combat_id])}/{len(players_in_group)} " + 
                      f"players completed level {level_num}. All completed: {all_completed}")
            
            # If all completed, increment level number and clear completions
            if all_completed:
                level_numbers[combat_id] = level_num + 1
                level_completions[combat_id] = set()
                
        # Send response
        response = {"allCompleted": all_completed}
        conn.send(json.dumps(response).encode('utf-8'))
        
    except Exception as e:
        logger.error(f"TCP level completion error: {e}")
        try:
            conn.send(json.dumps({"allCompleted": False, "error": str(e)}).encode('utf-8'))
        except:
            pass
    finally:
        conn.close()

def tcp_level_completion_server():
    """Start a TCP server on port 5001 to handle level completion synchronization."""
    tcp_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    tcp_socket.bind(('', 5001))
    tcp_socket.listen(5)
    logger.info("TCP level completion server listening on port 5001")
    while True:
        conn, addr = tcp_socket.accept()
        threading.Thread(target=handle_tcp_level_completion, args=(conn, addr), daemon=True).start()

# ------------------------
# UDP SERVER FUNCTIONS
# ------------------------

def pack_players_data(exclude_id=None, combat_tag=None):
    """Create a binary packet with filtered player data
    
    Args:
        exclude_id: Player ID to exclude from response
        combat_tag: If provided, only include players with this combat tag
    """
    active_players = []
    
    with player_lock:
        # Get filtered active players
        for pid, pdata in players.items():
            if pid != exclude_id:
                logger.debug(f"Player {pid}: has tag '{pdata['combatTag']}', filter is '{combat_tag}'")
                # If combat_tag filter is provided, check that it matches
                if combat_tag is None or pdata['combatTag'] == combat_tag:
                    # Create a copy to avoid potential race conditions
                    player_copy = pdata.copy()
                    active_players.append(player_copy)
                    logger.debug(f"Including player {pid}: pos=({player_copy['x']},{player_copy['y']}), " +
                                f"vel=({player_copy['velocityX']},{player_copy['velocityY']})")
    
    # Debug - show what we're packing
    logger.debug(f"Packing {len(active_players)} players")
    
    # Create binary packet - allocate more space to be safe
    # 4 bytes for player count + 8 bytes for timestamp + each player's data
    max_player_size = 100  # Increased from 50 to handle longer tags
    packet = bytearray(4 + 8 + len(active_players) * max_player_size)
    
    # Number of players (4 bytes)
    struct.pack_into('!I', packet, 0, len(active_players))
    
    # Get a more precise millisecond timestamp using time_ns() (Python 3.7+)
    try:
        # More precise method using nanoseconds divided by a million
        current_timestamp = time.time_ns() // 1_000_000  # Convert ns to ms
    except AttributeError:
        # Fallback for older Python versions
        import datetime
        current_timestamp = int(datetime.datetime.now().timestamp() * 1000)
    
    # Pack the precise timestamp (8 bytes)
    struct.pack_into('!Q', packet, 4, current_timestamp)
    
    # Start at offset 12 (after player count and timestamp)
    offset = 12
    
    for player in active_players:
        # Player ID (36 bytes)
        player_id_bytes = player['playerId'].encode('utf-8').ljust(36)
        packet[offset:offset+36] = player_id_bytes
        offset += 36
        
        # Combat tag with length prefix
        tag_bytes = player['combatTag'].encode('utf-8')
        tag_len = len(tag_bytes)
        
        # Ensure we have enough space for tag length + tag bytes
        if offset + 1 + tag_len >= len(packet):
            # Dynamically expand the packet if needed
            packet.extend(bytearray(max_player_size))
            
        # Write tag length
        packet[offset] = tag_len
        offset += 1
        
        # Write tag bytes
        packet[offset:offset+tag_len] = tag_bytes
        offset += tag_len
        
        # Ensure we have enough space for position and velocity (16 bytes)
        if offset + 16 >= len(packet):
            packet.extend(bytearray(max_player_size))
            
        # Position and velocity
        struct.pack_into('!iiii', packet, offset, 
                         player['x'], player['y'], 
                         player['velocityX'], player['velocityY'])
        offset += 16
        
        # Ensure we have enough space for color (1 byte)
        if offset + 1 >= len(packet):
            packet.extend(bytearray(max_player_size))
            
        # Color
        packet[offset] = 1 if player['color'] == 'red' else 2
        offset += 1
        last_updated_ms = int(player['timestamp'])
        struct.pack_into('!Q', packet, offset, last_updated_ms)
        offset += 8
        
        logger.debug(f"Packed player {player['playerId']}: pos=({player['x']},{player['y']}), " +
                    f"vel=({player['velocityX']},{player['velocityY']})")
    
    # Trim packet to actual size
    packet = packet[:offset]
    
    return packet, len(active_players)

def handle_udp_request(data, addr, sock):
    """Process incoming UDP request for player list"""
    try:
        # Extract player ID - should be a UUID string
        player_id = data.decode('utf-8').strip()
        logger.debug(f"Received player ID player_list_request: '{player_id}'")
        
        # Update player's UDP address so we can send them updates
        combat_tag = None
        with player_lock:
            if player_id in players:
                players[player_id]['addr'] = addr
                players[player_id]['timestamp'] = time.time_ns() // 1_000_000  # Update last seen time
                
                # Use the player's own combat tag as the filter
                combat_tag = players[player_id]['combatTag']
                logger.debug(f"UDP: Player {player_id} has tag '{combat_tag}', filtering players with same tag")
            else:
                logger.debug(f"UDP: New player {player_id} requesting player list but not yet registered")
        
        # Don't exclude self when filtering by combat tag
        # This is important so players can see themselves
        exclude_id = player_id
        
        # Create and send response with filtered players
        packet, count = pack_players_data(exclude_id=exclude_id, combat_tag=combat_tag)
        
        if packet:
            try:
                sock.sendto(packet, addr)
                logger.debug(f"UDP: Sent {count} players to {player_id[:8]}... with tag filter '{combat_tag}'")
                logger.debug(f"UDP: Packet length: {len(packet)}")
            except Exception as e:
                logger.error(f"UDP: Failed to send response: {e}")
    
    except Exception as e:
        logger.error(f"UDP: Error processing request: {e}")
        import traceback
        logger.error(traceback.format_exc())
        logger.error(f"UDP: Request data length: {len(data)}")
        if len(data) > 0:
            logger.error(f"UDP: First few bytes: {' '.join(f'{b:02x}' for b in data[:min(20, len(data))])}")

def handle_position_update(data, addr):
    """Process incoming position update packet"""
    try:
        offset = 0

        # Read player ID: 2 bytes length + variable string
        if len(data) < offset + 2:
            logger.warning("Packet too short for player ID length")
            return
        id_length = struct.unpack('!H', data[offset:offset+2])[0]
        offset += 2
        if len(data) < offset + id_length:
            logger.warning("Packet too short for player ID")
            return
        player_id = data[offset:offset + id_length].decode('utf-8')
        logger.debug(f"Received position update for player: '{player_id}'")
        offset += id_length

        # Read timestamp: 8 bytes (long)
        if len(data) < offset + 8:
            logger.warning("Packet too short for timestamp")
            return
        timestamp = struct.unpack('!Q', data[offset:offset+8])[0]
        offset += 8

        # Read x: 4 bytes (int)
        if len(data) < offset + 4:
            logger.warning("Packet too short for x")
            return
        x = struct.unpack('!i', data[offset:offset+4])[0]
        offset += 4

        # Read y: 4 bytes (int)
        if len(data) < offset + 4:
            logger.warning("Packet too short for y")
            return
        y = struct.unpack('!i', data[offset:offset+4])[0]
        offset += 4

        # Read velocityX: 4 bytes (int)
        if len(data) < offset + 4:
            logger.warning("Packet too short for velocityX")
            return
        velocityX = struct.unpack('!i', data[offset:offset+4])[0]
        offset += 4

        # Read velocityY: 4 bytes (int)
        if len(data) < offset + 4:
            logger.warning("Packet too short for velocityY")
            return
        velocityY = struct.unpack('!i', data[offset:offset+4])[0]
        offset += 4

        # Read color: 2 bytes length + variable string
        if len(data) < offset + 2:
            logger.warning("Packet too short for color length")
            return
        color_length = struct.unpack('!H', data[offset:offset+2])[0]
        offset += 2
        if len(data) < offset + color_length:
            logger.warning("Packet too short for color")
            return
        color = data[offset:offset + color_length].decode('utf-8')

        # Log the received data for debugging
        logger.info(f"Position update: player={player_id}, pos=({x},{y}), vel=({velocityX},{velocityY}), color={color}")

        # Update player data
        with player_lock:
            if player_id in players:
                players[player_id].update({
                    'x': x,
                    'y': y,
                    'velocityX': velocityX,
                    'velocityY': velocityY,
                    'color': color,
                    'timestamp': timestamp
                })
                logger.info(f"Updated player {player_id}: pos=({x},{y}), vel=({velocityX},{velocityY})")
            else:
                # If player doesn't exist yet, create it with default combat tag
                players[player_id] = {
                    'playerId': player_id,
                    'x': x,
                    'y': y,
                    'velocityX': velocityX,
                    'velocityY': velocityY,
                    'color': color,
                    'timestamp': timestamp,
                    'combatTag': '',  # Default empty tag
                    'addr': addr
                }
                logger.info(f"New player {player_id}: pos=({x},{y}), vel=({velocityX},{velocityY})")
            
            # Debug check - print out the stored values to verify
            logger.debug(f"Stored player {player_id}: pos=({players[player_id]['x']},{players[player_id]['y']}), " +
                          f"vel=({players[player_id]['velocityX']},{players[player_id]['velocityY']})")
    except Exception as e:
        logger.error(f"Error processing position update: {e}")
        import traceback
        logger.error(traceback.format_exc())

def handle_position_updates(sock):
    """Listen for position updates on the position update socket"""
    while True:
        data, addr = sock.recvfrom(1024)
        handle_position_update(data, addr)

def handle_player_list_requests(sock):
    """Listen for player list requests on the player list socket"""
    while True:
        data, addr = sock.recvfrom(1024)
        handle_udp_request(data, addr, sock)

def cleanup_thread_func():
    """Periodically clean up inactive players"""
    while True:
        time.sleep(1)
        current_time = time.time()
        with player_lock:
            for pid in list(players.keys()):
                if (current_time - players[pid]['timestamp'] / 1000 ) > TIMEOUT and players[pid]['playerId'] != 'dummy-player-id':
                    logger.info(f"Cleanup: Player {pid} timed out; timestamp: {players[pid]['timestamp']}, current time: {current_time}")
                    players.pop(pid)

def add_dummy_player():
    """Add a dummy player for testing"""
    dummy_id = "dummy-player-id"
    players[dummy_id] = {
        'playerId': dummy_id,
        'combatTag': '00000',
        'x': 200,
        'y': 200, 
        'velocityX': 0,
        'velocityY': 0,
        'color': 'blue',
        'timestamp': time.time_ns() // 1_000_000,
        'addr': None
    }
    logger.info("Added dummy player for testing")

def main():
    # Add a dummy player for testing
    add_dummy_player()
    
    # Start cleanup thread
    threading.Thread(target=cleanup_thread_func, daemon=True).start()
    
    # Start TCP combat ID server
    threading.Thread(target=tcp_combat_id_server, daemon=True).start()
    
    # Start TCP level completion server
    threading.Thread(target=tcp_level_completion_server, daemon=True).start()
    
    # Start position update thread
    threading.Thread(target=handle_position_updates, args=(position_update_socket,), daemon=True).start()
    
    # Start player list request thread
    threading.Thread(target=handle_player_list_requests, args=(player_list_socket,), daemon=True).start()
    
    logger.info("Server started")
    while True:
        time.sleep(1)  # Keep main thread alive

if __name__ == "__main__":
    main()