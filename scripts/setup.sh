#!/usr/bin/env bash
# setup.sh - Download TerraPlusMinus compile-time dependency
# Usage: bash scripts/setup.sh

set -euo pipefail

# Read version from pom.xml
TPM_VERSION=$(grep -A1 'terraplusminus' pom.xml | grep '<version>' | sed 's/.*<version>\([^<]*\)<\/version>.*/\1/')

if [ -z "$TPM_VERSION" ]; then
    echo "Error: Could not parse TerraPlusMinus version from pom.xml" >&2
    exit 1
fi

JAR_NAME="terraplusminus-${TPM_VERSION}.jar"
DOWNLOAD_URL="https://github.com/BTE-Germany/TerraPlusMinus/releases/download/v${TPM_VERSION}/${JAR_NAME}"
DEST_DIR="$(cd "$(dirname "$0")/.." && pwd)/libs"
DEST_FILE="${DEST_DIR}/${JAR_NAME}"

# Skip if already exists
if [ -f "$DEST_FILE" ]; then
    echo -e "\033[32mAlready exists: ${JAR_NAME}\033[0m"
    exit 0
fi

# Create libs/ directory
mkdir -p "$DEST_DIR"

# Download
echo -e "\033[36mDownloading ${JAR_NAME} ...\033[0m"
if ! curl -fSL -o "$DEST_FILE" "$DOWNLOAD_URL"; then
    echo "Download failed. Please download manually from: $DOWNLOAD_URL" >&2
    exit 1
fi

SIZE_KB=$(( $(wc -c < "$DEST_FILE") / 1024 ))
echo -e "\033[32mDownloaded: ${JAR_NAME} ($(( SIZE_KB / 1024 )) MB)\033[0m"
