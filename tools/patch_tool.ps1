param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [Parameter(Mandatory=$true)][string]$OutDir,
    [string]$Java = 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe',
    [string]$Lspatch = 'tools\lspatch\lspatch-v1.2-487-release.jar',
    [string]$Module = 'tools\pedometer-module\pedometer-module.apk',
    [string]$Keystore = '.debug-signing\debug.keystore',
    [string]$StorePass = 'android',
    [string]$Alias = 'androiddebugkey',
    [string]$KeyPass = 'android',
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
function Full([string]$p) { if ([IO.Path]::IsPathRooted($p)) { return $p } return (Join-Path $Root $p) }
$Apk = (Resolve-Path (Full $Apk)).Path
$OutDir = Full $OutDir
$Java = Full $Java
$Lspatch = Full $Lspatch
$Module = Full $Module
$Keystore = Full $Keystore
foreach ($p in @($Apk,$Java,$Lspatch,$Module,$Keystore)) { if (-not (Test-Path -LiteralPath $p)) { throw "missing required file: $p" } }
$Aapt2 = 'C:\Users\zrl00\AppData\Local\Android\Sdk\build-tools\36.0.0\aapt2.exe'
$ApkSigner = 'C:\Users\zrl00\AppData\Local\Android\Sdk\build-tools\36.0.0\apksigner.bat'
foreach ($p in @($Aapt2,$ApkSigner)) { if (-not (Test-Path -LiteralPath $p)) { throw "missing SDK tool: $p" } }
if (Test-Path -LiteralPath $OutDir) { if (-not $Force) { throw "output exists: $OutDir (use -Force)" } } else { New-Item -ItemType Directory -Force $OutDir | Out-Null }
$OriginalSha = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash
$OriginalCopy = Join-Path $OutDir 'original.apk'
Copy-Item -LiteralPath $Apk -Destination $OriginalCopy -Force
$log = New-Object System.Collections.Generic.List[string]
function Log([string]$s) { $script:log.Add($s); Write-Host $s }
Log "P0 APK auto-patch tool (offline only; does not install anything)"
Log ("time=" + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'))
Log ("input=" + $Apk)
Log ("original_sha256=" + $OriginalSha)
Log ("original_copy=" + $OriginalCopy)

# --- identify manifest/package ---
$badging = (& $Aapt2 dump badging $Apk 2>&1) -join "`n"
$package = [regex]::Match($badging, "package: name='([^']+)'").Groups[1].Value
$versionName = [regex]::Match($badging, "versionName='([^']+)'").Groups[1].Value
$versionCode = [regex]::Match($badging, "versionCode='([^']+)'").Groups[1].Value
$label = [regex]::Match($badging, "application-label:'([^']*)'").Groups[1].Value
$abis = @([regex]::Matches($badging, "native-code: '([^']+)'") | ForEach-Object { $_.Groups[1].Value })
if ($abis.Count -eq 0) { $abis = @('universal') }
Log ("package=" + $package)
Log ("versionName=" + $versionName + " versionCode=" + $versionCode)
Log ("label=" + $label)
Log ("abis=" + ($abis -join ','))

# --- split detection ---
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($Apk)
try {
    $names = @($zip.Entries | ForEach-Object { $_.FullName })
    $isSplit = @($names | Where-Object { $_ -match '^split_config|^config\.' }).Count -gt 0
    $man = (& $Aapt2 dump xmltree --file AndroidManifest.xml $Apk 2>&1) -join "`n"
    $manifestSplit = ($man -match 'split=|isSplitRequired|uses-split')
    $already = $names -contains 'assets/lspatch/loader.dex' -or $names -contains 'assets/lspatch/metaloader.dex'
    $dexEntries = @($zip.Entries | Where-Object { $_.FullName -match '^classes.*\.dex$' })
    $s = ''
    foreach ($e in $dexEntries) {
        $ms = New-Object IO.MemoryStream
        $st = $e.Open(); $st.CopyTo($ms); $st.Close()
        $s += [Text.Encoding]::GetEncoding(28591).GetString($ms.ToArray())
        $ms.Dispose()
    }
} finally { $zip.Dispose() }
if ($isSplit -or $manifestSplit) {
    Log 'split_apk=yes (zip entry or manifest split/isSplitRequired/uses-split)'
    Log 'RESULT=UNSUPPORTED_SPLIT'
    Log 'This CLI accepts one base APK only; split/App Bundle installs are rejected rather than producing a partial APK.'
    [IO.File]::WriteAllLines((Join-Path $OutDir 'report.log'), $log, (New-Object Text.UTF8Encoding($false)))
    exit 3
}
if ($already) {
    Log 'already_patched=yes'
    Log 'RESULT=ALREADY_PATCHED'
    [IO.File]::WriteAllLines((Join-Path $OutDir 'report.log'), $log, (New-Object Text.UTF8Encoding($false)))
    exit 4
}
Log 'split_apk=no'
# --- pedometer mode detection ---
$hasPlugin = $s.Contains('PedometerPlugin') -or $s.Contains('stepCountChannel') -or $s.Contains('stepDetectionChannel')
$hasStep = $s.Contains('TYPE_STEP_COUNTER') -or $s.Contains('TYPE_STEP_DETECTOR')
$hasSensor = $s.Contains('SensorManager')
$mode = if ($hasPlugin -and $hasStep) { 'FLUTTER_PEDOMETER_CANDIDATE' } else { 'UNSUPPORTED' }
Log ("pedometer_plugin=" + $hasPlugin + " step_types=" + $hasStep + " sensor_manager=" + $hasSensor)
Log ("detected_mode=" + $mode + " (DEX strings are candidates only; no structural verification)")
if ($mode -eq 'UNSUPPORTED') {
    Log 'RESULT=UNSUPPORTED_PEDOMETER_PATH'
    Log 'No candidate step-counter/step-detector strings were found; the base location tool remains usable without patching.'
    [IO.File]::WriteAllLines((Join-Path $OutDir 'report.log'), $log, (New-Object Text.UTF8Encoding($false)))
    exit 5
}
Log 'module=io.github.dsh.pedometer (LSPatch embedded)'
Log 'modification=SensorManager.registerListener wrapper + synthetic TYPE_STEP_COUNTER/TYPE_STEP_DETECTOR events'
Log 'compatibility=CANDIDATE_ONLY (string match cannot prove the target code path)'
Log 'control=loopback 127.0.0.1:39000+FNV1a(package)%1000 only (START/PAUSE/RESUME/STOP/STATUS)'
Log 'original_signature_preserved=no (patched copy is re-signed; official APK is not replaced)'

$ErrorActionPreference = 'Continue'
$javaVersion = (& $Java -version 2>&1) -join "`n"
Log ("java=" + $javaVersion)
$lspArgs = @('-jar', $Lspatch, '--embed', $Module, '-l', '2', '-k', $Keystore, $StorePass, $Alias, $KeyPass, '-o', $OutDir, '-v', $Apk)
$safeArgs = $lspArgs | ForEach-Object { if ($_ -eq $StorePass -or $_ -eq $KeyPass) { '***' } else { $_ } }
Log ("patch_command=" + $Java + " " + ($safeArgs -join ' '))
Get-ChildItem $OutDir -Filter '*-lspatched.apk' | Remove-Item -Force -ErrorAction SilentlyContinue
$patchOut = & $Java @lspArgs 2>&1
foreach ($line in $patchOut) { Log ([string]$line) }
$patchCode = $LASTEXITCODE
Log ("patch_exit=" + $patchCode)
if ($patchCode -ne 0) {
    Log 'RESULT=PATCH_FAILED'
    [IO.File]::WriteAllLines((Join-Path $OutDir 'report.log'), $log, (New-Object Text.UTF8Encoding($false)))
    exit $patchCode
}
$outputs = @(Get-ChildItem $OutDir -Filter '*-lspatched.apk')
if ($outputs.Count -ne 1) { Log ("RESULT=OUTPUT_COUNT_" + $outputs.Count); [IO.File]::WriteAllLines((Join-Path $OutDir 'report.log'), $log, (New-Object Text.UTF8Encoding($false))); exit 6 }
$patched = $outputs[0]
if (-not $patched) { Log 'RESULT=NO_OUTPUT'; [IO.File]::WriteAllLines((Join-Path $OutDir 'report.log'), $log, (New-Object Text.UTF8Encoding($false))); exit 6 }
$patchedSha = (Get-FileHash -LiteralPath $patched.FullName -Algorithm SHA256).Hash
$verify = & $ApkSigner verify --print-certs $patched.FullName 2>&1
foreach ($line in $verify) { Log ([string]$line) }
if ($LASTEXITCODE -ne 0) { Log 'RESULT=SIGNATURE_VERIFY_FAILED'; [IO.File]::WriteAllLines((Join-Path $OutDir 'report.log'), $log, (New-Object Text.UTF8Encoding($false))); exit 7 }
$newBadge = (& $Aapt2 dump badging $patched.FullName 2>&1) -join "`n"
$newPackage = [regex]::Match($newBadge, "package: name='([^']+)'").Groups[1].Value
$newVersion = [regex]::Match($newBadge, "versionName='([^']+)'").Groups[1].Value
$cert = ($verify | Select-String -Pattern 'Signer #1 certificate SHA-256 digest:' | Select-Object -First 1).Line
if ($newPackage -ne $package -or $newVersion -ne $versionName) {
    Log 'RESULT=PACKAGE_OR_VERSION_MISMATCH'
    [IO.File]::WriteAllLines((Join-Path $OutDir 'report.log'), $log, (New-Object Text.UTF8Encoding($false)))
    exit 8
}
if ((Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash -ne $OriginalSha) {
    Log 'RESULT=ORIGINAL_CHANGED'
    [IO.File]::WriteAllLines((Join-Path $OutDir 'report.log'), $log, (New-Object Text.UTF8Encoding($false)))
    exit 9
}
Log ("patched_apk=" + $patched.FullName)
Log ("patched_sha256=" + $patchedSha)
Log ("patched_package=" + $newPackage)
Log ("patched_versionName=" + $newVersion)
Log ("patched_cert=" + $cert)
Log 'RESULT=OFFLINE_PATCH_SIGNED_VERIFIED'
Log 'EVIDENCE=offline only; not installed, not target-app verified, no formal run submitted'
$labelSafe = if ($label -match '^[\x20-\x7E]*$') { $label } else { '(non-ASCII label omitted)' }
$report = @"
# APK patch report

- input: $Apk
- original SHA-256: $OriginalSha
- package: $package
- version: $versionName ($versionCode)
- label: $labelSafe
- ABIs: $($abis -join ', ')
- detected pedometer mode: $mode (candidate only; no structural verification)
- module: io.github.dsh.pedometer (LSPatch embedded)
- modified: SensorManager.registerListener wrapper + synthetic step events
- compatibility: CANDIDATE_ONLY; DEX string match does not prove the target code path
- original signature preserved: no (patched copy re-signed; official APK not replaced)
- patch result: OFFLINE_PATCH_SIGNED_VERIFIED
- patched APK: $($patched.FullName)
- patched SHA-256: $patchedSha
- signature: $cert
- evidence stage: offline patch + signature only; target app actual read is NOT verified
- control: loopback 127.0.0.1:39000+FNV1a(package)%1000 (START/PAUSE/RESUME/STOP/STATUS)

## Important

- Do not install over the official app: signature differs. Manual install requires a separate copy/uninstall decision by the user.
- This tool does not uninstall, replace, or submit any target-app data.
- LSPatch v1.2 is GPL-3.0; the module and this tool are MIT. The patched copy embeds the GPL runtime. See THIRD_PARTY_NOTICES.md and tools/lspatch/LICENSE.
"@
[IO.File]::WriteAllLines((Join-Path $OutDir 'report.md'), $report, (New-Object Text.UTF8Encoding($false)))
[IO.File]::WriteAllLines((Join-Path $OutDir 'report.log'), $log, (New-Object Text.UTF8Encoding($false)))
Write-Host ("`nPatched: " + $patched.FullName + "`nSHA256: " + $patchedSha + "`nReport: " + (Join-Path $OutDir 'report.md'))
