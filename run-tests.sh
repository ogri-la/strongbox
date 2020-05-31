#!/bin/bash
# (always ratchet threshold upwards)

# this file can't live in src/strongbox because lein-cloverage can't be found during dev.
# so we copy it in and destroy it afterwards
cp cloverage.clj src/strongbox/cloverage.clj

lein cloverage --runner "strongbox" --fail-threshold 80 --html || {
    # 1 for failed tests
    # 253 for failed coverage
    if [ "$?" = "253" ]; then
        if which otter-browser; then
            otter-browser "file://$(pwd)/target/coverage/index.html" &
        fi
    fi
}

rm src/strongbox/cloverage.clj
