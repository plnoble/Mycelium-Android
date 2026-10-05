# P0-9 安卓版改名为知衍（Mycelium）

- 仓库 / 基准：安卓仓库 `main`（第 0 阶段 PR #1 合并后）→ 分支 `wp/P0-9-rename-to-mycelium`
- 依赖：P0-6（第 0 阶段）已合并，且没有其他 agent 在这个仓库里工作
- 预计规模：0.5–1 个工作日
- 背景：用户 2026-09-29 决定：
  - 两端软件都叫 **Mycelium（知衍）**；
  - 「Nivra」不再用于产品；
  - 仓库改名为 `Mycelium-Android`，包名改为 `com.xavierxia.mycelium`。

  现在还没发布过安装包，是改名成本最低的时候。

## 目标（用户能得到什么）

手机上装的、看到的、更新检查连的，全部是「知衍」；代码和文档里不再把 Nivra 当产品名。

## 步骤

1. **显示名**：
   - `res/values/strings.xml` 的 `app_name` 改为「知衍」；
   - 界面上出现「Nivra」的文字改为「知衍」；
   - 主题名 `Theme.Nivra` 改为 `Theme.Mycelium`（以实际名称为准）。
2. **包名**：
   - `app/build.gradle.kts` 的 `namespace` 和 `applicationId` 改为 `com.xavierxia.mycelium`；
   - 源码和测试从 `com/nivra/app/` 移到 `com/xavierxia/mycelium/`，改 `package` 声明和 import；
   - 检查 `AndroidManifest.xml`、`res/xml/file_paths.xml`（FileProvider authority）、`proguard-rules.pro` 里的包名引用。
3. **类名**：`NivraApp` 改为 `MyceliumApp`，对应测试同步改名；其他带 Nivra 的类名同样处理。
4. **工程名与更新源**：
   - `settings.gradle.kts` 的 `rootProject.name` 改为 `Mycelium-Android`；
   - BuildConfig 的 `GITHUB_REPO` 改为 `Mycelium-Android`；
   - 更新检查的测试同步修改（假响应里的仓库名）。
5. **签名与 CI**：
   - 环境变量 `NIVRA_KEYSTORE_PATH / NIVRA_KEYSTORE_PASSWORD / NIVRA_KEY_ALIAS / NIVRA_KEY_PASSWORD` 改为 `MYCELIUM_ANDROID_KEYSTORE_PATH` 等；
   - `.github/workflows/*.yml` 里的密钥映射、工作流名、产物名（APK 文件名）一并修改；
   - 如果 GitHub 上已经按旧名建过密钥，在交付报告里提醒用户重建。
6. **文档**：
   - `README.md`、`docs/PRODUCT_SPEC.md`、`docs/UPDATE_ARCHITECTURE.md` 里的产品名改为「Mycelium（知衍）安卓版」；
   - 保留一句历史说明：「原名 Nivra，2026-09-29 更名」；
   - 按 [../MYCELIUM_INTEGRATION.md](../MYCELIUM_INTEGRATION.md) §7 更新规格：两个空间、隐私分级、圆桌允许多角色和云端、只做自己设备之间的局域网同步；
   - `docs/PHASE_0*.md` 是历史记录，只在开头加一行更名说明，不改正文。
7. **GitHub 仓库改名**：
   - 由**用户**在 GitHub 上操作（Settings → Repository name → `Mycelium-Android`）；agent 没有用户在对话里的明确指示，不得自己改。
   - 改名后执行 `git remote set-url origin https://github.com/plnoble/Mycelium-Android.git`。
8. **本地文件夹改名**：`D:\Agent\Project\Nivra` → `D:\Agent\Project\Mycelium-Android`。
   - 在本包合并、而且没有程序或 IDE 占用这个文件夹之后再做；
   - Windows 仓库文档里的路径由统筹（Claude）随后统一更新。

## 范围

- **可以改**：本仓库里与名称、包名、签名变量名、CI 名称、文档相关的全部文件。
- **不许碰**：
  - 功能逻辑（更新检查的行为不变）；
  - 版本号（保持 `0.0.1` / `1`）；
  - `docs/PHASE_0*.md` 的正文。

## 验收标准

- [ ] 在仓库里执行 `rg -i nivra`（排除 `.git`、`build`），只剩下历史说明这类允许的命中；逐条列出。
- [ ] `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 全部通过。
- [ ] APK 元数据（`aapt dump badging` 或等价方式）显示：包名 `com.xavierxia.mycelium`、应用名「知衍」、启动 Activity 正确。
- [ ] 在红魔真机上安装并启动，附桌面图标名称和首屏截图。
- [ ] 测试证明更新检查请求的是 `plnoble/Mycelium-Android`（假响应）。仓库改名完成后再做一次真实联网检查，结果写进交付报告。

## 必交证据

- `docs/reviews/<日期>-P0-9/DELIVERY.md`
- `rg` 输出
- 构建、测试、Lint 日志
- APK 元数据
- 真机截图
- 需要用户做的事：改 GitHub 仓库名、重建密钥等

## 风险与注意

- 包名一变，手机上旧的 debug 版和新版会**同时存在**（两个不同的应用）。旧的手动卸载即可，没有用户数据需要迁移。
- Robolectric 测试里写死的包名要一起改，**不要删测试**。
