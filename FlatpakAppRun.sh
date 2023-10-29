#!/bin/bash
set -e
DIR="$(dirname "$(readlink -f "$0")")"
cd $DIR
/app/jre/bin/java -jar /app/bin/app.jar "$@"

