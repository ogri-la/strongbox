#!/bin/bash
# creates a custom JRE and self-contained launcher for application using AppImage
set -ex

echo
echo "--- building Flatpak ---"
if ! command -v flatpak > /dev/null; then
    echo "flatpak not found, cannot continue"
    exit 1
fi
if ! command -v flatpak-builder > /dev/null; then
    echo "flatpak-builder not found, cannot continue"
    exit 1
fi

# ---

ARCH=x86_64

rm -rf "./target"

# 'lein clean' may delete jar files within ./target, but preserve ./target dir
if [ ! -e target/*-standalone.jar ]; then
    echo "--- building uberjar ---"
    lein clean
    rm -f resources/full-catalogue.json
    wget https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/full-catalogue.json \
        --quiet \
        --directory-prefix resources
    lein uberjar
fi

# ---

output_dir="flatpak"

rm -rf "./$output_dir"

mkdir -p "./$output_dir"
cp ./target/*-standalone.jar "$output_dir/app.jar"

# ---

echo "--- installing Flatpak runtime"
flatpak install \
    --noninteractive \
    --arch "$ARCH" \
    flathub \
    org.freedesktop.Platform//23.08 \
    org.freedesktop.Sdk//23.08 \
    org.freedesktop.Sdk.Extension.openjdk11//23.08

cp la.ogri.strongbox.yml la.ogri.strongbox.metainfo.xml flatpak/

(
    cd flatpak

    build_dir="flatpak" # strongbox/flatpak/flatpak
    appid="la.ogri.strongbox"
    manifest="$appid.yml"
    tag="master"
    output_filename="strongbox.flatpak"

    if [ ! -f "$manifest" ]; then
        echo "manifest not found, cannot continue"
        exit 1
    fi

    # todo: add 'Appdata': application metadata, like version etc

    echo "--- building"
    # export flatpak to local repo
    flatpak-builder --user --repo ./repo --force-clean "$build_dir" "$manifest"

    echo "--- installing"
    flatpak install --user --reinstall --noninteractive ./repo "$appid"

    # create a standalone .flatpak 'bundle' file. requires a repo.
    #flatpak build-bundle ./repo "$output_filename" "$appid" "$tag"

    # uninstall/install
    #flatpak --user list # list installed
    #flatpak uninstall --user --noninteractive "$appid"
    #flatpak install --user --reinstall --noninteractive "$output_filename"
)

echo
echo "--- cleaning up ---"

echo
echo "done."
