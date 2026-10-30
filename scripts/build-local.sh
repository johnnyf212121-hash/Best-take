#!/bin/sh
# Reuse this workspace's optional toolchain and keep the debug signing key stable.
set -eu
cd "$(dirname "$0")/.."
if [ -x .tooling/jdk/Contents/Home/bin/java ]; then
    export JAVA_HOME="$PWD/.tooling/jdk/Contents/Home"
fi
export GRADLE_USER_HOME="$PWD/.tooling/gradle-cache"
export ANDROID_USER_HOME="$PWD/.tooling/android-user"
if [ "$#" -eq 0 ]; then
    set -- :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
fi
if [ -x .tooling/gradle-8.11.1/bin/gradle ]; then
    exec .tooling/gradle-8.11.1/bin/gradle --no-daemon "$@"
else
    exec ./gradlew --no-daemon "$@"
fi
