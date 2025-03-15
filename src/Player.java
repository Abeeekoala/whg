package whg;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.Shape;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.logging.Level;
import java.io.DataInputStream;


import kuusisto.tinysound.Sound;
import kuusisto.tinysound.TinySound;
import javax.swing.SwingUtilities;

public class Player {

	private String name;
	private Color playerColor;
	private int x;
	private int y;
	private int snapX;
	private int snapY;
	private boolean collidingUp;
	private boolean collidingDown;
	private boolean collidingLeft;
	private boolean collidingRight;
	private int deaths;
	private boolean already_minus;
	private long powerupMessageEndTime = 0;
	private boolean dead;
	private double opacity;

	// New fields for FPGA controller support
	private int port;
	private double xTilt = 0.0;
	private double yTilt = 0.0;
	private FPGAServer fpgaServer;

	private boolean hasNotifiedLevelCompletion = false;

	//PowerUp 
	public enum PowerUp{
		SPEED_BOOST,
		IMMUNITY,
		SLOW_DOTS,
		MINUS_DEATHS,
	}

	private PowerUp activePowerUp = null;
	private long powerUpEndTime = 0;
	private static final int MOVEMENT_STEP_BOOST = 2;
	private static final long POWERUP_DURATION = 5000; // 5 seconds
	public boolean powerupactive = false; 

	//Random PowerUp generator
	private void activateRandomPowerUp() {
		powerupactive = true;
		PowerUp[] powerUps = PowerUp.values();
		activePowerUp = powerUps[(int) (Math.random() * powerUps.length)];
		Game.easyLog(Game.logger, Level.INFO, "Power-Up Activated: " + activePowerUp.name());
		powerUpEndTime = System.currentTimeMillis() + POWERUP_DURATION;
		powerupMessageEndTime = System.currentTimeMillis() + 1000;
		setPlayerColor(Color.GREEN);
		switch (activePowerUp) {
			case SPEED_BOOST:
				break;
			case IMMUNITY:
				break;
			case SLOW_DOTS:
				break;
			case MINUS_DEATHS:
				break;
		}
	}

	public long getPowerupMessageEndTime() {
    	return this.powerupMessageEndTime;
	}

	public PowerUp getActivePowerUp(){
		return this.activePowerUp;
	}

	// Inner class to handle FPGA server for each player
	public class FPGAServer {
		private ServerSocket serverSocket;
		private Socket clientSocket;
		private DataInputStream dataInputStream;
		private Thread serverThread;

		// Declare xTilt and yTilt as volatile for thread safety
		private volatile double xTilt = 0.0;
		private volatile double yTilt = 0.0;

		private static final int TILT_THRESHOLD_X = 100;
		private static final int TILT_THRESHOLD_Y = 100;
		private static final int MOVEMENT_STEP = 1;

		public FPGAServer(int port) {
			this.serverThread = new Thread(() -> {
				try {
					serverSocket = new ServerSocket(port);
					System.out.println("Server started on port " + port + ". Waiting for connection...");

					clientSocket = serverSocket.accept();
					System.out.println("Connected to client on port " + port + "!");

					// Use DataInputStream to read bytes
					dataInputStream = new DataInputStream(clientSocket.getInputStream());

					while (true) {
						// Read 4 bytes (2 bytes for X, 2 bytes for Y)
						byte[] buffer = new byte[4];
						int bytesRead = dataInputStream.read(buffer);
						if (bytesRead == 4) {
							// Unpack the bytes into two short integers (little-endian)
							short xValue = (short) ((buffer[1] & 0xFF) << 8 | (buffer[0] & 0xFF));
							short yValue = (short) ((buffer[3] & 0xFF) << 8 | (buffer[2] & 0xFF));

							// Update tilt values
							xTilt = xValue;  // Implicit conversion from short to double
							yTilt = yValue;  // Implicit conversion from short to double

							// Debugging: Print received values
							//System.out.println("FPGA Server - Received X: " + xTilt + ", Y: " + yTilt);
						} else {
							System.out.println("Invalid data received.");
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			});
		}

		public double getXTilt() {
			return xTilt;
		}

		public double getYTilt() {
			return yTilt;
		}

		public void start() {
			serverThread.start();
		}

		public void stop() {
			try {
				if (serverSocket != null) serverSocket.close();
				if (clientSocket != null) clientSocket.close();
				if (serverThread != null) serverThread.interrupt();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	// Constructors modified to include port for FPGA server
	public Player(String name, int x, int y, Color color, int port) {
		this.name = name;
		this.x = x;
		this.y = y;
		this.snapX = x/40;
		this.snapY = y/40;
		this.collidingUp = false;
		this.collidingDown = false;
		this.collidingLeft = false;
		this.collidingRight = false;
		this.deaths = 0;
		this.dead = false;
		this.opacity = 255;
		this.playerColor = color;
		this.port = port;

		// Initialize and start FPGA server for this player
		this.fpgaServer = new FPGAServer(port);
		this.fpgaServer.start();
	}

	// Name Getter and Setter
	public String getName() { return this.name;}
	public void setName(String name) { this.name = name;}

	public void draw(Graphics g) {
		g.setColor(new Color(0, 0, 0, (int) opacity));
		g.fillRect(x - 15, y - 15 + 22, 31, 31);
		// Use the player's color instead of always red
		g.setColor(new Color(playerColor.getRed(), playerColor.getGreen(),
				playerColor.getBlue(), (int) opacity));
		g.fillRect(x-12, y-12 + 22,
				25, 25);
	}

	public Color getPlayerColor() {
		return this.playerColor;
	}

	public void setPlayerColor(Color color) {
		this.playerColor = color;
	}



	Tile getRelativeTile(GameLevel level, int x1, int y1, int xOff, int yOff) {
		for (Tile t : level.getTileMap()) {
			if (x1/40 + xOff == t.getSnapX() && y1/40 + yOff == t.getSnapY()) {
				return t;
			}
		}
		return null;
	}
	

	
	
	Tile getTile(GameLevel level) {
		for (Tile t : level.getTileMap()) {
			if (this.x/40 == t.getSnapX() && this.y/40 == t.getSnapY()) {
				return t;
			}
		}
		return null;
	}

	boolean doesIntersect(Rectangle a, Rectangle b) {
		return (a.x + a.width < b.x || a.x > b.x + b.width
				|| a.y + a.height < b.y || a.y > b.y + b.height);
	}
	
	public Rectangle getBounds() {
		return new Rectangle(this.x - 15, this.y - 15, 31, 31);
	}
	
	void checkCollisionUp(GameLevel level) {
		if (getRelativeTile(level, this.x - 14, this.y + 24, 0, -1) != null &&
				getRelativeTile(level, this.x - 14, this.y + 24, 0, -1).getType() == 0 ||
				getRelativeTile(level, this.x + 15, this.y + 24, 0, -1) != null &&
				getRelativeTile(level, this.x + 15, this.y + 24, 0, -1).getType() == 0) {
			this.collidingUp = true;
			return;
		}
		this.collidingUp = false;
	}
	
	void checkCollisionDown(GameLevel level) {
		if (getRelativeTile(level, this.x - 14, this.y - 24, 0, 1) != null &&
				getRelativeTile(level, this.x - 14, this.y - 24, 0, 1).getType() == 0 ||
				getRelativeTile(level, this.x + 15, this.y - 24, 0, 1) != null &&
				getRelativeTile(level, this.x + 15, this.y - 24, 0, 1).getType() == 0) {
			this.collidingDown = true;
			return;
		}
		this.collidingDown = false;
	}
	
	void checkCollisionLeft(GameLevel level) {
		if (getRelativeTile(level, this.x + 24, this.y - 15, -1, 0) != null &&
				getRelativeTile(level, this.x + 24, this.y - 15, -1, 0).getType() == 0 ||
				getRelativeTile(level, this.x + 24, this.y + 14, -1, 0) != null &&
				getRelativeTile(level, this.x + 24, this.y + 14, -1, 0).getType() == 0) {
			this.collidingLeft = true;
			return;
		}
		this.collidingLeft = false;
	}
	
	void checkCollisionRight(GameLevel level) {
		if (getRelativeTile(level, this.x - 24, this.y - 15, 1, 0) != null &&
				getRelativeTile(level, this.x - 24, this.y - 15, 1, 0).getType() == 0 ||
				getRelativeTile(level, this.x - 24, this.y + 15, 1, 0) != null &&
				getRelativeTile(level, this.x - 24, this.y + 15, 1, 0).getType() == 0) {
			this.collidingRight = true;
			return;
		}
		this.collidingRight = false;
	}

	public void respawn(GameLevel level) {
		// Choose spawn point based on player color
		this.x = level.getSpawnPoint().x;
		this.y = level.getSpawnPoint().y;
		// Reset player 1's coins
		if (level.coins != null) {
			for (Coin coin : level.coins) coin.collected = false;
		}
	}

	private double normalizeTilt(double tilt, int threshold) {
		// Assuming tilt is in the range [-32768, 32767]
		double maxTilt = 32768.0;
		return (tilt / maxTilt) * threshold;
	}

	boolean collidesWith(Shape other) {
	    return this.getBounds().getBounds2D().intersects(other.getBounds2D());
	}

	private static final double TILT_THRESHOLD_X = 100;
	private static final double TILT_THRESHOLD_Y = 100;
	private static final int MOVEMENT_STEP = 1;

	public void update(GameLevel level) {
		if (activePowerUp != null && System.currentTimeMillis() > powerUpEndTime){
			activePowerUp = null;
			setPlayerColor(Color.RED);
			for (Dot dot : level.dots){
				dot.setSpeed(0.7);
			}
			this.already_minus = false;
			powerupactive = false;
		}
		this.snapX = this.x / 40;
		this.snapY = this.y / 40;

		// Determine which coin collection to use based on player color
		ArrayList<Coin> playerCoins;
		playerCoins = level.coins; // Player 1 coins

		// Check for coin collection
		if (playerCoins != null) {
			for (Coin coin : playerCoins) {
				if (this.collidesWith(coin.getBounds()) && !coin.collected) {
					coin.collected = true;

					// Coin sound
					TinySound.init();
					TinySound.loadSound(Player.class.getClassLoader()
							.getResource("resources/ding.wav")).play();

					//Randomly assign a power-up
					activateRandomPowerUp();
				}
			}
		}

		// Level completion logic
		if (!level.getTileMap().isEmpty()) {
			if (level.allCoinsCollected()) {
				for (Tile t : level.getTileMap()) {
					if (t.getType() == 3 && this.collidesWith(t.getBounds()) && !hasNotifiedLevelCompletion) {
						// Set flag to prevent multiple notifications
						if (Game.levelNum == 11){
							try (Socket socket = new Socket(Game.SERVER_ADDRESS, Game.SERVER_PORT);
									 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
									 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

									System.out.println("Connected to server.");

									out.println("SET_HIGHSCORE " + Game.username + ", " + deaths);
									String Score = in.readLine();
									System.out.println("Score: " + Score);

									// Explicitly close the socket and streams
									out.close();
									in.close();
									socket.close();
									System.out.println("Connection closed.");

							} catch (IOException e) {
									System.err.println("Error: " + e.getMessage());
							}
						}

						System.out.println("DEBUG: Transitioning from level " + Game.levelNum + " to " + (Game.levelNum+1));
						
						if (Game.isConnectedToServer() && Game.getNetworkManager() != null) {
							hasNotifiedLevelCompletion = true;
							handleMultiplayerLevelCompletion(level);
						} else {
							// Not connected to server, use original single player logic
							proceedToNextLevel(level);
						}
						// Exit the loop to prevent multiple triggers
						return;
					}
				}
			}
		}
		// Check collision in all directions
		checkCollisionUp(level);
		checkCollisionDown(level);
		checkCollisionLeft(level);
		checkCollisionRight(level);

		// Handle player death and respawn
		if (this.dead) {
			this.opacity -= 255 / 75;
			if (this.opacity < 0) this.opacity = 0;

			if (this.opacity == 0) {
				this.dead = false;
				this.opacity = 255;
				this.respawn(level);
			}
		} else {
			//Handles the TCP connection to the FPGA controller
			// Handle movement based on FPGA tilt data
			//Added power up logic
			int currentMovementStep = MOVEMENT_STEP;
			if(activePowerUp == PowerUp.SPEED_BOOST){
				currentMovementStep = MOVEMENT_STEP_BOOST;
			}

			// Get tilt values from FPGA server
			double xTilt = fpgaServer.getXTilt();
			double yTilt = fpgaServer.getYTilt();

			System.out.println("Received X: " + xTilt + ", Y: " + yTilt);

			if (xTilt < -TILT_THRESHOLD_X && !this.collidingRight) {
				this.x += currentMovementStep;  // Move right
			} else if (xTilt > TILT_THRESHOLD_X && !this.collidingLeft) {
				this.x -= currentMovementStep;  // Move left
			}

			if (yTilt > TILT_THRESHOLD_Y && !this.collidingDown) {
				this.y += currentMovementStep;  // Move down
			} else if (yTilt < -TILT_THRESHOLD_Y && !this.collidingUp) {
				this.y -= currentMovementStep;  // Move up
			}

			// Player-specific movement controls based on color
			//change this if statement color changes which controls the FPGA (BLUE - RED is controlled, RED - BLUE is controlled)
			//Place to add second port setup same way as 5000 but for different client to runt the other guy
			if (this.playerColor.equals(Color.RED) || this.playerColor.equals(Color.GREEN)) {
				// Player 1 uses arrow keys
				if (Input.up.isPressed && !this.collidingUp) this.y--;
				if (Input.down.isPressed && !this.collidingDown) this.y++;
				if (Input.left.isPressed && !this.collidingLeft) this.x--;
				if (Input.right.isPressed && !this.collidingRight) this.x++;
			} else {
				// Player 2 uses WASD
				if (Input.wKey.isPressed && !this.collidingUp) this.y--;
				if (Input.sKey.isPressed && !this.collidingDown) this.y++;
				if (Input.aKey.isPressed && !this.collidingLeft) this.x--;
				if (Input.dKey.isPressed && !this.collidingRight) this.x++;
			}
		}

		//Add slow dot logic
		if (activePowerUp == PowerUp.SLOW_DOTS){
			for (Dot dot : level.dots){
				dot.setSpeed(0.1);
			}
		}

		// Wrap-around logic for screen edges
		if (this.x > 800) this.x = 0;
		if (this.x < 0) this.x = 800;
		if (this.y > 600) this.y = 0;
		if (this.y < 0) this.y = 600;

		// Check for collision with dots (instant death)
		if (!this.dead && activePowerUp != PowerUp.IMMUNITY) {
			for (Dot dot : level.dots) {
				if (this.collidesWith(dot.getBounds())) {
					if (activePowerUp == PowerUp.MINUS_DEATHS && !this.already_minus){
						this.deaths = this.deaths - 1;
						this.already_minus = true;
					}
					else{
						this.deaths++;
					}
					this.dead = true;

					// Play death sound
					if (!Game.muted) {
						TinySound.init();
						TinySound.loadSound(ClassLoader.getSystemResource(
								"resources/smack.wav")).play();
					}
				}
			}
		}
	}

	private void handleMultiplayerLevelCompletion(GameLevel level) {
		// Notify server that this player completed the level
		Game.easyLog(Game.logger, Level.INFO, "Sending level completion to server for level " + Game.levelNum);
		Game.setWaitingForOtherPlayers(true);
		
		// Send level completion to server in a background thread
		new Thread() {
			public void run() {
				try {
					// This will block until all players complete or there's an error
					boolean allPlayersCompleted = Game.getNetworkManager().sendLevelCompletionToServer(Game.levelNum);
					
					if (allPlayersCompleted) {
						// All players completed the level, proceed to next level
						Game.setWaitingForOtherPlayers(false);
						proceedToNextLevelMultiplayer(level);
					} else {
						// There was an error or timeout
						Game.easyLog(Game.logger, Level.WARNING, "Did not receive confirmation from server that all players completed");
						// Fall back to single player behavior as a safety net
						Game.setWaitingForOtherPlayers(false);
						proceedToNextLevel(level);
					}
				} catch (Exception e) {
					Game.easyLog(Game.logger, Level.SEVERE, "Error handling level completion: " + Game.getStringFromStackTrace(e));
					// Fall back to single player behavior on error
					Game.setWaitingForOtherPlayers(false);
					proceedToNextLevel(level);
				}
			}
		}.start();
		
		// We've sent the notification and started waiting - the rest will happen when we get server confirmation
		hasNotifiedLevelCompletion = true;
	}

	private void proceedToNextLevelMultiplayer(GameLevel level) {
		SwingUtilities.invokeLater(() -> {
			// Add protection against reloading
			final int transitioningToLevel = Game.levelNum + 1;
			Game.levelNum++;
			
			// Handle game finish and update highscore if needed
			if (Game.levelNum == 11) {
				updateHighScore();
			}
			
			level.init(Game.getPlayers()[0], Game.levelNum);
			Game.gameState = Game.LEVEL_TITLE;
			Game.easyLog(Game.logger, Level.INFO, "Game state set to LEVEL_TITLE after server confirmation");
			hasNotifiedLevelCompletion = false;
			// Start next level after delay
			startLevelAfterDelay(transitioningToLevel);
		});
	}

	private void updateHighScore() {
		try (Socket socket = new Socket(Game.SERVER_ADDRESS, Game.SERVER_PORT);
			 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

			System.out.println("Connected to server.");
			out.println("SET_HIGHSCORE " + Game.username + ", " + deaths);
			String score = in.readLine();
			System.out.println("Score: " + score);
			System.out.println("Connection closed.");
		} catch (IOException e) {
			System.err.println("Error: " + e.getMessage());
		}
	}

	private void startLevelAfterDelay(final int targetLevel) {
		new Thread() {
			public void run() {
				try {
					Thread.sleep(1750);
				} catch (InterruptedException e) {
					Game.easyLog(Game.logger, Level.SEVERE, Game.getStringFromStackTrace(e));
				}
				// Only change game state if we're still on the level we transitioned to
				if (Game.levelNum == targetLevel) {
					Game.gameState = Game.LEVEL;
					Game.easyLog(Game.logger, Level.INFO, "Game state set to LEVEL after server confirmation");
				}
			}
		}.start();
	}

	// Helper method to handle transition to next level
	private void proceedToNextLevel(GameLevel level) {
		// Add protection against reloading
		final int transitioningToLevel = Game.levelNum + 1;
		Game.levelNum++;
		level.init(Game.getPlayers()[0], Game.levelNum);
		Game.gameState = Game.LEVEL_TITLE;
		Game.easyLog(Game.logger, Level.INFO, "Game state set to LEVEL_TITLE");

		// Wait 1.75 seconds then start the next level
		new Thread() {
			public void run() {
				try {
					Thread.sleep(1750);
				} catch (InterruptedException e) {
					Game.easyLog(Game.logger, Level.SEVERE, Game.getStringFromStackTrace(e));
				}
				// Only change game state if we're still on the level we transitioned to
				if (Game.levelNum == transitioningToLevel) {
					Game.gameState = Game.LEVEL;
					Game.easyLog(Game.logger, Level.INFO, "Game state set to LEVEL");
				}
			}
		}.start();
	}

	public int getX() {
		return this.x;
	}
	
	
	
	public int getY() {
		return this.y;
	}
	
	
	
	public int getSnapX() {
		return this.snapX;
	}
	
	
	
	public int getSnapY() {
		return this.snapY;
	}
	
	
	
	public int getWidth() {
		return (int) this.getBounds().getWidth();
	}
	
	
	
	public int getHeight() {
		return (int) this.getBounds().getHeight();
	}
	
	
	
	public boolean isCollidingLeft() {
		return this.collidingLeft;
	}
	
	
	
	public boolean isCollidingRight() {
		return this.collidingRight;
	}
	
	
	
	public boolean isCollidingUp() {
		return this.collidingUp;
	}
	
	
	
	public boolean isCollidingDown() {
		return this.collidingDown;
	}
	
	
	
	public int getDeaths() {
		return this.deaths;
	}
	
	
	
	public boolean isDead() {
		return this.dead;
	}
	
	
	
	public void setDead(boolean dead) {
		this.dead = dead;
	}
	
	
	
	public double getOpacity() {
		return this.opacity;
	}
	
	
	
	public void reset() {
		this.x = 400;
		this.y = 300;
		this.snapX = x/40;
		this.snapY = y/40;
		this.collidingUp = false;
		this.collidingDown = false;
		this.collidingLeft = false;
		this.collidingRight = false;
		this.deaths = 0;
		this.already_minus = false;
		this.dead = false;
		this.opacity = 255;
	}

	public void cleanup() {
		if (fpgaServer != null) {
			fpgaServer.stop();
		}
	}



	@Override
	public String toString() {
		return "Player [x=" + x + ", y=" + y + ", snapX=" + snapX + ", snapY="
				+ snapY + ", collidingUp=" + collidingUp + ", collidingDown="
				+ collidingDown + ", collidingLeft=" + collidingLeft
				+ ", collidingRight=" + collidingRight + ", deaths=" + deaths
				+ ", dead=" + dead + "]";
	}


}
