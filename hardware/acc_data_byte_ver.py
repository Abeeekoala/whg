import subprocess
import socket
import threading
import struct  # For packing data into bytes

NIOS_CMD_SHELL_BAT = "C:/intelFPGA_lite/18.1/nios2eds/Nios II Command Shell.bat"
TAPS = 49  

# Set up network connection to Mac
MAC_IP = "127.0.0.1"  # Replace with your IP
PORT = 5000

client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
client.connect((MAC_IP, PORT))
print(f"Connected to Mac at {MAC_IP}:{PORT}")

def collect():
    process = subprocess.Popen(
        ['nios2-terminal'],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        universal_newlines=True,  # Ensures text mode
        bufsize=1  # Line buffering for smoother reads
    )

    print("Reading accelerometer data... Sending to Mac...")

    def read_from_process():
        """Continuously read from nios2-terminal output and send smooth data."""
        while True:
            try:
                line = process.stdout.readline().strip()
                if not line:
                    continue  # Skip empty lines

                if "Accelerometer Data" in line:
                    try:
                        # Extract only relevant portion
                        line = line.split(" - ")[0]  # Keeps only "Accelerometer Data: X=3, Y=-14, Z=238"
                       
                        parts = line.split(',')
                        x_value = int(parts[0].split('=')[1].strip())
                        y_value = int(parts[1].split('=')[1].strip())

                        # Fix for Z-value extraction
                        z_value = int(parts[2].split('=')[1].strip().split()[0])

                        # Pack X and Y values into bytes (using 'h' for 2-byte signed integers)
                        data_bytes = struct.pack('<hh', x_value, y_value)  # 'hh' means two short integers (2 bytes each)

                        # Send the packed bytes to Mac
                        client.sendall(data_bytes)

                    except Exception as e:
                        print(f"Error parsing line: {line}, {e}")

            except Exception as e:
                print(f"Error reading process output: {e}")
                break  # Stop reading in case of error

    # Use a separate thread to read process output asynchronously
    read_thread = threading.Thread(target=read_from_process, daemon=True)
    read_thread.start()

    try:
        while True:
            process.stdin.write('r\n')
            process.stdin.flush()

    except KeyboardInterrupt:
        print("\nData collection stopped.")
    finally:
        process.terminate()
        client.close()

if __name__ == "__main__":
    collect()
