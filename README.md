# 快启动 MD3 (QuickStartMD3)

一款基于 Material Design 3 的 Android 极速应用启动器。通过 T9 / 拼音搜索快速找到并打开应用，支持悬浮球、快捷设置磁贴和桌面小部件等多种唤起方式。

## 功能特性

- **极速搜索**：T9 九宫格键盘与拼音（含首字母）搜索应用
- **快捷计算**：搜索框内直接输入算式得出结果
- **应用快捷方式**：读取并启动应用的 Shortcuts（借助 Shizuku）
- **多种唤起方式**
  - 悬浮球 / 常驻通知
  - 快捷设置（Quick Settings）磁贴
  - 桌面小部件：搜索栏、1×1 悬浮图标
  - 可设为系统助手（ASSIST）
  - 可作为桌面启动器（HOME）
- **图标包支持**与 Material You 动态取色（Android 12+）
- **按键音与震动反馈**
- 开机自启动悬浮球

## 环境要求

- Android 8.0（API 26）及以上，目标 API 35
- 可选：[Shizuku](https://shizuku.rikka.app/)，用于获取应用快捷方式等高级能力

## 所需权限

| 权限 | 用途 |
| --- | --- |
| `QUERY_ALL_PACKAGES` | 列出已安装应用 |
| `SYSTEM_ALERT_WINDOW` | 显示悬浮球与悬浮面板 |
| `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE` | 常驻通知与前台服务 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启动 |
| `READ_CONTACTS` / `CALL_PHONE` | 搜索联系人并拨号 |
| `REQUEST_DELETE_PACKAGES` | 从应用菜单卸载应用 |
| `VIBRATE` | 触感反馈 |

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- DataStore、Kotlin Coroutines、kotlinx.serialization
- Shizuku API
- pinyin4j（拼音搜索）

## 构建

```bash
# 调试版
./gradlew assembleDebug

# 单元测试
./gradlew test
```

Release 构建需要自行准备签名密钥（`signing/` 目录已被 `.gitignore` 忽略，不随仓库发布），并在构建配置中指定。

## 项目结构

```
app/src/main/java/com/quickstart/md3/
├── data/      # 应用仓库、拼音搜索、计算器、图标包、数据存储
├── service/   # 悬浮球、磁贴、小部件、开机接收器
├── shizuku/   # Shizuku 集成
└── ui/        # 主界面、设置界面、主题
```

## 许可证

尚未指定许可证。
