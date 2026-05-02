#!/bin/bash
java -Dorg.slf4j.simpleLogger.defaultLogLevel=warn \
     -Dorg.slf4j.simpleLogger.log.com.conveyal=warn \
     -Djava.util.logging.config.file=logging.properties \
     -cp "../lib/gtfs-lib-6.2.0-shaded.jar:." "$@"
