#!/usr/bin/env sh
# Build and run the dependency-free desktop dedicated host with the JDK already
# required by the Android project. Extra command-line options are passed through.
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output_dir="$script_dir/out"
source_file="$script_dir/src/main/java/com/simplecoil/laptophost/LaptopHost.java"

mkdir -p "$output_dir"
javac --release 17 --add-modules jdk.httpserver -d "$output_dir" "$source_file" \
    "$script_dir/src/main/java/com/simplecoil/laptophost/HotspotManager.java" \
    "$script_dir/src/main/java/com/simplecoil/laptophost/EventSettings.java" \
    "$script_dir/src/main/java/com/simplecoil/laptophost/PlayerDirectory.java" \
    "$script_dir/src/main/java/com/simplecoil/laptophost/PhoneUpdateCache.java" \
    "$script_dir/src/main/java/com/simplecoil/laptophost/AdbPhoneUpdater.java" \
    "$script_dir/../shared/src/main/java/com/simplecoil/protocol/EventMode.java" \
    "$script_dir/../shared/src/main/java/com/simplecoil/protocol/RelayConfig.java" \
    "$script_dir/../shared/src/main/java/com/simplecoil/protocol/RelayMesh.java" \
    "$script_dir/../shared/src/main/java/com/simplecoil/protocol/GunBatteryHistory.java" \
    "$script_dir/../shared/src/main/java/com/simplecoil/protocol/GameStartCountdown.java" \
    "$script_dir/../shared/src/main/java/com/simplecoil/protocol/Powerups.java" \
    "$script_dir/../shared/src/main/java/com/simplecoil/protocol/GpsOffset.java" \
    "$script_dir/../shared/src/main/java/com/simplecoil/protocol/RoundRoster.java" \
    "$script_dir/../shared/src/main/java/com/simplecoil/protocol/RoundRestart.java" \
    "$script_dir/../shared/src/main/java/com/simplecoil/protocol/RoundStatistics.java"
exec java --add-modules jdk.httpserver -Dsimplecoil.home="$script_dir" -cp "$output_dir" \
    com.simplecoil.laptophost.LaptopHost "$@"
