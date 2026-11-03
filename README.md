# TL-key

一个自用的、原生 Kotlin 的极简台铃电动车钥匙客户端。基于官方 Android APK 的**静态协议分析**重新实现，不包含官方代码、图片或商业资源，也不修改官方 APK。

> 非官方项目，与台铃及其关联公司无任何关系。仅用于操作**你自己账户下的车辆**。

## 功能

- 官方账号登录：腾讯滑块验证码 + 官方短信登录接口；Token 使用 Android Keystore 加密保存。
- 多车管理：左右滑动切车、首次配对、服务器绑定、BLE 密码写入、当前车切换。
- BLE 控车：开锁 / 上锁 / 上电 / 断电 / 寻车 / 座桶，覆盖 78 帧、85 帧、QGJ（Kuyi）三套协议。
- 快速入口：MD3 主页、Quick Settings Tile（单击开锁）、桌面 Unlock / Lock 快捷方式。
- 扩展控制：MQTT 云控、ECU 驾驶模式设置（fcc1 / fbb1 原始字节）、OTA 云侧触发与进度查询。
- 离线优先：已缓存的车辆凭据可离线切换与控车；仅登录/车辆列表/云控需要网络。

## 安全边界

- 不绕过服务器鉴权、登录校验或车辆授权；所有测试只针对项目所有者自己的账户和车辆。
- 不复刻官方对 `ssl://` 的 trust-all，改用系统默认信任库 + 主机名校验。
- 仓库不包含手机号、IMEI、MAC、Token、账号密码、签名密钥库或任何个人运行数据。
- KKS/YJ 的共享 MQTT broker 账号**不写入仓库**，构建时通过 Gradle 属性、环境变量或 CI secrets 注入；未配置时该车型的云控不可用。
- 78/85 帧的 BLE AES 常量**不写入仓库**，同样在构建时注入；未配置时对应车型不可控，QGJ（8/283）不受影响。
- AES 常量是车端互操作协议常量，不是用户凭据，也不足以单独控制他人车辆（仍需车端 token 与每车蓝牙密码）。
- 不下载、不上传、不写固件；OTA 只调用官方云侧接口，由车辆/ECU 自行完成升级。

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

创建 `v1.0.0` tag 后构建，即得到 `versionName=1.0.0` 的正式包。

## CI / Release

- `.github/workflows/ci.yml`：push / PR / 手动触发；运行单测、Lint、Debug 构建并上传 APK。
- `.github/workflows/release.yml`：推送 `v*` tag 时运行；注入签名与协议常量 secrets，构建并发布 GitHub Release APK。

> 本仓库所有者明确选择在公开 Release APK 中注入 AES/MQTT 常量。APK 是二进制分发，下载者可从中提取这些常量；如果这不是你想要的，请只在本地构建，不要把带常量的 APK 发布到公开 Release。

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
docs/                   协议与实现文档
tools/                  登录/分析辅助脚本（不含凭据）
local/                  本地运行数据（gitignored）
.github/workflows/      CI / Release
```

## 文档

- [docs/README.md](docs/README.md) — 文档索引
- [docs/AUTH.md](docs/AUTH.md) — 登录、Token、BLE 鉴权与密钥分层
- [docs/BLE.md](docs/BLE.md) — BLE 连接与帧格式
- [docs/VEHICLE.md](docs/VEHICLE.md) — modelType 与协议族
- [docs/BIND.md](docs/BIND.md) — 服务器绑定与首次配对
- [docs/CONTROL.md](docs/CONTROL.md) — 六个核心控制动作完整性
- [docs/MQTT.md](docs/MQTT.md) — 云控协议与安全差异
- [docs/ECU.md](docs/ECU.md) — ECU fcc1/fbb1 位映射
- [docs/OTA.md](docs/OTA.md) — OTA 三条链路与已实现范围
- [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md) — 实现状态与真机验证缺口

## 状态与限制

- 单元测试覆盖 78/85/QGJ 帧、AES、token/login/ACK、车辆模型、MQTT endpoint、ECU 编解码。
- 真车 BLE 时序、MQTT 云控、ECU 读写、OTA 触发仍需在真实车辆上验证；静态对齐不能代替实测。
- 不同批次/车型的 AES 常量、ECU 能力位与多位字段语义仍存在 UNKNOWN，详见文档。

## 使用限制

本项目仅用于学习、研究与操作本人账户下的车辆。请勿用于未授权车辆、批量控制、规避安全机制或任何违法用途。因使用本项目造成的车辆、设备、数据或法律风险由使用者自行承担。

项目未授予开源许可证，保留所有权利（All rights reserved）。如需复用，请先联系仓库所有者。
