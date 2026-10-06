#!/bin/sh
set -eu

CMON_HOME="${CMON_HOME:-/opt/cmon-update}"
if [ -f "$CMON_HOME/telegram.env" ]; then
    set -a
    . "$CMON_HOME/telegram.env"
    set +a
fi

: "${CMON_DATABASE_PATH:=$CMON_HOME/data/cmon-state}"
export CMON_DATABASE_PATH

exec "${JAVA_BIN:-/usr/bin/java}" -jar "$CMON_HOME/cmon-update.jar"
