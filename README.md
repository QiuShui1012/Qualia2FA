# Qualia2FA-UI

Qualia2FA 的**界面端**：Compose Multiplatform 应用，覆盖 Windows 桌面（JVM）、
Android 与 iOS。

> 本仓库是**独立仓库**。所有路径都相对于**本仓库根目录**，不假设任何上层目录结构。

## 元信息

| 项 | 值 |
|---|---|
| 状态 | **M0 骨架**——仅建立仓库与依赖关系，尚无界面代码 |
| UI 框架 | Compose Multiplatform |
| 目标平台 | Windows 桌面（**JVM** 目标）、Android、iOS |
| FFI 方案 | UniFFI + Gobley |
| 许可证 | LGPL-3.0-or-later（待补 LICENSE） |

## 依赖关系

```
Qualia2FA-UI (本仓库)
├── Core/  ← git submodule → qualia2fa-core     纯逻辑，无 unsafe，无 I/O
└── (未来) FFI/ ← git submodule → qualia2fa-ffi  UniFFI 绑定层
```

本仓库目前**只引入 `Core`**。`FFI` 层暂在 workspace 根独立存在，待其稳定后再以
submodule 方式移入本仓库——届时 `FFI` 的 `../Core` 路径依赖将正好指向本仓库的
`Core/`，不需要改动 `FFI` 的 `Cargo.toml`。

### 初始化

```bash
git clone --recurse-submodules <本仓库地址>
# 或已克隆后：
git submodule update --init --recursive
```

## 为什么桌面端选 JVM 而不是 Kotlin/Native

已调研并**否决**了两条无 JVM 的路线（`bitsycore/compose-desktop-native` 与
`brahmkshatriya/compose-native`，详见 workspace 根的 `TODO.md` §4.5）。理由摘要：

- Skiko（**YouTrack SKIKO-580**，`resolved: null`）是 GraalVM native-image 的
  真正阻塞点，**不是 JNA**——所以「去掉 JNA」并不能打开 GraalVM 路线
- 两个 Kotlin/Native 移植都只有 `-alpha` 版本，Windows UIA 桥缺失 COM 适配层、
  macOS 侧仍是占位实现、不支持多显示器与原生文件对话框
- 官方 Compose + jlink/jpackage 的产物体积已可接受：实测 **naive 149.36 MB →
  Compose 默认 73.10 MB → 再加压缩 45.47 MB**

## 打包

用 **jlink + jpackage** 产出离线自包含安装包。**不使用 Conveyor**——已实测否决，
理由见 `TODO.md` §4.6：

- 其 12.0 版对 `eton-desktop` 的实测产物（MSIX 79.66 MB / zip 78.49 MB）落在
  jpackage+jlink 的 70–85 MB 基线之内，并无体积优势
- 那个 0.6 MB 的 exe 是**下载器**，会从作者自己的站点拉取内容——不满足本项目的
  离线优先要求
- 其 jdeps/jlink 裁剪并非独有：Compose 的 Gradle 插件已内置
  `suggestRuntimeModules` 且默认裁得更多

> ⚠️ **不要用 MSIX**：其 AppData 虚拟化有丢失 TOTP 种子数据的风险。

## 待办

界面代码尚未开始。完整里程碑见 workspace 根的 `TODO.md`（M5 为 UI 绑定验证，
含 3 个 spike 项）。
