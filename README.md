# Qualia2FA

一个**无中心服务器、不实时同步**的多平台双因素认证（2FA）应用。

不同设备各自独立运行，不依赖任何厂商的服务器，也不需要注册账号或设置主密码。
在已登录同一账号的设备之间可以直接分享 2FA 条目，合并规则取并集。

| 项 | 值 |
|---|---|
| 平台 | Windows 桌面、Android、iOS |
| 界面 | Compose Multiplatform |
| 许可证 | LGPL-3.0-or-later |

## 设计取向

- **无中心**：没有服务端、没有账号体系、没有「忘记密码」流程。你的密钥只在你自己的
  设备上。
- **不实时同步**：设备之间按需手动分享，而不是后台持续对账。因此不存在「同步冲突」
  这个类别的问题，也不会因为服务器宕机而拿不到验证码。
- **端到端加密**：条目在离开设备前就已加密，分享通道上的任何中间人都拿不到明文。
- **离线可用**：断网时所有功能照常，验证码照常计算。

## 当前状态

**开发早期。** 核心库（TOTP/HOTP 计算、`otpauth://` 解析）已在
[`Qualia2FA-Core`](https://github.com/QiuShui1012/Qualia2FA-Core) 完成并有测试覆盖；
界面尚未开始。目前还不能作为日常使用的 2FA 应用。

## 获取与构建

```bash
git clone --recurse-submodules https://github.com/QiuShui1012/Qualia2FA
cd Qualia2FA
```

若已克隆但忘了 `--recurse-submodules`：

```bash
git submodule update --init --recursive
```

构建说明随界面代码的落地一并补上。

## 目录结构

```
.
└── Core/    ← git submodule → Qualia2FA-Core
```

`Core/` 是本项目依赖的核心库，承载全部密码学与 2FA 逻辑。界面代码将直接建在
本仓库根目录下。

> FFI 绑定层（[`Qualia2FA-FFI`](https://github.com/QiuShui1012/Qualia2FA-FFI)）
> 待接口稳定后会出现在这里，作为 `Core/` 的兄弟目录。

## 参与贡献

界面代码尚未开始，因此现在最适合参与的是设计讨论与核心库的完善
（见 [`Qualia2FA-Core`](https://github.com/QiuShui1012/Qualia2FA-Core)）。

开始之前请注意本项目的两条硬约束：

- **密码学实现不接受「看起来能跑」的改动。** 一切以规范正文的测试向量为准。
  核心库已有 RFC 4226 / 6238 / 4648 的完整向量测试，新增实现必须同样有出处。
- **秘密材料不得进入日志、错误消息或持久化的明文中。** 这是不可协商的。

### 初始化开发环境

```bash
git clone --recurse-submodules https://github.com/QiuShui1012/Qualia2FA
cd Qualia2FA/Core
cargo test --all-targets      # 先确认核心库在你机器上全绿
```

## 许可证

GNU Lesser General Public License v3.0 or later（`LGPL-3.0-or-later`）。
见 [`LICENSE`](LICENSE) 与 [`COPYING.LESSER`](COPYING.LESSER)。
