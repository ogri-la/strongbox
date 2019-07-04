#!/bin/bash
# downloads new test fixtures 

set -eux pipefail

function dl {
    url="$1"
    fname="$2"
    curl \
        --silent \
        --output "test/fixtures/$fname" \
        "$url"
    echo "$fname"
}


dl "https://www.curseforge.com/wow/addons?filter-sort=name&page=1" "curseforge-addon-summary-listing.html"
