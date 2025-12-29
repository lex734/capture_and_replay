#!/bin/bash
set -e  # Exit immediately if any command fails

# --- Configuration ---
ASM_VER="9.7"
LIB_DIR="lib"
BUILD_DIR="build"
DIST_DIR="libs"

echo "=== [1/5] Cleaning previous builds ==="
rm -rf $BUILD_DIR $DIST_DIR trace.bin
mkdir -p $BUILD_DIR/core $BUILD_DIR/instr $BUILD_DIR/test
mkdir -p $LIB_DIR
mkdir -p $DIST_DIR

# --- Dependency Management ---
echo "=== [2/5] Checking Dependencies ==="
download_jar() {
    local url=$1
    local file=$2
    if [ ! -f "$file" ]; then
        echo "Downloading $file..."
        curl -s -L -o "$file" "$url"
    fi
}

download_jar "https://repo1.maven.org/maven2/org/ow2/asm/asm/$ASM_VER/asm-$ASM_VER.jar" "$LIB_DIR/asm-$ASM_VER.jar"
download_jar "https://repo1.maven.org/maven2/org/ow2/asm/asm-commons/$ASM_VER/asm-commons-$ASM_VER.jar" "$LIB_DIR/asm-commons-$ASM_VER.jar"
download_jar "https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/$ASM_VER/asm-tree-$ASM_VER.jar" "$LIB_DIR/asm-tree-$ASM_VER.jar"

# --- Step 1: Build the Core (Monitor) ---
echo "=== [3/5] Building Trace Core ==="
javac -d $BUILD_DIR/core core/*.java
jar -cf $DIST_DIR/trace-core.jar -C $BUILD_DIR/core .
echo " -> Created trace-core.jar"

# --- Step 2: Build the Agent (Instrumenter) ---
echo "=== [4/5] Building Agent ==="
# Compile Agent with classpath pointing to ASM libs and Core
CP="$DIST_DIR/trace-core.jar:$LIB_DIR/asm-$ASM_VER.jar:$LIB_DIR/asm-commons-$ASM_VER.jar:$LIB_DIR/asm-tree-$ASM_VER.jar"
javac -d $BUILD_DIR/instr -cp "$CP" instr/*.java

# Create a "Fat Jar" (Unzip ASM libs into the agent build folder)
# This ensures the Agent works standalone without needing ASM on the app classpath
cd $BUILD_DIR/instr
jar -xf ../../$LIB_DIR/asm-$ASM_VER.jar
jar -xf ../../$LIB_DIR/asm-commons-$ASM_VER.jar
jar -xf ../../$LIB_DIR/asm-tree-$ASM_VER.jar
cd ../..

# Create Manifest
echo "Premain-Class: instr.Agent" > manifest.txt
echo "Can-Retransform-Classes: true" >> manifest.txt

# Package Agent
jar -cfm $DIST_DIR/trace-agent.jar manifest.txt -C $BUILD_DIR/instr .
rm manifest.txt
echo " -> Created trace-agent.jar"

# --- Step 3: Build Test App ---
echo "=== [5/5] Building Test App ==="
javac -d $BUILD_DIR/test test_app/Main.java

echo "=== Build Complete! ==="
echo "Run using:"
echo "java -javaagent:$DIST_DIR/trace-agent.jar -cp $BUILD_DIR/test Main"
