param()
$ErrorActionPreference='Stop'
$privateDir=Join-Path $PSScriptRoot '.local'
New-Item -ItemType Directory -Path $privateDir -Force | Out-Null
$path=Join-Path $privateDir 'runtime.env.json'
if (Test-Path -LiteralPath $path) { throw '已有私有配置，请先备份并在本机修改 .local/runtime.env.json，脚本不会覆盖。' }
$taskPgHost=Read-Host '数据库主机 PGHOST（从腾讯云控制台复制）'
$taskPgPort=Read-Host '数据库端口 PGPORT（留空为 5432）'
if (!$taskPgPort) { $taskPgPort='5432' }
if ($taskPgPort -notmatch '^\d+$' -or [int]$taskPgPort -lt 1 -or [int]$taskPgPort -gt 65535) { throw '无效端口。' }
$taskPgDatabase=Read-Host '数据库名 PGDATABASE'
$taskPgUser=Read-Host '数据库用户名 PGUSER'
$taskPgPassword=Read-Host '数据库密码（隐藏输入，不发送到聊天）' -AsSecureString
$taskPgSsl=Read-Host 'TLS 模式 verify-full / require / disable（留空为 verify-full，以控制台连接要求为准）'
if (!$taskPgSsl) { $taskPgSsl='verify-full' }
if ($taskPgSsl -notin @('verify-full','require','disable')) { throw '无效 TLS 模式。' }
$tokenPath=Join-Path $privateDir 'admin-token.txt'
if (!(Test-Path -LiteralPath $tokenPath)) { throw '先执行 node tools/create-admin-token.mjs，或将原管理口令保存到 .local/admin-token.txt。' }
$token=[IO.File]::ReadAllText($tokenPath).Trim()
if ($token.Length -lt 32 -or $token.Length -gt 256 -or $token -match '[\x00-\x20\x7f]' -or $token.StartsWith('replace-')) { throw '管理口令格式不正确。' }
$taskPasswordText=(New-Object System.Net.NetworkCredential('', $taskPgPassword)).Password
if (!$taskPgHost -or !$taskPgDatabase -or !$taskPgUser -or !$taskPasswordText) { throw '数据库配置不能为空。' }
$value=@{ PGHOST=$taskPgHost; PGPORT=$taskPgPort; PGDATABASE=$taskPgDatabase; PGUSER=$taskPgUser; PGPASSWORD=$taskPasswordText; PGSSLMODE=$taskPgSsl; SUIKAN_ADMIN_TOKEN=$token }
[IO.File]::WriteAllText($path,($value | ConvertTo-Json),(New-Object Text.UTF8Encoding($false)))
$taskPasswordText=$null
Write-Output '私有服务端配置已保存，不包含在 APK 或公开部署包中。'
