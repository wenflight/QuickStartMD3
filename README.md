# 快启动 MD3 (QuickStartMD3)

一款基于 Material Design 3 的 Android 极速应用启动器。通过 T9 / 拼音搜索快速找到并打开应用，支持**双击手机背部（集成 Tap, Tap / Columbus 轻量化引擎）**、边缘侧滑、悬浮球、快捷设置磁贴和桌面小部件等多种唤起方式。

## 功能特性

- **极速搜索**：T9 九宫格键盘与拼音（含首字母）搜索应用，支持自定义多音字与超级模糊匹配
- **快捷计算**：搜索框内直接输入算式得出结果
- **应用快捷方式**：读取并启动应用的 Shortcuts（借助 Shizuku）
- **双击背部唤起（Tap, Tap 引擎）**：
  - 将 [KieronQuinn/TapTap](https://github.com/KieronQuinn/TapTap) 的双击背部识别引擎轻量化合入单进程，**无需开启无障碍服务**
  - **双引擎与多模型支持**：默认启用三星 RegiStar「通用旗舰」引擎（**仅需加速度计、免开陀螺仪**，传感器功耗减半且大屏旗舰召回率更稳），同时保留 Columbus 660KB 大屏完整版及多尺寸轻量模型自由切换
  - **省电与防误触设计**：独立低优先级后台线程运行、零热路径对象分配、息屏自动注销传感器（待机零额外功耗），支持锁屏暂停与解锁复检、按需口袋防误触（仅触发瞬间读取一次距离传感器）、桌面平放暂停与省电模式暂停
  - **统一保活**：复用单一前台服务承载，支持开机/更新自动恢复与忽略电池优化引导
- **多种唤起方式**
  - 双击背部（Back Tap）
  - 边缘隐形侧滑触发条
  - 悬浮球 / 常驻通知
  - 快捷设置（Quick Settings）磁贴
  - 桌面小部件：搜索栏、1×1 零闪屏悬浮图标
  - 可设为系统助手（ASSIST）或桌面启动器（HOME）
- **图标包支持**与 Material You 动态取色（Android 12+）
- **按键音与震动反馈**

## 环境要求

- Android 8.0（API 26）及以上，目标 API 35
- 可选：[Shizuku](https://shizuku.rikka.app/)，用于获取应用快捷方式、冻结/解冻等高级能力

## 所需权限

| 权限 | 用途 |
| --- | --- |
| `QUERY_ALL_PACKAGES` | 列出已安装应用 |
| `SYSTEM_ALERT_WINDOW` | 显示零闪屏悬浮面板、悬浮球与边缘触发条 |
| `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE` | 常驻通知与前台服务保活 |
| `HIGH_SAMPLING_RATE_SENSORS` | 读取高采样率加速度计/陀螺仪用于双击背部识别 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 引导加入系统电池优化白名单，保障双击唤起稳定保活 |
| `RECEIVE_BOOT_COMPLETED` | 开机及应用更新后自动恢复触发服务 |
| `READ_CONTACTS` / `CALL_PHONE` | 搜索联系人并拨号 |
| `REQUEST_DELETE_PACKAGES` | 从应用菜单卸载应用 |
| `VIBRATE` | 按键与双击触感反馈 |

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- TensorFlow Lite（Columbus 7 分类手势推理）
- DataStore、Kotlin Coroutines、kotlinx.serialization
- Shizuku API
- pinyin4j（拼音搜索）

## 构建

```bash
# 调试版
./gradlew assembleDebug

# 正式签名版（需提供密钥环境变量）
./gradlew assembleRelease

# 单元测试
./gradlew test
```

Release 构建需要自行准备签名密钥（`signing/` 目录已被 `.gitignore` 忽略，不随仓库发布），并在构建配置中指定。

## 项目结构

```
app/src/main/java/com/quickstart/md3/
├── backtap/   # 轻量化 Columbus 双击背部引擎（滤波器、TFLite 推理、门控与传感器调度）
├── data/      # 应用仓库、拼音搜索、计算器、图标包、数据存储
├── service/   # 前台保活服务、悬浮面板、磁贴、小部件、开机接收器
├── shizuku/   # Shizuku 集成
└── ui/        # 主界面、设置界面、主题
```

## 致谢与许可证

- 双击背部识别算法与 Columbus 模型资产移植自开源项目 [KieronQuinn/TapTap](https://github.com/KieronQuinn/TapTap)。
- 本项目遵循 **GNU General Public License v3.0 (GPL-3.0)** 协议开源，详见 [LICENSE](LICENSE)。
