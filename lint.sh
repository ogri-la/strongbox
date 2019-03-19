#!/bin/bash
set -e
#git diff-index --quiet HEAD -- || { echo "commit your changes first"; exit 1; }
lein cljfmt fix
lein eastwood
