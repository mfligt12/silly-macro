# Starts Bee Macro's keyboard sender on the connected tablet (needed once after every tablet reboot).
# It runs as the adb shell user so it may send real keyboard keys to Roblox; it only listens on the tablet's own
# 127.0.0.1 and only accepts the Bee Macro app (token check). Usage:  powershell -File start_keys.ps1
$ErrorActionPreference = 'Stop'
$line = adb devices | Select-String "`tdevice$" | Select-Object -First 1
if (-not $line) { throw "No device connected over adb (pair the tablet with wireless debugging first)." }
$dev = $line.ToString().Split("`t")[0]
# stop only a previous Bee Macro key sender (other shell helpers such as scrcpy are left alone), then start one
$cmd = 'for p in $(ps -A -o PID,ARGS | grep com.iipxp.beemacro.KeyServer | grep -v grep | awk ''{print $1}''); do kill $p; done; P=$(pm path com.iipxp.beemacro | cut -d: -f2); CLASSPATH=$P nohup app_process / com.iipxp.beemacro.KeyServer > /data/local/tmp/keyserver.log 2>&1 &'
adb -s $dev shell $cmd
Start-Sleep -Seconds 2
adb -s $dev shell cat /data/local/tmp/keyserver.log
