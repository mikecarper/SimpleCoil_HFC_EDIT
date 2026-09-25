#!/usr/bin/env sh
# Build and run the dependency-free desktop dedicated host with the JDK already
# required by the Android project. Extra command-line options are passed through.
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output_dir="$script_dir/out"
source_file="$script_dir/src/main/java/com/simplecoil/laptophost/LaptopHost.java"

mkdir -p "$output_dir"
javac --release 17 --add-modules jdk.httpserver -d "$output_dir" "$source_file"
exec java --add-modules jdk.httpserver -Dsimplecoil.home="$script_dir" -cp "$output_dir" \
    com.simplecoil.laptophost.LaptopHost "$@"
