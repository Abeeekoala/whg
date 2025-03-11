package whg;

import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.logging.Level;

import kuusisto.tinysound.TinySound;

public class Input {

	/** The coordinates of the mouse relative to the Game.frame. */
	static Point mouseCoords = new Point(0, 0);

	/** True/false depending on if the mouse is pressed. */
	static boolean mousePressed = false;

	/** True/false depending on if the mouse is over the Game.frame. */
	static boolean mouseOnWindow = false;

	/** Map to store the state of all keyboard keys */
	private static boolean[] keys = new boolean[256];

	/** Used to easily store information of keys. */
	public static class Key {
		public boolean isPressed;
		public int numTimesPressed;

		public Key() {
			isPressed = false;
			numTimesPressed = 0;
		}
	}

	public static Key left = new Key();
	public static Key right = new Key();
	public static Key up = new Key();
	public static Key down = new Key();

	public static Key wKey = new Key(); // Up for player 2
	public static Key aKey = new Key(); // Left for player 2
	public static Key sKey = new Key(); // Down for player 2
	public static Key dKey = new Key(); // Right for player 2

	public static Key enter = new Key();
	public static Key escape = new Key();
	public static Key space = new Key();
	public static Key backspace = new Key();

	/** 
	 * Check if a specific key is pressed by character code
	 * @param keyChar The character code to check
	 * @return True if the key is pressed, false otherwise
	 */
	public static boolean isKeyPressed(int keyChar) {
		return keys[keyChar];
	}

	public static void init() {

		Game.frame.addMouseListener(new MouseListener() {

			public void mouseClicked(MouseEvent e) {
			}

			public void mouseEntered(MouseEvent e) {
				mouseOnWindow = true;
			}

			public void mouseExited(MouseEvent e) {
				mouseOnWindow = false;
			}

			public void mousePressed(MouseEvent e) {
				mousePressed = true;

				if (e.getX() >= 752 && e.getY() <= 48) {
					if (!Game.muted) {
						TinySound.setGlobalVolume(0);
						Game.muted = true;
						Game.easyLog(Game.logger, Level.INFO, "Muted");
					} else {
						TinySound.setGlobalVolume(1);
						Game.muted = false;
						Game.easyLog(Game.logger, Level.INFO, "Unmuted");
					}
				}
			}

			public void mouseReleased(MouseEvent e) {
				mousePressed = false;
			}
		});

		Game.frame.addMouseMotionListener(new MouseMotionListener() {

			public void mouseDragged(MouseEvent e) {
			}

			public void mouseMoved(MouseEvent e) {
				mouseCoords = e.getPoint();
			}

		});

		Game.frame.addKeyListener(new KeyListener() {

			public void keyTyped(KeyEvent e) {
			}

			public void keyPressed(KeyEvent e) {
				// Store all keys in the keys array
				if (e.getKeyCode() < 256) {
					keys[e.getKeyCode()] = true;
				}
				
				if (e.getKeyCode() == KeyEvent.VK_LEFT) {
					left.isPressed = true;
					left.numTimesPressed++;
				}
				if (e.getKeyCode() == KeyEvent.VK_RIGHT) {
					right.isPressed = true;
					right.numTimesPressed++;
				}
				if (e.getKeyCode() == KeyEvent.VK_UP) {
					up.isPressed = true;
					up.numTimesPressed++;
				}
				if (e.getKeyCode() == KeyEvent.VK_DOWN) {
					down.isPressed = true;
					down.numTimesPressed++;
				}

				// Player 2 controls (WASD)
				if (e.getKeyCode() == KeyEvent.VK_A) {
					aKey.isPressed = true;
					aKey.numTimesPressed++;
				}
				if (e.getKeyCode() == KeyEvent.VK_D) {
					dKey.isPressed = true;
					dKey.numTimesPressed++;
				}
				if (e.getKeyCode() == KeyEvent.VK_W) {
					wKey.isPressed = true;
					wKey.numTimesPressed++;
				}
				if (e.getKeyCode() == KeyEvent.VK_S) {
					sKey.isPressed = true;
					sKey.numTimesPressed++;
				}

				if (e.getKeyCode() == KeyEvent.VK_ENTER) {
					enter.isPressed = true;
					enter.numTimesPressed++;
				}
				if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
					escape.isPressed = true;
					escape.numTimesPressed++;
				}
				if (e.getKeyCode() == KeyEvent.VK_SPACE) {
					space.isPressed = true;
					space.numTimesPressed++;
				}
				if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
					backspace.isPressed = true;
					backspace.numTimesPressed++;
				}
			}

			public void keyReleased(KeyEvent e) {
				// Update the keys array on release
				if (e.getKeyCode() < 256) {
					keys[e.getKeyCode()] = false;
				}

				if (e.getKeyCode() == KeyEvent.VK_LEFT) {
					left.isPressed = false;
				}
				if (e.getKeyCode() == KeyEvent.VK_RIGHT) {
					right.isPressed = false;
				}
				if (e.getKeyCode() == KeyEvent.VK_UP) {
					up.isPressed = false;
				}
				if (e.getKeyCode() == KeyEvent.VK_DOWN) {
					down.isPressed = false;
				}

				// Player 2 controls
				if (e.getKeyCode() == KeyEvent.VK_A) {
					aKey.isPressed = false;
				}
				if (e.getKeyCode() == KeyEvent.VK_D) {
					dKey.isPressed = false;
				}
				if (e.getKeyCode() == KeyEvent.VK_W) {
					wKey.isPressed = false;
				}
				if (e.getKeyCode() == KeyEvent.VK_S) {
					sKey.isPressed = false;
				}

				if (e.getKeyCode() == KeyEvent.VK_ENTER) {
					enter.isPressed = false;
				}
				if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
					escape.isPressed = false;
				}
				if (e.getKeyCode() == KeyEvent.VK_SPACE) {
					space.isPressed = false;
				}
				if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
					backspace.isPressed = false;
				}
			}
		});

		Game.frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent windowEvent) {

				Game.easyLog(Game.logger, Level.INFO, "Shutting down...");

				if (Game.doLogging) LogZipper.zipLog();

				System.exit(0);
			}
		});
	}
}
