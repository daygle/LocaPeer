#!/bin/sh
# Gradle wrapper start script for UN*X

APP_HOME=$(cd "$(dirname "$0")" && pwd)
APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

warn() { echo "$*" >&2; }
die() { echo; echo "$*"; echo; exit 1; } >&2

JAVACMD=java
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
fi

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
