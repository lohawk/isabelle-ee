#!/bin/bash
cd "$(dirname "$0")"
CP=$(find ../lib -name '*.jar' | tr '\n' ':')
java -Dorg.slf4j.simpleLogger.defaultLogLevel=warn \
     -Dorg.slf4j.simpleLogger.log.com.conveyal=warn \
     -Djava.util.logging.config.file=logging.properties \
     -cp "${CP}." "$@"
