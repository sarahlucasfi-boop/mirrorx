@echo off
REM ============================================================
REM MirrorX Build + Push (v1.6.3)
REM Faz tudo: build APK + sign + build EXE + deploy + commit/push
REM Uso: BUILD_AND_PUSH.bat [version] [message]
REM Ex:  BUILD_AND_PUSH.bat 1.6.3 "fix: cursor visibility"
REM ============================================================

setlocal enabledelayedexpansion

REM ---------- CONFIG ----------
set VERSION=%1
if "%VERSION%"=="" set VERSION=1.6.4
set MSG=%2
if "%MSG%"=="" set MSG=v%VERSION%: build + push

set PROJECT_DIR=C:\Users\adm\Projects\mirrorx_hermes
set ANDROID_DIR=%PROJECT_DIR%\android_source
set OUTPUT_DIR=G:\Meu Drive\LUCAS\1
set DESKTOP_DIR=C:\Users\adm\Desktop
set REPO_DIR=C:\Users\adm\Documents\GitHub\mirrorx
set KEYSTORE=%OUTPUT_DIR%\mirrorx.keystore
set KEY_PASS=mirrorx
set BUILD_TOOLS=C:\Users\adm\Android\sdk\build-tools\34.0.0
set ANDROID_JAR=%BUILD_TOOLS%\apksigner.bat
set ALIGN=%BUILD_TOOLS%\zipalign.exe
set JAVA=C:\Users\adm\Android\jdk-17.0.13+11
set GRADLE=%ANDROID_DIR%\gradle-8.5\bin\gradle.bat
set VENV_PY=C:\Users\adm\Projects\mirrorx_hermes\venv\Scripts\python.exe
set VENV_PYINSTALLER=C:\Users\adm\Projects\mirrorx_hermes\venv\Scripts\pyinstaller.exe

REM ---------- STEP 0: Bump versions ----------
echo.
echo === [STEP 0] Bumping version to %VERSION% ===
cd /d %PROJECT_DIR%
powershell -Command "(Get-Content src\server.py) -replace 'VERSION = \"1.6.0\"', 'VERSION = \"%VERSION%\"' | Set-Content src\server.py"
powershell -Command "(Get-Content src\server_hermes.py) -replace 'VERSION = \"1.6.0\"', 'VERSION = \"%VERSION%\"' | Set-Content src\server_hermes.py"
powershell -Command "(Get-Content src\panel_ui.py) -replace 'version: str = \"1.6.0\"', 'version: str = \"%VERSION%\"' | Set-Content src\panel_ui.py"
powershell -Command "(Get-Content android_source\app\build.gradle.kts) -replace 'versionName = \"1.6.0\"', 'versionName = \"%VERSION%\"' | Set-Content android_source\app\build.gradle.kts"
powershell -Command "(Get-Content android_source\app\build.gradle.kts) -replace 'versionCode = 24', 'versionCode = 25' | Set-Content android_source\app\build.gradle.kts"
echo Bumped to %VERSION%

REM ---------- STEP 1: Build APK ----------
echo.
echo === [STEP 1] Building APK ===
cd /d %ANDROID_DIR%
set JAVA_HOME=%JAVA%
set PATH=%JAVA%\bin;%PATH%
call %GRADLE% assembleRelease --offline
if %ERRORLEVEL% neq 0 (
    echo APK build FAILED
    exit /b 1
)

REM ---------- STEP 2: Sign APK ----------
echo.
echo === [STEP 2] Signing APK ===
set UNSIGNED=%ANDROID_DIR%\app\build\outputs\apk\release\app-release-unsigned.apk
set ALIGNED=%ANDROID_DIR%\app\build\outputs\apk\release\MirrorX_v%VERSION%_aligned.apk
set APK=%OUTPUT_DIR%\MirrorX_v%VERSION%.apk
call %ALIGN% -p -f 4 "%UNSIGNED%" "%ALIGNED%"
call %ANDROID_JAR% sign --ks "%KEYSTORE%" --ks-pass pass:%KEY_PASS% --key-pass pass:%KEY_PASS% --ks-key-alias mirrorx --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true "%ALIGNED%"
copy /Y "%ALIGNED%" "%APK%" >nul
copy /Y "%ALIGNED%" "%DESKTOP_DIR%\MirrorX_v%VERSION%.apk" >nul
echo APK signed: %APK%

REM ---------- STEP 3: Build EXE ----------
echo.
echo === [STEP 3] Building EXE ===
cd /d %PROJECT_DIR%
copy /Y MirrorX_v1.6.0.spec MirrorX_v%VERSION%.spec >nul 2>nul
powershell -Command "(Get-Content MirrorX_v%VERSION%.spec) -replace 'MirrorX_v1.6.0', 'MirrorX_v%VERSION%' | Set-Content MirrorX_v%VERSION%.spec"
if exist build\MirrorX_v%VERSION% rmdir /S /Q build\MirrorX_v%VERSION%
if exist dist\MirrorX_v%VERSION%.exe del dist\MirrorX_v%VERSION%.exe
call %VENV_PYINSTALLER% --noconfirm --clean MirrorX_v%VERSION%.spec
if %ERRORLEVEL% neq 0 (
    echo EXE build FAILED
    exit /b 1
)

REM ---------- STEP 4: Verify MEI + Deploy ----------
echo.
echo === [STEP 4] Validating EXE ===
set EXE=dist\MirrorX_v%VERSION%.exe
%VENV_PY% -c "import struct;d=open(r'%EXE%','rb').read();i=d.rfind(b'MEI\x0c\x0b\x0a\x0b\x0e');print('MEI:','VALID' if i>0 else 'CORRUPT')"

copy /Y "%EXE%" "%OUTPUT_DIR%\MirrorX_v%VERSION%.exe" >nul
copy /Y "%EXE%" "%DESKTOP_DIR%\MirrorX_v%VERSION%.exe" >nul
echo EXE deployed.

REM ---------- STEP 5: Sync files to local repo ----------
echo.
echo === [STEP 5] Syncing source to local Git repo ===
xcopy /Y /E /Q /I src\* "%REPO_DIR%\src\" >nul
copy /Y MirrorX_v%VERSION%.spec "%REPO_DIR%\" >nul
copy /Y PLANO_MIRRORX_1.5.9.txt "%REPO_DIR%\" >nul
copy /Y test_ws.py "%REPO_DIR%\" >nul
copy /Y test_touch.py "%REPO_DIR%\" >nul
copy /Y requirements.txt "%REPO_DIR%\" >nul
xcopy /Y /E /Q /I android_source\* "%REPO_DIR%\android_source\" >nul
if exist "%REPO_DIR%\android_source\.git" rmdir /S /Q "%REPO_DIR%\android_source\.git"
echo Synced.

REM ---------- STEP 6: Git commit + push ----------
echo.
echo === [STEP 6] Commit + push ===
cd /d %REPO_DIR%
git add -A
git status --short | findstr /R "." >nul
if %ERRORLEVEL% neq 0 (
    echo No changes to commit. Skipping.
    goto :end
)
git commit -m "%MSG%"
git push origin master --force
if %ERRORLEVEL% neq 0 (
    echo PUSH FAILED - try: git pull origin master
    exit /b 1
)
echo PUSHED.

:end
echo.
echo === DONE ===
echo APK: %OUTPUT_DIR%\MirrorX_v%VERSION%.apk
echo EXE: %OUTPUT_DIR%\MirrorX_v%VERSION%.exe
echo GitHub: https://github.com/sarahlucasfi-boop/mirrorx
endlocal
