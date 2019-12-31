#!/bin/bash
set -e

lein clean
# generates a file like:
# ./target/wowman-1.1.1-standalone.jar
lein uberjar
(
    cd target
    filepath=$(realpath wowman-*-standalone.jar | head -n 1)
    filename=$(basename "$filepath")
    sha256sum wowman-*-standalone.jar > "$filename.sha256"
    echo "Created $filepath.sha256"
)
