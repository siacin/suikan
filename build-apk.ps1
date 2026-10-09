param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$PlatformJar = '',
    [string]$JdkHome = '',
    [string]$BuildToolsRoot = '',
    [string]$BuildBase = '',
    [string]$OutputApk = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
if (!$SdkRoot) { $SdkRoot = 'D:\Android\android-sdk' }
if (!$PlatformJar) { $PlatformJar = Join-Path $SdkRoot 'platforms\android-36\android.jar' }
if (!(Test-Path -LiteralPath $PlatformJar)) { throw '请指定 Android 36 的 android.jar，或安装 platforms;android-36。' }
if (!$JdkHome) {
    $javaCompiler = Get-Command javac -ErrorAction Stop
    $JdkHome = Split-Path (Split-Path $javaCompiler.Source -Parent) -Parent
}
$env:JAVA_HOME = $JdkHome
$env:PATH = (Join-Path $JdkHome 'bin') + ';' + $env:PATH
if (!$env:JAVA_TOOL_OPTIONS) { $env:JAVA_TOOL_OPTIONS = '-Xms32m -Xmx256m -XX:MaxMetaspaceSize=128m' }
if (!$BuildBase) { $BuildBase = Join-Path $projectRoot '..\..\work\android-build' }
if (!$OutputApk) { $OutputApk = Join-Path $projectRoot '..\SuiKan-0.1.7.apk' }
if (!$BuildToolsRoot) {
    $tools = Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'build-tools') -Directory | Sort-Object { [version]($_.Name -replace '-.*$','') } -Descending | Select-Object -First 1
    if (!$tools) { throw 'Android SDK 缺少 build-tools。' }
    $BuildToolsRoot = $tools.FullName
}
$toolRoot = (Resolve-Path -LiteralPath $BuildToolsRoot).Path
$PlatformJar = (Resolve-Path -LiteralPath $PlatformJar).Path
$JdkHome = (Resolve-Path -LiteralPath $JdkHome).Path
$buildRoot = Join-Path $BuildBase ('build-' + [guid]::NewGuid().ToString('N').Substring(0,8))
$generated = Join-Path $buildRoot 'generated'
$classes = Join-Path $buildRoot 'classes'
$dex = Join-Path $buildRoot 'dex'
New-Item -ItemType Directory -Path $buildRoot,$generated,$classes,$dex -Force | Out-Null
function Invoke-BuildTool([string]$File, [string[]]$ToolArgs) {
    & $File @ToolArgs
    if ($LASTEXITCODE -ne 0) { throw "构建工具失败：$File (exit $LASTEXITCODE)" }
}
$main = Join-Path $projectRoot 'app\src\main'
$compiled = Join-Path $buildRoot 'resources.zip'
$resourceApk = Join-Path $buildRoot 'resources.apk'
$buildManifest = Join-Path $buildRoot 'AndroidManifest.xml'
[xml]$manifestXml = Get-Content -LiteralPath (Join-Path $main 'AndroidManifest.xml') -Raw -Encoding UTF8
$manifestXml.DocumentElement.SetAttribute('package','com.suikan.app')
$manifestXml.Save($buildManifest)
Invoke-BuildTool (Join-Path $toolRoot 'aapt2.exe') @('compile','--dir',(Join-Path $main 'res'),'-o',$compiled)
Invoke-BuildTool (Join-Path $toolRoot 'aapt2.exe') @('link','-o',$resourceApk,'-I',$PlatformJar,'--manifest',$buildManifest,'--java',$generated,'--min-sdk-version','26','--target-sdk-version','36','--version-code','8','--version-name','0.1.7','-A',(Join-Path $main 'assets'),$compiled)
$sources = @(Get-ChildItem -LiteralPath (Join-Path $main 'java') -Recurse -Filter '*.java') + @(Get-ChildItem -LiteralPath $generated -Recurse -Filter '*.java')
$sourceList = Join-Path $buildRoot 'sources.txt'
$sources | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' } | Set-Content -LiteralPath $sourceList -Encoding ASCII
$bootClasspath = $PlatformJar + ';' + (Join-Path $toolRoot 'core-lambda-stubs.jar')
Invoke-BuildTool (Join-Path $JdkHome 'bin\javac.exe') @('-encoding','UTF-8','-source','8','-target','8','-bootclasspath',$bootClasspath,'-d',$classes,('@' + $sourceList))
$classesJar = Join-Path $buildRoot 'classes.jar'
Invoke-BuildTool (Join-Path $JdkHome 'bin\jar.exe') @('cf',$classesJar,'-C',$classes,'.')
Invoke-BuildTool (Join-Path $toolRoot 'd8.bat') @('--min-api','26','--lib',$PlatformJar,'--output',$dex,$classesJar)
$unsigned = Join-Path $buildRoot 'unsigned.apk'
Copy-Item -LiteralPath $resourceApk -Destination $unsigned
foreach ($dexFile in Get-ChildItem -LiteralPath $dex -Filter '*.dex') {
    Invoke-BuildTool (Join-Path $JdkHome 'bin\jar.exe') @('uf',$unsigned,'-C',$dex,$dexFile.Name)
}
$aligned = Join-Path $buildRoot 'aligned.apk'
Invoke-BuildTool (Join-Path $toolRoot 'zipalign.exe') @('-p','-f','4',$unsigned,$aligned)
$signingKey = Join-Path $BuildBase 'suikan-development.keystore'
if (!(Test-Path -LiteralPath $signingKey)) {
    Invoke-BuildTool (Join-Path $JdkHome 'bin\keytool.exe') @('-genkeypair','-keystore',$signingKey,'-storepass','android','-keypass','android','-alias','suikan','-dname','CN=SuiKan Development,O=Personal','-keyalg','RSA','-keysize','2048','-validity','10000')
}
New-Item -ItemType Directory -Path (Split-Path $OutputApk -Parent) -Force | Out-Null
Invoke-BuildTool (Join-Path $toolRoot 'apksigner.bat') @('sign','--ks',$signingKey,'--ks-pass','pass:android','--key-pass','pass:android','--out',$OutputApk,$aligned)
Invoke-BuildTool (Join-Path $toolRoot 'apksigner.bat') @('verify','--verbose',$OutputApk)
Get-Item -LiteralPath $OutputApk | Select-Object FullName,Length
Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256
[PSCustomObject]@{BuildRoot=$buildRoot;PlatformJar=$PlatformJar;BuildToolsRoot=$toolRoot;JdkHome=$JdkHome;SigningKey=$signingKey;OutputApk=$OutputApk} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $BuildBase 'latest-build.json') -Encoding UTF8
