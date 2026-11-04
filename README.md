# TL-key

一个自用的、原生 Kotlin 的极简台铃电动车钥匙客户端。基于官方 Android APK 的**静态协议分析**重新实现，不包含官方代码、图片或商业资源，也不修改或重打包官方 APK。

> 非官方项目，与台铃及其关联公司不存在官方合作、认可、授权或从属关系。仅用于操作**你自己账户下或经合法授权使用的车辆**。

## 功能

- 官方账号登录：腾讯滑块验证码 + 官方短信登录接口；Token 使用 Android Keystore 加密保存。
- 多车管理：左右滑动切车、首次配对、服务器绑定、BLE 密码写入、当前车切换。
- BLE 控车：开锁 / 上锁 / 上电 / 断电 / 寻车 / 座桶，覆盖 78 帧、85 帧、QGJ（Kuyi）三套协议。
- 快速入口：MD3 主页、Quick Settings Tile（单击开锁）、桌面 Unlock / Lock 快捷方式。
- 扩展控制：MQTT 云控、ECU 驾驶模式设置（fcc1 / fbb1 原始字节）、OTA 云侧触发与进度查询。
- 离线优先：已缓存的车辆凭据可离线切换与控车；仅登录、车辆列表、云控和 OTA 等云侧功能需要网络。

## 安全边界与授权说明

- 保留官方认证与绑定流程：登录使用官方账户、官方滑块与短信验证；车辆列表来自官方接口；添加/绑定车辆按官方的服务器与 BLE 流程执行。
- 控制目标来自当前登录账户的车辆列表及官方共享/绑定流程；不提供绕过车辆归属校验、直接指定任意车辆 MAC/凭据或批量控制的入口。
- 不实现绕过官方身份认证或车端 BLE 鉴权的逻辑。BLE 控制使用车端会话 Token 与相应协议鉴权；85 帧、QGJ 等协议族还使用服务器下发的每车蓝牙凭据。各协议族的车端鉴权强度由厂商实现决定，本项目不将其等同于车辆所有权验证。
- 离线控制基于此前通过官方流程取得并缓存在本地的车辆凭据（Android Keystore 加密）。日常 BLE 控制不依赖服务器实时在线，但这不表示可以绕过凭据来源或车辆授权。
- 不绕过服务器鉴权、登录校验或车辆授权；所有测试只针对项目所有者自己的账户和车辆。
- 不复刻官方对 `ssl://` 的 trust-all，改用系统默认信任库 + 主机名校验。
- 仓库不包含手机号、IMEI、MAC、Token、账号密码、签名密钥库或任何个人运行数据。
- KKS/YJ 的共享 MQTT broker 账号**不写入仓库**，构建时通过 Gradle 属性、环境变量或 CI secrets 注入；未配置时该车型的云控不可用。
- 78/85 帧的 BLE AES 常量**不写入仓库**，同样在构建时注入；未配置时对应车型不可控，QGJ（8/283）不受影响。
- AES 常量属于互操作协议材料；KKS/YJ 共享 broker 账号属于官方 APK 内置的共享服务端凭据。二者都不是用户个人凭据。构建时注入只避免它们进入仓库源码，**不保证最终 APK 中的常量或账号不可提取**；公开分发前请自行评估。
- 不下载、不上传、不写固件；OTA 只调用官方云侧接口，由车辆/ECU 自行完成升级。
- 与官方服务器和接口的兼容性、稳定性及长期可用性不作保证；官方可能调整接口、协议、证书或服务策略。
- “使用官方账户与认证流程”仅表示与既有体系互操作，不等于获得台铃的开发授权、兼容性认证或官方背书。

## 构建

要求：

- JDK 21（JDK 25 可能导致 Kotlin Gradle 脚本解析失败）
- Android SDK Platform 36、Build-Tools 36.1.0
- 网络可访问 Google Maven / Maven Central

```bash
cd client
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

产物：`client/app/build/outputs/apk/debug/app-debug.apk`（包名 `com.ywpc.tlkey.debug`，可与正式版共存）。

### 本地配置（可选）

仓库不提供任何协议常量或共享账号；不配置时仍可编译、跑单测，QGJ（8/283）可正常控车。

78/85 帧车型需要从你自己的官方 APK 静态分析中取得 AES 常量，并按车型注入：

```bash
cd client
./gradlew :app:assembleDebug \
  -Ptlkey.protocol78.key=<32 hex> \
  -Ptlkey.protocol85.bb=<32 hex> \
  -Ptlkey.protocol85.jw=<32 hex> \
  -Ptlkey.protocol85.jd=<32 hex> \
  -Ptlkey.protocol85.ax=<32 hex> \
  -Ptlkey.protocol85.hj=<32 hex> \
  -Ptlkey.protocol85.xl=<32 hex> \
  -Ptlkey.protocol85.yy=<32 hex>
```

也可以用环境变量：`TLKEY_PROTOCOL78_KEY`、`TLKEY_PROTOCOL85_BB`、`_JW`、`_JD`、`_AX`、`_HJ`、`_XL`、`_YY`。

KKS/YJ（modelType 1/2）云控需要官方 APK 内置的共享 broker 账号：

```bash
./gradlew :app:assembleDebug \
  -Ptlkey.mqtt.legacyUser=<user> \
  -Ptlkey.mqtt.legacyPassword=<password>
```

或设置环境变量 `TLKEY_MQTT_LEGACY_USER` / `TLKEY_MQTT_LEGACY_PASSWORD`。
QGJ / C18 / BGPS 使用 `carStatus` 下发的每车 `mqUsername` / `mqPassword`，不需要该配置。

### 正式版签名

`client/keystore.properties` 与 `client/keystore/*.jks` 已被 `.gitignore` 忽略，不会进入仓库。本地发版：

```bash
cd client
./gradlew :app:assembleRelease
```

版本号由 git 推导：

- `versionCode = git rev-list --count HEAD + 1`
- `versionName = 最近一个 tag`（去掉 `v` 前缀；没有 tag 时为 `0.1.0`）

创建 `vX.Y.Z` tag 后构建，即得到对应的 `versionName=X.Y.Z` 正式包；推送 tag 会触发 GitHub Release。

## CI / Release

- `.github/workflows/ci.yml`：push / PR / 手动触发；运行单测、Lint、Debug 构建并上传 APK。
- `.github/workflows/release.yml`：推送 `v*` tag 时运行；注入签名与协议常量 secrets，构建并发布 GitHub Release APK。
- `.github/workflows/keyed-release.yml`：手动触发；构建带协议常量的 APK 并创建 **draft** Release，仅供仓库所有者私有使用，不应发布为公开 Release。

> 本仓库所有者明确选择在公开 Release APK 中注入 AES/MQTT 常量。构建时注入只避免常量进入 Git 仓库，**不提供 APK 层面的保密**：APK 是二进制分发，下载者可从中提取这些常量。如果这不是你想要的，请只在本地构建，不要把带常量的 APK 发布到公开 Release。

Release 需要以下 Secrets：

| Secret | 含义 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `tlkey-release.jks` 的 base64（`base64 -w0`） |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 口令 |
| `ANDROID_KEY_ALIAS` | 签名 alias |
| `ANDROID_KEY_PASSWORD` | key 口令 |
| `TLKEY_PROTOCOL78_KEY` | 78 帧 AES 常量 |
| `TLKEY_PROTOCOL85_BB` | 85 帧 BB 常量 |
| `TLKEY_PROTOCOL85_JW` | 85 帧 JW 常量 |
| `TLKEY_PROTOCOL85_JD` | 85 帧 JD 常量 |
| `TLKEY_PROTOCOL85_AX` | 85 帧 AX 常量 |
| `TLKEY_PROTOCOL85_HJ` | 85 帧 HJ 常量 |
| `TLKEY_PROTOCOL85_XL` | 85 帧 XL 常量 |
| `TLKEY_PROTOCOL85_YY` | 85 帧 YY 常量 |
| `TLKEY_MQTT_LEGACY_USER` | KKS/YJ 共享 broker 用户名 |
| `TLKEY_MQTT_LEGACY_PASSWORD` | KKS/YJ 共享 broker 密码 |

没有签名 secrets 时 Release workflow 会明确失败，不会发布未签名 APK；缺少 78/85 协议常量时也会失败。

## 目录

```text
client/                 Android 客户端（Kotlin）
  app/src/main/         主代码、资源、Manifest
  app/src/test/         JVM 单元测试（协议 golden、编解码、endpoint）
  keystore/             本地签名文件（gitignored）
tools/                  登录/分析辅助脚本（不含凭据）
local/                  本地运行数据与私有协议文档（gitignored）
.github/workflows/      CI / Release
```

## 文档

详细协议分析文档仅保留在本地私有目录（`docs/`、`IMPLEMENTATION_PLAN.md`），已加入 `.gitignore`，不随公开仓库分发。公开仓库仅保留本 README 中的功能、构建、安全边界与许可说明。

## 状态与限制

- 单元测试覆盖 78/85/QGJ 帧、AES、token/login/ACK、车辆模型、MQTT endpoint、ECU 编解码。
- 真车 BLE 时序、MQTT 云控、ECU 读写、OTA 触发仍需在真实车辆上验证；静态对齐不能代替实测。
- 不同批次/车型的 AES 常量、ECU 能力位与多位字段语义仍存在 UNKNOWN；相关细节保留在本地私有文档中。

## 免责声明与使用须知

1. 本项目为独立开发的非官方开源项目，与台铃及其关联公司不存在合作、认可、授权或从属关系。台铃及其他第三方产品名称、商标归各自权利人所有，仅用于识别兼容设备。
2. 本节为使用建议与合法授权边界：本项目仅供合法拥有或合法使用相关车辆、或已获得车辆所有人明确授权的用户，用于协议互操作、学习与研究。使用者应使用本人合法账户及获授权车辆，不得用于未经授权的车辆控制、批量操作或规避安全机制；并应自行确认其行为符合所在地法律及官方服务条款。
3. 不同车型、批次、固件版本及官方服务变更可能导致功能不兼容或失效；蓝牙连接、控制延迟、车辆误动作、设备损坏等风险客观存在。请谨慎操作，并在骑行和停放时保留实体钥匙。
4. 本项目按“现状”提供，不对适销性、特定用途适用性、无侵权或不中断作任何明示或默示保证。在适用法律允许的范围内，项目作者及贡献者不对因使用或无法使用本项目产生的任何直接、间接、附带或后果性损失承担责任；依法不能排除的责任不受本段影响。
5. 本声明不构成对 MPL-2.0 许可证所授予权利的额外限制，代码许可条件以 [LICENSE](LICENSE) 为准。车辆的合法使用授权、第三方商标及官方服务器/服务的使用条件，均独立于该许可证。
6. 如认为本仓库内容侵犯合法权利，请通过本仓库 GitHub Issues 说明具体文件、权利依据及诉求。

本声明不构成法律意见，也不保证避免权利争议、下架或诉讼。

## 许可证

本仓库由项目作者创作的源代码、构建脚本与文档按 Mozilla Public License 2.0（MPL-2.0）授权，详见 [LICENSE](LICENSE)。第三方依赖仍遵循各自许可证；台铃及其他第三方产品名称、商标不因本许可证而转让。MPL-2.0 授予对本仓库原创内容的版权、专利等许可证所涵盖的权利，不授予商标许可、车辆访问权、官方服务使用权或任何厂商授权。
