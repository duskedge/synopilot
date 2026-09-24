# SynoPilot 群晖领航员

在手机上管理群晖 NAS 的 Android 应用：设备状态、Docker 容器、下载（Download Station / qBittorrent / Transmission）、文件和电源，集中在一个 App 里。

## 功能

- **总览**：CPU、内存、网络、存储池与硬盘健康、UPS；断网时显示缓存
- **连接**：主 / 备用地址自动选路，QuickConnect 兜底；证书指纹确认；两步验证与信任设备
- **容器**：Container Manager 容器与 Compose 项目，启停、日志、环境变量、挂载、网络
- **下载**：Download Station、qBittorrent、Transmission 统一管理，自动发现容器里的下载器，剪贴板磁力链接，限速
- **文件**：浏览、搜索、预览、上传下载、分享链接（有效期、提取码、二维码）
- **电源与系统**：重启 / 关机（指纹验证）、定时开关机、网络唤醒、DSM 更新、Hyper Backup、资源监控
- **安全**：自动封锁、登录的设备、证书到期
- **告警**：后台每 15 分钟检查容器、硬盘、空间、UPS、备份、证书，推送通知
- **系统集成**：桌面小部件（NAS 状态、下载进度）、快捷设置磁贴（离线时点按唤醒）、下载进度常驻通知
- 密码、会话只保存在本机，用 Android Keystore 加密；可以开启指纹解锁

## 下载安装

1. 到 [Releases](https://github.com/duskedge/synopilot/releases) 下载最新的 `SynoPilot-x.y.z.apk`；
2. 安装时如果提示「禁止安装未知应用」，在系统设置里允许浏览器（或文件管理器）安装应用；
3. 之后的新版本会在 App 内提示，下载后自动校验，再交给系统安装。

**自动更新说明**

- App 每天检查一次 GitHub Releases，也可以在「设置 → 检查更新」手动检查；
- 安装前会校验文件大小、SHA-256 和签名证书，任何一项不对都会拒绝安装；
- 想提前体验测试版，打开「设置 → 接收测试版」；
- GitHub 下载慢时，可以在「设置 → 更新下载地址」填写镜像地址前缀。

最低支持 Android 8.0。

## 从源码构建

需要 JDK 17 和 Android SDK（compileSdk 37）。

```bash
./gradlew assembleGithubDebug        # 调试版，输出在 app/build/outputs/apk/github/debug/
./gradlew testDebugUnitTest testGithubDebugUnitTest :build-logic:convention:test
```

- 本机的 `JAVA_HOME` 不是 17 也没关系：`gradle/gradle-daemon-jvm.properties` 要求用 JDK 17 运行构建，Gradle 会自动使用已安装的 JDK 17；
- 在国内访问 Google Maven 困难时，可以在项目根目录的 `local.properties`（不会提交）里加一行 `mirror=aliyun`，改用阿里云镜像。

## 发布

推送 `v` 开头的标签即可自动打包并发布到 Releases：

```bash
git tag -a v0.2.0 -m "- 更新说明第一条
- 更新说明第二条"
git push origin v0.2.0
```

| 标签 | 发布为 |
| :--- | :--- |
| `v1.2.0` | 正式版 |
| `v1.3.0-beta.1`、`v1.3.0-rc.1` | 预发布，只推送给打开了「接收测试版」的用户 |

- 附注标签（`-a -m`）的说明会作为更新说明显示在 App 里；
- 需要强制更新时，修改 `.github/update-policy.properties` 里的 `minSupportedVersionCode`；
- 签名密钥保存在仓库的 Actions Secrets 中（`SIGNING_KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`）。

## 工程结构

```
app/                 应用入口、导航、指纹锁
core/designsystem/   颜色、字体和基础组件（沿用群晖 DSM 配色）
core/network/        DSM WebAPI、证书固定、Container Manager、下载器适配（DS / qBittorrent / Transmission）
core/data/           设备与凭证存储、连接管理（主备地址 + QuickConnect）、各页面的数据仓库
core/security/       Android Keystore 加密
core/updater/        自动更新：检查、下载、校验、安装
feature/*            接入、总览、容器、下载、设置等页面
build-logic/         Gradle 约定插件：SDK 版本、版本号、签名
scripts/             发布脚本；dev/mock-dsm.py 是开发用的假 DSM
```

没有群晖也可以调试：`python3 scripts/dev/mock-dsm.py --port 5050`，模拟器里用 `http://10.0.2.2:5050` 添加设备（账号 `admin` / `admin`）。
它还模拟了 Container Manager、Download Station、qBittorrent（`admin` / `adminadmin`）和 Transmission。

## 第三方资源

- 数字字体 [Archivo](https://github.com/Omnibus-Type/Archivo)，SIL Open Font License 1.1（见 `core/designsystem/licenses/`）。

## 许可证

[MIT](LICENSE)
