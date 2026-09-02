#!/bin/zsh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ -f "$SCRIPT_DIR/app/markerdeck-server.js" ]; then
  APP_DIR="$SCRIPT_DIR/app"
  RUNTIME_DIR="$SCRIPT_DIR/runtime"
  export MARKERDECK_DATA_DIR="${MARKERDECK_DATA_DIR:-${CHROMA_DATA_DIR:-$SCRIPT_DIR/data}}"
else
  REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
  APP_DIR="$REPO_ROOT/src"
  RUNTIME_DIR="$REPO_ROOT/runtime/macos"
  export MARKERDECK_DATA_DIR="${MARKERDECK_DATA_DIR:-${CHROMA_DATA_DIR:-$REPO_ROOT/.data}}"
fi

cd "$APP_DIR"

NODE_BIN="$RUNTIME_DIR/node-macos/node"
if [ ! -x "$NODE_BIN" ]; then
  if command -v node >/dev/null 2>&1; then
    NODE_BIN="$(command -v node)"
  else
    echo "Node.js not found."
    echo "The portable package should include runtime/node-macos/node. Install Node.js if the runtime was removed."
    read -r "?Press Enter to close..."
    exit 1
  fi
fi

FFMPEG_BIN="$RUNTIME_DIR/ffmpeg-macos/ffmpeg"
if [ ! -x "$FFMPEG_BIN" ]; then
  if [ -x "/opt/homebrew/bin/ffmpeg" ]; then
    FFMPEG_BIN="/opt/homebrew/bin/ffmpeg"
  elif [ -x "/usr/local/bin/ffmpeg" ]; then
    FFMPEG_BIN="/usr/local/bin/ffmpeg"
  elif command -v ffmpeg >/dev/null 2>&1; then
    FFMPEG_BIN="$(command -v ffmpeg)"
  else
    FFMPEG_BIN=""
  fi
fi
if [ -n "$FFMPEG_BIN" ]; then
  export FFMPEG_PATH="$FFMPEG_BIN"
else
  echo "Warning: FFmpeg not found. PNG export works, but MP4 export is unavailable."
fi

BASE_PORT="${PORT:-8765}"
if [ ! -f "$APP_DIR/markerdeck-server.js" ] || [ ! -f "$APP_DIR/web/markerdeck-screen.html" ] || [ ! -f "$APP_DIR/web/markerdeck-launch.html" ]; then
  echo "Missing application files in $APP_DIR."
  read -r "?Press Enter to close..."
  exit 1
fi

find_free_port() {
  local start="$1"
  local end=$((start + 25))
  local port
  for port in $(seq "$start" "$end"); do
    if ! nc -z 127.0.0.1 "$port" >/dev/null 2>&1; then
      echo "$port"
      return 0
    fi
  done
  echo "$start"
}

PORT="$(find_free_port "$BASE_PORT")"
export PORT

LAN_IP="$("$NODE_BIN" -e 'const os=require("os"); for (const list of Object.values(os.networkInterfaces())) for (const item of list || []) if (item.family==="IPv4" && !item.internal) { console.log(item.address); process.exit(0); } console.log("127.0.0.1");')"
LAUNCH_URL="http://localhost:$PORT/markerdeck-launch.html"
MOBILE_URL="http://$LAN_IP:$PORT/markerdeck-launch.html"
DISPLAY_URL="http://$LAN_IP:$PORT/markerdeck-screen.html?mode=display"
CONTROL_URL="http://$LAN_IP:$PORT/markerdeck-screen.html?mode=control"

echo "MarkerDeck 视效标记屏控服务"
echo "Files: $APP_DIR"
echo "Local URL: $LAUNCH_URL"
echo "Mobile URL: $MOBILE_URL"
echo "Display URL: $DISPLAY_URL"
echo "Control URL: $CONTROL_URL"
if [ -n "$FFMPEG_BIN" ]; then echo "FFmpeg: $FFMPEG_BIN"; fi
echo
echo "Keep this window open while using the phones."
echo "Press Control-C to stop the service."
echo

(sleep 1; open "$LAUNCH_URL" >/dev/null 2>&1 || true) &
"$NODE_BIN" "$APP_DIR/markerdeck-server.js"
