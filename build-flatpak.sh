#!/bin/bash
# creates a custom JRE and self-contained launcher for application using AppImage
set -e

ARCH=x86_64

output_dir="flatpak"
#rm -rf "./$output_dir"

if [ ! -d target ]; then
    echo "--- building uberjar ---"
    lein clean
    rm -f resources/full-catalogue.json
    wget https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/full-catalogue.json \
        --quiet \
        --directory-prefix resources
    lein uberjar
fi

cp ./target/*-standalone.jar "$output_dir/app.jar"

# ----------

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

flatpak install \
    --noninteractive \
    --arch "$ARCH" \
    flathub \
    org.freedesktop.Platform//23.08 \
    org.freedesktop.Sdk//23.08 \
    org.freedesktop.Sdk.Extension.openjdk11//23.08

(
    cd flatpak
    
    build_dir="flatpak" # strongbox/flatpak/flatpak
    manifest="org.flatpak.strongbox.yml"

    if [ ! -f "$manifest" ]; then
        echo "manifest not found, cannot continue"
        exit 1
    fi

    rm -rf flatpak .flatpak-builder
    echo "--- building"
    flatpak-builder "$build_dir" "$manifest"
    echo "--- installing"
    flatpak-builder --user --install --force-clean "$build_dir" "$manifest"
    echo "--- running"
    flatpak run --socket=x11 org.flatpak.strongbox
)

echo
echo "--- cleaning up ---"

echo
echo "done."
