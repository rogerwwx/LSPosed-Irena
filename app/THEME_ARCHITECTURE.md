# 管理器主题架构

## 数据流与职责

`SharedPreferences → ThemeConfig → SeedResolver → ResolvedPalette → ThemeController → View / Compose`

- `theme/ThemeConfig`：纯 Java 不可变配置；规范化旧值，定义颜色来源及有效规范版本。Android 8.1 启动路径不依赖 Java 9 集合工厂方法。
- `theme/ThemePreferences`：在 App 初始化、偏好 XML 写入默认值之前迁移配置。原有 key 保留，新增 `theme_config_version=1`、`color_source`。
- `theme/SeedResolver`：仅调用公开系统接口；系统选色使用 `system_accent1_500`。这是系统调色板中的派生色，不是壁纸原始种子，也不保证复刻系统整套配色。壁纸主／次／第三候选色按等权重交给 MCU Score 筛选，不伪造像素占比。读取失败最终使用 `#2196F3`。
- `theme/SeedSelection`：可在 JVM 独立验证的种子选择与回退策略；延迟读取来源，系统选色成功或使用固定强调色时不请求壁纸。
- `theme/ResolvedPalette`：纯 Java 调用仓库现有 MCU，输出 47 个角色的日／夜配色；不改上游色彩数学，不引入新的规范版本。
- `util/monet/MonetPalette`：将完整配色适配到资源表，只管理自己创建的 ResourcesLoader／ResourcesProvider。替换时先添加新 loader，再移除旧 loader、清理 provider 引用并关闭 provider。失败不会启用运行时颜色覆盖。
- `theme/ThemeController`：统一应用顺序和刷新签名；最多缓存一份生成结果，不持有 Activity。
- `ThemeUtil`：旧调用者的兼容入口，皮肤、明暗、固定选色与配色参数来自统一配置。

主题应用顺序是：基础主题 → 配色或完整静态回退 → 皮肤 → 明暗表面映射 → 纯黑覆盖 → Rikka 偏好组件样式。颜色生成成功不等于加载成功，只有后者才能启用 palette overlay。

## 公共层与皮肤层

- `ManagerNavigationController` 负责导航行为；`NavigationAppearance` 隔离皮肤呈现。Material 使用显式 TextStyle 的 BasicText，miuix 保留原有 MiuixTheme／Text。
- `ThemedPreferenceAdapter`、列表装饰器使用当前主题角色。真正共享的资源采用 `theme_*`、`*.LSPosed.Common.*`；miuix 专属颜色、形状和组件保留原名。
- `miuix_theme.xml`、`m3e_theme.xml` 分别定义皮肤；`theme_palette.xml` 绑定生成颜色；`theme_surfaces.xml` 只映射表面用途；`theme_widgets.xml` 提供共享菜单与列表样式。

| 用途 | Material 浅色 | Material 深色 | miuix |
|---|---|---|---|
| 页面 | surfaceContainer | surface | 原背景色 |
| 卡片 | surfaceContainerLowest | surfaceContainerLow | 原 surface |
| 弹出菜单 | surfaceContainerHigh | surfaceContainerHigh | 原 surface／菜单背景 |
| 导航选中 | secondaryContainer / onSecondaryContainer | 同左 | 原着色／加粗行为 |
| 正文／说明 | onSurface / onSurfaceVariant | 同左 | 原文字色 |

纯黑模式保持卡片层次，不把所有表面都覆盖成黑色。Material 成功状态使用独立的绿色语义角色；错误继续使用 errorContainer 配对角色。导航角标使用 primary / onPrimary，不再固定白字。Material 选中容器不再混入动画强调色，以保留前景与背景的配对关系。

## 设置与迁移

日常主题页集中界面风格、明暗、纯黑、颜色来源及固定强调色；高级配色默认折叠，保留七种风格、两个规范版本和壁纸来源入口。

| 配置 | 处理 |
|---|---|
| 全新偏好 | 界面仍默认 miuix；Material 配色默认 SYSTEM_SEED / TONAL_SPOT / SPEC_2025 |
| 旧 SYSTEM / SYSTEM | SYSTEM_DIRECT，原样使用系统颜色 |
| 旧动态自定义组合 | WALLPAPER；SYSTEM 风格按原行为解释为 TONAL_SPOT，SYSTEM 规范解释为 SPEC_2021 |
| 旧固定选色 | FIXED，保留原颜色；COLOR_BLUE 归一为 MATERIAL_BLUE |
| 旧安装只有 doh 等非主题偏好 | 视为旧默认配置，保留系统直接配色行为 |
| 不认识的值或错误类型 | 归一到有效默认值，不传递给引擎 |

2025 仅在 Tonal Spot、Vibrant、Expressive 下有效。其他保留风格实际使用 2021，设置页显示原因并禁用规范选择；用户之前选择的规范仍保留，切回支持的风格可以恢复。2026 不暴露。

系统直接模式禁用风格和规范，因为它们不参与生成。壁纸生成从高级设置进入，选中后会作为日常颜色来源的当前值显示。miuix 保留原系统强调色开关及静态选色，不运行 Material 生成器。

Android 11 起支持资源表生成；系统种子优先在 Android 12 起读取，否则回退到壁纸／蓝色。Android 8.1～10 隐藏运行时来源和高级生成控件，使用静态选色。

## 刷新与失败处理

配置先写入 SharedPreferences，再在 UI 队列重建 Activity。独立／寄生模式均不调用进程退出。签名包含皮肤、来源、实际种子、风格、有效规范、固定选色、明暗和纯黑状态；系统直接模式还包含系统辅助色及中性色指纹。

返回前台时检测签名变化；可见期间监听壁纸颜色变化，覆盖 Material 生成和 miuix 系统强调色。Android 8.1～10 的静态主题不读取壁纸种子，也不因壁纸变化重建。监听注册失败不阻断页面，离开可见状态移除监听。来源读取失败使用回退种子；生成或加载失败使用完整静态主题，不能叠加半套紫色占位角色。

## 验证与剩余验收

运行 `python tools/check_theme.py`，需要 JDK 21+ 和 Python 3，**不需要 Android SDK**。测试包含旧配置迁移、未知值和错误类型、签名失效、API 能力、系统色优先与壁纸回退（含权限异常、空值和低彩度候选）、6 种种子 × 7 种风格 × 2 个请求规范 × 明暗，共 168 份颜色方案；对无效 2025 组合验证实际使用 2021。

检查正文、说明文字、容器配对、分组标题与控件边界的对比度，以及卡片与页面的明度层次。47 个颜色角色会使用稀疏资源 ID 和重命名条目生成资源表，并由独立解析器检查日夜值；角色绑定、资源声明和 XML 也会检查。

生成物在 `app/build/theme-check/`：

- `palette-comparison.html`：6 组种子的明暗配色预览，明确标记为角色预览，**不是 Android 截图**。
- `palette.arsc`、`palette-expected.txt`：资源表与预期值，可再用 Androguard 检查。
- `baseline-colors.txt`：推荐默认蓝色的日夜角色值。

Manager 工作流加入相同检查，并上传配色预览；原 debug／release 构建步骤保留。本地配色与资源测试已通过，也用 Androguard 独立确认了全部 47 个角色。本地完整构建在解析 Kotlin 2.4.10 离线插件缓存时失败，且本机没有 Android SDK；当前无 adb 设备。因此下列事项仍需云端构建／设备验收，不能由本地测试代替：

1. debug 与经过资源优化的 release 均能启动，并读取正确的运行时颜色。
2. API 27～29 静态回退、API 30 生成、API 31+ 系统选色；miuix 与 Material 均覆盖浅色、深色、纯黑。
3. 独立及寄生管理器连续切换来源、风格、规范和皮肤，确认无旧色残留、闪退或导航状态丢失。
4. 修改系统选色／壁纸后返回，旋转屏幕，验证颜色、系统栏和 Compose 导航一致。
5. 对设置、作用域／模块列表、仓库、日志、搜索、菜单和对话框进行真机截图比较；确认 miuix 原有观感保留。

本轮不改框架注入逻辑、导航结构或全量 UI 技术栈。
