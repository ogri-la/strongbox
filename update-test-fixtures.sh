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
## one search result
dl "https://addons-ecs.forgesvc.net/api/v2/addon/search?gameId=1&index=0&pageSize=1&searchFilter=&sort=2" "curseforge-api-search--truncated.json"

# wowinterface
dl "https://wowinterface.com/addons.php" "wowinterface-category-list.html"
dl "https://www.wowinterface.com/downloads/cat19.html" "wowinterface-category-page.html"
dl "https://www.wowinterface.com/downloads/info9085-Dominos.html" "wowinterface-addon-page.html"

# wowinterface api
dl "https://api.mmoui.com/v3/game/WOW/filelist.json" "wowinterface-api--complete.json"
dl "https://api.mmoui.com/v3/game/WOW/filedetails/25122.json" "wowinterface-api--addon-details.json"
dl "https://api.mmoui.com/v3/game/WOW/filedetails/24910.json" "wowinterface-api--addon-details-classic.json" # WeakAuras2

# github api
dl "https://api.github.com/repos/Robert388/Necrosis-classic/releases" "github-repo-releases--no-assets.json"
dl "https://api.github.com/repos/jsb/RingMenu/releases" "github-repo-releases--single-asset-classic.json"
dl "https://api.github.com/repos/jsb/RingMenu/releases" "github-repo-releases--broken-assets.json"
dl "https://api.github.com/repos/Ravendwyr/Chinchilla/releases" "github-repo-releases--many-assets-many-gametracks.json" 
dl "https://api.github.com/repos/Ravendwyr/Chinchilla/contents" "github-repo-contents--many-assets-many-gametracks.json" 

# tukui api
dl "https://www.tukui.org/api.php?addon=98" "tukui--addon-details.json"
dl "https://www.tukui.org/api.php?classic-addon=13" "tukui--classic-addon-details.json"
dl "https://www.tukui.org/api.php?ui=tukui" "tukui--tukui-addon-proper.json"
dl "https://www.tukui.org/api.php?ui=elvui" "tukui--elvui-addon-proper.json"
