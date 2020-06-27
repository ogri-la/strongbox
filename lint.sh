#!/bin/bash
set -e

if which joker > /dev/null; then
    echo "joker lint"
    joker --lint --working-dir ./
    echo "joker lint done"
fi

echo "eastwood lint"
lein cljfmt fix
lein eastwood
echo "eastwood lint done"
