param([Parameter(Mandatory=$true)][string]$JsonJar,[Parameter(Mandatory=$true)][string]$JdkHome,[string]$TestRoot='')
$ErrorActionPreference='Stop'
if (!$TestRoot) { $TestRoot=Join-Path $PSScriptRoot '..\.local-build\usage-tests' }
New-Item -ItemType Directory -Force -Path $TestRoot | Out-Null
$JsonJar=(Resolve-Path -LiteralPath $JsonJar).Path
$source=Join-Path $PSScriptRoot '..\app\src\main\java\com\suikan\app\UsageCounter.java'
& (Join-Path $JdkHome 'bin\javac.exe') -encoding UTF-8 -source 8 -target 8 -cp $JsonJar -d $TestRoot $source (Join-Path $PSScriptRoot '..\app\src\main\java\com\suikan\app\AccessPolicy.java') (Join-Path $PSScriptRoot 'transport\UsageTests.java')
if ($LASTEXITCODE -ne 0) { throw 'Usage tests compilation failed' }
& (Join-Path $JdkHome 'bin\java.exe') -cp ($TestRoot+';'+$JsonJar) com.suikan.app.UsageTests
if ($LASTEXITCODE -ne 0) { throw 'Usage tests failed' }
$main=Join-Path $PSScriptRoot '..\app\src\main\java\com\suikan\app'
$extra=@('VideoUsageCounter.java','SourceIdentity.java','Json.java','Transcript.java') | ForEach-Object {Join-Path $main $_}
& (Join-Path $JdkHome 'bin\javac.exe') -encoding UTF-8 -source 8 -target 8 -cp ($TestRoot+';'+$JsonJar) -d $TestRoot @extra (Join-Path $PSScriptRoot 'transport\VideoUsageTests.java')
if ($LASTEXITCODE -ne 0) { throw 'Video usage compilation failed' }
& (Join-Path $JdkHome 'bin\java.exe') -cp ($TestRoot+';'+$JsonJar) com.suikan.app.VideoUsageTests
if ($LASTEXITCODE -ne 0) { throw 'Video usage tests failed' }
