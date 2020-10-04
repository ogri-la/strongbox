#!/bin/bash
# creates a custom JRE and launcher for application uberjar
set -e

output_dir="linux-image"
rm -rf "./$output_dir"


echo "building custom JRE for app"
# note: --module-path is redundant, jlink will add the path to the JDK jmods directory containing java.base
# I'm specifying it for explicitness
jlink \
    --module-path "/usr/lib/jvm/java-14-openjdk/jmods" \
    --add-modules "java.sql,java.naming,java.desktop,jdk.unsupported,jdk.crypto.ec" \
    --output "$output_dir" \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=2
du -sh "$output_dir"


echo "building app"
lein uberjar
cp target/*-standalone.jar "$output_dir/uberjar.jar"


echo "building AppImage"
if [ ! -e appimagetool ]; then
    wget \
        -c "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage" \
        -o appimagetool
    mv appimagetool-x86_64.AppImage appimagetool
    chmod +x appimagetool
fi
rm -rf ./AppDir
mkdir AppDir
mv "$output_dir" AppDir/usr
cp strongbox.desktop AppDir/
cp strongbox.png AppDir/
cp AppRun AppDir/
du -sh AppDir/
ARCH=x86_64 ./appimagetool AppDir strongbox
du -sh strongbox


echo "cleaning up"
rm -rf AppDir
lein clean
