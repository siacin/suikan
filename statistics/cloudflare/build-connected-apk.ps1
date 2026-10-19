param(
    [Parameter(Mandatory=$true)][string]$BaseUrl,
    [string]$SdkRoot = 'D:\Android\android-sdk',
    [string]$JdkHome = '',
    [string]$PlatformJar = '',
    [string]$BuildToolsRoot = '',
    [string]$BuildBase = '',
    [string]$OutputApk = ''
)
$ErrorActionPreference = 'Stop'
$uri = $null
if (![Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -ne 'https' -or !$uri.Host -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/') { throw '请提供后台 HTTPS 根地址，例如 https://suikan-statistics.你的子域.workers.dev ，不要附带路径或口令。' }
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if (!(Test-Path -LiteralPath (Join-Path $project 'build-apk.ps1'))) { throw '此步骤需要完整 Android 源码包，请将 cloudflare 目录放在源码 statistics 内。' }
if (!$OutputApk) { $OutputApk = Join-Path $project 'dist\SuiKan-0.1.17-cloudflare.apk' }
$params = @{ StatisticsUrl=$uri.GetLeftPart([UriPartial]::Authority)+'/v1/usage'; SdkRoot=$SdkRoot; OutputApk=$OutputApk }
if ($JdkHome) { $params.JdkHome=$JdkHome }
if ($PlatformJar) { $params.PlatformJar=$PlatformJar }
if ($BuildToolsRoot) { $params.BuildToolsRoot=$BuildToolsRoot }
if ($BuildBase) { $params.BuildBase=$BuildBase }
& (Join-Path $project 'build-apk.ps1') @params
