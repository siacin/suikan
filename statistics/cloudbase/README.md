2026-10-19 更新：已为原上海环境应用增量迁移 0003 并更新函数代码。安卓 0.1.17 / 浏览器 0.1.1 支持视频分析统计，真实线上 24 项检查通过并清理自建测试记录，原口令与统计保留。视频明细保留 90 天。老环境升级只应用 0003 后更新函数；不要重新初始化或导入旧备份。若 CLI/SDK 提示登录失效，先检查终端继承的 HTTP_PROXY/HTTPS_PROXY 是否指向已停止的本机代理，再用官方登录验证。只更改当前进程，避免擅自修改系统代理。

# 随看：腾讯云 CloudBase 部署（Windows）

0.1.12 的国内后台使用上海 CloudBase HTTP 云函数 + 原生 PostgreSQL RPC。手机只请求公开 HTTPS 接口；数据库及环境级 API Key 留在后台。原有首次启动、日活、版本、功能次数和停用/恢复协议均保持兼容。

## 当前部署状态（2026-10-10）

- 已部署到现有上海体验版环境，公网入口：[随看管理后台](https://suikan-d9gi643yk8b95195e.service.tcloudbase.com/)，健康检查为 `/healthz`。
- 真实云端接口 28 项检查通过，包括多请求并发去重、重复/乱序上报、停用与恢复；自建测试安装已清理。
- 已导入 Cloudflare 的 2026-10-10 备份：1 个原安装、1 条活跃记录，备份时没有功能次数或停用记录。管理口令沿用原值。这里只迁移备份时点的数据，旧客户端之后上报到 Cloudflare 的新数据不会自动同步。
- 用户确认手机访问 `/healthz` 返回 `{"ok":true}`；新版 APK 的实际使用上报仍需覆盖安装后确认。默认域名用于测试，未购买域名、升级套餐或启用自动续费。旧 Cloudflare 服务继续保留。
- 公网地址不代表已完成正式分发。腾讯云默认域名可能有中间提示、下载响应头、使用期限和频率限制；正式入口应使用已备案自有域名。

## 费用与环境条件

截至 2026-10-10，官方体验版每月提供 3000 资源点，需按规则手动续期，云函数固定 256MB / 3 秒，不能绑定自定义域名。个人版限时价为 19.9 元/月，可绑定 1 个域名；实际订单和配额以控制台为准。[官方套餐与额度](https://cloud.tencent.com/document/product/876/127357)。

体验版和个人版均不支持连接自定义 VPC。本项目默认通过 CloudBase 原生 RPC 访问同环境 PostgreSQL，因此不依赖自建 VPC 或额外购买数据库。另保留 `pg` 直连适配用于确实拥有可访问连接地址的部署；不能凭数据库内网 IP 推断体验版可直连。[PostgreSQL 连接说明](https://docs.cloudbase.net/database/postgresql/connecting-to-postgresql)。

免费环境只能先验收功能，不能承诺永久免费、长期稳定或任意使用规模。统计服务不会替用户总结视频，不承担模型请求，原有手机直连模型 API 的流程保持不变。

## 1. 安装工具并登录

安装 Node.js 20.19 以上，PowerShell 运行：

```powershell
npm install -g @cloudbase/cli@3.8.5
tcb login
tcb env list --region ap-shanghai --json
```

以最后的环境查询成功作为实际登录依据。网页“授权成功”但 CLI 仍提示未登录时，运行官方备用方式：

```powershell
tcb login --key
```

仅在本机终端输入 SecretID / SecretKey。可使用 `login-local.ps1` 打开同一登录流程。不要将密码或密钥放入命令参数、聊天、Git、APK 或公开 ZIP。[官方登录说明](https://docs.cloudbase.net/cli-v1/install#login-authentication)。

## 2. 选择已有上海 PostgreSQL 环境

在 [CloudBase 控制台](https://tcb.cloud.tencent.com/) 确认环境地域为上海，数据库类型为 PostgreSQL。使用原有体验环境即可先做测试；没有时按控制台当期规则领取免费体验环境。脚本不会创建订单、升级套餐或替你购买资源。

下面命令均在源码 `statistics/cloudbase` 目录运行，环境 ID 用自己的真实值替换：

```powershell
.\configure.ps1 -EnvironmentId '你的真实环境ID' -Region ap-shanghai
$taskCliEntry = Join-Path (npm root -g) '@cloudbase/cli/dist/standalone/cli.js'
node tools/prepare-native.mjs '你的真实环境ID' $taskCliEntry
```

第二条脚本按顺序执行 `0001_schema.sql`、`0002_rpc.sql`、`0003_video_usage.sql`，创建随看独立 schema、表和服务端 RPC，生成仅限当前环境的 API Key，并将配置写入 `.local/runtime.env.json`。已有配置时会停止，避免覆盖。RPC 表不会暴露给匿名用户；匿名和普通登录角色不能执行入口函数。管理操作仍需随看管理口令。

管理口令保存在 `.local/admin-token.txt`。新目录没有该文件时，优先沿用同一源码中 Cloudflare 的私有口令，否则自动生成随机口令；如需手动沿用，请在执行准备脚本前把原口令保存到该文件。原始口令只供管理员本机查看：

```powershell
Get-Content .local/admin-token.txt
```

`.local`、真实 `cloudbaserc.json`、API Key 和数据库备份不在分发包中。后台只使用环境级 API Key，不需要将账号级 SecretKey 写入云函数。[HTTP 云函数认证说明](https://docs.cloudbase.net/en/cloud-function/develop/how-to-writing-functions-code)。

## 3. 从 Cloudflare 导入历史数据（如需要）

先备份 D1，再转换和导入到空的随看目标库：

```powershell
# 在 Cloudflare 项目目录执行；备份文件必须留在自己的私有目录。
npx wrangler d1 export suikan-statistics --remote --config wrangler.json --output .local/backup-for-cloudbase.sql

# 回到 statistics/cloudbase 目录。
python tools/convert-d1-backup.py ../cloudflare/.local/backup-for-cloudbase.sql .local/cloudflare-snapshot.json
node tools/import-cloudbase.mjs .local/cloudflare-snapshot.json
```

源库导出期间可能短暂无法查询，安排在低使用时段。导入校验字段、散列编号、日期、计数、外键关系，采用单个 PostgreSQL 事务；目标已有随看数据时拒绝导入，任何失败都回滚。CLI 导入工具将 SQL 正文限制为 1MiB；更大的历史数据需另行准备分批离线迁移，不能拆成多次重复运行本导入工具。

只保留原有记录，不会补造旧版从未收集的功能次数。切换时应先暂停旧入口写入，让客户端保留待补报快照，再导出最终备份、导入空库、验证后启用新入口；暂时停写用 503 响应而非 204，避免客户端误以为已送达。如果旧入口继续接收旧 APK，两边数据会分开，不能把两个累计数字直接相加，也不能反复覆盖导入。

当前部署已导入一个备份时点，旧入口仍保留；尚未进行冻结旧入口或自动转发切换。若后续旧端产生了新数据，应先准备并审核增量合并方案，再正式切换。

## 4. 部署 HTTP 云函数

```powershell
.\deploy.ps1
```

脚本本机安装生产依赖，准备包含 `scf_bootstrap` 的小包，以 ZIP 方式上传，避免官方 CLI 在 Windows COS 上传后的临时文件锁问题。只部署 `suikan-statistics`，绑定根路径 `/` 和匿名公开访问；APP 统计入口为 `/v1/usage`，管理入口的数据仍由独立口令保护。已存在同名函数且确实要更新时使用 `-ReplaceExisting`。

默认配置匹配体验版的 256MB / 3 秒。已由用户在控制台开通个人版后，可以重新执行 `configure.ps1 -PersonalPlan` 生成 15 秒配置；该选项本身不购买套餐。

控制台上传时使用部署包中的 `suikan-statistics-function.zip`：选 HTTP 函数、Nodejs20.19、监听 9000，并手工填写私有配置中的四个环境变量 `SUIKAN_DB_MODE`、`SUIKAN_ENV_ID`、`CLOUDBASE_APIKEY`、`SUIKAN_ADMIN_TOKEN`。启动文件使用 LF 和可执行权限，ZIP 已保留该属性。绑定根路由且允许匿名访问。[官方函数部署](https://docs.cloudbase.net/cli-v1/functions/deploy)。

## 5. 验收、接入 APK 与 Windows 管理

部署完成后使用 CLI 输出的真实 HTTPS 根地址：

```powershell
node probe.mjs 'https://你的实际后台域名' .local/admin-token.txt
.\build-connected-apk.ps1 -BaseUrl 'https://你的实际后台域名' -SdkRoot 'D:\Android\android-sdk' -JdkHome 'JDK17目录'
```

构建脚本先检查真实 `/healthz`，失败则不生成接入包。完整源码、Android SDK 36、Build Tools 36 及原签名密钥需要可用；只解压后台部署包不能编译安卓。发布升级包须沿用原证书。本项目本次 APK 使用原证书并将 versionCode 增至 13。

用手机关闭代理分别在移动数据和 Wi-Fi 打开 `/healthz`，应直接返回 `{"ok":true}`。安装接入包、打开一次，浏览器后台安装数应增加；同一天重复打开不增加日活。做一次字幕提取与总结，检查启动/成功/失败次数；按安装编号停用并等待前台刷新或重新进入 APP，确认新任务被阻止，再恢复确认可用。已有笔记仍可查看和导出。

Windows 管理只需浏览器打开后台首页并输入原管理口令，电脑不必一直开机。默认域名遇到腾讯云中间提示页，应先确认是官方测试域名提示；正式分发前改用自有域名。[默认域名限制](https://docs.cloudbase.net/service/alias)。

## 6. 正式分发的域名步骤

1. 注册一个由自己控制的域名，完成实名及大陆服务需要的 ICP 备案，按腾讯云控制台办理；域名费用和 CloudBase 付费套餐在实际订单中确认。
2. 升级到支持绑定域名的套餐，在 CloudBase HTTP 网关绑定子域名，例如 `stats.你的域名`，按控制台给出的 CNAME 配置 DNS，申请并绑定 HTTPS 证书。
3. 让新域名的根路径 `/` 路由到同一统计函数，保持四个 API 路径不变；管理页面也在该域名上。
4. 重跑只读检查及手机直连验收，用新根地址重新构建并签名 APK，之后分发这个包。
5. 核对历史数据切换方案、资源点用量与到期日期，保留旧服务和私有备份，验收完成后再决定是否关闭旧资源。

目前没有自有域名，因此本次交付属于可验证的腾讯云接入测试版本。

## 本地验证

```powershell
npm ci --ignore-scripts
npm test
python -m unittest discover -s tests -p test_convert.py
```

Node 测试使用 PGlite 真实 PostgreSQL SQL 引擎，包含原子回滚、去重、旧协议、停用恢复、保留期、角色权限与迁移。本轮 45 项 Node 及 4 项 Python 转换器检查通过。嵌入式测试只有一个数据库连接；另用 28 项真实云端检查验证多连接事务，不能用本地测试替代云端部署和手机网络验收。

Windows 可用内存很低时，PGlite 的 WASM 编译可能导致测试进程内存不足；此时不要并行测试。在本目录按文件顺序直接执行即可降低编译内存：

```powershell
foreach ($taskTest in @('backend', 'migration', 'rpc', 'cloud-import')) {
    node --liftoff-only --wasm-num-compilation-tasks=1 --no-wasm-tier-up "tests/$taskTest.test.mjs"
    if ($LASTEXITCODE -ne 0) { throw "测试失败：$taskTest" }
}
```

每天明细保留 90 天，由收到新上报时按北京时间惰性清理；安装累计及管理状态继续保留。函数的限流是单实例的轻量保护，多个云实例会分别计数；正式大规模分发应按网关能力设置全局限流并监控数据库连接和资源点用量。应用不输出请求正文或凭据，但腾讯云平台仍按其服务规则处理运行日志及连接元数据。
