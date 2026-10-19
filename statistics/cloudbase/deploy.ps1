param([string]$NodePath='node',[string]$NpmPath='npm.cmd',[string]$CliPath='tcb',[switch]$ReplaceExisting)
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
  $configPath=Join-Path $PSScriptRoot 'cloudbaserc.json'
  $runtimePath=Join-Path $PSScriptRoot '.local/runtime.env.json'
  if (!(Test-Path -LiteralPath $configPath) -or !(Test-Path -LiteralPath $runtimePath)) { throw '先运行 configure.ps1 和 set-runtime.ps1。' }
  $config=Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
  $runtime=Get-Content -LiteralPath $runtimePath -Raw -Encoding UTF8 | ConvertFrom-Json
  if ($config.envId -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_-]{5,127}$' -or $config.envId.StartsWith('replace-')) { throw '无效环境 ID。' }
  $required=if ($runtime.SUIKAN_DB_MODE -eq 'cloudbase-rpc') { @('SUIKAN_ENV_ID','CLOUDBASE_APIKEY','SUIKAN_ADMIN_TOKEN') } else { @('PGHOST','PGPORT','PGDATABASE','PGUSER','PGPASSWORD','PGSSLMODE','SUIKAN_ADMIN_TOKEN') }
  foreach ($key in $required) { if (!$runtime.$key) { throw ('缺少服务端配置：'+$key) } }
  & $NpmPath ci --ignore-scripts --no-audit --no-fund
  if ($LASTEXITCODE -ne 0) { throw '依赖安装失败。' }
  & $NodePath tools/bundle.mjs
  if ($LASTEXITCODE -ne 0) { throw '打包失败。' }
  $upload=Join-Path $PSScriptRoot '.local/upload/suikan-statistics'
  Push-Location $upload
  try { & $NpmPath ci --omit=dev --ignore-scripts --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { throw '生产依赖安装失败。' } }
  finally { Pop-Location }
  $config.functions[0] | Add-Member -NotePropertyName dir -NotePropertyValue $upload -Force
  $config.functions[0].installDependency=$false
  $config.functions[0] | Add-Member -NotePropertyName envVariables -NotePropertyValue $runtime -Force
  $privateConfig=Join-Path $PSScriptRoot '.local/deployment.json'
  [IO.File]::WriteAllText($privateConfig,($config | ConvertTo-Json -Depth 16),(New-Object Text.UTF8Encoding($false)))
  # The small package can use direct ZIP upload, avoiding Windows COS cleanup locks.
  $taskCliArgs=@('fn','deploy','suikan-statistics','--httpFn','--path','/','--deployMode','zip','--config-file',$privateConfig,'--region',$config.region,'--yes')
  if ($ReplaceExisting) { $taskCliArgs+='--force' }
  # This deploys only the named function; it does not purchase/upgrade an environment.
  & $CliPath @taskCliArgs
  if ($LASTEXITCODE -ne 0) { throw '腾讯云部署失败，请在控制台核对环境、权限、网络和套餐。' }
}
finally { Pop-Location }
