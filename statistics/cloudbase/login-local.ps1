param([string]$CliPath='tcb',[string]$NodePath='')
$ErrorActionPreference='Stop'
if ($NodePath) {
  if (!(Test-Path -LiteralPath $NodePath)) { throw '找不到 Node.js。' }
  $env:Path=(Split-Path -Parent ([IO.Path]::GetFullPath($NodePath)))+';'+$env:Path
}
Write-Host '腾讯云本机登录：只在这个窗口输入 SecretID / SecretKey，不要发到聊天里。'
Write-Host '官方密钥管理：https://console.cloud.tencent.com/cam/capi'
Write-Host '此操作仅保存 CLI 登录状态，不创建或购买云资源。'
# Official CLI owns validation/storage. Do not put credentials in command arguments or logs.
& $CliPath login --key
if ($LASTEXITCODE -ne 0) { throw '官方 CLI 登录未完成。' }
$privateDir=Join-Path $PSScriptRoot '.local'
New-Item -ItemType Directory -Path $privateDir -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $privateDir 'login-result.json'),('{"success":true,"completedAt":"'+[DateTime]::UtcNow.ToString('o')+'"}'),(New-Object Text.UTF8Encoding($false)))
Write-Host '登录完成，可以回到聊天继续部署。'
Read-Host '按 Enter 关闭此窗口' | Out-Null
