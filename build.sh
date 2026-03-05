#!/bin/bash
set -e  # Exit immediately if any command fails

# --- Configuration ---
ASM_VER="9.7"
LIB_DIR="lib"
BUILD_DIR="build"
DIST_DIR="libs"

echo "=== [1/6] Cleaning previous builds ==="
rm -rf $BUILD_DIR $DIST_DIR trace.bin
mkdir -p $BUILD_DIR/{common,instr,capture,replay,test}
mkdir -p $LIB_DIR
mkdir -p $DIST_DIR

# --- Dependency Management ---
echo "=== [2/6] Checking Dependencies ==="
download_jar() {
    local url=$1
    local file=$2
    if [ ! -f "$file" ]; then
        echo "Downloading $file..."
        curl -s -L -o "$file" "$url"
    fi
}

# Using your original working URLs
download_jar "https://repo1.maven.org/maven2/org/ow2/asm/asm/$ASM_VER/asm-$ASM_VER.jar" "$LIB_DIR/asm-$ASM_VER.jar"
download_jar "https://repo1.maven.org/maven2/org/ow2/asm/asm-commons/$ASM_VER/asm-commons-$ASM_VER.jar" "$LIB_DIR/asm-commons-$ASM_VER.jar"
download_jar "https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/$ASM_VER/asm-tree-$ASM_VER.jar" "$LIB_DIR/asm-tree-$ASM_VER.jar"
download_jar "https://repo1.maven.org/maven2/org/ow2/asm/asm-util/$ASM_VER/asm-util-$ASM_VER.jar" "$LIB_DIR/asm-util-$ASM_VER.jar"

# --- Step 1: Build Common (The Schema) ---
echo "=== [3/6] Building Trace Common ==="
javac -d $BUILD_DIR/common common/*.java
jar -cf $DIST_DIR/trace-common.jar -C $BUILD_DIR/common .
echo " -> Created trace-common.jar"

# --- Step 2: Build Shared Instrumentation Engine ---
echo "=== [4/6] Building Shared Instrumentation Engine ==="
# Needs ASM + Common
CP_ASM="$LIB_DIR/asm-$ASM_VER.jar:$LIB_DIR/asm-commons-$ASM_VER.jar:$LIB_DIR/asm-tree-$ASM_VER.jar:$LIB_DIR/asm-util-$ASM_VER.jar"
CP_INSTR="$DIST_DIR/trace-common.jar:$CP_ASM"
javac -d $BUILD_DIR/instr -cp "$CP_INSTR" instr/*.java

# --- Step 3: Build Capture and Replay Hooks ---
echo "=== [5/6] Building Phase-Specific Monitors ==="
# These need Common and the Instrumentation engine
CP_PHASE="$DIST_DIR/trace-common.jar:$BUILD_DIR/instr"
javac -d $BUILD_DIR/capture -cp "$CP_PHASE" capture/*.java
javac -d $BUILD_DIR/replay -cp "$CP_PHASE" replay/*.java

# --- Step 4: Package Fat Agents ---

package_agent() {
    local name=$1
    local premain=$2
    local specific_dir=$3
    local out_jar="$DIST_DIR/trace-$name-agent.jar"

    echo " -> Creating $out_jar"
    
    # Use a temporary folder for assembly to keep build/ clean
    local tmp="tmp_asm_$name"
    mkdir -p $tmp
    
    # Unzip ASM libs
    (cd $tmp && jar -xf ../$LIB_DIR/asm-$ASM_VER.jar && \
            jar -xf ../$LIB_DIR/asm-commons-$ASM_VER.jar && \
            jar -xf ../$LIB_DIR/asm-tree-$ASM_VER.jar && \
            jar -xf ../$LIB_DIR/asm-util-$ASM_VER.jar)
    
    # Copy Compiled Classes
    cp -r $BUILD_DIR/common/* $tmp/
    cp -r $BUILD_DIR/instr/* $tmp/
    cp -r $specific_dir/* $tmp/
    
    # Manifest
    echo "Premain-Class: $premain" > manifest.txt
    echo "Can-Retransform-Classes: true" >> manifest.txt
    
    jar -cfm $out_jar manifest.txt -C $tmp .
    rm -rf manifest.txt $tmp
}

package_agent "capture" "instr.Agent" "$BUILD_DIR/capture"
package_agent "replay" "replay.ReplayAgent" "$BUILD_DIR/replay"

# --- Step 5: Build Test App ---
echo "=== [6/6] Building Test App ==="
javac -d $BUILD_DIR/test test_app/Main.java

echo "=== Build Complete! ==="
