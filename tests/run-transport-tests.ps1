param([Parameter(Mandatory=$true)][string]$JsonJar,[string]$JdkHome='',[string]$TestRoot='')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if (!$JdkHome) { $JdkHome=Split-Path (Split-Path (Get-Command javac -ErrorAction Stop).Source -Parent) -Parent }
if (!$TestRoot) { $TestRoot=Join-Path $projectRoot ('..\..\work\transport-tests\classes-'+[guid]::NewGuid().ToString('N').Substring(0,8)) }
$JsonJar=(Resolve-Path -LiteralPath $JsonJar).Path
New-Item -ItemType Directory -Force -Path $TestRoot | Out-Null
$main=Join-Path $projectRoot 'app\src\main\java\com\suikan\app'
$sources=@('Http.java','Json.java','VideoLink.java','Transcript.java','SummaryPipeline.java','LocalStore.java','SummaryDepth.java','SubtitleFiles.java','FastSummaryPipeline.java','SummaryDraft.java') | ForEach-Object {Join-Path $main $_}
$sources+=Join-Path $PSScriptRoot 'transport\android\webkit\CookieManager.java'
$sources+=Join-Path $PSScriptRoot 'transport\HttpTests.java'
$sources+=Join-Path $PSScriptRoot 'transport\PipelineTests.java'
$sources+=Join-Path $PSScriptRoot 'transport\android\content\Context.java'
$sources+=Join-Path $PSScriptRoot 'transport\android\util\AtomicFile.java'
$sources+=Join-Path $PSScriptRoot 'CoreTests.java'
$sources+=Join-Path $PSScriptRoot 'transport\FeatureTests.java'
$sources+=Join-Path $PSScriptRoot 'transport\FastPipelineTests.java'
$env:JAVA_TOOL_OPTIONS='-Xms32m -Xmx256m -XX:MaxMetaspaceSize=128m'
& (Join-Path $JdkHome 'bin\javac.exe') -encoding UTF-8 -source 8 -target 8 -cp $JsonJar -d $TestRoot @sources
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
$classpath=$TestRoot+';'+$JsonJar
& (Join-Path $JdkHome 'bin\java.exe') -cp $classpath com.suikan.app.HttpTests
if ($LASTEXITCODE -ne 0) { throw 'Transport tests failed' }
& (Join-Path $JdkHome 'bin\java.exe') -cp $classpath com.suikan.app.PipelineTests
if ($LASTEXITCODE -ne 0) { throw 'Detailed pipeline tests failed' }
& (Join-Path $JdkHome 'bin\java.exe') -cp $classpath com.suikan.app.CoreTests
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }

& (Join-Path $JdkHome 'bin\java.exe') -cp $classpath com.suikan.app.FeatureTests
if ($LASTEXITCODE -ne 0) { throw 'Subtitle / depth tests failed' }

& (Join-Path $JdkHome 'bin\java.exe') -cp $classpath com.suikan.app.FastPipelineTests
if ($LASTEXITCODE -ne 0) { throw 'Fast summary checks failed' }
