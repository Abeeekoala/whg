package whg;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.lang.StringBuilder;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.Timer;

import java.io.*;
import java.net.*;

import kuusisto.tinysound.Music;
import kuusisto.tinysound.Sound;
import kuusisto.tinysound.TinySound;

public class Game extends JPanel implements ActionListener {

	public static final String SERVER_ADDRESS = "13.60.53.107";
	public static final int SERVER_PORT = 12000;

	/** An instance of the game. */
	private static Game game;

	/** The timer used for the game's clock. */
	private Timer t = new Timer(5, this);

	/** Used for logging information during the game. */
	public final static Logger logger = Logger.getLogger(Game.class.getName());

	static String logFilePath = System.getProperty("user.home")
			+ "/worldshardestgame/logs/" +  new SimpleDateFormat("YY-MM-dd").format(new Date()) + ".log";

	private static final long serialVersionUID = 1L;

	/** The frame that the panel goes in. */
	static JFrame frame = new JFrame();

	/** The enum instance used for switching the state of the game. */
	static final int INTRO = 0, LOGIN = 1, MAIN_MENU = 2, LEVEL_TITLE = 3, LEVEL = 4;

	/** The integer used for the game state. */
	static int gameState = INTRO;

	/** Used for when the instructions should be shown. */
	private boolean showIntro = false;


	/** This is the level that the player is on. */
	static int levelNum = 0;

	public static StringBuilder inputStringBuilder = new StringBuilder();

	public static String username = "Player 1";
	public static String highscore = "0";
	/** A player class, used to get information about the player. */
	//private Player player = new Player();

	//private Player player2 = new Player();

	// Player 1 on port 5000
	Player player = new Player(username, 400, 300, Color.RED, 5000);

	// Player 2 on port 5001
	// Player player2 = new Player(400, 300, Color.BLUE, 5001);

	/** The data of the current level. This should be given data in initLevel(). */
	static GameLevel level = new GameLevel();

	/** Controls whether the game has sound or not. */
	static boolean muted = false;

	/** Images for indicating volume. */
	private final Image VOLUME_BLACK = new ImageIcon(ClassLoader.getSystemResource(
			"resources/volume_black.png")).getImage();
	private final Image VOLUME_BLACK_MUTE = new ImageIcon(ClassLoader.getSystemResource(
			"resources/volume_black_mute.png")).getImage();
	private final Image VOLUME_WHITE = new ImageIcon(ClassLoader.getSystemResource(
			"resources/volume_white.png")).getImage();
	private final Image VOLUME_WHITE_MUTE = new ImageIcon(ClassLoader.getSystemResource(
			"resources/volume_white_mute.png")).getImage();

	/** Background music. */
	static Thread bgMusic = new Thread() {
		public void run() {
			TinySound.init();
			Music bgmusic = TinySound.loadMusic(ClassLoader.getSystemResource(
					"resources/music.ogg"));
			bgmusic.play(true);
		}
	};

	Thread endIntro = new Thread() {
		public void run() {
			try {
				Thread.sleep(1500);
			} catch (InterruptedException e) {
				TextFileWriter.appendToFile(logFilePath, e.getMessage());
			}
			gameState = LOGIN;
			easyLog(logger, Level.INFO, "Game state set to LOGIN");
		}
	};

	static boolean doLogging = false;

	private static int totalLevels = 0;


	//Intro objects

	/** True if the intro text should move down. */
	private boolean fadeOutIntro = false;

	/** The opacity of the intro text. */
	private int introTextOpacity = 0;

	/** A whoosh sound. */
	Sound drone = TinySound.loadSound(ClassLoader.getSystemResource("resources/drone.ogg"));

	/** A bell sound. */
	Sound bell = TinySound.loadSound(ClassLoader.getSystemResource("resources/bell.wav"));

	/** Network manager for multiplayer functionality */
	private NetworkManager networkManager = null;
	
	/** Whether the game is connected to a server */
	private boolean connectedToServer = false;
	
	/** Default server URL */
	private String serverUrl = "http://18.171.220.207:5000";
	
	// Server connection button dimensions (for main menu)
	private final int SERVER_BUTTON_X = 600;
	private final int SERVER_BUTTON_Y = 500;
	private final int SERVER_BUTTON_WIDTH = 180;
	private final int SERVER_BUTTON_HEIGHT = 40;

	/** Combat ID for multiplayer matching */
	private String combatId = "";
	private boolean showCombatIdInput = false;
	private String tempCombatId = "";

	// Add these UI elements for combat ID entry
	private final int COMBAT_ID_X = 300;
	private final int COMBAT_ID_Y = 400;
	private final int COMBAT_ID_WIDTH = 200;
	private final int COMBAT_ID_HEIGHT = 40;
	private final int COMBAT_ID_BUTTON_X = 350;
	private final int COMBAT_ID_BUTTON_Y = 500;
	private final int COMBAT_ID_BUTTON_WIDTH = 100;
	private final int COMBAT_ID_BUTTON_HEIGHT = 40;

	// Apply an offset to the UI coordinates (adjust X_OFFSET and Y_OFFSET based on your findings)
	private static final int X_OFFSET = 0;
	private static final int Y_OFFSET = -30; 

	public void paintComponent(final Graphics g) {
		super.paintComponent(g);

		update(g);
		render(g);

		// If connected to server, update remote player positions for smooth rendering
		if (connectedToServer && networkManager != null) {
			networkManager.updateRemotePlayers();
		}

		// Start the timer
		t.start();

		Toolkit.getDefaultToolkit().sync();
	}

	public static Player[] getPlayers() {
		return new Player[] { game.player };
	}



	/** Update the game.
	 *
	 * @param g
	 * */
	public void update(Graphics g) {

		if (gameState == INTRO) {

			if (introTextOpacity == 0 && !fadeOutIntro) {
				drone.play();
			}

			if (introTextOpacity < 255 && !fadeOutIntro) {
				introTextOpacity += 255/10;
				if (introTextOpacity > 255) introTextOpacity = 255;
			}

			if (introTextOpacity == 225) {
				new Thread() {
					public void run() {
						try {
							Thread.sleep(3500);
						} catch (InterruptedException e) {
							TextFileWriter.appendToFile(logFilePath, e.getMessage());
						}
						fadeOutIntro = true;
						bgMusic.start();
					}
				}.start();
			}

			if (fadeOutIntro) {
				if (introTextOpacity > 0) {
					introTextOpacity -= 255/20;
					if (introTextOpacity < 0) introTextOpacity = 0;
				}
			}

			if (fadeOutIntro && introTextOpacity == 0 && !endIntro.isAlive()) {
				endIntro.start();
			}

		} else if (gameState == LOGIN){
			// Handle username input
			Input.typing = true;
			// Handle backspace
			if (Input.backspace.isPressed && !inputStringBuilder.isEmpty()) {
				inputStringBuilder.setLength(inputStringBuilder.length() - 1);
			}

			// Handle submit
			if (Input.enter.isPressed && !inputStringBuilder.isEmpty()) {
				username = inputStringBuilder.toString().trim();
				player.setName(username);
				try (Socket socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
					 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
					 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

					System.out.println("Connected to server.");

					out.println("GET_HIGHSCORE " + username);
					highscore = in.readLine();
					System.out.println("HighScore: " + highscore);

					// Explicitly close the socket and streams
					out.close();
					in.close();
					socket.close();
					System.out.println("Connection closed.");

				} catch (IOException e) {
					System.err.println("Error: " + e.getMessage());
				}
				gameState = MAIN_MENU;
			}

		} else if (gameState == MAIN_MENU) {

			if (showIntro) {

				if (Input.enter.isPressed) {
					showIntro = false;
					gameState = LEVEL_TITLE;
					// Update the combat ID on the server
					if (connectedToServer && networkManager != null && !combatId.isEmpty()) {
						networkManager.sendCombatIdToServer(combatId);
						easyLog(logger, Level.INFO, "Set combat ID: " + combatId);
					}
					
					easyLog(logger, Level.INFO, "Game state set to LEVEL_TITLE");

					player.reset();

					levelNum = 1;
					level.init(player, levelNum);

					//Wait 1.75 seconds then start the level.
					new Thread() {
						public void run() {
							try { Thread.sleep(1750); } catch (InterruptedException e) { TextFileWriter.appendToFile(logFilePath, e.getMessage()); }
							gameState = LEVEL;
							easyLog(logger, Level.INFO, "Game state set to LEVEL");
						}
					}.start();
				}
			} else {

				//Click to start the first level
				if (Input.mousePressed && Input.mouseCoords.x > 304 && Input.mouseCoords.y < 323
						&& Input.mouseCoords.x < 515 && Input.mouseCoords.y > 192) {
					showIntro = true;
					bell.play();
				}
			}

			// Add server connect/disconnect functionality in main menu
			if (Input.mousePressed && 
				Input.mouseCoords.x + X_OFFSET > SERVER_BUTTON_X && 
				Input.mouseCoords.x + X_OFFSET < SERVER_BUTTON_X + SERVER_BUTTON_WIDTH &&
				Input.mouseCoords.y + Y_OFFSET > SERVER_BUTTON_Y && 
				Input.mouseCoords.y + Y_OFFSET < SERVER_BUTTON_Y + SERVER_BUTTON_HEIGHT) {
				
				// Toggle connection state
				if (!connectedToServer) {
					boolean success = connectToServer();
					// Only update the UI state if connection was successful
					connectedToServer = success;
				} else {
					disconnectFromServer();
					connectedToServer = false;
				}
				
				// Add a small delay to prevent double-clicks
				try {
					Thread.sleep(200);
				} catch (InterruptedException e) {
					// Ignore
				}
				
				// Reset mouse pressed flag to prevent other elements from triggering
				Input.mousePressed = false;
			}

			// Add combat ID input button in main menu
			if (Input.mousePressed && 
				Input.mouseCoords.x + X_OFFSET > COMBAT_ID_X && 
				Input.mouseCoords.x + X_OFFSET < COMBAT_ID_X + COMBAT_ID_WIDTH &&
				Input.mouseCoords.y + Y_OFFSET > COMBAT_ID_Y && 
				Input.mouseCoords.y + Y_OFFSET < COMBAT_ID_Y + COMBAT_ID_HEIGHT) {
				
				showCombatIdInput = true;
				tempCombatId = combatId;
			}
			
			// Handle combat ID input dialog
			if (showCombatIdInput) {
				// Check for Enter key to confirm input
				if (Input.enter.isPressed) {
					showCombatIdInput = false;
					if (!tempCombatId.isEmpty()) {
						combatId = tempCombatId;
					}
				}
				
				// Check for Escape key to cancel
				if (Input.escape.isPressed) {
					showCombatIdInput = false;
				}
				
				// Check for backspace to delete characters
				if (Input.backspace.isPressed) {
					if (tempCombatId.length() > 0) {
						tempCombatId = tempCombatId.substring(0, tempCombatId.length() - 1);
					}
					// Add a small delay to prevent multiple deletes from one press
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						// Ignore
					}
				}
				
				// Check for digit key presses to add to combat ID
				for (int i = 0; i <= 9; i++) {
					if (Input.isKeyPressed('0' + i)) {
						if (tempCombatId.length() < 5) { // Limit to 5 digits
							tempCombatId += i;
						}
						// Add a small delay to prevent multiple inputs from one press
						try {
							Thread.sleep(100);
						} catch (InterruptedException e) {
							// Ignore
						}
						break;
					}
				}
			}
			
			// Generate random combat ID button
			if (Input.mousePressed && 
				Input.mouseCoords.x + X_OFFSET > COMBAT_ID_BUTTON_X && 
				Input.mouseCoords.x + X_OFFSET < COMBAT_ID_BUTTON_X + COMBAT_ID_BUTTON_WIDTH &&
				Input.mouseCoords.y + Y_OFFSET > COMBAT_ID_BUTTON_Y && 
				Input.mouseCoords.y + Y_OFFSET < COMBAT_ID_BUTTON_Y + COMBAT_ID_BUTTON_HEIGHT) {
				
				// Generate a random 5-digit combat ID
				combatId = String.format("%05d", (int)(Math.random() * 100000));
				
				// Update the combat ID on the server
				if (connectedToServer && networkManager != null) {
					// networkManager.sendCombatIdToServer(combatId);
					easyLog(logger, Level.INFO, "Generated random combat ID: " + combatId);
				}
			}

		} else if (gameState == LEVEL) {

			if (Input.mouseOnWindow && Input.mouseCoords.x <= 65 && Input.mouseCoords.y <= 22
					&& Input.mousePressed) {
				gameState = MAIN_MENU;
				easyLog(logger, Level.INFO, "Game state set to MAIN_MENU");
			}

			// If we're in a level and connected to the server, send player position
			if (connectedToServer && networkManager != null) {
				networkManager.sendPlayerPositionAsync(player.getX(), player.getY(), player.getPlayerColor());
			}
		}
	}





	/** Draw the game's graphics.
	 *
	 * @param g
	 */
	private void render(Graphics g) {
		Graphics2D g2 = (Graphics2D) g;

		if (gameState == INTRO) {

			//Background
			g2.setPaint(new GradientPaint(0, 0, new Color(213, 213, 255), 0, 600, Color.WHITE));
			g2.fillRect(0, 0, 800, 600);

			g2.setFont(new Font("Tahoma", Font.BOLD, 50));
			g2.setColor(new Color(0, 0, 0, introTextOpacity));
			drawCenteredString("Dont Tilt: Made by Team 6", 400, 250, g2);

		}else if (gameState == LOGIN) {
			// Background
			g2.setColor(Color.LIGHT_GRAY);
			g2.fillRect(0, 0, 800, 600);

			// Title
			g2.setFont(new Font("Tahoma", Font.BOLD, 30));
			g2.setColor(Color.BLACK);
			drawCenteredString("Login", 400, 100, g2);

			// Username Label
			g2.setFont(new Font("Tahoma", Font.PLAIN, 20));
			drawCenteredString("Enter Username:", 400, 200, g2);

			// Username Input Box
			g2.setColor(Color.WHITE);
			g2.fillRect(300, 230, 200, 40);
			g2.setColor(Color.BLACK);
			g2.drawRect(300, 230, 200, 40);

			// Draw the username text inside the input box
			g2.setFont(new Font("Tahoma", Font.PLAIN, 18));
			g2.drawString(String.valueOf(inputStringBuilder), 310, 255);

			// Submit Button
			g2.setColor(Color.BLUE);
			g2.fillRect(350, 300, 100, 40);
			g2.setColor(Color.WHITE);
			g2.setFont(new Font("Tahoma", Font.BOLD, 20));
			drawCenteredString("Submit", 400, 328, g2);

		} else if (gameState == MAIN_MENU) {

			if (showIntro) {
				//Instructions
				g2.setFont(new Font("Tahoma", Font.BOLD, 20));
				g2.setColor(Color.BLACK);
				drawString("You are the red square. Avoid the blue circles and collect the\n" +
						"yellow circles. Once you have collected all of the yellow\n" +
						"circles, move to the green beacon to complete the level.\n" +
						"Some levels consist of more than one beacon; the\n" +
						"intermediary beacons act as checkpoints. You must complete\n" +
						"all 30 levels in order to submit your score. Your score is a\n" +
						"reflection of how many times you have died; the less, the better.", 30, 40, g2);

				g2.setColor(Color.BLUE);
				drawCenteredString("Press enter to continue", 400, 350, g2);
			} else {
				//Background
				g2.setPaint(new GradientPaint(0, 0, new Color(213, 213, 255), 0, 600, Color.WHITE));
				g2.fillRect(0, 0, 800, 600);

				//Draw and outline the title
				g2.setPaint(Color.BLACK);
				g2.setFont(new Font("SansSerif", Font.BOLD, 32));
				g2.drawString("The world's...", 40, 60);
				g2.setPaint(new Color(66, 117, 192));
				g2.setFont(new Font("SansSerif", Font.BOLD, 80));
				g2.drawString("HARDEST GAME", 40, 145);
				g2.setPaint(Color.BLACK);
				drawTextOutline("HARDEST GAME", 40, 145, 5, g2);

				g2.setFont(new Font("SansSerif", Font.BOLD, 60));

				//Gradient of "play game" text depending on the mouse location
				if (Input.mouseCoords.x > 284 && Input.mouseCoords.y < 343
						&& Input.mouseCoords.x < 515 && Input.mouseCoords.y > 192) {
					g2.setPaint(new GradientPaint(0, 175, new Color(220, 220, 220), 0, 255, new Color(190, 60, 60)));
				} else {
					g2.setPaint(new GradientPaint(0, 175, Color.WHITE, 0, 255, Color.RED));
				}

				//Draw and outline the "play game" text
				drawCenteredString("PLAY", 400, 255, g2);
				drawCenteredString("GAME", 400, 320, g2);
				g2.setColor(Color.BLACK);
				drawTextOutline("PLAY", 315, 255, 3, g2);
				drawTextOutline("GAME", 302, 320, 3, g2);
			}

			// Draw server connection button
			if (!connectedToServer) {
				// Green connect button
				g2.setColor(new Color(0, 180, 0));
				g2.fillRect(SERVER_BUTTON_X, SERVER_BUTTON_Y, SERVER_BUTTON_WIDTH, SERVER_BUTTON_HEIGHT);
				g2.setColor(Color.WHITE);
				g2.setFont(new Font("Tahoma", Font.BOLD, 16));
				g2.drawString("Connect to Server", SERVER_BUTTON_X + 20, SERVER_BUTTON_Y + 25);
			} else {
				// Red disconnect button
				g2.setColor(new Color(180, 0, 0));
				g2.fillRect(SERVER_BUTTON_X, SERVER_BUTTON_Y, SERVER_BUTTON_WIDTH, SERVER_BUTTON_HEIGHT);
				g2.setColor(Color.WHITE);
				g2.setFont(new Font("Tahoma", Font.BOLD, 16));
				g2.drawString("Disconnect", SERVER_BUTTON_X + 45, SERVER_BUTTON_Y + 25);
			}

			// Server connection status
			g2.setFont(new Font("Tahoma", Font.BOLD, 12));
			if (connectedToServer) {
				g2.setColor(new Color(0, 150, 0));
				g2.drawString("Connected", SERVER_BUTTON_X + 50, SERVER_BUTTON_Y + 60);
			} else {
				g2.setColor(new Color(150, 0, 0));
				g2.drawString("Not Connected", SERVER_BUTTON_X + 45, SERVER_BUTTON_Y + 60);
			}

			// Draw combat ID input box
			g2.setColor(Color.WHITE);
			g2.fillRect(COMBAT_ID_X, COMBAT_ID_Y, COMBAT_ID_WIDTH, COMBAT_ID_HEIGHT);
			g2.setColor(Color.BLACK);
			g2.drawRect(COMBAT_ID_X, COMBAT_ID_Y, COMBAT_ID_WIDTH, COMBAT_ID_HEIGHT);
			g2.drawString("Combat ID: " + (combatId.isEmpty() ? "<None>" : combatId), 
						   COMBAT_ID_X + 10, COMBAT_ID_Y + 25);
			
			// Draw random ID generation button
			g2.setColor(new Color(100, 100, 255));
			g2.fillRect(COMBAT_ID_BUTTON_X, COMBAT_ID_BUTTON_Y, COMBAT_ID_BUTTON_WIDTH, COMBAT_ID_BUTTON_HEIGHT);
			g2.setColor(Color.WHITE);
			g2.drawString("Random ID", COMBAT_ID_BUTTON_X + 10, COMBAT_ID_BUTTON_Y + 25);
			
			// Draw combat ID input dialog if active
			if (showCombatIdInput) {
				// Draw modal dialog background
				g2.setColor(new Color(0, 0, 0, 150));
				g2.fillRect(0, 0, 800, 600);
				
				// Draw dialog box
				g2.setColor(Color.WHITE);
				g2.fillRect(200, 200, 400, 200);
				g2.setColor(Color.BLACK);
				g2.drawRect(200, 200, 400, 200);
				
				g2.setFont(new Font("Tahoma", Font.BOLD, 20));
				g2.drawString("Enter Combat ID (5 digits)", 250, 230);
				
				// Draw input field
				g2.setColor(Color.WHITE);
				g2.fillRect(250, 250, 300, 40);
				g2.setColor(Color.BLACK);
				g2.drawRect(250, 250, 300, 40);
				g2.setFont(new Font("Monospace", Font.BOLD, 24));
				g2.drawString(tempCombatId + (System.currentTimeMillis() % 1000 < 500 ? "|" : ""), 
							  270, 280);
				
				// Draw instructions
				g2.setFont(new Font("Tahoma", Font.PLAIN, 14));
				g2.drawString("Enter up to 5 digits (0-9)", 250, 320);
				g2.drawString("Press Enter to confirm, Escape to cancel", 250, 340);
			}

		} else if (gameState == LEVEL) {

			if (levelNum != 0) {
				level.drawTiles(g);

				level.drawCoins(g);
				// level.drawCoinsPlayer2(g); // Draw coins for player 2

				level.drawDots(g);
				level.updateDots();

				player.draw(g);
				// player2.draw(g); // Draw player 2

				player.update(level);
				// player2.update(level, player);

				g.setColor(Color.WHITE);
				g.setFont(new Font("Tahoma", Font.BOLD, 18));

				// Show death counts for both players
				g.setColor(player.getPlayerColor());
				drawString(username + " Deaths: " + player.getDeaths(), 5, 17, g);
				if ( Integer.parseInt(highscore) == 1000){
					drawString( "First Attempt", 5, 37, g);
				}
				else {
					drawString("Highscore: " + highscore, 5, 37, g);
				}

				// g.setColor(player2.getPlayerColor());
				// drawRightJustifiedString("P2 Deaths: " + player2.getDeaths(), 750, 17, g);

				g.setColor(Color.WHITE);
				drawCenteredString(levelNum + "/" + totalLevels, 400, 17, g);

				if (Input.mouseOnWindow && Input.mouseCoords.x <= 65 && Input.mouseCoords.y <= 22) {
					g.setColor(Color.LIGHT_GRAY);
				}
				g.drawString("MENU", 0, 17);

				if (muted) {
					g.drawImage(VOLUME_WHITE_MUTE, 760, -12, null);
				} else {
					g.drawImage(VOLUME_WHITE, 760, -12, null);
				}
			}

			// Draw remote players in the level if connected
			if (connectedToServer && networkManager != null) {
				drawRemotePlayers(g);
			}
		} else if (gameState == LEVEL_TITLE) {
			//Background
			g2.setPaint(new GradientPaint(0, 0, new Color(213, 213, 255), 0, 600, Color.WHITE));
			g2.fillRect(0, 0, 800, 600);

			//Draw the title text
			g2.setFont(new Font("Tahoma", Font.BOLD, 48));
			g.setColor(Color.BLACK);
			int textY = 200;
			for (String s : level.getTitle().split("\n")) {
				drawCenteredString(s, 400, textY += g.getFontMetrics().getHeight(), g);
			}
		}

		if (gameState != LEVEL) {
			if (muted) {
				g.drawImage(VOLUME_BLACK_MUTE, 760, -12, null);
			} else {
				g.drawImage(VOLUME_BLACK, 760, -12, null);
			}
		}

		g.dispose();
	}

	/**
	 * Draws all visible remote players
	 * @param g Graphics to draw with
	 */
	private void drawRemotePlayers(Graphics g) {
		if (networkManager == null) return;
		
		networkManager.withVisiblePlayers(() -> {
			RemotePlayer[] players = networkManager.getVisiblePlayers();
			int playerCount = networkManager.getPlayerCount();
			// Game.easyLog(Game.logger, Level.INFO, "Drawing " + playerCount + " remote players");
			for (int i = 0; i < playerCount; i++) {
				if (players[i] != null) {
					players[i].interpolatePosition(System.currentTimeMillis());
					players[i].draw(g);
				}
			}
		});
	}
	
	/**
	 * Connect to the multiplayer server
	 * @return true if connection was successful, false otherwise
	 */
	private boolean connectToServer() {
		try {
			easyLog(logger, Level.INFO, "Attempting to connect to server: " + serverUrl);
			
			// Initialize the NetworkManager only when attempting connection
			if (networkManager == null) {
				networkManager = NetworkManager.getInstance();
				networkManager.init(serverUrl);
			}
			
			// Connect synchronously to get immediate feedback
			boolean result = networkManager.connect(serverUrl);
			
			if (result) {
				easyLog(logger, Level.INFO, "Successfully connected to server: " + serverUrl);
			} else {
				easyLog(logger, Level.WARNING, "Failed to connect to server");
			}
			
			return result;
			
		} catch (Exception e) {
			easyLog(logger, Level.SEVERE, "Error in connectToServer: " + e.getMessage());
			return false;
		}
	}
	
	/**
	 * Disconnect from the multiplayer server
	 */
	private void disconnectFromServer() {
		try {
			easyLog(logger, Level.INFO, "Disconnecting from server");
			
			if (networkManager != null) {
				networkManager.disconnect();
			}
		} catch (Exception e) {
			easyLog(logger, Level.SEVERE, "Error in disconnectFromServer: " + e.getMessage());
		}
	}
	
	/**
	 * Force cleanup of network resources when the game exits
	 */
	private static void setupShutdownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			// Ensure we disconnect from the server on shutdown
			if (game != null && game.networkManager != null) {
				game.networkManager.disconnect();
				easyLog(logger, Level.INFO, "Network resources cleaned up on shutdown");
			}
		}));
	}

	public void actionPerformed(ActionEvent arg0) {
		repaint();
	}





	/** Draw a string centered on its x axis.
	 *
	 * @param text
	 * 		the text to be drawn
	 * @param x
	 * 		the x coordinate of the text
	 * @param y
	 * 		the y coordinate of the text
	 * @param g
	 * 		the graphics the text will be drawn with
	 */
	private void drawCenteredString(String s, int w, int h, Graphics g) {
		FontMetrics fm = g.getFontMetrics();
		int x = (w*2 - fm.stringWidth(s)) / 2;
		g.drawString(s, x, h);
	}





	/** Draw a string centered on its x axis.
	 *
	 * @param text
	 * 		the text to be drawn
	 * @param x
	 * 		the x coordinate of the text
	 * @param y
	 * 		the y coordinate of the text
	 * @param g2
	 * 		the 2D graphics the text will be drawn with
	 */
	private void drawCenteredString(String s, int w, int h, Graphics2D g2) {
		FontMetrics fm = g2.getFontMetrics();
		int x = (w*2 - fm.stringWidth(s)) / 2;
		g2.drawString(s, x, h);
	}





	/** Draw a right-justified string.
	 *
	 * @param text
	 * 		the text to be drawn
	 * @param x
	 * 		the x coordinate of the text
	 * @param y
	 * 		the y coordinate of the text
	 * @param g2
	 * 		the 2D graphics the text will be drawn with
	 */
	private void drawRightJustifiedString(String s, int w, int h, Graphics g) {
		FontMetrics fm = g.getFontMetrics();
		int x = (w - fm.stringWidth(s));
		g.drawString(s, x, h);
	}





	/** Draw the outline of a string of text.
	 *
	 * @param text
	 * 		the text to be drawn
	 * @param x
	 * 		the x coordinate of the text
	 * @param y
	 * 		the y coordinate of the text
	 * @param thickness
	 * 		the thickness of the outline
	 * @param g2
	 * 		the 2D graphics the text will be drawn with
	 */
	private void drawTextOutline(String text, int x, int y, int thickness, Graphics2D g2) {
		TextLayout tl = new TextLayout(text, g2.getFont(), new FontRenderContext(null,false,false));
		AffineTransform textAt = new AffineTransform();
		textAt.translate(x, y);
		g2.setStroke(new BasicStroke(thickness));
		g2.draw(tl.getOutline(textAt));
		g2.setStroke(new BasicStroke());
	}





	/** Draw a string, with the use of \n implemented.
	 *
	 * @param text
	 * 		the text to be drawn
	 * @param x
	 * 		the x coordinate of the text
	 * @param y
	 * 		the y coordinate of the text
	 * @param g
	 * 		the graphics the text will be drawn with
	 */
	private void drawString(String text, int x, int y, Graphics g) {
		for (String line : text.split("\n"))
			g.drawString(line, x, y += g.getFontMetrics().getHeight());
	}





	/**
	 * Convert an exception to a String with full stack trace
	 *
	 * @param ex
	 *            the exception
	 * @return A string with the full stacktrace error text
	 */
	public static String getStringFromStackTrace(Throwable ex) {
		if (ex == null) {
			return "";
		}
		StringWriter str = new StringWriter();
		PrintWriter writer = new PrintWriter(str);
		try {
			ex.printStackTrace(writer);
			return str.getBuffer().toString();
		} finally {
			try {
				str.close();
				writer.close();
			} catch (IOException e) {
				// ignore
			}
		}
	}





	/**
	 * Easily log a string of text, and write it to the log file
	 *
	 * @param logger
	 * 		The logger for the string to be logged with
	 * @param level
	 * 		The level of the logger
	 * @param s
	 * 		The string of text to be logged
	 */
	static void easyLog(Logger logger, Level level, String s) {
		if (doLogging) {
			logger.setLevel(level);

			if (level == Level.CONFIG) logger.config(s);
			else if (level == Level.FINE) logger.fine(s);
			else if (level == Level.FINER) logger.finer(s);
			else if (level == Level.FINEST) logger.finest(s);
			else if (level == Level.INFO) logger.info(s);
			else if (level == Level.SEVERE) logger.severe(s);
			else if (level == Level.WARNING) logger.warning(s);

			else {
				logger.setLevel(Level.WARNING);
				logger.warning("Logging error");
			}

			TextFileWriter.appendToFile(logFilePath, new SimpleDateFormat(
					"MMM dd, YYYY h:mm:ss a").format(new Date())
					+ " net.thedanpage.worldshardestgame easyLog\n" + level + ": " + s);
		}
	}





	public static void main(String[] args) {

		int option = JOptionPane.showConfirmDialog(
				new Dialog(frame, true),
				"Would you like to enable logging to " + System.getProperty("user.home") + "/worldshardestgame/logs?",
				"Setup",
				JOptionPane.YES_NO_OPTION);
		if (option == JOptionPane.YES_OPTION) Game.doLogging = true;
		else Game.doLogging = false;

		if (Game.doLogging) {

			//Create directory for logs if it does not exist
			if (!new File(System.getProperty("user.home") + "/worldshardestgame/logs").isDirectory()) {
				new File(System.getProperty("user.home") + "/worldshardestgame/logs").mkdirs();
			}

			if (new File(Game.logFilePath + ".zip").exists()) {
				LogZipper.unzip(
						System.getProperty("user.home") + "/worldshardestgame/logs", Game.logFilePath + ".zip");
				new File(Game.logFilePath + ".zip").delete();
			}

			try {
				if (new File(Game.logFilePath).exists() && new BufferedReader(new FileReader(Game.logFilePath)).readLine() != null) {
					TextFileWriter.appendToFile(Game.logFilePath, "\n");
				}
			} catch (IOException e) {
				Game.easyLog(Game.logger, Level.WARNING, Game.getStringFromStackTrace(e));
			}
		}

		try {
			while (new File(ClassLoader
					.getSystemResource("resources/maps/level_" + (totalLevels+1) + ".txt").toURI())
					.exists()) {
				totalLevels++;
			}
		} catch (Exception e) {
			System.out.println("Total levels: " + totalLevels);
		}

		Game.easyLog(Game.logger, Level.INFO, "Starting The World's Hardest Game");

		TinySound.init();
		Game.easyLog(Game.logger, Level.INFO, "TinySound initialized");

		if (Game.muted) TinySound.setGlobalVolume(0);

		Input.init();
		Game.easyLog(Game.logger, Level.INFO, "Input initialized");

		frame.setTitle("World's Hardest Game");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setSize(new Dimension(800, 622));
		frame.setResizable(false);
		frame.setLocationRelativeTo(null);

		game = new Game();
		frame.add(game);

		frame.setIconImage(new ImageIcon(ClassLoader.getSystemResource("resources/favicon.png")).getImage());
		frame.setVisible(true);
		
		// Register shutdown hook for clean network disconnection
		setupShutdownHook();
	}

}
