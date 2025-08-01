package whg;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.logging.Level;

public class GameLevel {

	@Override
	public String toString() {
		return "GameLevel [spawnPoint=" + spawnPoint + ", id=" + id
				+ ", levelTitle=" + levelTitle + ", tileMap=" + tileMap
				+ ", dots=" + dots + ", coins=" + coins + ", levelArea="
				+ levelArea + "]";
	}



	/** Spawn point of the level. */
	private Point spawnPoint;

	/** The ID of the level. The first level has an ID of 1. */
	private int id;

	/** The message that is displayed before the level. */
	private String levelTitle;

	/** A list of all of the level's tiles. */
	ArrayList<Tile> tileMap;

	/** A list of all of the level's dots. */
	public ArrayList<Dot> dots;

	/** A list of all of the level's coins. **/
	public ArrayList<Coin> coins;

	public ArrayList<Coin> coinsPlayer2;

	/** The area of the level, not including background tiles. */
	Area levelArea;

	public GameLevel() {
		this.levelArea = new Area();
		this.tileMap = new ArrayList<Tile>();
		this.dots = new ArrayList<Dot>();
		this.coins = new ArrayList<Coin>();
		// this.coinsPlayer2 = new ArrayList<Coin>(); // Initialize coins for player 2
		this.spawnPoint = new Point(20, 20);
		// this.spawnPointPlayer2 = new Point(60, 20); // Default spawn for player 2
		this.id = -1;
		this.levelTitle = "\"Intimidating message\nhere\"";
	}

	public GameLevel(Point spawn, int id) {
		this.levelArea = new Area();
		this.tileMap = new ArrayList<Tile>();
		this.dots = new ArrayList<Dot>();
		this.spawnPoint = spawn;
		this.id = id;
		this.levelTitle = "\"Intimidating message\nhere\"";
	}

	/**
	 * @return spawnPoint
	 */
	public Point getSpawnPoint() {
		return this.spawnPoint;
	}

	/**
	 * @return id
	 */
	public int getID() {
		return this.id;
	}

	/**
	 * @return tileMap
	 */
	public ArrayList<Tile> getTileMap() {
		return this.tileMap;
	}

	/**
	 * @return levelTitle
	 */
	public String getTitle() {
		return this.levelTitle;
	}



	/** Draw the tiles based on a text file in the maps package.
	 *
	 *
	 * */
	public void drawTiles(Graphics g) {

		Graphics2D g2 = (Graphics2D) g;

		try {
			/*for (Tile t : this.tileMap) {
				//Background
				if (t.getType() == 0) {
					g.setColor(new Color(180, 181, 254));
					g.fillRect(t.getX(), t.getY(), 40, 40);
				}
			}*/

			g.setColor(new Color(180, 181, 254));
			g.fillRect(0, 22, 800, 622);

			//Border around level
			g2.setColor(Color.BLACK);
			g2.fill(this.levelArea);

			for (Tile t : this.tileMap) {

				t.draw(this, g);

			}
		} catch (Exception e) {
			System.out.println("File not found.");
			TextFileWriter.appendToFile(Game.logFilePath, Game.getStringFromStackTrace(e));
		}
	}



	public void drawDots(Graphics g) {
		for (Dot dot : this.dots) dot.draw(g);
	}



	public void updateDots() {
		if (this.dots != null)
			for (Dot dot : this.dots) dot.update();
	}



	public void drawCoins(Graphics g) {
		if (this.coins != null)
			for (Coin coin : this.coins) coin.draw(g);
	}

	// public void drawCoinsPlayer2(Graphics g) {
	// 	if (this.coinsPlayer2 != null)
	// 		for (Coin coin : this.coinsPlayer2) coin.draw(g);
	// }



	public boolean allCoinsCollected() {
		if (this.coins != null) {
			for (Coin coin : this.coins) {
				if (!coin.collected) return false;
			}
		}
		return true;
	}

	// public boolean allCoinsCollectedPlayer2() {
	// 	if (this.coinsPlayer2 != null) {
	// 		for (Coin coin : this.coinsPlayer2) {
	// 			if (!coin.collected) return false;
	// 		}
	// 	}
	// 	return true;
	// }

	// public boolean allCoinsCollectedBothPlayers() {
	// 	return allCoinsCollected() && allCoinsCollectedPlayer2();
	// }

	/**
	 * Load the current level data from
	 * net.thedanpage.worldshardestgame.resources.maps
	 */
	public void init(Player player1, int levelNum) {

		if (ClassLoader.getSystemResource("resources/maps/level_" + levelNum + ".txt") != null)
			Game.easyLog(Game.logger, Level.INFO, "The file for level " + levelNum + " has been found");
		else Game.easyLog(Game.logger, Level.SEVERE, "The file for level " + levelNum + " could not be found");

		Game.easyLog(Game.logger, Level.INFO, "Level " + Game.levelNum + " is being initialized");

		//Clears the tile data
		this.tileMap = new ArrayList<Tile>();

		//Clears the dot data
		this.dots = new ArrayList<Dot>();

		// Clear the coin data for both players
		this.coins = new ArrayList<Coin>();
		// this.coinsPlayer2 = new ArrayList<Coin>();

		//Clears the level area data
		this.levelArea = new Area();

		//Resets the level title
		this.levelTitle = "\"Intimidating message\nhere\"";

		try {
			String resourcePath = "resources/maps/level_" + levelNum;
			
			// Load player 1 spawn point
			this.spawnPoint = new Point(
					Integer.parseInt(PropLoader
							.loadProperty("spawn_point", resourcePath + ".properties")
							.split(",")[0]) * 40 + 20,
					Integer.parseInt(PropLoader
							.loadProperty("spawn_point", resourcePath + ".properties")
							.split(",")[1]) * 40 + 20);

			this.id = Integer.parseInt(PropLoader.loadProperty("level_id",
					resourcePath + ".properties"));

			this.levelTitle = PropLoader.loadProperty("level_title",
					resourcePath + ".properties").toString();

			// Process coins
			String coinData = PropLoader.loadProperty("coins",
					resourcePath + ".properties");

			if (coinData != null && !coinData.equals("null")) {
				coinData = coinData.replaceAll("\\Z", "");

				if (coinData.contains("-")) {
					String[] coins = coinData.split("-");
					for (String s : coins) {
						int coinX = (int) (Double.parseDouble(s.split(",")[0]) * 40);
						int coinY = (int) (Double.parseDouble(s.split(",")[1]) * 40);

						this.coins.add(new Coin(coinX, coinY));
					}
				} else {
					int coinX = (int) (Double.parseDouble(coinData.split(",")[0]) * 40);
					int coinY = (int) (Double.parseDouble(coinData.split(",")[1]) * 40);

					this.coins.add(new Coin(coinX, coinY));
				}
			}
			Game.easyLog(Game.logger, Level.INFO, "All coins have been added");

			// New robust approach using BufferedReader
			ArrayList<String> mapLines = new ArrayList<>();
			try (java.io.BufferedReader reader = new java.io.BufferedReader(
					new java.io.InputStreamReader(
							ClassLoader.getSystemResourceAsStream(resourcePath + ".txt"), 
							java.nio.charset.StandardCharsets.UTF_8))) {
				
				String line;
				while ((line = reader.readLine()) != null) {
					mapLines.add(line.trim());
				}
			}
			
			// Process map data
			StringBuilder mapData = new StringBuilder();
			int mapHeight = Math.min(15, mapLines.size());
			for (int i = 0; i < mapHeight; i++) {
				mapData.append(mapLines.get(i).replaceAll("\\s+", ""));
			}
			
			Game.easyLog(Game.logger, Level.INFO, "Map data length: " + mapData.length());

			// Create tile map
			for (int i = 0; i < mapData.length() && i < 300; i++) {
				char tileChar = mapData.charAt(i);
				int tileType = Character.getNumericValue(tileChar);
				
				if (tileType >= 0 && tileType <= 9) {
					this.tileMap.add(new Tile((i % 20) * 40, (i / 20) * 40, tileType));
				} else {
					Game.easyLog(Game.logger, Level.WARNING, "Invalid tile character at position " + i + 
							   ": '" + tileChar + "' (code: " + (int)tileChar + ")");
					this.tileMap.add(new Tile((i % 20) * 40, (i / 20) * 40, 0));
				}
			}
			
			// Generate level area
			this.levelArea = new Area();
			for (Tile t : this.tileMap) {
				if (t.getType() != 0) {
					this.levelArea.add(new Area(
							new Rectangle(t.getX() - 3, t.getY() - 3 + 22, 46, 46)));
				}
			}
			
			// Process dot data
			int dotStartLine = 19;  // Typically dots start after line 19
			while (dotStartLine < mapLines.size()) {
				String line = mapLines.get(dotStartLine);
				if (line.isEmpty()) {
					dotStartLine++;
					continue;
				}
				
				try {
					String[] dotData = line.replaceAll("\\s+", "").split("-");
					if (dotData.length >= 7) {
						this.dots.add(new Dot(
								Integer.parseInt(dotData[0]),
								Integer.parseInt(dotData[1]),
								new Point(Integer.parseInt(dotData[2].split(",")[0]),
										Integer.parseInt(dotData[2].split(",")[1])),
								new Point(Integer.parseInt(dotData[3].split(",")[0]),
										Integer.parseInt(dotData[3].split(",")[1])),
								Double.parseDouble(dotData[4]),
								Boolean.parseBoolean(dotData[5]),
								Boolean.parseBoolean(dotData[6])
						));
					}
				} catch (Exception e) {
					if (e.getClass().getName() != "java.lang.ArrayIndexOutOfBoundsException")
						Game.easyLog(Game.logger, Level.WARNING, "Error parsing dot data: " + line);
				}
				dotStartLine++;
			}
			
			Game.easyLog(Game.logger, Level.INFO, "All dots have been added");
			
		} catch (Exception e) {
			if (e.getClass().getName() != "java.lang.ArrayIndexOutOfBoundsException")
				Game.easyLog(Game.logger, Level.SEVERE, "Dots unable to be loaded:\n" + Game.getStringFromStackTrace(e));
		}
		
		if (this.tileMap.size() == 300) Game.easyLog(Game.logger, Level.INFO, "All tiles have been added");
		else Game.easyLog(Game.logger, Level.WARNING, "Not all tiles were added");

		// Respawn player
		player1.respawn(this);
	}

}