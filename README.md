# Qualia2FA

一个**无中心服务器、不实时同步**的多平台双因素认证（2FA）应用。

不同设备各自独立运行，不依赖任何厂商的服务器，也不需要注册账号或设置主密码。
在已登录同一账号的设备之间可以直接分享 2FA 条目，合并规则取并集。

| 项 | 值 |
|---|---|
| 平台 | Windows 桌面、Android、iOS |
| 界面 | Compose Multiplatform |
| 许可证 | Apache-2.0 |

## 设计取向

- **无中心**：没有服务端、没有账号体系、没有「忘记密码」流程。你的密钥只在你自己的
  设备上。
- **不实时同步**：设备之间按需手动分享，而不是后台持续对账。因此不存在「同步冲突」
  这个类别的问题，也不会因为服务器宕机而拿不到验证码。
- **端到端加密**：条目在离开设备前就已加密，分享通道上的任何中间人都拿不到明文。
- **离线可用**：断网时所有功能照常，验证码照常计算。

## 当前状态

**开发早期。** 核心库（TOTP/HOTP 计算、`otpauth://` 解析、加密保险库、设备身份、
二维码配对、恢复密钥）已完成并有测试覆盖；界面尚未开始。目前还不能作为日常使用的
2FA 应用。

## 依赖从哪里来

本仓库**不含任何 submodule**。Rust 各层都在自己的仓库里开发、发布到 crates.io，
应用这一侧按版本依赖它们：

| crate | 内容 | 许可证 |
|---|---|---|
| `qualia-dik` | 设备身份原语（表示 / 指纹 / 验签）与平台原生硬件密钥库；唯一允许 `unsafe` 的 crate | LGPL-3.0-or-later |
| `qualia2fa-core` | 全部密码学、保险库格式、同步与配对协议。无 I/O、无时钟、无 `unsafe` | LGPL-3.0-or-later |
| `qualia2fa-ffi` | 把核心库暴露为 UniFFI 绑定（Kotlin / Swift），并附带绑定生成器 | LGPL-3.0-or-later |
| `qualia-transport` | 传输通道：二维码这一半（容量模型、编码、渲染、文本信封、图像解码）已实现 | LGPL-3.0-or-later |

```bash
git clone https://github.com/Qualia2FA/Qualia2FA
cd Qualia2FA
```

界面代码尚未落地，因此现在还没有一条「构建出应用」的命令。要单独构建某一层，进它
自己的仓库里做：

```bash
cargo test --all-targets        # Qualia2FA-Core
cargo test --all-targets        # QualiaDeviceIdentityKit（含打真 TPM 的集成测试）
cargo test                      # Qualia2FA-FFI
cargo test --all-targets        # QualiaTransport
```

## 目录结构

```
.
└── （界面代码将直接建在本仓库根目录下）
```

四个 Rust 层各有自己的仓库，本仓库不再以 submodule 的形式引入它们，也不再保存它们
的副本。

## 参与贡献

界面代码尚未开始，因此现在最适合参与的是设计讨论与各层自身的完善。开始之前请
注意三条硬约束：

- **密码学实现不接受「看起来能跑」的改动。** 一切以规范正文的测试向量为准。
  核心库已有 RFC 4226 / 6238 / 4648 / 9106 的向量测试，新增实现必须同样有出处。
- **秘密材料不得进入日志、错误消息或持久化的明文中。** 这是不可协商的。
- **不得静默降级。** 硬件密钥库不可用时必须如实上报，由用户决定是否接受较弱的
  保护，而不是悄悄换成软件密钥。

### 初始化开发环境

```bash
git clone https://github.com/Qualia2FA/Qualia2FA
cd Qualia2FA
```

## 许可证

应用本体采用 Apache License 2.0，见 [`LICENSE`](LICENSE) 与 [`NOTICE`](NOTICE)。

四个 Rust 层（`qualia-dik`、`qualia2fa-core`、`qualia2fa-ffi`、`qualia-transport`）
采用 LGPL-3.0-or-later。两套许可证的组合是有意选择——应用层用宽松许可证，便于分发
与二次开发；核心的密码学实现保持 copyleft，使任何修改都必须回馈。
