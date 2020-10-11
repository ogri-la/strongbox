#!/bin/bash
# called by Travis CI via the .travis.yml file
set -e

lein clean
# generates a file like:
# ./target/strongbox-1.1.1-standalone.jar
lein uberjar
(
    cd target
    filepath=$(realpath strongbox-*-standalone.jar | head -n 1)
    filename=$(basename "$filepath")
    sha256sum strongbox-*-standalone.jar > "$filename.sha256"
)

# generates an AppImage called 'strongbox'
. build-linux-image.sh
sha256sum strongbox > "target/strongbox.sha256"
mv strongbox target/strongbox
