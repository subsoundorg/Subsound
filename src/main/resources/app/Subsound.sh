#!/usr/bin/env bash

export JAVA_OPTS="--enable-native-access=ALL-UNNAMED"

# We use `exec -a` in the Subsound.sh script to rename the process we start, so its not just named 'java' everywhere:
exec -a Subsound java ${JAVA_OPTS} -jar /app/lib/Subsound.jar
