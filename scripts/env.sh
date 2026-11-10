# Source this file (". scripts/env.sh") before running Gradle or adb from a plain shell.
# Defaults match this machine; export JAVA_HOME or ANDROID_HOME first to override them.
export JAVA_HOME="${JAVA_HOME:-/Users/aiuser/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/Users/aiuser/Library/Android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
