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

# curseforge
dl "https://www.curseforge.com/wow/addons?filter-sort=name&page=1" "curseforge-addon-summary-listing.html"

# wowinterface
dl "https://wowinterface.com/addons.php" "wowinterface-category-list.html"
dl "https://www.wowinterface.com/downloads/cat19.html" "wowinterface-category-page.html"
dl "https://www.wowinterface.com/downloads/info9085-Dominos.html" "wowinterface-addon-page.html"
