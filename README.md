<div align="center" style="align-items: center">

<img src=".idea/icon.svg" alt="icon" style="height: 128px; width: 128px">

# Qualia2FA

简体中文 | [English](README.en.md)

一个**无中心服务器、不实时同步**的多平台双因素认证（2FA）应用。

[![License](https://img.shields.io/github/license/Qualia2FA/Qualia2FA?label=License)][License]
[![Releases](https://img.shields.io/github/downloads/Qualia2FA/Qualia2FA/total?logo=github&label=Downloads)][Releases]

[License]: LICENSE

[Releases]: https://github.com/Qualia2FA/Qualia2FA/releases

</div>

---

不同设备各自独立运行，不依赖任何厂商的服务器，也不需要注册账号或设置主密码。
在已登录同一账号的设备之间可以直接分享 2FA 条目，合并规则取并集。

| 项       | 值                     |
|----------|------------------------|
| 平台     | Windows 与 Android     |
| 界面     | Compose Multiplatform  |
| 界面语言 | 英文（默认）、简体中文 |
| 界面主题 | 跟随系统的浅色 / 深色  |
| 许可证   | Apache-2.0             |

## 安装

每个版本都发布在 [Releases](https://github.com/Qualia2FA/Qualia2FA/releases) 页面上：

| 平台                            | 产物                        | 说明                                                   |
|---------------------------------|-----------------------------|--------------------------------------------------------|
| Windows 10 / 11（x64）          | `.msi` 与 `.exe` 离线安装器 | 带签名，但证书是自签发的，Windows 会警告「未知发布者」 |
| Android 8.0 及以上（64 位 ARM） | `.apk`                      | 用项目的发布密钥签名，可直接安装                       |

Android 端目前仅支持 `arm64-v8a` 一个架构的包，Windows 端目前仅支持 `x86_64` 一个架构的包。
我们十分欢迎 Pull Request。

安装后不需要账号，也不需要联网。  
相机权限仅在需要扫码时才询问获取，拒绝后也仍有「选一张二维码图片」方式扫码；
局域网权限仅在配对或同步时才会用到。  
应用不申请任何后台权限，也不做后台同步。

## 设计取向

- **无中心**：没有服务端、没有传统账号体系、没有「忘记密码」流程。你的密钥只在你自己的设备上。
- **不实时同步**：设备之间按需手动分享，而不是后台持续同步。因此不会因为服务器宕机而拿不到验证码。
- **端到端加密**：条目在离开设备前就已加密，分享通道上的任何中间人都拿不到明文。
- **离线可用**：断网时所有功能照常，验证码照常计算。

## 当前状态

**开发早期，目前支持 Windows 桌面端与 Android，两个平台是同一套界面与同一套逻辑。**

基础功能已经基本完成。剩余未完成部分如下：

- **应用密码**：

  使应用解锁时需要验证身份，进一步保证程序的安全性。  
  将会接入设备自带的生物识别技术，否则需要额外设定密码。

- **删除设备 & 主密钥轮换**：
  
  可在某台设备丢弃或不被信任时将该设备删除。  
  由于程序设计，该功能会需要所有同一账号且未被删除的设备进行一次 **整体**同步
  才能将对应设备彻底删除。

- **Linux、iOS、macOS设备支持**：

  支持以上三个平台的设备。  
  由于作者（@QiuShui1012）暂无任何苹果设备且未加入开发者计划，后两者的支持计划已无限延迟。  
  **欢迎苹果开发者加入！**

## 构建与运行

| crate              | 内容                                                                                                            | 许可证            |
|--------------------|-----------------------------------------------------------------------------------------------------------------|-------------------|
| `qualia-dik`       | 设备身份原语（表示 / 指纹 / 验签）与平台原生硬件密钥库；唯一允许 `unsafe` 的库 crate                            | LGPL-3.0-or-later |
| `qualia-transport` | 传输通道：二维码支持（容量模型、编解码、渲染、文本信封）与局域网支持（组播发现、连接信息、QUIC 数据面）都已实现 | LGPL-3.0-or-later |
| `qualia2fa-core`   | 全部密码学、保险库格式、同步与配对协议。无 I/O、无时钟、无 `unsafe`                                             | LGPL-3.0-or-later |
| `qualia2fa-ffi`    | 把核心库暴露为 UniFFI 绑定（Kotlin / Swift），并附带绑定生成器                                                  | LGPL-3.0-or-later |

构建需要 JDK 21 与 Rust 工具链（Windows 上是 MSVC）：

```bash
git clone https://github.com/Qualia2FA/Qualia2FA
cd Qualia2FA

./gradlew :composeApp:jvmTest      # 编译 + 跑单元测试（含打通 Rust 库的往返测试）
./gradlew :composeApp:run          # 起桌面应用
./gradlew :composeApp:packageMsi   # 打包（jlink + jpackage）

./gradlew :composeApp:assembleRelease  # Android 安装包（签名、可直接安装）
./gradlew :composeApp:assembleDebug    # Android 调试包，只用来验证功能
```

Android 的产物在 `composeApp/build/outputs/apk/release/`，装到设备上是
`adb install -r composeApp/build/outputs/apk/release/composeApp-release.apk`。

要在设备上检查这个应用的外观，请用 `assembleRelease`：**Android 拒绝优化 debuggable 的包**，
debug 构建因此天生慢好几倍，拿它判断流畅度会得出与用户所见相反的结论。
`assembleRelease` 需要签名才能安装，签名信息从仓库根目录的 `keystore.properties`（不
提交）读；没有那个文件时回退到 debug 密钥，够本地安装，但不是可以对外发布的身份。

应用构建的是**已发布**的 `qualia2fa-ffi`：源码在配置阶段从 crates.io 取下来、按索引里的
SHA-256 校验后解包进 git 忽略的 `composeApp/rust/`。换版本时**必须同时换掉
`ffiCrateSha256`**：只改版本号会在校验那一步失败，这是故意的，它挡住了「版本号写着新、
实际拿到旧包」这类静默错配。

每次推送都会在 GitHub Actions 上跑一遍同样的构建：Windows 那一侧跑单元测试并打出 MSI 与 EXE，
Android 那一侧打出 arm64 的 APK，产物都挂在那次运行的页面上。打 `v*` 标签时另有一次发布构建，
三个产物一并传到新建的 Release 上。

要单独构建某一层，进它自己的仓库里做：

```bash
cargo test --all-targets        # Qualia2FA-Core
cargo test --all-targets        # QualiaDeviceIdentityKit（含打真 TPM 的集成测试）
cargo test                      # Qualia2FA-FFI
cargo test --all-targets        # QualiaTransport
```

## 目录结构

```
composeApp/
├── src/commonMain/             界面与业务代码（Kotlin，两个平台共用）
│   ├── composeResources/       标志与图标（drawable/），界面文案：values/ 英文（默认）、values-zh/ 中文
│   └── kotlin/dev/qualia/qualia2fa/
│       ├── Layout.kt           按窗口尺寸决定布局的规则，写成纯函数以便测试
│       ├── Theme.kt            两套配色（浅色 / 深色）、圆角与间距：色板来自标志本身（#EAF2F4 与 #091012）
│       └── *Screen.kt          加载、登录、2FA、同步、设置，以及配对的两侧
├── src/androidJvmMain/         桌面端与 Android 共用：保险库文件，以及写在它旁边的包裹后主密钥
├── src/jvmMain/                桌面端实现：数据目录、文件对话框、窗口
├── src/androidMain/            Android 实现：Activity、相机、文件选择器、局域网，以及窗口主题
│   └── res/values-night/       深色下的窗口底色与系统栏图标
└── src/jvmTest/                单元测试（含两套配色的对比度）
```

`Qualia.kt` 是全仓唯一 import 生成绑定的文件：界面只看到普通的 Kotlin 值，绑定层要换、
要打补丁或 fork 时只应该动这一个文件。两处绝不能越过这条线的东西——条目列表与主密钥的存法
——也在那里包好。

主密钥本身从不落盘：平台密钥库内部生成一把密钥加密密钥，它不外泄，数据目录里存的是它
产出的密文。因此拷走那个文件并不等于拷走账号；设置界面会如实显示是哪个后端在保护它
（`tpm`、`android-keystore` 或 `software`）。

`icons/` 下是打包用的 `.ico`（Windows 安装包与窗口图标），由 `icons/make_ico.py` 从
标志重新生成。

## 界面主题

浅色与深色两套配色，**跟随系统设置**，应用内不再提供自己的开关——用户已经有了那个设置，再放
一个只会与它打架。

两套配色是同一个标志的两种读法：浅色是白底黑线（底色 `#EAF2F4`、线条 `#091012`），深色把这两
个色**对调**——线色当底、底色当线——次级表面与错误红都由这两个色推出来，没有引入第三种颜色。
对比度是**算出来的**而不是看出来的：`ThemeTest` 把界面上真会出现的每一对前景 / 背景都按
WCAG 2.1 算一遍，两套配色都要达到 AA（4.5:1）；任何一处调色调到读不清，测试会先于用户发现。

两个平台**读到系统设置的时刻**不同，这是各自的工具包决定的：Android 会在应用运行期间收到配置
变更，所以在系统设置里切换主题是立刻生效的；Windows 上的桌面工具包是在创建窗口时读一次系统
主题，因此运行中切换要**下次启动**才生效。

## 界面语言

文案放在 `composeApp/src/commonMain/composeResources/`：`values/` 是默认语言（英文），
`values-zh/` 是中文翻译。默认跟随系统语言，登录界面与设置界面都能手动切换，选择记在数据
目录下的 `settings.properties` 里。

登录界面上那个切换器是**刻意**放的：那是保险库打开之前最后一个界面，读不懂界面语言的人
如果只能到设置里改，就等于改不了。

`StringsTest` 会比较两份文案的键集合：漏掉一条翻译在运行时只会静默回退到英文，只有这个
比较能发现它。

## 参与贡献

三条硬约束，动手之前请先看一眼：

- **密码学实现不接受「看起来能跑」的改动。** 一切以规范正文的测试向量为准。
  核心库已有 RFC 4226 / 6238 / 4648 / 9106 的向量测试，新增实现必须同样有出处。
- **秘密材料不得进入日志、错误消息或持久化的明文中。** 这是不可协商的。
- **不得静默降级。** 硬件密钥库不可用时必须如实上报，由用户决定是否接受较弱的
  保护，而不是悄悄换成软件密钥。

改界面文案时记得**两份都要改**（英文与中文），否则用户看到的是回退后的英文。

## 许可证

应用本体采用 Apache License 2.0，见 [`LICENSE`](LICENSE) 与 [`NOTICE`](NOTICE)。

四个 Rust 层（`qualia-dik`、`qualia2fa-core`、`qualia2fa-ffi`、`qualia-transport`）
采用 LGPL-3.0-or-later。两套许可证的组合是有意选择——应用层用宽松许可证，便于分发
与二次开发；核心的密码学实现保持 copyleft，使任何修改都必须回馈。
