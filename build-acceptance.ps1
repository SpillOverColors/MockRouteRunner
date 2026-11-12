# Build a self-contained acceptance APK with an independent applicationId.
# Reuses the same app/src and app/res; only the manifest package/label differ.
param(
    [string]$Out = 'dist\acceptance\MockRouteRunner-acceptance.apk'
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path $PSScriptRoot).Path
Set-Location $Root
$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot'
$Javac = Join-Path $JavaHome 'bin\javac.exe'
$Sdk = 'C:\Users\zrl00\AppData\Local\Android\Sdk'
$Bt = Join-Path $Sdk 'build-tools\36.0.0'
$AndroidJar = Join-Path $Sdk 'platforms\android-37.0\android.jar'
$Aapt2 = Join-Path $Bt 'aapt2.exe'
$D8 = Join-Path $Bt 'd8.bat'
$ZipAlign = Join-Path $Bt 'zipalign.exe'
$ApkSigner = Join-Path $Bt 'apksigner.bat'
$Keystore = Join-Path $Root '.debug-signing\debug.keystore'
foreach ($p in @($Javac,$Aapt2,$D8,$ZipAlign,$ApkSigner,$AndroidJar,$Keystore)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "missing $p" }
}
$Build = Join-Path $Root 'build\acceptance'
Remove-Item -Recurse -Force $Build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Build,(Join-Path $Build 'gen'),(Join-Path $Build 'classes'),(Join-Path $Build 'dex') | Out-Null
$Out = if ([IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path $Root $Out }
New-Item -ItemType Directory -Force (Split-Path -Parent $Out) | Out-Null

$manifest = Get-Content -LiteralPath (Join-Path $Root 'app\AndroidManifest.xml') -Raw -Encoding UTF8
$manifest = $manifest.Replace('package="io.github.dsh.mockroute"',
    'package="io.github.dsh.mockroute.acceptance"')
$manifest = $manifest.Replace('android:name=".MainActivity"',
    'android:name="io.github.dsh.mockroute.MainActivity"')
$manifest = $manifest.Replace('android:name=".RouteEditorActivity"',
    'android:name="io.github.dsh.mockroute.RouteEditorActivity"')
$manifest = $manifest.Replace('android:name=".MockRouteService"',
    'android:name="io.github.dsh.mockroute.MockRouteService"')
$manifest = $manifest.Replace('android:label="@string/app_name"',
    'android:label="MockRouteRunner Acceptance Test"')
$manifest = $manifest.Replace('android:versionCode="5"', 'android:versionCode="1"')
$manifest = $manifest.Replace('android:versionName="1.0.4"', 'android:versionName="1.0.4-acceptance"')
$ManifestPath = Join-Path $Build 'AndroidManifest.xml'
[IO.File]::WriteAllText($ManifestPath, $manifest, (New-Object Text.UTF8Encoding($false)))
$resZip = Join-Path $Build 'res.zip'
& $Aapt2 compile --dir (Join-Path $Root 'app\res') -o $resZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
$baseApk = Join-Path $Build 'base.apk'
& $Aapt2 link -o $baseApk -I $AndroidJar --manifest $ManifestPath `
    --min-sdk-version 26 --target-sdk-version 34 `
    --custom-package io.github.dsh.mockroute `
    --java (Join-Path $Build 'gen') $resZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }
$sources = @(Get-ChildItem (Join-Path $Root 'app\src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$sources += (Join-Path $Build 'gen\io\github\dsh\mockroute\R.java')
& $Javac -encoding UTF-8 --release 8 -cp $AndroidJar -d (Join-Path $Build 'classes') @sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
$classes = @(Get-ChildItem (Join-Path $Build 'classes') -Recurse -Filter '*.class' | ForEach-Object { $_.FullName })
& $D8 --lib $AndroidJar --min-api 26 --output (Join-Path $Build 'dex') @classes
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
$unsigned = Join-Path $Build 'unsigned.apk'
Copy-Item $baseApk $unsigned -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open($unsigned, 'Update')
try {
    $entry = $zip.CreateEntry('classes.dex')
    $in = [IO.File]::OpenRead((Join-Path $Build 'dex\classes.dex'))
    $os = $entry.Open()
    try { $in.CopyTo($os) } finally { $os.Close(); $in.Close() }
} finally { $zip.Dispose() }
$aligned = Join-Path $Build 'aligned.apk'
& $ZipAlign -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }
& $ApkSigner sign --ks $Keystore --ks-pass pass:android --key-pass pass:android `
    --v4-signing-enabled false --out $Out $aligned
if ($LASTEXITCODE -ne 0) { throw 'sign failed' }
& $ApkSigner verify --print-certs $Out
if ($LASTEXITCODE -ne 0) { throw 'signature verify failed' }
$badge = (& $Aapt2 dump badging $Out 2>&1) -join "`n"
Write-Host $badge
Write-Host ("ACCEPTANCE_APK=" + $Out)
Write-Host ("SHA256=" + (Get-FileHash -LiteralPath $Out -Algorithm SHA256).Hash)
