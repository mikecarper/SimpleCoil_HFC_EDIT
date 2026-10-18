#!/bin/sh
# One-time Ubuntu setup for the TL-WN722N v2/v3 (USB 2357:010c).
# Build and review the source first. Run this through pkexec or sudo.
set -eu

package=realtek-rtl8188eus
version='5.3.9~20221105+simplecoil1'
module=8188eu
configuration=/etc/modprobe.d/simplecoil-tl-wn722n.conf
destination="/usr/src/$package-$version"

if [ "$(id -u)" -ne 0 ]; then
    printf 'Administrator authentication is required to install the Wi-Fi driver.\n' >&2
    exit 1
fi

if [ "${1:-}" = '--remove' ]; then
    modprobe -r "$module" || true
    if [ -f "$configuration" ]; then
        if ! head -n 1 "$configuration" | grep -qx '# SimpleCoil TL-WN722N driver preference'; then
            printf 'The driver preference file was changed; preserving it.\n' >&2
            exit 1
        fi
        rm -- "$configuration"
    fi
    dkms remove -m "$package" -v "$version" --all
    modprobe rtl8xxxu
    printf 'The original rtl8xxxu driver has been restored.\n'
    exit 0
fi

if [ "$#" -ne 1 ]; then
    printf 'Usage: %s /path/to/reviewed/rtl8188eus/source\n' "$0" >&2
    printf 'Undo:  %s --remove\n' "$0" >&2
    exit 2
fi

source_dir=$(readlink -f -- "$1")
kernel=$(uname -r)
test -f "$source_dir/8188eu.ko"
test -f "$source_dir/dkms.conf"
grep -q '^#define CONFIG_AP_MODE' "$source_dir/include/autoconf.h"
grep -q 'USB_DEVICE(0x2357, 0x010c)' "$source_dir/os_dep/linux/usb_intf.c"
grep -qx "PACKAGE_VERSION=\"$version\"" "$source_dir/dkms.conf"
if [ "$(modinfo -F vermagic "$source_dir/8188eu.ko" | cut -d ' ' -f 1)" != "$kernel" ]; then
    printf 'Build the driver for the running kernel before installing it.\n' >&2
    exit 1
fi
if [ -f "$configuration" ] && ! head -n 1 "$configuration" | grep -qx '# SimpleCoil TL-WN722N driver preference'; then
    printf 'An existing driver preference file has different contents; preserving it.\n' >&2
    exit 1
fi

if [ ! -d "$destination" ]; then
    install -d -m 755 "$destination"
    # Copy reviewed source, not a link to a user-writable build directory.
    tar -C "$source_dir" --exclude=.git --exclude='*.o' --exclude='*.ko' --exclude='*.cmd' \
        --exclude=Module.symvers --exclude=modules.order -cf - . | tar -C "$destination" -xf -
    chown -R root:root "$destination"
fi
if ! dkms status -m "$package" -v "$version" | grep -q "$package"; then
    dkms add -m "$package" -v "$version"
fi
if ! dkms status -m "$package" -v "$version" -k "$kernel" | grep -q ': installed'; then
    dkms install -m "$package" -v "$version" -k "$kernel"
fi

printf '%s\n' '# SimpleCoil TL-WN722N driver preference' \
    'softdep rtl8xxxu pre: 8188eu' > "$configuration"
chmod 644 "$configuration"

restore_on_failure() {
    rm -f -- "$configuration"
    modprobe -r "$module" 2>/dev/null || true
    modprobe rtl8xxxu || true
}
if ! modprobe -r rtl8xxxu; then
    restore_on_failure
    exit 1
fi
if ! modprobe "$module"; then
    restore_on_failure
    exit 1
fi
udevadm settle
printf 'The AP-capable TP-Link driver is installed and loaded.\n'
printf 'DKMS will rebuild it when the kernel is updated.\n'
