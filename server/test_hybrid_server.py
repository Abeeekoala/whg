import requests
import socket
import struct
import time
import uuid
import threading
import argparse
import sys
import binascii

class HybridClient:
    def __init__(self, http_host, http_port, udp_port=None):
        self.http_url = f"http://{http_host}:{http_port}"
        self.udp_host = http_host
        self.udp_port = udp_port  # May be updated after HTTP connection
        self.player_id = str(uuid.uuid4())
        self.combat_tag = "TEST_HYBRID"
        self.udp_socket = None
        self.receive_thread = None
        self.running = False
        self.players = []
    
    def http_update_position(self, x, y, vel_x=0, vel_y=0, color="red"):
        """Send position update using HTTP/TCP"""
        player_data = {
            "playerId": self.player_id,
            "combatTag": self.combat_tag,
            "x": x,
            "y": y,
            "velocityX": vel_x,
            "velocityY": vel_y,
            "color": color
        }
        
        try:
            response = requests.post(f"{self.http_url}/update", json=player_data)
            if response.status_code == 200:
                # If the server tells us the UDP port, use it
                data = response.json()
                if self.udp_port is None and 'udp_port' in data:
                    self.udp_port = data['udp_port']
                    print(f"Using UDP port {self.udp_port} from server")
                return True, data
            else:
                return False, f"HTTP error: {response.status_code} - {response.text}"
        except Exception as e:
            return False, f"HTTP request failed: {e}"
    
    def start_udp(self):
        """Start UDP socket for receiving player updates"""
        if self.udp_port is None:
            return False, "UDP port not set"
        
        try:
            self.udp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.udp_socket.settimeout(1.0)
            self.running = True
            
            self.receive_thread = threading.Thread(target=self._udp_receive_loop)
            self.receive_thread.daemon = True
            self.receive_thread.start()
            
            return True, "UDP client started"
        except Exception as e:
            return False, f"Failed to start UDP client: {e}"
    
    def stop(self):
        """Stop the client and release resources"""
        self.running = False
        if self.udp_socket:
            try:
                self.udp_socket.close()
            except:
                pass
            self.udp_socket = None
    
    def request_player_positions(self, filter_combat_tag=None):
        """Send UDP request for player positions
        
        Args:
            filter_combat_tag: If provided, server will only return players with this combat tag
        """
        if not self.udp_socket or not self.running:
            return False, "UDP client not running"
        
        try:
            # Clear previous players list
            self.players = []
            
            # Base request is just our player ID
            request_packet = bytearray(self.player_id.encode('utf-8'))
            
            # If we're filtering by combat tag, add it
            if filter_combat_tag:
                tag_bytes = filter_combat_tag.encode('utf-8')
                request_packet.append(len(tag_bytes))  # Tag length
                request_packet.extend(tag_bytes)       # Tag value
            
            bytes_sent = self.udp_socket.sendto(
                request_packet, 
                (self.udp_host, self.udp_port)
            )
            
            filter_info = f" (filtering by tag '{filter_combat_tag}')" if filter_combat_tag else ""
            return True, f"Sent {bytes_sent} bytes UDP request{filter_info}"
        except Exception as e:
            return False, f"Failed to send UDP request: {e}"
    
    def _udp_receive_loop(self):
        """Background thread to receive UDP packets"""
        print("UDP receive thread started")
        while self.running:
            try:
                data, addr = self.udp_socket.recvfrom(4096)
                #print(f"Received {len(data)} bytes UDP data from {addr}")
                
                # Parse the received data
                try:
                    players = self._parse_player_packet(data)
                    if players:
                        self.players = players
                        #print(f"Received data for {len(players)} players")
                        #for i, player in enumerate(players):
                        #print(f"  {i+1}. {player['tag']} at ({player['x']},{player['y']})")
                except Exception as e:
                    print(f"Error parsing player data: {e}")
                    # Print packet in hex for debugging
                    print(f"Packet: {binascii.hexlify(data[:64])}...")
                    
            except socket.timeout:
                # This is expected, just retry
                pass
            except Exception as e:
                if self.running:
                    print(f"UDP receive error: {e}")
                break
    
    def _parse_player_packet(self, data):
        """Parse a UDP player update packet"""
        offset = 0
        players = []
        
        # Read number of players
        if len(data) < 4:
            print("Packet too short")
            return []
            
        player_count = struct.unpack('!I', data[0:4])[0]
        offset = 4
        
        #print(f"Packet contains {player_count} players")
        
        for _ in range(player_count):
            if offset + 36 > len(data):
                break
                
            # Read player ID
            player_id = data[offset:offset+36].decode('utf-8').strip()
            offset += 36
            
            # Read tag length and tag
            if offset >= len(data):
                break
            tag_len = data[offset]
            offset += 1
            
            if offset + tag_len > len(data):
                break
            tag = data[offset:offset+tag_len].decode('utf-8')
            offset += tag_len
            
            # Read position and velocity
            if offset + 16 > len(data):
                break
            x, y, vel_x, vel_y = struct.unpack('!iiii', data[offset:offset+16])
            offset += 16
            
            # Read color
            if offset >= len(data):
                break
            color = "red" if data[offset] == 1 else "blue"
            offset += 1
            
            players.append({
                "playerId": player_id,
                "tag": tag,
                "x": x,
                "y": y,
                "velocityX": vel_x,
                "velocityY": vel_y,
                "color": color
            })
        
        return players
    
    def get_players(self):
        """Get the latest list of players"""
        return self.players.copy()

def run_tests(server_host, http_port, udp_port=None):
    """Run a series of tests on the hybrid server"""
    print(f"\n--- Testing Hybrid Server at {server_host}:{http_port} ---\n")
    
    # Create two test clients
    client1 = HybridClient(server_host, http_port, udp_port)
    client2 = HybridClient(server_host, http_port, udp_port)
    client1.combat_tag = "Combat_1"
    client2.combat_tag = "Combat_1"
    
    try:
        # Test 1: HTTP Position Updates
        print("\n=== Test 1: HTTP Position Updates ===")
        success, result = client1.http_update_position(100, 100, 0, 0, "red")
        print(f"Client 1 HTTP update: {'Success' if success else 'Failed'} - {result}")
        
        success, result = client2.http_update_position(300, 300, 0, 0, "blue")
        print(f"Client 2 HTTP update: {'Success' if success else 'Failed'} - {result}")
        
        if udp_port is None:
            udp_port = client1.udp_port
            print(f"Using UDP port {udp_port} from server response")
        
        # Test 2: Start UDP Clients
        print("\n=== Test 2: UDP Client Initialization ===")
        success, result = client1.start_udp()
        print(f"Client 1 UDP start: {'Success' if success else 'Failed'} - {result}")
        
        success, result = client2.start_udp()
        print(f"Client 2 UDP start: {'Success' if success else 'Failed'} - {result}")
        
        # Test 3: Request Player Positions
        print("\n=== Test 3: UDP Position Requests ===")
        success, result = client1.request_player_positions()
        print(f"Client 1 UDP request: {'Success' if success else 'Failed'} - {result}")
        
        # Record start time
        start_time = time.time()
        
        # Wait for UDP response with timeout
        #print("Waiting for UDP response...")
        max_wait_time = 10  # Maximum wait time in seconds
        response_received = False
        
        while time.time() - start_time < max_wait_time:
            players = client1.get_players()
            if players:
                response_time = time.time() - start_time
                print(f"Response received after {response_time:.4f} seconds")
                response_received = True
                break
            time.sleep(0.0005)  # Small sleep to prevent CPU hogging
        
        # Check if players were received
        if response_received:
            print(f"Client 1 received {len(players)} players:")
            for i, player in enumerate(players):
                print(f"  {i+1}. {player['tag']} at ({player['x']},{player['y']})")
            print("Test PASSED")
        else:
            print(f"Client 1 didn't receive any players after waiting {max_wait_time} seconds. Test FAILED")
        
        # Test 4: Movement Updates
        print("\n=== Test 4: Movement Updates ===")
        success, result = client1.http_update_position(150, 150, 50, -50, "red")
        print(f"Client 1 moved: {'Success' if success else 'Failed'} - {result}")
        
        success, result = client2.request_player_positions()
        print(f"Client 2 UDP request: {'Success' if success else 'Failed'} - {result}")
        start_time = time.time()
        # Wait briefly for response
        #print("Waiting for UDP response...")
        max_wait_time = 10  # Maximum wait time in seconds
        response_received = False
        
        while time.time() - start_time < max_wait_time:
            players = client2.get_players()
            if players:
                response_time = time.time() - start_time
                print(f"Response received after {response_time:.4f} seconds")
                response_received = True
                break
            time.sleep(0.0005)  # Small sleep to prevent CPU hogging
        
        # Check if updated position was received
        if response_received:
            print(f"Client 2 received {len(players)} players:")
            for i, player in enumerate(players):
                print(f"  {i+1}. {player['tag']} at ({player['x']},{player['y']})")
                
            # Check if client1's new position is reflected
            for player in players:
                if player['tag'] == client1.combat_tag and player['x'] == 150 and player['y'] == 150:
                    print("Client 1's new position correctly received by Client 2. Test PASSED")
                    break
            else:
                print("Client 1's new position not received by Client 2. Test FAILED")
        else:
            print("Client 2 didn't receive any players. Test FAILED")
        
        # Test 5: Combat Tag Filtering
        print("\n=== Test 5: Combat Tag Filtering ===")
        
        # Create a third client with a different combat tag
        client3 = HybridClient(server_host, http_port, udp_port)
        client3.combat_tag = "DIFFERENT_TEAM"
        
        try:
            # Add the third client to the game
            success, result = client3.http_update_position(400, 400, 0, 0, "blue")
            print(f"Client 3 HTTP update: {'Success' if success else 'Failed'} - {result}")
            
            success, result = client3.start_udp()
            print(f"Client 3 UDP start: {'Success' if success else 'Failed'} - {result}")
            
            print("\n--- Testing unfiltered request ---")
            # Request all players (no filter)
            client1.players = []  # Clear any existing player data
            success, result = client1.request_player_positions()
            print(f"Client 1 requests all players: {'Success' if success else 'Failed'} - {result}")
            
            # Wait for response with better timing
            start_time = time.time()
            max_wait = 5  # seconds
            while time.time() - start_time < max_wait:
                if client1.players:
                    response_time = time.time() - start_time
                    print(f"Unfiltered response received after {response_time:.4f} seconds")
                    break
                time.sleep(0.0005)  # Small sleep to prevent CPU hogging
            
            # Make sure we have players before continuing
            if not client1.players:
                print(f"ERROR: No players received after {max_wait} seconds")
            else:
                print(f"Without filter, Client 1 received {len(client1.players)} players:")
                for player in client1.players:
                    print(f"  - {player['tag']} at ({player['x']},{player['y']})")
            
            print("\n--- Testing filtered request ---")
            # Now request only players with matching combat tag
            time.sleep(0.5)  # Add small delay between requests
            client1.players = []  # Clear any existing player data
            success, result = client1.request_player_positions(filter_combat_tag=client1.combat_tag)
            print(f"Client 1 requests only '{client1.combat_tag}' tag players: {'Success' if success else 'Failed'} - {result}")
            
            # Wait for response with better timing
            start_time = time.time()
            response_received = False
            while time.time() - start_time < max_wait:  # Longer timeout
                if client1.players:
                    response_time = time.time() - start_time
                    print(f"Filtered response received after {response_time:.4f} seconds")
                    response_received = True
                    break
                time.sleep(0.0005)
            
            if response_received:
                print(f"With filter, Client 1 received {len(client1.players)} players:")
                for player in client1.players:
                    print(f"  - {player['tag']} at ({player['x']},{player['y']})")
                
                # Print all tags for debugging
                print("Tags received:")
                for p in client1.players:
                    print(f"  - {p['tag']}")
                
                # Verify we only got players with the requested combat tag
                all_match = all(p['tag'] == client1.combat_tag for p in client1.players)
                print(f"Tag filter test {'PASSED' if all_match else 'FAILED'}")
                
                if not all_match:
                    print("ERROR: Received players with tags that don't match the filter")
            else:
                print(f"Failed to get filtered player list after {max_wait} seconds")
        
        finally:
            client3.stop()
        
        # Interactive mode
        if args.interactive:
            run_interactive_mode(client1)
        
    except Exception as e:
        print(f"Test error: {e}")
    finally:
        client1.stop()
        client2.stop()

def run_interactive_mode(client):
    """Run an interactive test session"""
    print("\n=== Starting Interactive Mode ===")
    print("Commands:")
    print("  move x y    - Move to position (x,y)")
    print("  color red|blue - Change color")
    print("  request     - Request player positions")
    print("  players     - Show current players")
    print("  quit        - Exit interactive mode")
    
    x, y = 200, 200
    color = "red"
    
    while True:
        try:
            cmd = input("> ").strip().lower()
            if cmd == "quit" or cmd == "exit" or cmd == "q":
                break
                
            if cmd.startswith("move "):
                parts = cmd.split()
                if len(parts) == 3:
                    try:
                        x = int(parts[1])
                        y = int(parts[2])
                        success, result = client.http_update_position(x, y, 0, 0, color)
                        print(f"Moved to ({x},{y}): {'Success' if success else 'Failed'} - {result}")
                    except ValueError:
                        print("Invalid coordinates")
                else:
                    print("Usage: move x y")
            
            elif cmd.startswith("color "):
                parts = cmd.split()
                if len(parts) == 2 and parts[1] in ["red", "blue"]:
                    color = parts[1]
                    success, result = client.http_update_position(x, y, 0, 0, color)
                    print(f"Changed color to {color}: {'Success' if success else 'Failed'} - {result}")
                else:
                    print("Usage: color red|blue")
            
            elif cmd == "request":
                success, result = client.request_player_positions()
                print(f"Requested positions: {'Success' if success else 'Failed'} - {result}")
                
            elif cmd == "players":
                players = client.get_players()
                if players:
                    print(f"Server reports {len(players)} players:")
                    for i, player in enumerate(players):
                        print(f"  {i+1}. {player['tag']} at ({player['x']},{player['y']})")
                else:
                    print("No players received")
            
            else:
                print("Unknown command")
        
        except KeyboardInterrupt:
            break
        except Exception as e:
            print(f"Error: {e}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Test the hybrid TCP/UDP server")
    parser.add_argument("--host", default="localhost", 
                      help="Server hostname (default: localhost)")
    parser.add_argument("--http-port", type=int, default=5000,
                      help="HTTP port (default: 5000)")
    parser.add_argument("--udp-port", type=int, default=None,
                      help="UDP port (default: use server response)")
    parser.add_argument("--interactive", action="store_true",
                      help="Start interactive mode after tests")
    
    args = parser.parse_args()
    
    run_tests(args.host, args.http_port, args.udp_port) 