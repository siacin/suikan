# 第三方资料与工具

Android SDK、Android API、JDK 和构建工具不打包进生产 APK。

Gradle wrapper 8.11.1 来自 [Gradle 官方仓库](https://github.com/gradle/gradle/tree/v8.11.1/gradle/wrapper)，按 Apache License 2.0 使用，许可证全文见 `licenses/Apache-2.0.txt`。wrapper JAR 已按官方仓库 Git blob SHA 校验；Gradle 分发包的 SHA-256 写入 wrapper properties。

字幕获取流程参考 [BiliNote 字幕获取实现](https://github.com/JefferyHcool/BiliNote/blob/master/backend/app/downloaders/bilibili_subtitle.py)，生产实现为本项目原生 Java 代码。

模型传输协议参考 [OpenAI Chat API](https://developers.openai.com/api/reference/resources/chat)，支持用户自行选择的兼容服务商。项目不内置或分发任何服务商密钥。
