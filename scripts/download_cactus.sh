#!/usr/bin/env bash
# ==============================================================================
# Download Cactus Compute's android-arm64 engine (libcactus.a + cactus.h)
# and on-device model weights:
#   - whistle.cact (Whistle speech-to-text, 16.9 MB)
#   - needle3.cact (Needle 3 tool-calling, 29 MB)
#
# Usage:
#   ./scripts/download_cactus.sh [--version 4.1.0] [--layers 20] [--use-cli]
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

CACTUS_VERSION="${CACTUS_VERSION:-4.1.0}"
CACTUS_ENGINE_REPO="https://huggingface.co/Cactus-Compute/cactus/resolve/main"
WHISTLE_REPO="https://huggingface.co/Cactus-Compute/whistle/resolve/main"
NEEDLE3_REPO="https://huggingface.co/Cactus-Compute/needle3/resolve/main"

PREBUILT_DIR="${ROOT_DIR}/app/src/main/cpp/prebuilt/arm64-v8a"
INCLUDE_DIR="${ROOT_DIR}/app/src/main/cpp/include"
ASSETS_MODELS_DIR="${ROOT_DIR}/app/src/main/assets/models"

USE_CLI=0
NEEDLE_LAYERS=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version)
            CACTUS_VERSION="$2"
            shift 2
            ;;
        --layers)
            NEEDLE_LAYERS="$2"
            shift 2
            ;;
        --use-cli)
            USE_CLI=1
            shift
            ;;
        -h|--help)
            echo "Usage: $0 [--version <ver>] [--layers <2..20>] [--use-cli]"
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

mkdir -p "${PREBUILT_DIR}" "${INCLUDE_DIR}" "${ASSETS_MODELS_DIR}"

download_file() {
    local url="$1"
    local dest="$2"
    local label="$3"

    echo "==> Downloading ${label}..."
    echo "    URL : ${url}"
    echo "    Dest: ${dest}"
    if command -v curl >/dev/null 2>&1; then
        curl -fL --retry 3 --retry-delay 2 -o "${dest}.tmp" "${url}"
    elif command -v wget >/dev/null 2>&1; then
        wget -q --show-progress -O "${dest}.tmp" "${url}"
    else
        echo "Error: neither curl nor wget is installed." >&2
        exit 1
    fi
    mv "${dest}.tmp" "${dest}"
}

if [[ "${USE_CLI}" -eq 1 ]] && command -v cactus >/dev/null 2>&1; then
    echo "==> Using cactus CLI to fetch android-arm64 runtime and weights..."
    TMP_RUNTIME="$(mktemp -d)"
    trap 'rm -rf "${TMP_RUNTIME}"' EXIT

    if [[ -n "${NEEDLE_LAYERS}" ]]; then
        cactus build --platform android-arm64 --layers "${NEEDLE_LAYERS}" --out "${TMP_RUNTIME}/needle"
        cp "${TMP_RUNTIME}/needle/libcactus.a" "${PREBUILT_DIR}/libcactus.a"
        cp "${TMP_RUNTIME}/needle/cactus.h" "${INCLUDE_DIR}/cactus.h"
        cp "${TMP_RUNTIME}/needle/needle3.cact" "${ASSETS_MODELS_DIR}/needle3.cact"
    else
        cactus download android-arm64 --model needle3 --out "${TMP_RUNTIME}"
        cp "${TMP_RUNTIME}/android-arm64/libcactus.a" "${PREBUILT_DIR}/libcactus.a"
        cp "${TMP_RUNTIME}/android-arm64/cactus.h" "${INCLUDE_DIR}/cactus.h"
        cp "${TMP_RUNTIME}/android-arm64/needle3.cact" "${ASSETS_MODELS_DIR}/needle3.cact"
    fi

    cactus download whistle --out "${ASSETS_MODELS_DIR}"
else
    ENGINE_BASE="${CACTUS_ENGINE_REPO}/releases/${CACTUS_VERSION}/android-arm64"

    download_file \
        "${ENGINE_BASE}/libcactus.a" \
        "${PREBUILT_DIR}/libcactus.a" \
        "Cactus android-arm64 static engine (libcactus.a v${CACTUS_VERSION})"

    download_file \
        "${ENGINE_BASE}/cactus.h" \
        "${INCLUDE_DIR}/cactus.h" \
        "Cactus C API header (cactus.h v${CACTUS_VERSION})"

    download_file \
        "${WHISTLE_REPO}/whistle.cact" \
        "${ASSETS_MODELS_DIR}/whistle.cact" \
        "Whistle speech-to-text weights (whistle.cact)"

    download_file \
        "${NEEDLE3_REPO}/needle3.cact" \
        "${ASSETS_MODELS_DIR}/needle3.cact" \
        "Needle 3 tool-calling weights (needle3.cact)"
fi

echo ""
echo "==> Verifying downloaded artifacts..."
for f in \
    "${PREBUILT_DIR}/libcactus.a" \
    "${INCLUDE_DIR}/cactus.h" \
    "${ASSETS_MODELS_DIR}/whistle.cact" \
    "${ASSETS_MODELS_DIR}/needle3.cact"; do
    if [[ -f "$f" ]]; then
        size=$(wc -c < "$f" | tr -d ' ')
        if command -v sha256sum >/dev/null 2>&1; then
            sha=$(sha256sum "$f" | awk '{print $1}')
        elif command -v shasum >/dev/null 2>&1; then
            sha=$(shasum -a 256 "$f" | awk '{print $1}')
        else
            sha="n/a"
        fi
        echo "  [OK] $(basename "$f") (${size} bytes, sha256=${sha:0:16}...)"
    else
        echo "  [MISSING] $f" >&2
        exit 1
    fi
done

echo ""
echo "All Cactus Compute android-arm64 runtime and .cact weights are ready!"
