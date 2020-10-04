#!/bin/bash
# creates a custom JRE and launcher for application uberjar
set -ex

output_dir="jlink-output"

rm -rf "./$output_dir"

echo "creating custom runtime for app"
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

echo "writing launcher"
printf "#!/bin/bash\n./bin/java -jar uberjar.jar" > "$output_dir/launcher"
chmod +x "$output_dir/launcher"
