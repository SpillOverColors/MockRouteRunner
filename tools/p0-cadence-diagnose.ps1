param(
    [string]$Adb = 'C:\Users\zrl00\AppData\Local\Android\Sdk\platform-tools\adb.exe',
    [string]$Serial = '87533458',
    [string]$OutFile = ''
)
$ErrorActionPreference = 'Continue'
if (-not (Test-Path -LiteralPath $Adb)) { throw "adb not found: $Adb" }
$lines = New-Object System.Collections.Generic.List[string]
function Add-Text([string]$s) { $script:lines.Add($s); Write-Host $s }
function Run-Adb([string]$label, [string]$shell) {
    Add-Text "== $label =="
    $out = & $Adb -s $Serial shell $shell 2>&1
    foreach ($line in $out) { Add-Text ([string]$line) }
    Add-Text ''
}
Add-Text 'P0 cadence/sensor diagnostic (READ-ONLY; no app start, no submission)'
Add-Text ("time: " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'))
$devices = & $Adb devices -l 2>&1
foreach ($line in $devices) { Add-Text ([string]$line) }
if (-not ($devices -match [regex]::Escape($Serial) + '\s+device')) { throw "device $Serial not online" }
Run-Adb 'build/root state' 'getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.model; getprop ro.debuggable; getprop ro.secure; getprop ro.build.type; getprop ro.boot.verifiedbootstate; getprop ro.boot.flash.locked; id; which su; which magisk'
Run-Adb 'target package uid/version' 'cmd package list packages -U com.joyrunworld.app; dumpsys package com.joyrunworld.app | grep -E "versionName|versionCode|firstInstallTime|lastUpdateTime"'
Run-Adb 'target permissions (selected)' 'dumpsys package com.joyrunworld.app | grep -E "ACTIVITY_RECOGNITION|BODY_SENSORS|ACCESS_MOCK_LOCATION|HIGH_SAMPLING_RATE_SENSORS|FOREGROUND_SERVICE_HEALTH"'
Run-Adb 'sensor list (step-related)' 'dumpsys sensorservice | grep -E "step_detector|pedometer|step_counter"'
Run-Adb 'active sensor connections' 'dumpsys sensorservice | grep -E "Connection Number|package=|step_detector|step_counter|pedometer|uid " ' 
Run-Adb 'sensorservice shell capabilities' 'cmd sensorservice help'
Run-Adb 'MIUI step counter shell capabilities' 'cmd miui_step_counter_service help'
Run-Adb 'MIUI step counter state' 'dumpsys miui_step_counter_service'
Run-Adb 'health connect shell capability' 'cmd healthconnect help'
$summary = @(
    'Interpretation:',
    '- If target app uses SensorManager TYPE_STEP_COUNTER/TYPE_STEP_DETECTOR, mock location alone cannot set cadence.',
    '- No root/su and no sensorservice inject command means no supported rootless sensor-event injection.',
    '- Do not start a formal run from this script; observe the target app only after explicit approval.',
    '- P0 remains incomplete until target-app UI/record shows injected cadence and the root/hook path is explicitly approved.'
)
Add-Text ''
foreach ($s in $summary) { Add-Text $s }
if ($OutFile -and $OutFile.Length -gt 0) {
    [System.IO.File]::WriteAllLines((Join-Path (Get-Location) $OutFile), $lines, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host ("saved: " + (Join-Path (Get-Location) $OutFile))
}
