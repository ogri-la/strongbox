#!/bin/bash
# downloads new test fixtures 

set -eux pipefail

function dl {
    url="$1"
    fname="$2"
    if [[ "$fname" == *json ]]; then
        curl --silent "$url" | jq . > "test/fixtures/$fname"
    else
        curl --silent "$url" > "test/fixtures/$fname"
    fi
    echo "$fname"
}

# curseforge
dl "https://www.curseforge.com/wow/addons?filter-sort=name&page=1" "curseforge-addon-summary-listing.html"

# curseforge api
dl "https://addons-ecs.forgesvc.net/api/v2/addon/327019" "curseforge-api-addon--everyaddon.json"
cp "test/fixtures/curseforge-api-addon--everyaddon.json" "test/fixtures/curseforge-api-addon--everyotheraddon.json"

# wowinterface
dl "https://wowinterface.com/addons.php" "wowinterface-category-list.html"
dl "https://www.wowinterface.com/downloads/cat19.html" "wowinterface-category-page.html"
dl "https://www.wowinterface.com/downloads/info9085-Dominos.html" "wowinterface-addon-page.html"
