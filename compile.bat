@echo off
echo Compiling Java files...

if exist build\classes rmdir /s /q build\classes
mkdir build\classes

rem Compile Java files
javac -d build\classes -cp ".;lib\json-simple-1.1.1.jar;TinySound\tinysound-1.1.1.jar;TinySound\lib\jorbis-0.0.17.jar;TinySound\lib\tritonus_share.jar;TinySound\lib\vorbisspi1.0.3.jar" src\*.java

rem Copy resources to the build directory
mkdir build\classes\resources
xcopy /E /I src\resources build\classes\resources

echo Compilation complete.
pause