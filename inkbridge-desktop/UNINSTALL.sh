#!/bin/bash
set -e

# InkBridge Uninstaller — reverses everything inkbridge-desktop/INSTALL.sh does.
# Run with: sudo ./UNINSTALL.sh

if [ "$EUID" -ne 0 ]; then
  echo "Please run as root (use sudo ./UNINSTALL.sh)"
  exit 1
fi

echo "🧹 Removing InkBridge..."

rm -f /usr/bin/inkbridge
rm -f /etc/udev/rules.d/99-inkbridge.rules
rm -f /usr/share/applications/inkbridge.desktop
rm -f /usr/share/icons/hicolor/512x512/apps/inkbridge.png

# Re-apply udev state after rule removal
udevadm control --reload-rules
udevadm trigger

# Refresh Application Launcher caches (wrong one being missing is fine)
command -v update-desktop-database >/dev/null && update-desktop-database /usr/share/applications

# Optional: revoke the group memberships the installer granted.
# Only pass --remove-groups if you didn't belong to 'input'/'plugdev' before.
if [ "$1" == "--remove-groups" ]; then
  REAL_USER=${SUDO_USER:-$USER}
  gpasswd -d "$REAL_USER" input
  gpasswd -d "$REAL_USER" plugdev
  echo "⚠️  Log out and back in for the group removal to take effect."
fi

echo "✅ InkBridge fully removed."
