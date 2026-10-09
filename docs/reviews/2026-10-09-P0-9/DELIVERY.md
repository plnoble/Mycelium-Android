# P0-9 安卓版改名为知衍（Mycelium）· 交付报告

- 分支：`wp/P0-9-rename-to-mycelium`，基准 main `f28c71d`（PR #1 后），tip `45e3143`。
- 范围：只动名称/包名/签名变量/CI/文档相关文件；功能逻辑、版本号（0.0.1/1）、PHASE_0* 正文未动。

## 步骤落实

1. 显示名：`app_name`=知衍；界面文字 Nivra→知衍（TopAppBar 标题与测试断言同步）；`Theme.Nivra`→`Theme.Mycelium`（themes.xml + Manifest）。
2. 包名：namespace/applicationId=`com.xavierxia.mycelium`；源码与测试目录 `com/nivra/app/`→`com/xavierxia/mycelium/`（10 个 .kt 全迁移，package/import 全改）；FileProvider authority 用 `${applicationId}.fileprovider` 自动跟随；proguard 注释同步。
3. 类名：`NivraApp`→`MyceliumApp`（含测试类 NivraAppTest→MyceliumAppTest）。
4. 工程名与更新源：rootProject.name=`Mycelium-Android`；BuildConfig `GITHUB_REPO`=`Mycelium-Android`；User-Agent `Mycelium-Android/<version>`；更新检查假响应（test）改到 `plnoble/Mycelium-Android` 并断言。
5. 签名与 CI：`NIVRA_KEYSTORE_*`→`MYCELIUM_ANDROID_KEYSTORE_*`（本地密钥文件沿用 `nivra-release.jks`，若在 GitHub 已建旧名 secrets 需用户重建，见下）；workflows 名称/产物/报告名 nivra→mycelium。
6. 文档：README/PRODUCT_SPEC/UPDATE_ARCHITECTURE 产品名改为「Mycelium（知衍）安卓版」；MYCELIUM_INTEGRATION 保留历史说明；PHASE_0*.md 开头一行更名说明，正文未改。
7. **GitHub 仓库改名：未做（需用户）**——见「需要用户做的事」。
8. 本地文件夹改名：**未做**（本包未合并、当前有 checkout 占用；合并后按任务书执行）。

## 验证

| 项 | 结果 |
|---|---|
| `rg -i nivra`（排除 .git/build/PHASE_0*/work-packages/reviews） | 代码与配置 **0 命中**；剩余命中为历史说明（docs/MYCELIUM_INTEGRATION.md 的「原名 Nivra」句）与历史评审记录（docs/reviews/2026-10-05-P0-6/），逐条允许 |
| `gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`（--rerun-tasks，ANDROID_HOME） | **全过**：19/19 测试、lint 0 error / 5 warning（与基线同口径） |
| aapt dump badging | `package name='com.xavierxia.mycelium' versionName='0.0.1'`、`application-label:'知衍'` |
| 真机（红魔）安装启动 | Success；`mCurrentFocus=…com.xavierxia.mycelium/.MainActivity`；首屏截图 `p09-first-screen.png`（OCR 核对含「知衍」「Version 0.0.1」），**待 Claude 看图** |
| 更新检查假响应 | 单测 `publishedReleaseSelectsApkAsset` 等断言 `plnoble/Mycelium-Android` 与 UA（注意：旧测试里 APK 资源名 `nivra.APK` 是 `.apk` 点号结尾才被选择器命中，改名时曾误改成 `mycelium-APK` 导致 1 条测试红，已还原为带点号的 `mycelium.apk`） |
| 版本号 | 0.0.1 / 1，未动 |

## 需要用户做的事（按任务书步骤 7）

1. **GitHub 仓库改名**：Settings → Repository name → `Mycelium-Android`（agent 不做）；完成后我执行 `git remote set-url origin https://github.com/plnoble/Mycelium-Android.git`；
2. 若 GitHub 上已按旧名 `NIVRA_KEYSTORE_*` 建过 Actions secrets，需按新名 `MYCELIUM_ANDROID_KEYSTORE_*` 重建（本报告提醒）；
3. 仓库改名后的真实联网更新检查验证，放到改名完成后补做并写回本报告；
4. 手机上旧包 `com.nivra.app` 与新包并存，用户手动卸载旧 debug 版即可（无数据迁移）。

## 备注

- 构建环境：JAVA_HOME=JBR（`D:/Development/android-studio-quail4/Install/jbr`）；SDK 经 `ANDROID_HOME` 注入、不写 local.properties（本地文件会触发 lint PropertyEscape 报错，且不应入库）。
- 真机截图按项目规则交 Claude 看图后确定。
## 补充任务书一落实（2026-10-09，tip fa1844d）

- 版本号规则：`versionName=0.1.0`（首正式版=改名后的壳）、标签 `v主.次.补` 与 versionName 去掉 `v` 后一致、每次发布 versionCode+1；release 工作流新增「标签 vs versionName 一致性门禁」（不一致即失败）。
- 门禁演示双用例（本机跑）：`v0.1.0` 匹配 → 放行；`v0.1.2` 不一致 → 拒绝退出。演示输出见提交记录附注。
- release 工作流 secrets 统一为改名后的四名：`MYCELIUM_ANDROID_KEYSTORE_BASE64 / MYCELIUM_ANDROID_KEYSTORE_PASSWORD / MYCELIUM_ANDROID_KEY_ALIAS / MYCELIUM_ANDROID_KEY_PASSWORD`；构建环境变量 `MYCELIUM_ANDROID_KEYSTORE_PATH` 等一一对应。
- 构建+单测：assembleDebug + testDebugUnitTest 全绿（19/19）。
- **本包当前状态：等统筹审（代码+版本规则）。审过后按授权推送分支、开 PR（统筹合并）；再生成密钥、配 secrets、发 v0.1.0/v0.1.1、真机更新验证。**
