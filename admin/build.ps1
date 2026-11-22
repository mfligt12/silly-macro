# Builds the Silly Admin app (user list + disable/enable) without Gradle. Uses the same assets/firebase.properties as Silly Macro.
param([switch]$Install)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$sdk = "$env:LOCALAPPDATA\Android\Sdk"; $bt = "$sdk\build-tools\36.0.0"; $jar = "$sdk\platforms\android-36\android.jar"
if (-not (Test-Path ..\assets\firebase.properties)) { throw "Put your Firebase config in ..\assets\firebase.properties first (see firebase.properties.example)." }
Remove-Item build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory build/gen, build/classes, build/dex, assets -Force | Out-Null
Copy-Item ..\assets\firebase.properties assets\ -Force
& "$bt\aapt2.exe" compile --dir res -o build/res.zip
& "$bt\aapt2.exe" link -o build/base.apk -I $jar --manifest AndroidManifest.xml -A assets --java build/gen --min-sdk-version 26 --target-sdk-version 36 build/res.zip
if ($LASTEXITCODE) { throw "aapt2 failed" }
$src = @(Get-ChildItem src, build/gen -Recurse -Filter *.java | ForEach-Object FullName) + (Resolve-Path ..\src\com\iipxp\beemacro\Fb.java).Path
javac --release 17 -encoding UTF-8 -classpath $jar -d build/classes $src
if ($LASTEXITCODE) { throw "javac failed" }
jar cf build/classes.jar -C build/classes .
& "$bt\d8.bat" --release --min-api 26 --lib $jar --output build/dex build/classes.jar
if ($LASTEXITCODE) { throw "d8 failed" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::Open("$PWD\build\base.apk", 'Update')
[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($z, "$PWD\build\dex\classes.dex", 'classes.dex') | Out-Null
$z.Dispose()
& "$bt\zipalign.exe" -f -p 4 build/base.apk build/aligned.apk
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out SillyAdmin.apk build/aligned.apk
if ($LASTEXITCODE) { throw "signing failed" }
Write-Host "Built SillyAdmin.apk"
if ($Install) { adb install -r SillyAdmin.apk }
