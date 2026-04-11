#!/usr/bin/env bash

# TODO: add the needed $JAVA_OPTS args here?
# --enable-native-access=ALL-UNNAMED

# We use `exec -a` in the Subsound.sh script to rename the process we start, so its not just named 'java' everywhere:
exec -a Subsound java $JAVA_OPTS -jar /app/lib/Subsound.jar
