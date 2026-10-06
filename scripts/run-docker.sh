#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    printf 'Usage: %s IMAGE\n' "$0" >&2
    exit 2
fi

CMON_HOME="${CMON_HOME:-/opt/cmon-update}"
image="$1"

if [ -f "$CMON_HOME/telegram.env" ]; then
    set -- --env-file "$CMON_HOME/telegram.env"
else
    set --
fi

exec "${DOCKER_BIN:-/usr/bin/docker}" run --rm --pull=always \
    "$@" \
    --mount "type=bind,source=$CMON_HOME/data,target=/data" \
    "$image"
