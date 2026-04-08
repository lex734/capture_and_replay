#!/usr/bin/env bash
# Builds the observer-effect-bench JCStress jar.
# Requires JDK 11+ and Maven on $PATH.
#
# The agents (instr/SyncTransformer) must have been rebuilt after the
# exclude= argument support was added:
#   mvn package -DskipTests  (from the repo root)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "Building observer-effect-bench..."
mvn package -q -f "$SCRIPT_DIR/pom.xml" -DskipTests

echo "Built: $SCRIPT_DIR/target/jcstress.jar"
