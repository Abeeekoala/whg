package whg;

import java.awt.Color;
import java.awt.Graphics;
import java.util.logging.Level;

public class RemotePlayer {
    private String playerId;
    private String combatTag;
    private int x;
    private int y;
    private Color color;

    private int velocityX = 0;
    private int velocityY = 0;
    
    // Timing fields
    private long positionTimestamp = 0;
    private long lastUpdateTime = System.currentTimeMillis();

    // Target position for interpolation
    private long targetTimestamp;
    
    public RemotePlayer(String playerId, String combatTag, int x, int y, Color color) {
        this.playerId = playerId;
        this.combatTag = combatTag;
        this.x = x;
        this.y = y;
        this.color = color;
        this.positionTimestamp = System.currentTimeMillis();
        this.targetTimestamp = positionTimestamp;
        this.velocityX = 0;
        this.velocityY = 0;
    }
 
    public void interpolatePosition(long currentTime) {
        if (currentTime > targetTimestamp + 8) {
            // Calculate time since the last update (in 100ms)
            float timeSinceUpdate = (currentTime - targetTimestamp) / 10.0f;
            
            // Predict the current position using velocity
            int predictedX = x + (int)(velocityX * timeSinceUpdate * 0.18);
            int predictedY = y + (int)(velocityY * timeSinceUpdate * 0.18);
            // Update the current position
            this.x = predictedX;
            this.y = predictedY;
            
            // Update the timestamp to the current time to avoid over-interpolation
            this.targetTimestamp = currentTime;
        }
        
        
    }
    
    public void draw(Graphics g) {
        // Set transparency for remote players
        int transparency = 100; // Out of 255
        
        // Draw outer black square with transparency
        g.setColor(new Color(0, 0, 0, transparency));
        g.fillRect(x - 15 , y - 15 + 22, 31, 31);
        
        // Draw colored inner square with transparency
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), transparency));
        g.fillRect(x - 12 , y - 12 + 22, 25, 25);
        
        // Draw combat tag above player
        if (playerId != null) {
            g.setColor(Color.BLACK);
            int tagWidth = g.getFontMetrics().stringWidth(playerId.substring(0, Math.min(8, playerId.length())));
            g.drawString("Player_" + playerId.substring(0, Math.min(8, playerId.length())), x - (tagWidth / 2), y - 15);
        }
    }
    
    // Set velocity components (stored but not used)
    public void setVelocity(int velocityX, int velocityY) {
        this.velocityX = velocityX;
        this.velocityY = velocityY;
    }
    
    // Set last update time
    public void setLastUpdateTime(long time) {
        this.lastUpdateTime = time;
    }
    
    // Set timestamp for packet ordering
    public void setPacketTimestamp(long timestamp) {
        this.positionTimestamp = timestamp;
    }
    
    // Get the last packet timestamp
    public long getPacketTimestamp() {
        return positionTimestamp;
    }
    
    // Getters
    public String getPlayerId() { return playerId; }
    public String getCombatTag() { return combatTag; }
    public int getX() { return x; }
    public int getY() { return y; }
    public Color getColor() { return color; }
    public int getVelocityX() { return velocityX; }
    public int getVelocityY() { return velocityY; }

    // Update method with timestamp checking to handle out-of-order packets
    public boolean update(String playerId, String combatTag, int x, int y, Color color, long lastUpdated) {
        // Only update if this packet is newer than our last update
        if (lastUpdated > this.positionTimestamp) {
            this.playerId = playerId;
            this.combatTag = combatTag;
            this.x = x;
            this.y = y;
            this.color = color;
            this.positionTimestamp = lastUpdated;
            this.targetTimestamp = lastUpdated;
            return true;  // Update was applied
        }
        return false;  // Update was ignored (out of order)
    }
    
    // Check if this player is stale (hasn't been updated recently)
    public boolean isStale(long currentTime) {
        return (currentTime - lastUpdateTime) > 10000; // 10 seconds
    }
} 
