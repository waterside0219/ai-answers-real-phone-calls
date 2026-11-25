#!/bin/sh
set -eu
cd "$(dirname "$0")"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 11+}"
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_HOME/platforms/android-34/android.jar}"
D8="${D8:-$ANDROID_HOME/build-tools/34.0.0/d8}"
rm -rf out
rm -f classes.dex probe.jar
mkdir -p out
"$JAVA_HOME/bin/javac" --release 11 -cp "$ANDROID_JAR" -d out src/*.java
"$D8" --min-api 30 --output . out/*.class
"$JAVA_HOME/bin/jar" cfM probe.jar classes.dex
rm classes.dex
printf '%s\n' 'Built probe.jar'
