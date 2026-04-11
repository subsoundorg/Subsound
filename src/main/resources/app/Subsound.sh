#!/usr/bin/env bash

JAVABIN=$JAVA_HOME/bin/java
JAVA_OPTS="${JAVA_OPTS:+$JAVA_OPTS }--enable-native-access=ALL-UNNAMED"

# We use `exec -a` in the Subsound.sh script to rename the process we start, so its not just named 'java' everywhere:
# JAVA_OPTS is intentionally unquoted for word splitting
exec -a Subsound "${JAVABIN}" $JAVA_OPTS -jar /app/lib/Subsound.jar
