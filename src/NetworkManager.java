package whg;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.Socket;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.net.SocketTimeoutException;
import java.net.InetSocketAddress;
import java.io.OutputStreamWriter;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

public class NetworkManager {
    private static NetworkManager instance;
    private String serverUrl;
    private String playerId;
    private String combatTag;
    private boolean connected = false;
    private ScheduledExecutorService scheduler;
    
    // Remote player tracking
    private RemotePlayer[] visiblePlayers = new RemotePlayer[20];
    private int playerCount = 0;
    private ExecutorService udpExecutor;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    // Position and velocity tracking
    private int lastSentX = -1;
    private int lastSentY = -1;
    private int velocityX = 0;
    private int velocityY = 0;
    private InetAddress serverAddr;
    
    // UDP networking
    private DatagramSocket udpSocket;
    private int positionUpdatePort = 8089; // Port for position updates
    private int playerListPort = 8090;     // Port for player list requests
    private byte[] udpBuffer = new byte[2048];
    
    // Threshold settings
    private static final long POSITION_UPDATE_INTERVAL = 75;
    
    // Connection management
    private HttpURLConnection persistentConnection;
    private long lastPositionSendTime = 0;
    
    private boolean initialized = false;
    
    private NetworkManager() {
        this.playerId = UUID.randomUUID().toString();
        this.combatTag = "Player_" + this.playerId.substring(0, 5);
    }
    
    public static synchronized NetworkManager getInstance() {
        if (instance == null) {
            instance = new NetworkManager();
        }
        return instance;
    }
    
    public void init(String serverUrl, String username) {
        if (initialized) return;
        
        this.serverUrl = serverUrl;
        this.playerId = username;
        this.udpExecutor = Executors.newSingleThreadExecutor();
        
        Game.easyLog(Game.logger, Level.INFO, "NetworkManager initialized with server: " + serverUrl);
        initialized = true;
    }
    
    /**
     * Connect to the server
     * @param serverUrl The URL of the server to connect to
     * @return true if connection was successful, false otherwise
     */
    public boolean connect(String serverUrl) {
        if (connected) return true; // Already connected
        
        // Update server URL if changed
        this.serverUrl = serverUrl;
        
        try {
            // Parse the server URL to get the host
            URI uri = new URI(serverUrl);
            String host = uri.getHost();
            
            if (host == null || host.isEmpty()) {
                Game.easyLog(Game.logger, Level.SEVERE, "Invalid server URL: " + serverUrl);
                return false;
            }
            
            // Resolve the server address
            try {
                this.serverAddr = InetAddress.getByName(host);
                Game.easyLog(Game.logger, Level.INFO, "Resolved server address: " + serverAddr.getHostAddress());
            } catch (Exception e) {
                Game.easyLog(Game.logger, Level.SEVERE, "Failed to resolve server address: " + e.getMessage());
                return false;
            }
            
            // Setup UDP socket
            try {
                udpSocket = new DatagramSocket();
                udpSocket.setSoTimeout(1000);  // 1 second timeout
            } catch (Exception e) {
                Game.easyLog(Game.logger, Level.SEVERE, "Failed to create UDP socket: " + e.getMessage());
                return false;
            }
            
            // Test UDP connectivity
            try {
                // Send a test packet to the player list port
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                
                // Send a minimal player ID for testing
                dos.writeUTF(playerId);
                
                byte[] testData = baos.toByteArray();
                DatagramPacket testPacket = new DatagramPacket(
                    testData, testData.length, 
                    serverAddr, playerListPort);
                
                // Send the test packet
                udpSocket.send(testPacket);
                
                // Try to receive a response
                byte[] receiveBuffer = new byte[1024];
                DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                
                try {
                    udpSocket.receive(receivePacket);
                    Game.easyLog(Game.logger, Level.INFO, "UDP connectivity test successful");
                } catch (SocketTimeoutException e) {
                    Game.easyLog(Game.logger, Level.WARNING, "UDP test timed out - server might be unreachable");
                    return false;
                }
            } catch (Exception e) {
                Game.easyLog(Game.logger, Level.WARNING, "UDP connectivity test failed: " + e.getMessage());
                return false;
            }
            
            // Test TCP connectivity (for combat ID)
            try {
                // Try to establish a TCP connection to port 5000
                Socket tcpTestSocket = new Socket();
                tcpTestSocket.connect(new InetSocketAddress(serverAddr, 5000), 2000); // 2-second timeout
                
                if (tcpTestSocket.isConnected()) {
                    Game.easyLog(Game.logger, Level.INFO, "TCP connectivity test successful");
                    
                    // Send a minimal test message
                    JSONObject testMsg = new JSONObject();
                    testMsg.put("playerId", playerId);
                    testMsg.put("combatId", "test");
                    
                    OutputStreamWriter out = new OutputStreamWriter(tcpTestSocket.getOutputStream());
                    out.write(testMsg.toString());
                    out.flush();
                    
                    // Close the test connection
                    tcpTestSocket.close();
                } else {
                    Game.easyLog(Game.logger, Level.WARNING, "TCP connection failed");
                    return false;
                }
            } catch (Exception e) {
                Game.easyLog(Game.logger, Level.WARNING, "TCP connectivity test failed: " + e.getMessage());
                return false;
            }
            
            // Setup scheduler for periodic updates
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdown();
            }
            
            scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(() -> {
                fetchUpdatesAsync();
            }, 0, 75, TimeUnit.MILLISECONDS);
            
            connected = true;
            Game.easyLog(Game.logger, Level.INFO, "Connected to server: " + serverUrl);
            return true;
        } catch (Exception e) {
            connected = false;
            Game.easyLog(Game.logger, Level.SEVERE, "Failed to connect to server: " + e.getMessage());
            
            // Clean up resources on failed connection
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
                udpSocket = null;
            }
            
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdown();
                scheduler = null;
            }
            
            return false;
        }
    }
    
    public void disconnect() {
        this.connected = false;
        if (scheduler != null) {
            scheduler.shutdown();
        }
        
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
        
        if (persistentConnection != null) {
            try {
                persistentConnection.disconnect();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
            persistentConnection = null;
        }
        
        udpExecutor.shutdown();
        
        Game.easyLog(Game.logger, Level.INFO, "Disconnected from server");
    }
    
    private void updateVelocity(int newX, int newY) {
        if (lastSentX != -1 && lastSentY != -1) {
            int deltaX = newX - lastSentX;
            int deltaY = newY - lastSentY;
            velocityX = deltaX;
            velocityY = deltaY;
        }
    }

    public void sendPlayerPosition(int x, int y, Color playerColor) {
        if (!connected || udpSocket == null) return;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPositionSendTime < POSITION_UPDATE_INTERVAL) {
            return;
        }
        
        try {
            updateVelocity(x, y);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeUTF(playerId); // 2-byte length + UTF-8 string
            dos.writeLong(currentTime); // 8 bytes
            dos.writeInt(x); // 4 bytes
            dos.writeInt(y); // 4 bytes
            dos.writeInt(velocityX); // 4 bytes
            dos.writeInt(velocityY); // 4 bytes
            dos.writeUTF(playerColor.equals(Color.RED) ? "red" : "blue"); // 2-byte length + UTF-8 string
            byte[] data = baos.toByteArray();

            InetAddress serverAddr = InetAddress.getByName(new URI(serverUrl).getHost());
            DatagramPacket packet = new DatagramPacket(data, data.length, serverAddr, positionUpdatePort);
            udpSocket.send(packet);
            lastPositionSendTime = currentTime;
            lastSentX = x;
            lastSentY = y;
        } catch (Exception e) {
            Game.easyLog(Game.logger, Level.SEVERE, "Error sending UDP position update: " + e.getMessage());
        }
    }

    /**
     * Fetch updates from the server asynchronously
     */
    private void fetchUdpUpdates() {
        if (!connected || udpSocket == null) return;
        
        try {
            // Simply send the UUID as a string without any prefixes or special formatting
            String simpleRequest = playerId;
            byte[] requestData = simpleRequest.getBytes("UTF-8");
            
            // Send the request to the player list port
            DatagramPacket requestPacket = new DatagramPacket(
                requestData, requestData.length, 
                serverAddr, playerListPort);
            
            udpSocket.send(requestPacket);
            
            // Prepare to receive the response
            byte[] responseBuffer = new byte[8192]; // 8KB buffer
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            
            // Wait for response with timeout
            udpSocket.receive(responsePacket);
            // Process the response
            processUdpResponse(responsePacket.getData(), responsePacket.getLength());
            
        } catch (SocketTimeoutException e) {
            // This is expected if no response comes back within timeout
        } catch (Exception e) {
            Game.easyLog(Game.logger, Level.WARNING, 
                "Error fetching updates: " + e.getMessage());
        }
    }
    
    private void processUdpResponse(byte[] data, int length) {
        rwLock.writeLock().lock();
        try {
            if (length < 12) {
                Game.easyLog(Game.logger, Level.WARNING, "UDP response too short");
                return;
            }
            
            int offset = 0;
            int playerCount = ((data[offset] & 0xFF) << 24) | 
                             ((data[offset+1] & 0xFF) << 16) | 
                             ((data[offset+2] & 0xFF) << 8) | 
                             (data[offset+3] & 0xFF);
            offset += 4;
            
            long serverTimestamp = 
                ((long)(data[offset] & 0xFF) << 56) |
                ((long)(data[offset+1] & 0xFF) << 48) |
                ((long)(data[offset+2] & 0xFF) << 40) |
                ((long)(data[offset+3] & 0xFF) << 32) |
                ((long)(data[offset+4] & 0xFF) << 24) |
                ((long)(data[offset+5] & 0xFF) << 16) |
                ((long)(data[offset+6] & 0xFF) << 8) |
                ((long)(data[offset+7] & 0xFF));
            offset += 8;

            if (playerCount > 0) {
                this.playerCount = 0;
                
                for (int i = 0; i < playerCount && offset < length; i++) {
                    byte[] playerIdBytes = new byte[36];
                    System.arraycopy(data, offset, playerIdBytes, 0, 36);
                    String playerId = new String(playerIdBytes).trim();
                    offset += 36;
                    
                    int tagLength = data[offset] & 0xFF;
                    offset += 1;
                    byte[] tagBytes = new byte[tagLength];
                    System.arraycopy(data, offset, tagBytes, 0, tagLength);
                    String tag = new String(tagBytes);
                    offset += tagLength;
                    
                    int x = ((data[offset] & 0xFF) << 24) | 
                           ((data[offset+1] & 0xFF) << 16) | 
                           ((data[offset+2] & 0xFF) << 8) | 
                           (data[offset+3] & 0xFF);
                    offset += 4;
                    
                    int y = ((data[offset] & 0xFF) << 24) | 
                           ((data[offset+1] & 0xFF) << 16) | 
                           ((data[offset+2] & 0xFF) << 8) | 
                           (data[offset+3] & 0xFF);
                    offset += 4;
                    
                    int velX = ((data[offset] & 0xFF) << 24) | 
                              ((data[offset+1] & 0xFF) << 16) | 
                              ((data[offset+2] & 0xFF) << 8) | 
                              (data[offset+3] & 0xFF);
                    offset += 4;
                    
                    int velY = ((data[offset] & 0xFF) << 24) | 
                              ((data[offset+1] & 0xFF) << 16) | 
                              ((data[offset+2] & 0xFF) << 8) | 
                              (data[offset+3] & 0xFF);
                    offset += 4;
                    
                    byte colorByte = data[offset];
                    offset += 1;
                    Color color = (colorByte == 1) ? Color.RED : Color.BLUE;
                    long lastUpdated = 
                        ((long)(data[offset] & 0xFF) << 56) |
                        ((long)(data[offset+1] & 0xFF) << 48) |
                        ((long)(data[offset+2] & 0xFF) << 40) |
                        ((long)(data[offset+3] & 0xFF) << 32) |
                        ((long)(data[offset+4] & 0xFF) << 24) |
                        ((long)(data[offset+5] & 0xFF) << 16) |
                        ((long)(data[offset+6] & 0xFF) << 8) |
                        (data[offset+7] & 0xFF);
                    offset += 8;
                    RemotePlayer player = null;
                    int playerIndex = -1;
                    
                    for (int j = 0; j < visiblePlayers.length; j++) {
                        if (visiblePlayers[j] != null && 
                            visiblePlayers[j].getPlayerId().equals(playerId)) {
                            player = visiblePlayers[j];
                            playerIndex = j;
                            break;
                        }
                    }
                    
                    if (player == null) {
                        for (int j = 0; j < visiblePlayers.length; j++) {
                            if (visiblePlayers[j] == null) {
                                visiblePlayers[j] = new RemotePlayer(playerId, tag, x, y, color);
                                visiblePlayers[j].setVelocity(velX, velY);
                                visiblePlayers[j].setPacketTimestamp(lastUpdated);
                                playerIndex = j;
                                break;
                            }
                        }
                    } else {
                        boolean updated = player.update(playerId, tag, x, y, color, lastUpdated);
                        if (updated) {
                            player.setVelocity(velX, velY);
                        }
                    }
                    
                    if (playerIndex >= 0) {
                        this.playerCount = Math.max(this.playerCount, playerIndex + 1);
                    }
                }
                
                for (int i = 0; i < this.playerCount; i++) {
                    if (visiblePlayers[i] != null) {
                        visiblePlayers[i].setLastUpdateTime(serverTimestamp);
                    }
                }
            }
        } catch (Exception e) {
            Game.easyLog(Game.logger, Level.WARNING, "Error processing UDP response: " + e.getMessage());
        } finally {
            rwLock.writeLock().unlock();
        }
    }
    
    public void updateRemotePlayers() {
        rwLock.writeLock().lock();
        try {
            long currentTime = System.currentTimeMillis();
            
            for (int i = 0; i < playerCount; i++) {
                if (visiblePlayers[i] != null) {
                    visiblePlayers[i].interpolatePosition(currentTime);
                }
            }
            
            boolean playerRemoved = false;
            for (int i = 0; i < playerCount; i++) {
                if (visiblePlayers[i] != null && visiblePlayers[i].isStale(currentTime)) {
                    Game.easyLog(Game.logger, Level.INFO, 
                        "Removing stale player: " + visiblePlayers[i].getPlayerId().substring(0, 5));
                    visiblePlayers[i] = null;
                    playerRemoved = true;
                }
            }
            
            if (playerRemoved) {
                compactPlayerArray();
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void withVisiblePlayers(Runnable action) {
        rwLock.readLock().lock();
        try {
            action.run();
        } finally {
            rwLock.readLock().unlock();
        }
    }
    
    private void compactPlayerArray() {
        int newCount = 0;
        for (int i = 0; i < visiblePlayers.length; i++) {
            if (visiblePlayers[i] != null) {
                if (i != newCount) {
                    visiblePlayers[newCount] = visiblePlayers[i];
                    visiblePlayers[i] = null;
                }
                newCount++;
            }
        }
        this.playerCount = newCount;
    }
    
    private void fetchUpdates() {
        if (!connected) return;
        long startTime = System.currentTimeMillis();
        fetchUdpUpdates();
        long endTime = System.currentTimeMillis();
        if (endTime - startTime > 100) {
            Game.easyLog(Game.logger, Level.INFO, "Fetch operation took " + (endTime - startTime) + "ms");
        }
        // updateRemotePlayers();
    }
    
    public void fetchUpdatesAsync() {
        udpExecutor.submit(this::fetchUpdates);
    }
    
    public RemotePlayer[] getVisiblePlayers() {
        return visiblePlayers;
    }
    
    public int getPlayerCount() {
        return playerCount;
    }
    
    public String getPlayerId() {
        return playerId;
    }
    
    public String getCombatTag() {
        return combatTag;
    }

    /**
     * Send the combat ID to the server via TCP
     * @param combatId The combat ID to send
     */
    public void sendCombatIdToServer(String combatId) {
        this.combatTag = combatId;
        
        try {
            // Open a TCP connection on port 5000
            Socket socket = new Socket(serverAddr, 5000);
            
            // Create a JSON message with the playerId and combatId
            JSONObject obj = new JSONObject();
            obj.put("playerId", playerId);
            obj.put("combatId", combatId);
            String jsonStr = obj.toJSONString();
            
            // Use OutputStreamWriter instead of DataOutputStream.writeUTF()
            // This will send the raw JSON string without any length prefix
            OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream(), "UTF-8");
            writer.write(jsonStr);
            writer.flush();
            socket.close();
            
            Game.easyLog(Game.logger, Level.INFO, "Sent combat ID update to server: " + combatId);
        } catch(Exception e) {
            Game.easyLog(Game.logger, Level.SEVERE, "Failed to send combat ID update: " + e.getMessage());
        }
    }

    public boolean isConnected() {
        return this.connected;
    }
    
    public void sendPlayerPositionAsync(final int x, final int y, final Color playerColor) {
        Thread networkThread = new Thread(new Runnable() {
            @Override
            public void run() {
                sendPlayerPosition(x, y, playerColor);
            }
        });
        networkThread.start();
    }

    /**
     * Sends level completion notification to server and waits for response
     * @param levelNum The level number that was completed
     * @return True if all players have completed the level and we can proceed
     */
    public boolean sendLevelCompletionToServer(int levelNum) {
        try {
            // Create socket
            Socket socket = new Socket(serverAddr, 5001); // New port for level completion
            socket.setSoTimeout(10000); // 10 second timeout
            
            // Prepare message
            JSONObject message = new JSONObject();
            message.put("playerId", playerId);
            message.put("combatId", combatTag);
            message.put("levelNum", levelNum);
            
            // Send message
            OutputStream output = socket.getOutputStream();
            output.write(message.toString().getBytes());
            
            // Wait for response
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[1024];
            int bytesRead = input.read(buffer);
            
            if (bytesRead > 0) {
                String response = new String(buffer, 0, bytesRead);
                JSONParser parser = new JSONParser();
                JSONObject responseJson = (JSONObject)parser.parse(response);
                boolean allCompleted = Boolean.TRUE.equals(responseJson.get("allCompleted"));
                
                Game.easyLog(Game.logger, Level.INFO, 
                    "Level completion response: " + (allCompleted ? "All players completed" : "Waiting for others"));
                
                socket.close();
                return allCompleted;
            }
            
            socket.close();
            return false;
        } catch (Exception e) {
            Game.easyLog(Game.logger, Level.SEVERE, "Error sending level completion: " + Game.getStringFromStackTrace(e));
            return false; // On error, allow the player to proceed (graceful degradation)
        }
    }
}