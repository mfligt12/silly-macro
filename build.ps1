# Builds Bee Macro without Gradle: self-test -> aapt2 -> javac -> d8 -> zipalign -> apksigner [-> adb install]
param([switch]$Install)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt  = "$sdk\build-tools\36.0.0"
$jar = "$sdk\platforms\android-36\android.jar"

Remove-Item build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory build/gen, build/classes, build/dex, build/test | Out-Null

# 1. desktop tests (vision on real screenshots, Skillet port, profile)
javac -encoding UTF-8 -d build/test src/com/iipxp/beemacro/Moves.java src/com/iipxp/beemacro/RecordedRoutes.java src/com/iipxp/beemacro/RecordedKeys.java src/com/iipxp/beemacro/Profile.java src/com/iipxp/beemacro/Vision.java src/com/iipxp/beemacro/Remap.java src/com/iipxp/beemacro/NavGuard.java src/com/iipxp/beemacro/FieldMap.java src/com/iipxp/beemacro/HoneyOcr.java src/com/iipxp/beemacro/Patterns.java src/com/iipxp/beemacro/Routes.java src/com/iipxp/beemacro/SprinklerFinder.java src/com/iipxp/beemacro/Catalog.java src/com/iipxp/beemacro/StackOcr.java src/com/iipxp/beemacro/Buffs.java src/com/iipxp/beemacro/Detector.java src/com/iipxp/beemacro/VisualAnchor.java src/com/iipxp/beemacro/FieldMessages.java src/com/iipxp/beemacro/Report.java src/com/iipxp/beemacro/HidKeys.java src/com/iipxp/beemacro/SenderStatus.java src/com/iipxp/beemacro/Playlists.java src/com/iipxp/beemacro/AiBandit.java src/com/iipxp/beemacro/AiBrain.java src/com/iipxp/beemacro/AiPower.java src/com/iipxp/beemacro/FrameWire.java test/BuildCatalog.java test/SelfTest.java test/AiSelfTest.java
if ($LASTEXITCODE) { throw "test compile failed" }
java -cp build/test AiSelfTest; if ($LASTEXITCODE) { throw "AI self-test failed" }
# the game-art catalog and screenshot fixtures are not in the public repo: skipped when missing (the app then runs without buff/token icons)
if (Test-Path catalog/src) { java -cp build/test BuildCatalog; if ($LASTEXITCODE) { throw "catalog build failed" } }
if (Test-Path test/fixtures) { java -cp build/test SelfTest; if ($LASTEXITCODE) { throw "self-test failed" } }

# 2. android build
& "$bt\aapt2.exe" compile --dir res -o build/res.zip
& "$bt\aapt2.exe" link -o build/base.apk -I $jar --manifest AndroidManifest.xml -A assets --java build/gen --min-sdk-version 28 --target-sdk-version 36 build/res.zip
if ($LASTEXITCODE) { throw "aapt2 failed" }
$src = Get-ChildItem src, build/gen -Recurse -Filter *.java | ForEach-Object FullName
$libs = Get-ChildItem libs -Filter *.jar | ForEach-Object FullName   # Shizuku API 13.1.5 (Apache-2.0), from Maven Central
javac --release 17 -encoding UTF-8 -Xlint:-deprecation -classpath ((@($jar) + $libs) -join ';') -d build/classes $src
if ($LASTEXITCODE) { throw "javac failed" }
jar cf build/classes.jar -C build/classes .   # one input: listing every .class overflows the Windows command line
$cls = "build/classes.jar"
& "$bt\d8.bat" --release --min-api 28 --lib $jar --output build/dex $cls $libs
if ($LASTEXITCODE) { throw "d8 failed" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::Open("$PWD\build\base.apk", 'Update')
[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($z, "$PWD\build\dex\classes.dex", 'classes.dex') | Out-Null
# aapt2 on Windows stores asset sub-folders as "assets/patterns\x.ahk", which Android can't open: use '/'
foreach ($e in @($z.Entries | Where-Object { $_.FullName.Contains('\') })) {
    $n = $z.CreateEntry($e.FullName.Replace('\', '/'))
    $src = $e.Open(); $dst = $n.Open(); $src.CopyTo($dst); $dst.Dispose(); $src.Dispose()
    $e.Delete()
}
$z.Dispose()
& "$bt\zipalign.exe" -f -p 4 build/base.apk build/aligned.apk
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out BeeMacro.apk build/aligned.apk
if ($LASTEXITCODE) { throw "signing failed" }
Write-Host "Built BeeMacro.apk"
if ($Install) { adb install -r BeeMacro.apk }
