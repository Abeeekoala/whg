#!/bin/bash
echo "Compiling Java files..."

# Remove existing build directory if it exists
rm -rf build/classes
mkdir -p build/classes

# Compile Java files
javac -d build/classes -cp ".:lib/json-simple-1.1.1.jar:TinySound/tinysound-1.1.1.jar:TinySound/lib/jorbis-0.0.17.jar:TinySound/lib/tritonus_share.jar:TinySound/lib/vorbisspi1.0.3.jar" src/*.java

# Check if compilation was successful
if [ $? -eq 0 ]; then
    echo "Compilation successful."
    
    # Copy resources to the build directory
    mkdir -p build/classes/resources
    cp -r src/resources/* build/classes/resources/
    
    echo "Resources copied."
else
    echo "Compilation failed."
    exit 1
fi

echo "Compilation complete."