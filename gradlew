#!/bin/sh
# Minimal launcher for the official Gradle 8.10.2 wrapper JAR.
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
if [ -n "$JAVA_HOME" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
else
  JAVA_CMD=java
fi
exec "$JAVA_CMD" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
