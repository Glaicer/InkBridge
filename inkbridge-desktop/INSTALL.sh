#!/bin/bash
set -e

# InkBridge v1.0.0 - Linux Setup Script
# This script installs the app binary, udev rules and configures permissions.

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$SCRIPT_DIR"

echo "🎨 Starting InkBridge Setup..."

# 1. Check for root/sudo
if [ "$EUID" -ne 0 ]; then
  echo "Please run as root (use sudo ./INSTALL.sh)"
  exit 1
fi

# 2. Install the application binary
if [ ! -x build/InkBridge ]; then
  echo "Build inkbridge-desktop/build/InkBridge from source first (see README.md)."
  exit 1
fi
echo "📦 Installing InkBridge binary..."
install -Dm755 build/InkBridge /usr/bin/inkbridge

# 3. Install udev rules
echo "📦 Installing udev rules..."
cp assets/99-inkbridge.rules /etc/udev/rules.d/
udevadm control --reload-rules
udevadm trigger

# 4. Setup User Groups
# We get the 'real' user if run with sudo
REAL_USER=${SUDO_USER:-$USER}

echo "👥 Adding $REAL_USER to 'input' and 'plugdev' groups..."
groupadd -f input
groupadd -f plugdev
usermod -aG input "$REAL_USER"
usermod -aG plugdev "$REAL_USER"

# 5. Install Desktop Entry & Icon
echo "🖥️  Installing Desktop shortcut..."
mkdir -p /usr/share/icons/hicolor/512x512/apps
cp assets/inkbridge.png /usr/share/icons/hicolor/512x512/apps/
cp assets/inkbridge.desktop /usr/share/applications/

echo "✅ Setup Complete!"
echo "⚠️  IMPORTANT: Please log out and log back in for group changes to take effect."
echo "🚀 You can now find InkBridge in your Application Launcher."
