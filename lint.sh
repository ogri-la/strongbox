#!/bin/bash
set -e

if which joker > /dev/null; then
    echo "joker lint"
    joker --lint --working-dir ./
    echo "joker lint done"
fi

echo "eastwood lint"
#git diff-index --quiet HEAD -- || { echo "commit your changes first"; exit 1; }
lein cljfmt fix
lein eastwood
echo "eastwood lint done"
