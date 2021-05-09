#!/bin/bash
set -e

# always ratchet *upwards*
fail_threshold=80

# this file can't live in src/strongbox because lein-cloverage can't be found during dev.
# so we copy it in and destroy it afterwards
cp cloverage.clj src/strongbox/cloverage.clj

# any coverage reports from previous run
rm -rf ./coverage/

function finish {
    rm src/strongbox/cloverage.clj

    # 'lein clean' wipes out the 'target' directory, including the Cloverage report
    if [ -d target/coverage ]; then
        echo
        echo "wrote coverage/index.html"
        mv target/coverage coverage
    fi

    lein clean
}
trap finish EXIT

if which xvfb-run; then
    # CI
    xvfb-run lein cloverage --runner "strongbox"
else
    # dev
    lein cloverage --runner "strongbox" --fail-threshold "$fail_threshold" --html
fi
