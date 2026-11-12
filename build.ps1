# Build the native-Java APK with the installed JDK 17 and Android SDK build-tools.
# No Gradle, no downloads, no global installation.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

$Root = (Resolve-Path $PSScriptRoot).Path
Set-Location $Root
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot'
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
$Dist = Join-Path $Root 'dist'
$Build = Join-Path $Root 'build'
if (-not $Dist.StartsWith($Root, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'dist escaped workspace' }
if (-not $Build.StartsWith($Root, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'build escaped workspace' }

$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot'
$Javac = Join-Path $JavaHome 'bin\javac.exe'
$Java = Join-Path $JavaHome 'bin\java.exe'
$Keytool = Join-Path $JavaHome 'bin\keytool.exe'
$Sdk = 'C:\Users\zrl00\AppData\Local\Android\Sdk'
$Bt = Join-Path $Sdk 'build-tools\36.0.0'
$AndroidJar = Join-Path $Sdk 'platforms\android-37.0\android.jar'
$Aapt2 = Join-Path $Bt 'aapt2.exe'
$D8 = Join-Path $Bt 'd8.bat'
$ZipAlign = Join-Path $Bt 'zipalign.exe'
$ApkSigner = Join-Path $Bt 'apksigner.bat'
foreach ($p in @($Javac, $Java, $Keytool, $Aapt2, $D8, $ZipAlign, $ApkSigner, $AndroidJar)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "missing required tool: $p" }
}

Remove-Item -Recurse -Force $Build, $Dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Build, $Dist, (Join-Path $Build 'apk'), (Join-Path $Build 'gen'), (Join-Path $Build 'classes'), (Join-Path $Build 'dex'), (Join-Path $Build 'test-classes') | Out-Null
$LogPath = Join-Path $Dist 'build.log'
$script:LogPath = $LogPath
New-Item -ItemType File -Force $LogPath | Out-Null

function Log([string]$Text) {
    Add-Content -LiteralPath $script:LogPath -Value $Text -Encoding UTF8
    Write-Host $Text
}

function Invoke-Logged([string]$Label, [string]$Exe, [string[]]$Arguments) {
    Log "== $Label =="
    Log ("> " + $Exe + " " + ($Arguments -join ' '))
    $oldEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = & $Exe @Arguments 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $oldEap
    if ($null -ne $output) { foreach ($line in $output) { Log ([string]$line) } }
    Log "exit code: $code"
    if ($code -ne 0) { throw "$Label failed with exit code $code" }
}

Log "MockRouteRunner native Android build"
Log ("date: " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'))
Log ("workspace: " + $Root)
Log ("JDK: " + $JavaHome)
Log ("Android SDK: " + $Sdk)
Log ("build-tools: " + $Bt)
Log ("android.jar: " + $AndroidJar)
Log "strategy: aapt2 + javac + d8 + zipalign + apksigner (no Gradle, no third-party runtime libraries)"

# --- Offline pure-JVM tests first ---
$coreSources = @(
    (Join-Path $Root 'app\src\io\github\dsh\mockroute\MiniJson.java'),
    (Join-Path $Root 'app\src\io\github\dsh\mockroute\RouteModel.java'),
    (Join-Path $Root 'app\src\io\github\dsh\mockroute\MotionSimulator.java'),
    (Join-Path $Root 'app\src\io\github\dsh\mockroute\Outcome.java'),
    (Join-Path $Root 'app\src\io\github\dsh\mockroute\RunCheckpoint.java'),
    (Join-Path $Root 'app\src\io\github\dsh\mockroute\BridgeProtocol.java')
)
$testSources = $coreSources + @(
    (Join-Path $Root 'tests\CoreTests.java'),
    (Join-Path $Root 'tests\io\github\dsh\mockroute\CheckpointTests.java')
)
Invoke-Logged 'compile offline tests' $Javac (@('-encoding', 'UTF-8', '--release', '8', '-cp', $AndroidJar, '-d', (Join-Path $Build 'test-classes')) + $testSources)
$TestLog = Join-Path $Dist 'test.log'
Invoke-Logged 'run offline tests' $Java @('-cp', ((Join-Path $Build 'test-classes') + ';' + $AndroidJar), 'CoreTests', $TestLog)
if (-not (Test-Path -LiteralPath $TestLog)) { throw 'test.log was not produced' }
$CheckpointLog = Join-Path $Dist 'checkpoint-test.log'
Invoke-Logged 'run checkpoint tests' $Java @('-cp', ((Join-Path $Build 'test-classes') + ';' + $AndroidJar), 'io.github.dsh.mockroute.CheckpointTests', $CheckpointLog)
if (-not (Test-Path -LiteralPath $CheckpointLog)) { throw 'checkpoint-test.log was not produced' }

# --- Resources ---
$resZip = Join-Path $Build 'apk\res.zip'
Invoke-Logged 'aapt2 compile resources' $Aapt2 @('compile', '--dir', (Join-Path $Root 'app\res'), '-o', $resZip)
$baseApk = Join-Path $Build 'apk\base.apk'
Invoke-Logged 'aapt2 link manifest/resources' $Aapt2 @(
    'link', '-o', $baseApk, '-I', $AndroidJar,
    '--manifest', (Join-Path $Root 'app\AndroidManifest.xml'),
    '--min-sdk-version', '26', '--target-sdk-version', '34',
    '--version-code', '5', '--version-name', '1.0.4',
    '--java', (Join-Path $Build 'gen'), $resZip
)

# --- Java / dex ---
$javaSources = @(Get-ChildItem (Join-Path $Root 'app\src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$javaSources += (Join-Path $Build 'gen\io\github\dsh\mockroute\R.java')
Invoke-Logged 'javac Android sources' $Javac (@('-encoding', 'UTF-8', '--release', '8', '-cp', $AndroidJar, '-d', (Join-Path $Build 'classes')) + $javaSources)
$classFiles = @(Get-ChildItem (Join-Path $Build 'classes') -Recurse -Filter '*.class' | ForEach-Object { $_.FullName })
Invoke-Logged 'd8 dex' $D8 (@('--lib', $AndroidJar, '--min-api', '26', '--output', (Join-Path $Build 'dex')) + $classFiles)

# --- package classes.dex into the resource APK ---
$unsigned = Join-Path $Build 'apk\unsigned.apk'
Copy-Item -LiteralPath $baseApk -Destination $unsigned -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, 'Update')
try {
    $entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
    $in = [System.IO.File]::OpenRead((Join-Path $Build 'dex\classes.dex'))
    $out = $entry.Open()
    try { $in.CopyTo($out) } finally { $out.Close(); $in.Close() }
} finally { $zip.Dispose() }
Log "added classes.dex to resource APK"

# --- align + debug sign ---
$aligned = Join-Path $Build 'apk\aligned.apk'
Invoke-Logged 'zipalign' $ZipAlign @('-f', '4', $unsigned, $aligned)
$Keystore = Join-Path $Root '.debug-signing\debug.keystore'
New-Item -ItemType Directory -Force (Split-Path $Keystore) | Out-Null
if (-not (Test-Path -LiteralPath $Keystore)) {
    Log 'generating local debug keystore (password android; NOT for production)'
    Invoke-Logged 'keytool debug key' $Keytool @(
        '-genkeypair', '-keystore', $Keystore, '-storepass', 'android', '-keypass', 'android',
        '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
        '-dname', 'CN=Android Debug,O=Android,C=US'
    )
}
$Apk = Join-Path $Dist 'MockRouteRunner-debug.apk'
Invoke-Logged 'apksigner sign (debug)' $ApkSigner @(
    'sign', '--ks', $Keystore, '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
    '--v4-signing-enabled', 'false', '--out', $Apk, $aligned
)
Invoke-Logged 'apksigner verify' $ApkSigner @('verify', '--verbose', '--print-certs', $Apk)
Invoke-Logged 'aapt2 dump badging' $Aapt2 @('dump', 'badging', $Apk)

Copy-Item -LiteralPath (Join-Path $Root 'README.md') -Destination (Join-Path $Dist 'README.md') -Force
Copy-Item -LiteralPath (Join-Path $Root 'BUILDING.md') -Destination (Join-Path $Dist 'BUILDING.md') -Force
Copy-Item -LiteralPath (Join-Path $Root 'LICENSE') -Destination (Join-Path $Dist 'LICENSE') -Force
Copy-Item -LiteralPath (Join-Path $Root 'THIRD_PARTY_NOTICES.md') -Destination (Join-Path $Dist 'THIRD_PARTY_NOTICES.md') -Force
Copy-Item -LiteralPath (Join-Path $Root 'examples') -Destination (Join-Path $Dist 'examples') -Recurse -Force
Copy-Item -LiteralPath (Join-Path $Root 'docs') -Destination (Join-Path $Dist 'docs') -Recurse -Force
Copy-Item -LiteralPath (Join-Path $Root 'tools') -Destination (Join-Path $Dist 'tools') -Recurse -Force

$hash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash
$size = (Get-Item -LiteralPath $Apk).Length
Log ("APK: " + $Apk)
Log ("APK bytes: " + $size)
Log ("APK SHA256: " + $hash)
Log "BUILD SUCCESS"
Write-Host ("`nAPK: $Apk`nSHA256: $hash`nSize: $size bytes`nLog: $LogPath")
