import socket
import boto3
from botocore.exceptions import ClientError

# DynamoDB setup
dynamodb = boto3.resource('dynamodb', region_name='eu-north-1')
table = dynamodb.Table('Leaderboard')

# Server setup
HOST = '0.0.0.0'  # Listen on all interfaces
PORT = 12000      # Port to listen on

def handle_client(conn, addr):
    print(f'Connected by {addr}')
    data = conn.recv(1024).decode()
    if not data:
        return

    try:
        command, payload = data.strip().split(' ', 1)
    except ValueError:
        print("Invalid input format. Expected 'COMMAND payload'")
        conn.sendall(b'Invalid input format. Expected "COMMAND payload"')
        return

    if command == "GET_HIGHSCORE":
        username = payload.strip()
        try:
            response = table.get_item(Key={'PlayerName': username})
            if 'Item' in response:
                highscore = response['Item']['HighScore']
            else:
                highscore = 1000
                table.put_item(Item={'PlayerName': username, 'HighScore': highscore})
        except ClientError as e:
            print(e.response['Error']['Message'])
            highscore = 1000

        conn.sendall(str(highscore).encode())

    elif command == "SET_HIGHSCORE":
        try:
            username, highscore = payload.strip().split(',')
            highscore = int(highscore)
        except ValueError:
            print("Invalid input format. Expected 'Playername, Highscore'")
            conn.sendall(b'Invalid input format. Expected "Playername, Highscore"')
            return

        try:
            response = table.get_item(Key={'PlayerName': username})
            if 'Item' in response:
                current_highscore = response['Item']['HighScore']
                if highscore < current_highscore:
                    table.update_item(
                        Key={'PlayerName': username},
                        UpdateExpression='SET HighScore = :val1',
                        ExpressionAttributeValues={':val1': highscore}
                    )
            else:
                table.put_item(Item={'PlayerName': username, 'HighScore': highscore})
        except ClientError as e:
            print(e.response['Error']['Message'])
            conn.sendall(b'Error accessing database')
            return

        conn.sendall(b'Highscore updated')

    else:
        conn.sendall(b'Unknown command')

def main():
    #create a welcoming socket
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)  
    s.bind((HOST, PORT))
    s.listen()
    print(f'Server listening on {HOST}:{PORT}')
    while True:
        conn, addr = s.accept()
        with conn:
            handle_client(conn, addr)

if __name__ == "__main__":
    main()