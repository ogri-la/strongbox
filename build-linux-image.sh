#!/bin/bash
# creates a custom JRE and self-contained launcher for application using AppImage
set -e

output_dir="custom-jre"
rm -rf "./$output_dir"

echo "--- building custom JRE ---"

# compress=1 'constant string sharing' compresses better eventually than compress=2 'zip', 52MB -> 45MB
# - https://docs.oracle.com/en/java/javase/19/docs/specs/man/jlink.html#plugin-compress
jlink \
    --add-modules "java.sql,java.naming,java.desktop,jdk.unsupported,jdk.crypto.ec" \
    --output "$output_dir" \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=1

# needed when built using Ubuntu as libjvm.so is *huge*
# doesn't seem to hurt to strip the other .so files.
find -name "*.so" | xargs strip --preserve-dates --strip-unneeded

du -sh "$output_dir"

echo
echo "--- building app ---"
lein clean
rm -f resources/full-catalogue.json
wget https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/full-catalogue.json \
    --quiet \
    --directory-prefix resources
lein uberjar

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
cp AppImage/strongbox.desktop AppDir/
cp resources/strongbox.svg resources/strongbox.png AppDir/
cp AppImage/AppRun AppDir/
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
