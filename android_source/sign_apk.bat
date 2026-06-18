@echo off
cd /d "G:\Meu Drive\LUCAS\1\MirrorX"
C:\Android\build-tools\34.0.0\apksigner.bat sign ^
  --ks "G:\Meu Drive\LUCAS\1\mirrorx.keystore" ^
  --ks-pass pass:mirrorx ^
  --ks-key-alias mirrorx ^
  --out "app\build\outputs\apk\release\MirrorX_v1.4.3.apk" ^
  "app\build\outputs\apk\release\MirrorX_v1.4.3_aligned.apk"
echo EXIT CODE: %ERRORLEVEL%
C:\Android\build-tools\34.0.0\apksigner.bat verify --print-certs "app\build\outputs\apk\release\MirrorX_v1.4.3.apk"
echo VERIFY EXIT CODE: %ERRORLEVEL%
