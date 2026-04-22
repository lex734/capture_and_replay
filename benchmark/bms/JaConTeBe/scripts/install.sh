#!/usr/bin/env bash

set -euo pipefail

JAVA_HOME=${JACONTEBE_JAVA_HOME:-${JDK11_HOME:-/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home}}
JAVA=$JAVA_HOME/bin/java
JAVAC=$JAVA_HOME/bin/javac

if [ ! -x "$JAVA" ] || [ ! -x "$JAVAC" ]; then
    echo "ERROR: Java 11 not found at JAVA_HOME=$JAVA_HOME"
    echo "Set JACONTEBE_JAVA_HOME or JDK11_HOME to a Java 11 installation."
    exit 1
fi

echo "$("$JAVA" -version 2>&1 | head -n 1)"
echo "$("$JAVAC" -version)"

if test $# -ne 2
    then \
        echo Usage:
        echo install.sh comp_dir target_bug
        echo comp_dir: orig
        echo "target_bug: Bug directory's name"
        exit 0
fi

subject_dir=$(realpath $(dirname $0))/..
target=$2
echo removing old files
rm -f ${subject_dir}/outputs/*

if test $1 = "orig"
then
     echo copying files for orig version
     rm -rf ${subject_dir}/build/$target
     mkdir -p ${subject_dir}/build/$target
     cp -a ${subject_dir}/versions.alt/$target/orig/* ${subject_dir}/build/$target
else
    echo orig is the only option currently available
    exit 0
fi
cd ${subject_dir}/build/$target

echo compiling application
echo ${subject_dir}

find . -name "*.java" -print0 | xargs -0 "$JAVAC" --release 11 -cp ${subject_dir}/versions.alt/lib/$target.jar
