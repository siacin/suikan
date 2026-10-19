param(
  [Parameter(Mandatory=$true)][string]$EnvironmentId,
  [ValidateSet('ap-shanghai','ap-guangzhou','ap-beijing')][string]$Region='ap-shanghai',
  [switch]$PersonalPlan
)
$ErrorActionPreference='Stop'
if ($EnvironmentId -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_-]{5,127}$' -or $EnvironmentId.StartsWith('replace-')) { throw '请填写腾讯云控制台显示的真实环境 ID。' }
$config=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'cloudbaserc.example.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$config.envId=$EnvironmentId
$config.region=$Region
if ($PersonalPlan) { $config.functions[0].timeout=15 }
$path=Join-Path $PSScriptRoot 'cloudbaserc.json'
$privateDir=Join-Path $PSScriptRoot '.local'
New-Item -ItemType Directory -Path $privateDir -Force | Out-Null
if (Test-Path -LiteralPath $path) { Copy-Item -LiteralPath $path -Destination (Join-Path $privateDir ('config-backup-'+[DateTime]::UtcNow.ToString('yyyyMMddHHmmss')+'.json')) }
[IO.File]::WriteAllText($path,($config | ConvertTo-Json -Depth 12),(New-Object Text.UTF8Encoding($false)))
Write-Output '已保存部署配置；没有创建资源、开通套餐或产生订单。请在云函数环境变量中填写数据库配置和管理口令。'
