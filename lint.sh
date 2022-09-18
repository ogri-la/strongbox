#!/bin/bash
set -e

if which joker > /dev/null; then
    echo "joker lint"
    joker --lint --working-dir ./
fi

echo "cljfmt lint"
lein cljfmt fix

echo "eastwood lint"
lein eastwood
