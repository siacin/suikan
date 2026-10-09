# 随看 SuiKan

手机接收 B 站分享链接，提取字幕并直接调用用户配置的模型 API，无需电脑后端。原生 Java，生产 APK 无第三方运行库，Android 8.0 及以上。

作者 **Cin** · [下载 0.1.7 APK](https://github.com/siacin/suikan-android/releases/download/v0.1.7/SuiKan-0.1.7.apk) · [所有版本](https://github.com/siacin/suikan-android/releases) · [使用说明](使用说明.md) · [验证记录](验证记录.md)

## 开始使用

1. 下载 Release 中的 APK 并安装。0.1.7 沿用既有签名，可覆盖旧版本。
2. 在 B 站打开普通投稿视频，分享给「随看」；也可粘贴链接 / BV 号或导入 TXT、SRT、VTT 字幕。
3. 点击「只提取字幕」即可查看、复制或另存完整字幕，这一步无需模型 API。
4. 需要总结时，在「设置」填写自己的 HTTPS API 地址、模型名称和 API Key，选择简洁 / 标准 / 详细，再点击「总结整段视频」。

悬浮入口使用最近一次分享的视频链接，换视频后重新分享。当前版本保持分享流程，无需无障碍、通知读取或屏幕捕获授权。权限和数据说明见 [PRIVACY.md](PRIVACY.md)。Release 附带的 `SHA256SUMS.txt` 提供 APK 和源码 ZIP 的校验值。

## 0.1.7 的界面调整

- 提取的字幕直接展示全文，240dp 固定高度，内部独立滚动并显示滚动条；标题旁可收起 / 展开。
- 页面切换与屏幕旋转保留展开状态及阅读位置；收起不影响完整字幕复制与保存。
- 「关于随看」只显示版本号和作者 Cin。

## 继承 0.1.6 的速度优化

- 简洁、标准模式优先一次读取完整字幕并直接生成总结，省去逐批证据抽取。请求用 `[s编号] 字幕文字` 紧凑表示，不重复发送每条字幕的时间、字段名等 JSON 结构。
- 普通长度只需一次模型生成；超过约 32000 字符的紧凑输入预算时按完整顺序分批，每批直接总结，再归纳。接口明确返回输入长度错误时自适应缩小，不把鉴权 / 限流错误当作长度错误。
- 每个总结条目引用真实字幕 ID，客户端关联原文和精确时间。无效引用过滤；缺少概述或有效依据时失败，保留旧笔记。
- 流式响应优先直接按 Content-Type 读取，避免安卓固定长度预读阻塞小首段。生成中的概述可作为草稿展示，草稿不进入历史 / 导出，取消或失败即撤下；不展示思考原文。
- 通过校验的完整分段可恢复。详细模式继续逐段整理全部论据、案例、条件与方法；快速模式使用独立流程版本，不混用旧流程缓存。

## 其他功能

- 普通视频、BV 号、b23.tv 分享链接及指定分 P；中文字幕优先，使用助手自己的官方网页登录会话。
- 「只提取字幕」无需模型 API；全文滚动阅读、复制全文或带时间戳全文，使用系统文件选择器另存 UTF-8 TXT / SRT。SRT 必须有完整真实起止时间。
- TXT / SRT / VTT 文件与粘贴文稿导入；最近字幕保存在手机，可全文查看、复制 / 保存。
- 三种总结详细程度、历史笔记、Markdown 导出、悬浮入口、前台进度与取消。
- Android Keystore / AES-GCM 保存 API Key，B 站会话与模型请求隔离。大文稿、导出待保存文本与服务任务通过私有文件传递。
- TCP 60 秒、读取 / TLS 15 分钟、单次模型调用 60 分钟。连接重置 / 提前 EOF / 空正文最多重试一次；请求发出后仍中断可有限拆分。超时和鉴权错误不按此规则重试。重试 / 拆分可能增加费用。

## 构建

Android Studio 打开本目录；JDK 17、Gradle wrapper 8.11.1、AGP 8.10.1、compile / target SDK 36、min SDK 26。

```powershell
.\gradlew.bat assembleDebug
```

本次实际 APK 使用 `build-apk.ps1` 通过 Android SDK CLI 构建，aapt2、javac、d8、zipalign、apksigner；不是 Gradle 构建结果。

```powershell
.\build-apk.ps1 -SdkRoot 'Android SDK目录' -JdkHome 'JDK 17目录' -BuildBase '.\.local-build' -OutputApk '.\dist\SuiKan-0.1.7.apk'
```

上述命令输出 `dist/SuiKan-0.1.7.apk`，构建缓存保存在 `.local-build/`。脚本未指定输出路径时默认输出到上级目录。可指定 `-PlatformJar`、`-BuildToolsRoot`、`-BuildBase`、`-OutputApk`。保留构建缓存中的开发签名才能覆盖升级，签名私钥不包含在源码 ZIP 中。商店发布需要另行配置发布签名。

## 验证（0.1.6 完整回归记录，0.1.7 界面验证见验证记录）

先运行本机 `tests/mock_model_server.py`，再执行：

```powershell
.\tests\run-transport-tests.ps1 -JsonJar 'org.json 测试 JAR' -JdkHome 'JDK目录' -TestRoot '.\.local-build\transport-tests'
```

146 项主机检查：51 传输、31 详细流程、20 核心、12 字幕 / 模式标识、32 快速流程。覆盖完整 1077 段一次生成、长文稿完整分批、上下文 / 输出限制、有限断线恢复、缓存、真实依据、精确时间和安全草稿。`tests/transport/android/` 仅为主机编译适配，不进入 APK。

安装主 APK 后，运行 `tests/build-tests.ps1 -BuildInfo .local-build/latest-build.json` 构建并安装同签名测试 APK，在 Android 9 模拟器执行：

```text
adb shell am instrument -w -r com.suikan.app.tests/com.suikan.app.TestHarness
adb shell am instrument -w -r com.suikan.app.tests/com.suikan.app.FeatureHarness
adb shell am instrument -w -r com.suikan.app.tests/com.suikan.app.SpeedHarness
```

三组分别 35 / 18 / 17 项 Android 检查，共 70 项，含实际 HTTP POST 次数、首段很小的 SSE 在完成前展示草稿、UI、前台保存、缓存、取消不覆盖旧笔记、剪贴板与文件写入。总计 216 项自动检查。测试服务仅在本机，模拟器使用 `10.0.2.2:18765`；生产使用无需测试服务。

真实 WorldCodes / DeepSeek 标准总结另使用既有测试视频的完整 1077 段字幕，一次生成约 38.6 秒。模型测试走电脑已有代理，不是重新获取 B 站字幕或手机直连测速。具体证据和历史验证记录见 [验证记录](验证记录.md)。

## 主要实现

| 文件 | 职责 |
|---|---|
| `VideoLink` / `BiliClient` / `VideoSource` | 链接、指定分 P、完整字幕与导入来源 |
| `FastSummaryPipeline` | 一次直接总结、长文稿分批、校验与本地依据关联 |
| `SummaryPipeline` | 详细模式分段抽取 / 详解 / 全视频总览 |
| `SummaryDepth` | 篇幅提示、流程版本与模式标识 |
| `Http` / `SummaryDraft` | JSON / SSE、超时 / 恢复 / 取消、受限概述草稿 |
| `SummaryService` / `JobCache` | 前台任务、小型 IPC、完整分段恢复 |
| `SubtitleFiles` / `SubtitleStore` / `LocalStore` | 字幕文件、最近字幕、笔记历史 / Markdown |
| `MainActivity` / `OverlayService` | 手机界面与悬浮入口 |

不包含音频转写、画面分析、跨多 P 合并或离线大模型。不读取 B 站安卓 APP 私有数据。模型 API 与调用费用由使用者提供。字幕获取参考资料见 [THIRD_PARTY.md](THIRD_PARTY.md)。使用方法见 [使用说明](使用说明.md)。
