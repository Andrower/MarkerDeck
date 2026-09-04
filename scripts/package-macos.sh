#!/bin/zsh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:-1.5.0}"
ARTIFACT_DIR="${ARTIFACT_DIR:-$ROOT/artifacts}"
PACKAGE_NAME="markerdeck-macos-arm64-v$VERSION"
STAGING_DIR="$(mktemp -d "${TMPDIR:-/tmp}/markerdeck-macos.XXXXXX")"
PACKAGE_DIR="$STAGING_DIR/$PACKAGE_NAME"
ARCHIVE="$ARTIFACT_DIR/$PACKAGE_NAME.zip"

cleanup() {
  rm -rf "$STAGING_DIR"
}
trap cleanup EXIT

mkdir -p "$PACKAGE_DIR/app" "$PACKAGE_DIR/runtime/node-macos" "$ARTIFACT_DIR"
/usr/bin/ditto "$ROOT/src" "$PACKAGE_DIR/app"
if [ ! -f "$ROOT/node_modules/bonjour-service/package.json" ]; then
  echo "bonjour-service is missing. Run npm ci --omit=dev before packaging."
  exit 1
fi
/usr/bin/ditto "$ROOT/node_modules" "$PACKAGE_DIR/app/node_modules"
/usr/bin/ditto "$ROOT/platform/macos/start-markerdeck-server.command" "$PACKAGE_DIR/start-markerdeck-server.command"
/usr/bin/ditto "$ROOT/docs/macos.md" "$PACKAGE_DIR/README.md"

NODE_SOURCE="${NODE_MACOS_BIN:-$ROOT/runtime/macos/node-macos/node}"
if [ ! -x "$NODE_SOURCE" ]; then
  NODE_SOURCE="$(command -v node || true)"
fi
if [ -z "$NODE_SOURCE" ] || [ ! -x "$NODE_SOURCE" ]; then
  echo "Node.js runtime not found. Set NODE_MACOS_BIN or prepare runtime/macos/node-macos/node."
  exit 1
fi
/usr/bin/ditto "$NODE_SOURCE" "$PACKAGE_DIR/runtime/node-macos/node"

FFMPEG_SOURCE="${FFMPEG_MACOS_BIN:-$ROOT/runtime/macos/ffmpeg-macos/ffmpeg}"
if [ -x "$FFMPEG_SOURCE" ]; then
  mkdir -p "$PACKAGE_DIR/runtime/ffmpeg-macos"
  /usr/bin/ditto "$FFMPEG_SOURCE" "$PACKAGE_DIR/runtime/ffmpeg-macos/ffmpeg"
fi

chmod +x "$PACKAGE_DIR/start-markerdeck-server.command" "$PACKAGE_DIR/runtime/node-macos/node"
if [ -f "$PACKAGE_DIR/runtime/ffmpeg-macos/ffmpeg" ]; then
  chmod +x "$PACKAGE_DIR/runtime/ffmpeg-macos/ffmpeg"
fi

COPYFILE_DISABLE=1 /usr/bin/ditto -c -k --norsrc --noextattr --keepParent "$PACKAGE_DIR" "$ARCHIVE"
echo "$ARCHIVE"
