@echo off
setlocal
cd /d "%~dp0"

if "%PORT%"=="" set PORT=8765

set "NODE_EXE=%~dp0node-windows\node.exe"
if not exist "%NODE_EXE%" (
  echo Node.js not found.
  echo Missing bundled node.exe in node-windows\.
  pause
  exit /b 1
)

set "FFMPEG_EXE=%~dp0ffmpeg-windows\ffmpeg.exe"
if exist "%FFMPEG_EXE%" (
  set "FFMPEG_PATH=%FFMPEG_EXE%"
) else (
  where ffmpeg >nul 2>nul
  if not errorlevel 1 (
    for /f "delims=" %%F in ('where ffmpeg') do if not defined FFMPEG_PATH set "FFMPEG_PATH=%%F"
  ) else (
    echo Warning: FFmpeg not found. PNG export works, but MP4 export is unavailable.
  )
)

if not exist chroma-control-server.js (
  echo Missing chroma-control-server.js.
  pause
  exit /b 1
)

set LAUNCH_URL=http://localhost:%PORT%/chroma-launch.html
set CONTROL_URL=http://localhost:%PORT%/chroma-cross-screen.html?mode=control
set DISPLAY_URL=http://localhost:%PORT%/chroma-cross-screen.html?mode=display

echo Chroma Cross Server
echo Launch URL: %LAUNCH_URL%
echo Control URL: %CONTROL_URL%
echo Display URL: %DISPLAY_URL%
if defined FFMPEG_PATH echo FFmpeg: %FFMPEG_PATH%
echo.
echo The launch page will show the QR code and buttons for choosing a page.
echo The server log below will also print the LAN display URL.
echo.
echo Keep this window open while using the phones.
echo Press Control-C to stop the service.
echo.

start "" "%LAUNCH_URL%"
"%NODE_EXE%" chroma-control-server.js
pause
