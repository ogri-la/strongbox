#!/bin/bash
# creates a custom JRE and self-contained launcher for application using AppImage
set -e

output_dir="custom-jre"
rm -rf "./$output_dir"

echo "--- building custom JRE ---"
jlink \
    --add-modules "java.sql,java.naming,java.desktop,jdk.unsupported,jdk.crypto.ec" \
    --output "$output_dir" \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=2
du -sh "$output_dir"

if [ ! -f ./target/*-standalone.jar ]; then
    echo
    echo "--- building app ---"
    lein uberjar
fi
cp ./target/*-standalone.jar "$output_dir/app.jar"

echo
echo "--- building AppImage ---"
if [ ! -e appimagetool ]; then
    wget \
        -c "https://github.com/AppImage/AppImageKit/releases/download/12/appimagetool-x86_64.AppImage" \
        -o appimagetool
    mv appimagetool-x86_64.AppImage appimagetool
    chmod +x appimagetool
fi
rm -rf ./AppDir
mkdir AppDir
mv "$output_dir" AppDir/usr
cp strongbox.desktop AppDir/
cp resources/strongbox.svg resources/strongbox.png AppDir/
cp AppRun AppDir/
du -sh AppDir/
rm -f strongbox.appimage # safer than 'rm -f strongbox'
ARCH=x86_64 ./appimagetool AppDir/ strongbox.appimage
mv strongbox.appimage strongbox
du -sh strongbox

echo
echo "--- cleaning up ---"
rm -rf AppDir

echo
echo "done."
