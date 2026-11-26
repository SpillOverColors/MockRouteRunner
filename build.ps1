param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT,
    [string]$Keystore = $env:MOCKROUTE_KEYSTORE
)
$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
Set-Location -LiteralPath $Root
if (-not $JavaHome) { throw 'Set JAVA_HOME to JDK 17 or newer.' }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$Bt = Join-Path $AndroidSdk 'build-tools\36.0.0'
$AndroidJar = Join-Path $AndroidSdk 'platforms\android-37.0\android.jar'
$Javac = Join-Path $JavaHome 'bin\javac.exe'
$Java = Join-Path $JavaHome 'bin\java.exe'
foreach ($tool in @($Javac,$Java,$AndroidJar,(Join-Path $Bt 'aapt2.exe'),(Join-Path $Bt 'd8.bat'),(Join-Path $Bt 'zipalign.exe'),(Join-Path $Bt 'apksigner.bat'))) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Missing build prerequisite: $tool" }
}
$Build = [IO.Path]::GetFullPath((Join-Path $Root 'build'))
if ($Build -ne ([IO.Path]::GetFullPath($Root).TrimEnd('\') + '\build')) { throw 'Unexpected build path' }
if (Test-Path -LiteralPath $Build) { Remove-Item -LiteralPath $Build -Recurse -Force }
foreach ($dir in @('build','build\test','build\gen','build\classes','build\dex','dist')) {
    New-Item -ItemType Directory -Force (Join-Path $Root $dir) | Out-Null
}
$coreNames = @('MiniJson','RouteModel','MotionSimulator','Outcome','RunCheckpoint','BridgeProtocol','ProviderOwnership')
$core = @($coreNames | ForEach-Object { Join-Path $Root "app\src\io\github\dsh\mockroute\$_.java" })
$tests = @(Get-ChildItem -LiteralPath (Join-Path $Root 'tests') -Recurse -Filter '*.java' | ForEach-Object FullName)
& $Javac -encoding UTF-8 --release 8 -cp $AndroidJar -d (Join-Path $Build 'test') @core @tests
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
foreach ($entry in @(@('CoreTests','core-test.log'),@('io.github.dsh.mockroute.CheckpointTests','checkpoint-test.log'),@('io.github.dsh.mockroute.ProviderOwnershipTests','ownership-test.log'))) {
    & $Java -cp ((Join-Path $Build 'test') + ';' + $AndroidJar) $entry[0] (Join-Path $Root ('dist\' + $entry[1]))
    if ($LASTEXITCODE -ne 0) { throw "Tests failed: $($entry[0])" }
}
$aapt = Join-Path $Bt 'aapt2.exe'
& $aapt compile --dir (Join-Path $Root 'app\res') -o (Join-Path $Build 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'Resource compilation failed' }
& $aapt link -o (Join-Path $Build 'base.apk') -I $AndroidJar --manifest (Join-Path $Root 'app\AndroidManifest.xml') --min-sdk-version 26 --target-sdk-version 34 --custom-package io.github.dsh.mockroute --java (Join-Path $Build 'gen') (Join-Path $Build 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'Resource linking failed' }
$sources = @(Get-ChildItem -LiteralPath (Join-Path $Root 'app\src') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources += Join-Path $Build 'gen\io\github\dsh\mockroute\R.java'
& $Javac -encoding UTF-8 --release 8 -cp $AndroidJar -d (Join-Path $Build 'classes') @sources
if ($LASTEXITCODE -ne 0) { throw 'App compilation failed' }
$classes = @(Get-ChildItem -LiteralPath (Join-Path $Build 'classes') -Recurse -Filter '*.class' | ForEach-Object FullName)
& (Join-Path $Bt 'd8.bat') --lib $AndroidJar --min-api 26 --output (Join-Path $Build 'dex') @classes
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed' }
$unsigned = Join-Path $Build 'unsigned.apk'
Copy-Item -LiteralPath (Join-Path $Build 'base.apk') -Destination $unsigned
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open($unsigned,'Update')
try {
    foreach ($dex in Get-ChildItem -LiteralPath (Join-Path $Build 'dex') -Filter 'classes*.dex') {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$dex.FullName,$dex.Name) | Out-Null
    }
} finally { $zip.Dispose() }
& (Join-Path $Bt 'zipalign.exe') -f 4 $unsigned (Join-Path $Build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }
if (-not $Keystore) {
    $Keystore = Join-Path $Root '.debug-signing\debug.keystore'
    New-Item -ItemType Directory -Force (Split-Path -Parent $Keystore) | Out-Null
    if (-not (Test-Path -LiteralPath $Keystore)) {
        & (Join-Path $JavaHome 'bin\keytool.exe') -genkeypair -keystore $Keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
        if ($LASTEXITCODE -ne 0) { throw 'Local debug key creation failed' }
    }
}
$apk = Join-Path $Root 'dist\MockRouteRunner-source-build.apk'
& (Join-Path $Bt 'apksigner.bat') sign --ks $Keystore --ks-pass pass:android --key-pass pass:android --v4-signing-enabled false --out $apk (Join-Path $Build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'Signing failed' }
& (Join-Path $Bt 'apksigner.bat') verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed' }
Write-Output ('APK=' + $apk)
Write-Output ('SHA256=' + (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash)
