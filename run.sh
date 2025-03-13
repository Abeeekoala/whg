#!/bin/bash
echo "Running the game..."

java -cp "build/classes:lib/json-simple-1.1.1.jar:TinySound/tinysound-1.1.1.jar:TinySound/lib/jorbis-0.0.17.jar:TinySound/lib/tritonus_share.jar:TinySound/lib/vorbisspi1.0.3.jar" whg.Game

# Check if the game ran successfully
if [ $? -eq 0 ]; then
    echo "Game exited successfully."
else
    echo "Game exited with an error."
    exit 1
fi