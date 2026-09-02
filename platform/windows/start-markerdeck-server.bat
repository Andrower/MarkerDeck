@echo off
setlocal
set "SCRIPT_DIR=%~dp0"

if exist "%SCRIPT_DIR%app\markerdeck-server.js" (
  set "APP_DIR=%SCRIPT_DIR%app"
  set "RUNTIME_DIR=%SCRIPT_DIR%runtime"
  if "%MARKERDECK_DATA_DIR%"=="" if not "%CHROMA_DATA_DIR%"=="" set "MARKERDECK_DATA_DIR=%CHROMA_DATA_DIR%"
  if "%MARKERDECK_DATA_DIR%"=="" set "MARKERDECK_DATA_DIR=%SCRIPT_DIR%data"
) else (
  for %%I in ("%SCRIPT_DIR%..\..") do set "REPO_ROOT=%%~fI"
  set "APP_DIR=%REPO_ROOT%\src"
  set "RUNTIME_DIR=%REPO_ROOT%\runtime\windows"
  if "%MARKERDECK_DATA_DIR%"=="" if not "%CHROMA_DATA_DIR%"=="" set "MARKERDECK_DATA_DIR=%CHROMA_DATA_DIR%"
  if "%MARKERDECK_DATA_DIR%"=="" set "MARKERDECK_DATA_DIR=%REPO_ROOT%\.data"
)

cd /d "%APP_DIR%"

if "%PORT%"=="" set PORT=8765

set "NODE_EXE=%RUNTIME_DIR%\node-windows\node.exe"
if not exist "%NODE_EXE%" (
  where node >nul 2>nul
  if errorlevel 1 (
    echo Node.js not found.
    echo The portable package should include runtime\node-windows\node.exe.
    pause
    exit /b 1
  )
  set "NODE_EXE=node"
)

set "FFMPEG_EXE=%RUNTIME_DIR%\ffmpeg-windows\ffmpeg.exe"
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

if not exist "%APP_DIR%\markerdeck-server.js" (
  echo Missing application files in %APP_DIR%.
  pause
  exit /b 1
)
if not exist "%APP_DIR%\web\markerdeck-screen.html" (
  echo Missing application files in %APP_DIR%.
  pause
  exit /b 1
)
if not exist "%APP_DIR%\web\markerdeck-launch.html" (
  echo Missing application files in %APP_DIR%.
  pause
  exit /b 1
)

set LAUNCH_URL=http://localhost:%PORT%/markerdeck-launch.html
set CONTROL_URL=http://localhost:%PORT%/markerdeck-screen.html?mode=control
set DISPLAY_URL=http://localhost:%PORT%/markerdeck-screen.html?mode=display

echo MarkerDeck 视效标记屏控服务
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
"%NODE_EXE%" "%APP_DIR%\markerdeck-server.js"
pause
