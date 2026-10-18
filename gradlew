#!/usr/bin/env sh
##############################################################################
##
##  Gradle start up script for UN*X
##
##############################################################################

APP_HOME=$(cd "$(dirname "$0")" && pwd)

if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
    if command -v gradle >/dev/null 2>&1; then
        exec gradle "$@"
    else
        echo "Downloading Gradle wrapper jar..."
        mkdir -p "$APP_HOME/gradle/wrapper"
        curl -sLo "$WRAPPER_JAR" https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradle/wrapper/gradle-wrapper.jar 2>/dev/null || true
    fi
fi

if [ -f "$WRAPPER_JAR" ]; then
    exec "$JAVACMD" "-Dorg.gradle.appname=gradlew" -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
else
    exec gradle "$@"
fi
