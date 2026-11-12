# Build the icon/P2 release APK without touching reviewed acceptance artifacts.
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path $PSScriptRoot).Path
Set-Location $Root
$JavaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot' }
$Javac = Join-Path $JavaHome 'bin\javac.exe'
$Java = Join-Path $JavaHome 'bin\java.exe'
$Sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$Bt = Join-Path $Sdk 'build-tools\36.0.0'
$AndroidJar = Join-Path $Sdk 'platforms\android-37.0\android.jar'
$Aapt2 = Join-Path $Bt 'aapt2.exe'
$D8 = Join-Path $Bt 'd8.bat'
$ZipAlign = Join-Path $Bt 'zipalign.exe'
$ApkSigner = Join-Path $Bt 'apksigner.bat'
$Keystore = Join-Path $Root '.debug-signing\debug.keystore'
$Build = Join-Path $Root 'build\release'
$OutDir = Join-Path $Root 'dist\release'
$ApkDir = Join-Path $OutDir 'apk'
foreach ($p in @($Javac,$Java,$Aapt2,$D8,$ZipAlign,$ApkSigner,$AndroidJar)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "missing $p" }
}
New-Item -ItemType Directory -Force (Split-Path -Parent $Keystore) | Out-Null
if (-not (Test-Path -LiteralPath $Keystore)) {
    & (Join-Path $JavaHome 'bin\keytool.exe') -genkeypair -keystore $Keystore `
        -storepass android -keypass android -alias androiddebugkey -keyalg RSA `
        -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
    if ($LASTEXITCODE -ne 0) { throw 'debug key generation failed' }
}
Remove-Item -Recurse -Force $Build,$OutDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Build,$OutDir,$ApkDir,(Join-Path $Build 'gen'),(Join-Path $Build 'classes'),(Join-Path $Build 'dex'),(Join-Path $Build 'test') | Out-Null

$core = @(
    (Join-Path $Root 'app\src\io\github\dsh\mockroute\MiniJson.java'),
    (Join-Path $Root 'app\src\io\github\dsh\mockroute\RouteModel.java'),
    (Join-Path $Root 'app\src\io\github\dsh\mockroute\MotionSimulator.java'),
    (Join-Path $Root 'app\src\io\github\dsh\mockroute\Outcome.java'),
    (Join-Path $Root 'app\src\io\github\dsh\mockroute\RunCheckpoint.java'),
    (Join-Path $Root 'app\src\io\github\dsh\mockroute\BridgeProtocol.java')
)
$tests = $core + @(
    (Join-Path $Root 'tests\CoreTests.java'),
    (Join-Path $Root 'tests\io\github\dsh\mockroute\CheckpointTests.java')
)
& $Javac -encoding UTF-8 --release 8 -cp $AndroidJar -d (Join-Path $Build 'test') @tests
if ($LASTEXITCODE -ne 0) { throw 'offline test compile failed' }
& $Java -cp ((Join-Path $Build 'test') + ';' + $AndroidJar) CoreTests (Join-Path $OutDir 'test.log')
if ($LASTEXITCODE -ne 0) { throw 'core tests failed' }
& $Java -cp ((Join-Path $Build 'test') + ';' + $AndroidJar) io.github.dsh.mockroute.CheckpointTests (Join-Path $OutDir 'checkpoint-test.log')
if ($LASTEXITCODE -ne 0) { throw 'checkpoint tests failed' }

$manifest = Get-Content -LiteralPath (Join-Path $Root 'app\AndroidManifest.xml') -Raw -Encoding UTF8
$manifest = $manifest.Replace('android:versionCode="5"', 'android:versionCode="8"')
$manifest = $manifest.Replace('android:versionName="1.0.4"', 'android:versionName="1.2.1"')
$ManifestPath = Join-Path $Build 'AndroidManifest.xml'
[IO.File]::WriteAllText($ManifestPath, $manifest, (New-Object Text.UTF8Encoding($false)))
$resZip = Join-Path $Build 'res.zip'
& $Aapt2 compile --dir (Join-Path $Root 'app\res') -o $resZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
$baseApk = Join-Path $Build 'base.apk'
& $Aapt2 link -o $baseApk -I $AndroidJar --manifest $ManifestPath `
    --min-sdk-version 26 --target-sdk-version 34 --version-code 8 --version-name 1.2.1 `
    --java (Join-Path $Build 'gen') $resZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }
$javaSources = @(Get-ChildItem (Join-Path $Root 'app\src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$javaSources += (Join-Path $Build 'gen\io\github\dsh\mockroute\R.java')
& $Javac -encoding UTF-8 --release 8 -cp $AndroidJar -d (Join-Path $Build 'classes') @javaSources
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
$apk = Join-Path $ApkDir 'MockRouteRunner-1.2.1-debug.apk'
& $ApkSigner sign --ks $Keystore --ks-pass pass:android --key-pass pass:android `
    --v4-signing-enabled false --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw 'sign failed' }
& $ApkSigner verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'signature verify failed' }
& $Aapt2 dump badging $apk
if ($LASTEXITCODE -ne 0) { throw 'badging failed' }
Write-Host ("P2_APK=" + $apk)
Write-Host ("SHA256=" + (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash)
