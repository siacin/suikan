param(
    [Parameter(Mandatory=$true)][string]$DatabaseId,
    [string]$DatabaseName = 'suikan-statistics',
    [string]$WorkerName = 'suikan-statistics'
)
$ErrorActionPreference = 'Stop'
if ($DatabaseId -notmatch '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' -or $DatabaseId -eq '00000000-0000-0000-0000-000000000000') { throw '请填写 Cloudflare 创建 D1 后返回的真实 database_id。' }
foreach ($value in @($DatabaseName,$WorkerName)) { if ($value -notmatch '^[a-z][a-z0-9-]{0,62}$') { throw '名称须以小写字母开头，仅含小写字母、数字和连字符，最多 63 字符。' } }
$config = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'wrangler.example.json') -Raw | ConvertFrom-Json
$config.name = $WorkerName
$config.d1_databases[0].database_name = $DatabaseName
$config.d1_databases[0].database_id = $DatabaseId.ToLowerInvariant()
$target = Join-Path $PSScriptRoot 'wrangler.json'
if (Test-Path -LiteralPath $target) {
    $privateDir = Join-Path $PSScriptRoot '.local'
    New-Item -ItemType Directory -Path $privateDir -Force | Out-Null
    Copy-Item -LiteralPath $target -Destination (Join-Path $privateDir 'wrangler.previous.json') -Force
}
[IO.File]::WriteAllText($target, ($config | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding($false)))
Write-Output "已生成配置：$target"
Write-Output '已有配置会备份到 .local/wrangler.previous.json；本步骤不创建或修改云端资源。'
