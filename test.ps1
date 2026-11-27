$ErrorActionPreference = 'Stop'
$Here = $PSScriptRoot
$Root = Split-Path -Parent (Split-Path -Parent $Here)
$Javac = (Join-Path $env:JAVA_HOME 'bin\javac.exe')
$Java = (Join-Path $env:JAVA_HOME 'bin\java.exe')
$b = Join-Path $Root 'build\module-tests'
Remove-Item -Recurse -Force $b -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $b,(Join-Path $Root 'dist') | Out-Null
$src = @(
    (Join-Path $Here 'src\io\github\dsh\pedometer\CadenceEngine.java'),
    (Join-Path $Here 'src\io\github\dsh\pedometer\ControlProtocol.java'),
    (Join-Path $Here 'src\io\github\dsh\pedometer\DeliveryGate.java'),
    (Join-Path $Here 'src\io\github\dsh\pedometer\RegistrationTable.java'),
    (Join-Path $Here 'tests\io\github\dsh\pedometer\ModuleLogicTests.java'),
    (Join-Path $Here 'tests\io\github\dsh\pedometer\LoopbackProtocolTests.java')
)
& $Javac -encoding UTF-8 --release 8 -d $b @src
if ($LASTEXITCODE -ne 0) { throw 'module test compile failed' }
& $Java -cp $b io.github.dsh.pedometer.ModuleLogicTests (Join-Path $Root 'dist\module-test.log')
if ($LASTEXITCODE -ne 0) { throw 'module tests failed' }
& $Java -cp $b io.github.dsh.pedometer.LoopbackProtocolTests (Join-Path $Root 'dist\loopback-test.log')
if ($LASTEXITCODE -ne 0) { throw 'loopback protocol tests failed' }
