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

## 获取与构建

```bash
git clone --recurse-submodules https://github.com/Qualia2FA/Qualia2FA
cd Qualia2FA
```

> **`--recurse-submodules` 不能省。** 本仓库依赖的三个子模块各自又依赖核心库，
> 因此漏掉它得到的是一棵**能看不能编译**的树。已经克隆了的话：

```bash
git submodule update --init --recursive
```

界面代码尚未落地，因此现在还没有一条「构建出应用」的命令。三个子模块各自可以
独立构建与测试：

```bash
cargo test --manifest-path Core/Cargo.toml --all-targets
cargo test --manifest-path keystore/Cargo.toml --all-targets
cargo test --manifest-path FFI/Cargo.toml
```

## 目录结构

```
.
├── Core/          ← git submodule → Qualia2FA-Core
├── keystore/      ← git submodule → Qualia2FA-Keystore
│   └── Core/      ← 同一个核心库
└── FFI/           ← git submodule → Qualia2FA-FFI
    └── Core/      ← 同一个核心库
```

| 目录 | 承载什么 | 许可证 |
|---|---|---|
| `Core/` | 全部密码学与 2FA 逻辑。无 I/O、无时钟、无 `unsafe` | LGPL-3.0-or-later |
| `keystore/` | 设备身份密钥对的生成、保管与签名。唯一允许 `unsafe` 的地方 | LGPL-3.0-or-later |
| `FFI/` | 把核心库暴露给 Kotlin/Swift 的绑定层 | LGPL-3.0-or-later |

界面代码将直接建在本仓库根目录下。

`keystore/` 与 `FFI/` 都按**相对自身的路径**依赖核心库，所以各自带一份 `Core/`。
三份指向同一个仓库、同一个提交——它们不是三个副本，而是同一棵树的三条引用路径。

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
git clone --recurse-submodules https://github.com/Qualia2FA/Qualia2FA
cd Qualia2FA
cargo test --manifest-path Core/Cargo.toml --all-targets   # 先确认核心库全绿
```

## 许可证

应用本体采用 Apache License 2.0，见 [`LICENSE`](LICENSE) 与 [`NOTICE`](NOTICE)。

三个子模块采用 LGPL-3.0-or-later。两套许可证的组合是有意选择——应用层用宽松
许可证，便于分发与二次开发；核心的密码学实现保持 copyleft，使任何修改都必须
回馈。
