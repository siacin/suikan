param([string]$BuildInfo = '',[string]$TestOutput = '')
$ErrorActionPreference='Stop'
if (!$BuildInfo) { $BuildInfo=Join-Path $PSScriptRoot '..\..\..\work\android-build\latest-build.json' }
$build=Get-Content -LiteralPath $BuildInfo -Raw -Encoding UTF8 | ConvertFrom-Json
$env:JAVA_HOME=$build.JdkHome
$env:PATH=(Join-Path $build.JdkHome 'bin')+';'+$env:PATH
if (!$env:JAVA_TOOL_OPTIONS) { $env:JAVA_TOOL_OPTIONS='-Xms32m -Xmx256m -XX:MaxMetaspaceSize=128m' }
if (!$TestOutput) { $TestOutput=Join-Path $build.BuildRoot 'instrumentation.apk' }
$testRoot=Join-Path $build.BuildRoot 'test'
New-Item -ItemType Directory -Path $testRoot,(Join-Path $testRoot 'classes'),(Join-Path $testRoot 'dex') -Force | Out-Null
function Invoke-Checked([string]$File,[string[]]$ToolArgs){ & $File @ToolArgs; if($LASTEXITCODE -ne 0){throw "Test build failed: $File"} }
$resources=Join-Path $testRoot 'resources.apk'
Invoke-Checked (Join-Path $build.BuildToolsRoot 'aapt2.exe') @('link','-o',$resources,'-I',$build.PlatformJar,'--manifest',(Join-Path $PSScriptRoot 'android\AndroidManifest.xml'),'--min-sdk-version','26','--target-sdk-version','36')
$mainClasses=Join-Path $build.BuildRoot 'classes.jar'
$classpath=$build.PlatformJar+';'+$mainClasses
$boot=$build.PlatformJar+';'+(Join-Path $build.BuildToolsRoot 'core-lambda-stubs.jar')
Invoke-Checked (Join-Path $build.JdkHome 'bin\javac.exe') @('-encoding','UTF-8','-source','8','-target','8','-bootclasspath',$boot,'-classpath',$classpath,'-d',(Join-Path $testRoot 'classes'),(Join-Path $PSScriptRoot 'android\TestHarness.java'),(Join-Path $PSScriptRoot 'android\LiveHarness.java'),(Join-Path $PSScriptRoot 'android\FeatureHarness.java'),(Join-Path $PSScriptRoot 'android\SpeedHarness.java'))
$jar=Join-Path $testRoot 'tests.jar'
Invoke-Checked (Join-Path $build.JdkHome 'bin\jar.exe') @('cf',$jar,'-C',(Join-Path $testRoot 'classes'),'.')
Invoke-Checked (Join-Path $build.BuildToolsRoot 'd8.bat') @('--min-api','26','--lib',$build.PlatformJar,'--classpath',$mainClasses,'--output',(Join-Path $testRoot 'dex'),$jar)
$unsigned=Join-Path $testRoot 'unsigned.apk';Copy-Item -LiteralPath $resources -Destination $unsigned
Invoke-Checked (Join-Path $build.JdkHome 'bin\jar.exe') @('uf',$unsigned,'-C',(Join-Path $testRoot 'dex'),'classes.dex')
$aligned=Join-Path $testRoot 'aligned.apk'
Invoke-Checked (Join-Path $build.BuildToolsRoot 'zipalign.exe') @('-p','-f','4',$unsigned,$aligned)
Invoke-Checked (Join-Path $build.BuildToolsRoot 'apksigner.bat') @('sign','--ks',$build.SigningKey,'--ks-pass','pass:android','--key-pass','pass:android','--out',$TestOutput,$aligned)
Get-Item -LiteralPath $TestOutput | Select-Object FullName,Length
