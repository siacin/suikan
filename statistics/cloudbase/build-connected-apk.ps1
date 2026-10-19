param(
  [Parameter(Mandatory=$true)][string]$BaseUrl,
  [string]$SdkRoot='D:\Android\android-sdk',
  [string]$JdkHome='', [string]$PlatformJar='', [string]$BuildToolsRoot='',
  [string]$BuildBase='', [string]$OutputApk='',
  [string]$NodePath='node'
)
$ErrorActionPreference='Stop'
$uri=$null
if (![Uri]::TryCreate($BaseUrl,[UriKind]::Absolute,[ref]$uri) -or $uri.Scheme -ne 'https' -or !$uri.Host -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/') { throw '请提供已部署的 HTTPS 根地址，不要附带路径、口令或查询参数。' }
# Never ship a package with an invented URL or a service that fails its health check.
& $NodePath (Join-Path $PSScriptRoot 'probe.mjs') $uri.GetLeftPart([UriPartial]::Authority)
if ($LASTEXITCODE -ne 0) { throw '后台健康检查未通过，没有生成接入 APK。' }
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if (!(Test-Path -LiteralPath (Join-Path $project 'build-apk.ps1'))) { throw '请在完整 Android 源码包的 statistics/cloudbase 目录执行。' }
if (!$OutputApk) { $OutputApk=Join-Path $project 'dist/SuiKan-0.1.17-cloudbase-test.apk' }
$params=@{ StatisticsUrl=$uri.GetLeftPart([UriPartial]::Authority)+'/v1/usage'; SdkRoot=$SdkRoot; OutputApk=$OutputApk }
foreach ($key in @('JdkHome','PlatformJar','BuildToolsRoot','BuildBase')) { if ((Get-Variable -Name $key -ValueOnly)) { $params[$key]=(Get-Variable -Name $key -ValueOnly) } }
& (Join-Path $project 'build-apk.ps1') @params
