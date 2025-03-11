@echo off
echo Running the game...

java -cp "build\classes;lib\json-simple-1.1.1.jar;TinySound\tinysound-1.1.1.jar;TinySound\lib\jorbis-0.0.17.jar;TinySound\lib\tritonus_share.jar;TinySound\lib\vorbisspi1.0.3.jar" whg.Game

pause