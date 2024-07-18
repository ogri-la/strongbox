#!/bin/bash
set -eu -o pipefail

cmd="$1"

if test ! "$cmd"; then
    echo "command required."
    echo
    echo "available commands:"
    echo "  update-deps            update project dependencies"
    echo "  test                   run application tests"
    echo "  lint                   run linters"
    echo "  update-test-fixtures   update static files used during testing"
    exit 1
fi

shift
rest=$*

if test "$cmd" = "update-deps"; then
    lein ancient
    exit 0

elif test "$cmd" = "test"; then
    # always ratchet *upwards*
    fail_threshold=80
    # this file can't live in src/strongbox because lein-cloverage can't be found during dev.
    # so we copy it in and destroy it afterwards
    cp cloverage.clj src/strongbox/cloverage.clj
    rm -rf ./coverage/ # any coverage reports from previous run

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
    exit 0

elif test "$cmd" = "lint"; then
    if which joker > /dev/null; then
        echo "joker lint"
        joker --lint --working-dir ./
    fi
    echo "cljfmt lint"
    lein cljfmt fix
    echo "eastwood lint"
    lein eastwood
    exit 0

elif test "$cmd" = "update-test-fixtures"; then
    # downloads new test fixtures 
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

    # gitlab api
    dl "https://gitlab.com/api/v4/projects/woblight%2Fnitro" "gitlab-repo--woblight-nitro.json"
    dl "https://gitlab.com/api/v4/projects/woblight%2Fnitro/releases" "gitlab-repo-releases--woblight-nitro.json"
    dl "https://gitlab.com/api/v4/projects/woblight%2Fnitro/repository/tree" "gitlab-repo-tree--woblight-nitro.json"
    dl "https://gitlab.com/api/v4/projects/thing-engineering%2Fwowthing%2Fwowthing-collector/repository/blobs/125c899d813d2e11c976879f28dccc2a36fd207b" "gitlab-repo-blobs--wowthing.json"
    dl "https://gitlab.com/api/v4/projects/woblight%2Fnitro/repository/tree" "gitlab-repo-tree--woblight-nitro.json"

    # user-catalogue
    dl "https://api.github.com/repos/Stanzilla/AdvancedInterfaceOptions/releases" "user-catalogue--github.json"

    exit 0

# ...

fi

echo "unknown command: $cmd"
exit 1
