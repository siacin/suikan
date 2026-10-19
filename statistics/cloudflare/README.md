# 随看 Cloudflare 后台部署教程（Windows）

## 0.1.11：升级现有后台

2026-10-10 已为现有 Worker/D1 增加功能次数。若复现升级，先备份现有 D1，执行 `npx.cmd --no-install wrangler d1 migrations apply DB --remote --config wrangler.json`，确认包含 `0002_operations.sql`，再执行 `npm.cmd run deploy`。安装与权限记录、数据库 ID、管理口令和公开地址保持原值，不要重新建库或生成口令。

后台显示累计/今日使用、提取与总结的启动及结果、每日次数和每安装累计次数。协议、计数及保留期限见 [统计口径](../README.md)。旧版继续上报安装/活跃，次数从接入新版后开始。当前免费域名的大陆手机直连问题没有解决。


本程序把统计后台运行在 Cloudflare Workers，数据保存在 D1。电脑只负责部署和打开管理页面，部署成功后不用一直开机。B 站字幕和 AI 总结仍由手机直接请求，视频、字幕、模型 Key 不经过此后台。

**2026-10-09 已完成 Workers + D1 部署、迁移和云端管理口令设置。32 项公网接口检查在电脑现有代理网络下通过；电脑直连超时，用户也确认手机关闭代理后无法打开健康检查。因此当前 workers.dev 地址未通过大陆手机验收，接入版 APK 仅供联调，暂不作为大陆直连发行包。原版 SuiKan-0.1.11.apk 及源码默认地址仍为空。**

本机已部署的管理地址：[https://suikan-statistics.suikan-statistics-cloudflare.workers.dev](https://suikan-statistics.suikan-statistics-cloudflare.workers.dev)；健康检查：[https://suikan-statistics.suikan-statistics-cloudflare.workers.dev/healthz](https://suikan-statistics.suikan-statistics-cloudflare.workers.dev/healthz)。本机继续维护直接使用现有 wrangler.json 和 D1，不必重新注册账号或创建数据库。以下步骤供重新部署或其他开发者复现。

## 一、准备账号和工具

1. 在 [Cloudflare 控制台](https://dash.cloudflare.com/sign-up)注册自己的账号并验证邮箱。选择 Workers Free；可先使用免费 workers.dev 域名部署；能否供大陆手机直连必须实测，可能需要自己的域名或其他部署入口。
2. 在 Windows 安装 [Node.js](https://nodejs.org/en/download) 22 或更新的受支持版本，包含 npm。此电脑已有 Node.js 24，可直接使用。
3. 推荐使用完整源码 `SuiKan-source-0.1.11.zip`；也可以用 `SuiKan-Cloudflare-0.1.11.zip` 只部署后台。解压后找到包含 `package.json` 的 `statistics\cloudflare` 文件夹，在资源管理器地址栏输入 `powershell` 打开终端。

本会话已有源码，直接进入：

```powershell
Set-Location 'C:\Projects\suikan-workspace\outputs\suikan-android\statistics\cloudflare'
node --version
npm.cmd --version
```

以下命令都在此文件夹执行。每一步成功后再继续；若 PowerShell 拦截脚本，仅为当前窗口设置：

```powershell
Set-ExecutionPolicy -Scope Process Bypass
$env:WRANGLER_SEND_METRICS = 'false'
npm.cmd ci --no-audit --no-fund
npm.cmd test
npm.cmd run check
```

测试是本机临时数据库，不需要 Cloudflare 登录，也不会创建虚假云端用户。`check` 是打包检查，不上传服务。依赖版本已用 lock 文件锁定。

## 二、登录并创建数据库

```powershell
npx.cmd --no-install wrangler login --scopes account:read user:read workers_scripts:write d1:write
npx.cmd --no-install wrangler whoami
npx.cmd --no-install wrangler d1 create suikan-statistics --location apac --update-config=false
```

第一条会打开官方浏览器授权页，使用刚注册的账号登录并授权；账号密码和 API Token 都不需要发给别人。若有多个 Cloudflare 账号/组织，确认操作的是自己准备使用的那个。

创建成功会显示 `database_id`，是一串 UUID。将下面示例替换为真正返回的 ID：

```powershell
.\configure.ps1 -DatabaseId '这里替换为实际的database_id'
npx.cmd --no-install wrangler d1 migrations apply DB --remote --config wrangler.json
```

迁移命令可能要求确认，核对目标是新建的 `suikan-statistics` 后输入 `y`。它创建统计和权限表，不删除旧表。`configure.ps1` 仅写本地配置；重复执行会把上次配置备份到 `.local\wrangler.previous.json`。数据库名称如已存在，先查看控制台并复用其 ID，不要反复创建多份数据库。

如果需要不同名称：创建时使用新名称，再运行 `configure.ps1 -DatabaseId '真实ID' -DatabaseName '新数据库名' -WorkerName '新服务名'`。

## 三、部署服务并设置管理口令

```powershell
npm.cmd run deploy
node .\set-admin-password.mjs
```

初次部署时，Cloudflare 可能要求设置账号的 `workers.dev` 子域名，按 CLI 或控制台提示完成。部署成功会输出类似 `https://suikan-statistics.你的子域.workers.dev` 的真实地址。

设置口令脚本会生成 48 字符随机口令，通过标准输入传给 `wrangler secret put`，保存在云端 Secret 和本机 `.local\admin-token.txt`。不会把口令写进源码、APK 或 URL，也不在终端回显。上传失败可原样重试；旧的有效口令文件保留，新值暂存在 `admin-token.pending.txt`。

**初次部署完成但口令尚未设置时，API 和健康检查返回 503 `not_configured`，这是预期行为。** 设置后验证：

```powershell
node .\probe.mjs 'https://实际部署地址.workers.dev'
notepad .\.local\admin-token.txt
```

在浏览器打开部署输出的 HTTPS 根地址，填写记事本中的口令，点击「查看统计」。新数据库的累计、日活等为 0。这个口令只交给管理员；丢失本机文件但服务仍在时，可以在 Cloudflare 控制台更新同名 Secret 并妥善保存新口令。

不要把本机原有 Windows 后台的口令当作云端口令；两者独立。复制安装编号就能在网页筛选并停用/恢复对应安装。

## 四、先测大陆手机直连

**当前部署实测：电脑直连超时，用户已确认手机关闭代理后打不开或超时。该地址目前未通过验收。** 不要将电脑代理下的通过结果当作大陆可用。换域名或部署入口后重新完成本节，再重建并分发 APK。

关闭手机代理，用手机浏览器打开 `https://实际部署地址/healthz`，应显示 `{"ok":true}`。分别验证移动数据和 Wi-Fi；有条件再找移动、联通、电信不同网络的朋友测试。

`probe.mjs` 只说明当前电脑网络是否可用，不能证明手机网络可用。需要多次、不同时段测试。**免费 Workers 不包含 Cloudflare 中国网络；官方指出跨境访问存在延迟和可靠性问题。** 自有域名也不等于接入大陆节点。若目标网络经常失败，先更换部署方案，不要通过延长超时把问题掩盖后直接分发。[Cloudflare 中国网络说明](https://developers.cloudflare.com/china-network/)

## 五、把真实地址接入 APK

完整源码才能执行本步骤。Android SDK 36、Build Tools 和 JDK 17 按项目 README 准备。`build-connected-apk.ps1` 会调用原有构建脚本，只改变本次构建的统计地址。

```powershell
.\build-connected-apk.ps1 -BaseUrl 'https://实际部署地址.workers.dev' -SdkRoot 'D:\Android\android-sdk' -JdkHome '你的JDK17目录'
```

这台电脑的 SDK 36 和 JDK 17 已在本项目工作目录准备好，可以使用下列参数（先替换公网地址）：

```powershell
$taskRoot = 'C:\Projects\suikan-workspace'
$buildOptions = @{
    BaseUrl = 'https://实际部署地址.workers.dev'
    JdkHome = "$taskRoot\work\tools\jdk17\jdk-17.0.20.1+1"
    PlatformJar = "$taskRoot\work\tools\platform-36\android-36\android.jar"
    BuildToolsRoot = "$taskRoot\work\tools\build-tools-36\android-16"
}
.\build-connected-apk.ps1 @buildOptions
```

结果默认在完整源码的 `dist\SuiKan-0.1.11-cloudflare.apk`。也可传 `-OutputApk '绝对路径\随看.apk'`。需要继续覆盖安装原有随看时，必须沿用作者原来的签名文件；本机脚本默认沿用原构建目录中的签名，可通过 `-BuildBase` 指定含原签名的目录。没有原签名的其他电脑只能生成自己签名的包，不能覆盖旧版。

不要把 `admin-token.txt` 填进 APP；APP 只需要公开的 `/v1/usage` 地址。不要分享占位地址 APK。若不会构建，完成云端部署后提供**公开的 HTTPS 根地址**即可协助打包，不需要提供管理口令。

安装后的验收顺序：

1. 启动 APP，网页累计数和今日活跃增加 1；同一天多次打开，日活保持不变。
2. 在 APP 设置复制安装编号，在网页找到唯一对应记录。
3. 点「停用」，填写可选原因。等待下一次成功检查（前台一般一分钟内），新的字幕提取和总结应被阻止，已有笔记仍能导出。
4. 点「恢复」，下次成功检查后再次提取/总结。
5. 切换 Wi-Fi/移动数据、重启 APP，再核对权限和统计。

安装编号代表安装实例。清除数据或重装会生成新编号，远程停用无法阻止这种绕过；不是账户级授权或防破解系统。

## 六、免费额度与离线行为

截至 2026-10-09，官方公布的 Workers Free 为每天 100,000 次请求；D1 Free 每天读取 500 万行、写入 10 万行，账号总存储 5 GB，单库上限 500 MB。索引更新也计入写入。额度不是用户人数。[Workers 限制](https://developers.cloudflare.com/workers/platform/limits/)、[D1 价格](https://developers.cloudflare.com/d1/platform/pricing/)、[D1 限制](https://developers.cloudflare.com/d1/platform/limits/)

本版权限检查仍为前台每分钟一次；后台对同一天、同版本的重复检查只读不写。首次/新一天使用、补报缺失日期、版本变更及管理员修改权限才会写入相应记录。管理页刷新也会消耗请求和读额度。若一台手机全天保持前台，权限检查就可能接近 1,440 次/天，不能把免费额度理解成支持十万用户。

活跃和每日功能次数每天北京时间 02:00 由定时任务清理，保留最近 90 天，最多多保留约一天；安装累计、功能累计次数和使用权限继续保留。Cloudflare 限流按节点近似执行，不能当作全球精确额度保护；控制台中仍应关注 Workers 请求和 D1 Row Metrics。

连接失败时，APP 保留上次成功的权限状态：从未取得状态则允许使用；已经停用则保持停用，直到成功获取恢复状态。后台新停用指令也需要手机成功联网才能生效。统计会有限重试补报，长期离线可能漏计。**本方案不是严格的实时强制授权。**

## 七、备份、升级和维护

数据库备份放入私有目录：

```powershell
New-Item -ItemType Directory -Path .local -Force | Out-Null
npx.cmd --no-install wrangler d1 export DB --remote --config wrangler.json --output .local\suikan-backup.sql
```

备份含安装散列、日期和停用原因，不应随软件分发。新版本更新时先备份，再应用新增迁移并运行 `npm.cmd run deploy`；同一 Worker、D1 和 Secret 保持不变时，通常无需重编 APK。后台地址变更则需要重新构建并更新用户 APK。

已有 Windows SQLite 数据不会自动转移到 D1。本版提供独立的新库部署，先备份原库再安排迁移；不要把本机示例数据导入正式后台。

常见问题：

| 现象 | 处理 |
|---|---|
| 找不到 npm/npx | 安装 Node.js 后重新打开终端，命令使用 `npm.cmd`/`npx.cmd` |
| 登录或安装依赖失败 | 先检查这台电脑是否能访问 Cloudflare、npm，不修改手机逻辑掩盖部署失败 |
| `not_configured` | 执行口令脚本；检查 Secret 名为 `SUIKAN_ADMIN_TOKEN` |
| `/healthz` 为 503 `temporarily_unavailable` | 检查 D1 绑定、真实 ID、迁移及免费额度 |
| 管理页有界面但没有统计 | 管理口令正确才能读数据；确认 APK 编入了该云端地址 |
| 本机有统计，云端没有 | 本机 Python 库与云端 D1 是两个独立数据库 |
| 云端禁用手机没反应 | 检查安装编号、APP 是否为接入版及手机能否直连；已有任务不会被中断 |
| 手机频繁超时 | 重新评估目标网络连通性，考虑迁移至大陆云服务 |

本次已完成官方 OAuth 授权、D1 创建和远程迁移、Worker 部署、Secret 上传、云端 HTTPS 访问及 32 项公网接口检查。检查通过所用网络为电脑现有代理；电脑和用户手机直连均失败。定时触发器已配置，但尚未等待一次真实定时执行，也未验证长期免费额度表现或多运营商稳定性。后续验收按第四、第五节执行。

0.1.11 本地 27 项 Worker/D1 用例与 28 项后台页面检查通过，另通过 26 项真实云端接口检查，以及安卓真实任务计数、APK 构建、签名和主 dex 一致性检查。云端测试安装已清理，升级交付时保留原有 1 个安装，功能启动次数为 0。详见 [验证记录](TESTING.md)。

官方命令参考：[Wrangler](https://developers.cloudflare.com/workers/wrangler/commands/)、[D1 创建与迁移](https://developers.cloudflare.com/d1/wrangler-commands/)、[workers.dev 域名](https://developers.cloudflare.com/workers/configuration/routing/workers-dev/)。
