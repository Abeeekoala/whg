package whg;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class PropLoader {
	
	public static String loadProperty(String property, String filename) {
		Properties prop = new Properties();
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		InputStream stream = null;
		try {
			stream = loader.getResourceAsStream(filename);
			if (stream == null) {
				System.err.println("Cannot find resource: " + filename);
				return null;
			}
			
			prop.load(stream);
			return prop.getProperty(property);
		} catch (IOException e) {
			System.err.println("Error loading property " + property + " from " + filename + ": " + e.getMessage());
			TextFileWriter.appendToFile(Game.logFilePath, Game.getStringFromStackTrace(e));
			return null;
		} finally {
			if (stream != null) {
				try {
					stream.close();
				} catch (IOException e) {
					// Ignore close errors
				}
			}
		}
	}
	
}
