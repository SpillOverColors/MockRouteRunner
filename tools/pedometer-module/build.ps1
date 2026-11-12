param(
    [string]$Out = '',
    [string]$Keystore = ''
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Here = $PSScriptRoot
$Sdk = 'C:\Users\zrl00\AppData\Local\Android\Sdk'
$Bt = Join-Path $Sdk 'build-tools\36.0.0'
$Jar = Join-Path $Sdk 'platforms\android-37.0\android.jar'
$Javac = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot\bin\javac.exe'
if (-not $Out) { $Out = Join-Path $Here 'pedometer-module.apk' }
if (-not $Keystore) { $Keystore = Join-Path $Root '.debug-signing\debug.keystore' }
$b = Join-Path $Root 'build\module'
Remove-Item -Recurse -Force $b -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Join-Path $b 'classes'),(Join-Path $b 'gen'),(Join-Path $b 'dex') | Out-Null
$src = @(Get-ChildItem (Join-Path $Here 'src') -Recurse -Filter '*.java' | % FullName)
$stubs = @(Get-ChildItem (Join-Path $Here 'stubs') -Recurse -Filter '*.java' | % FullName)
& $Javac -encoding UTF-8 --release 8 -cp $Jar -d (Join-Path $b 'classes') @src @stubs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
$moduleClasses = @(Get-ChildItem (Join-Path $b 'classes\io\github\dsh\pedometer') -Filter '*.class' | % FullName)
& (Join-Path $Bt 'd8.bat') --lib $Jar --min-api 26 --output (Join-Path $b 'dex') @moduleClasses
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
& (Join-Path $Bt 'aapt2.exe') link -o (Join-Path $b 'base.apk') -I $Jar --manifest (Join-Path $Here 'AndroidManifest.xml') --min-sdk-version 26 --target-sdk-version 34 -A (Join-Path $Here 'assets') --java (Join-Path $b 'gen')
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }
$unsigned = Join-Path $b 'unsigned.apk'
Copy-Item (Join-Path $b 'base.apk') $unsigned -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open($unsigned,'Update')
try {
    $entry = $zip.CreateEntry('classes.dex')
    $in = [IO.File]::OpenRead((Join-Path $b 'dex\classes.dex'))
    $os = $entry.Open()
    try { $in.CopyTo($os) } finally { $os.Close(); $in.Close() }
} finally { $zip.Dispose() }
& (Join-Path $Bt 'zipalign.exe') -f 4 $unsigned (Join-Path $b 'aligned.apk')
& (Join-Path $Bt 'apksigner.bat') sign --ks $Keystore --ks-pass pass:android --key-pass pass:android --v4-signing-enabled false --out $Out (Join-Path $b 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw "sign failed" }
Write-Host "module: $Out"
