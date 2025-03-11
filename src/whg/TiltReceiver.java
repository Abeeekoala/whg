package whg;    
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;   
import java.net.Socket;

public class TiltReceiver {
    public static void main(String[] args) {
        try {
            // Set up the server to listen on port 5000
            ServerSocket serverSocket = new ServerSocket(5000); // Server listens on port 5000
            System.out.println("Server started. Waiting for connection...");

            // Accept the incoming client connection (Python code on Mac)
            Socket socket = serverSocket.accept();
            System.out.println("Connected to client!");

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Continuously listen for incoming data from the client (Python)
            while (true) {
                String data = reader.readLine(); // Read data sent from the Python client
                if (data != null) {
                    // Split the     data string by commas (assuming format is "x,y")
                    String[] values = data.split(",");
                    if (values.length == 2) {
                        double x = Double.parseDouble(values[0].trim()); // Parse X value
                        double y = Double.parseDouble(values[1].trim()); // Parse Y value

                        // Print the received tilt values (X and Y)
                        System.out.println("Tilt X: " + x + ", Tilt Y: " + y);
                    } else {
                        System.out.println("Received data format is incorrect.");
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace(); // Print any errors that occur
        }
    }
}
