$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Sdk = 'C:\Users\zrl00\AppData\Local\Android\Sdk'
$Bt = Join-Path $Sdk 'build-tools\36.0.0'
$AndroidJar = Join-Path $Sdk 'platforms\android-37.0\android.jar'
$Aapt2 = Join-Path $Bt 'aapt2.exe'
$PowerShell = (Get-Command powershell.exe).Source
$Tmp = Join-Path $Root 'build\patch-tool-tests'
Remove-Item -Recurse -Force $Tmp -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Tmp | Out-Null
$Manifest = Join-Path $Tmp 'AndroidManifest.xml'
@'
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="io.github.dsh.patchtest"
    android:versionCode="1" android:versionName="1.0">
    <application android:label="patchtest" />
</manifest>
'@ | Set-Content -LiteralPath $Manifest -Encoding UTF8
$Base = Join-Path $Tmp 'base.apk'
& $Aapt2 link -o $Base -I $AndroidJar --manifest $Manifest --min-sdk-version 26 --target-sdk-version 34
if ($LASTEXITCODE -ne 0) { throw 'test APK build failed' }
$Module = Join-Path $Root 'tools\pedometer-module\pedometer-module.apk'
$Lspatch = Join-Path $Root 'tools\lspatch\lspatch-v1.2-487-release.jar'
$Keystore = Join-Path $Root '.debug-signing\debug.keystore'
foreach ($p in @($Module, $Lspatch, $Keystore)) { if (-not (Test-Path $p)) { throw "missing $p" } }
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Invoke-Case([string]$Name, [string]$Apk, [int]$Expected) {
    $Out = Join-Path $Tmp ("out-" + $Name)
    & $PowerShell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root 'tools\patch_tool.ps1') `
        -Apk $Apk -OutDir $Out -Module $Module -Lspatch $Lspatch -Keystore $Keystore -Force | Out-Null
    $code = $LASTEXITCODE
    if ($code -ne $Expected) { throw "$Name expected exit $Expected, got $code" }
    $log = Get-Content -LiteralPath (Join-Path $Out 'report.log') -Raw
    Write-Host ("PASS negative " + $Name + " exit=" + $code + " :: " + ($log -split "`n" | Where-Object { $_ -match 'RESULT=' } | Select-Object -First 1))
}

$Split = Join-Path $Tmp 'split.apk'
Copy-Item $Base $Split -Force
$zip = [IO.Compression.ZipFile]::Open($Split, 'Update')
try { $zip.CreateEntry('split_config.arm64_v8a.apk') | Out-Null } finally { $zip.Dispose() }
Invoke-Case 'split' $Split 3

$Already = Join-Path $Tmp 'already.apk'
Copy-Item $Base $Already -Force
$zip = [IO.Compression.ZipFile]::Open($Already, 'Update')
try { $zip.CreateEntry('assets/lspatch/loader.dex') | Out-Null } finally { $zip.Dispose() }
Invoke-Case 'already-patched' $Already 4

Invoke-Case 'unsupported-no-dex' $Base 5

$Report = Get-Content -LiteralPath (Join-Path $Root 'tools\patch_tool.ps1') -Raw
if ($Report -notmatch 'GPL-3\.0') { throw 'patch_tool report template does not declare GPL-3.0' }
if ($Report -match 'Apache-2\.0 build tool') { throw 'stale Apache claim remains' }
if ($Report -notmatch 'CANDIDATE_ONLY') { throw 'candidate-only compatibility disclaimer missing' }
Write-Host 'PASS patch_tool license/candidate text'
Write-Host 'PATCH TOOL NEGATIVE TESTS PASSED'
exit 0
