# 知衍安卓版发版步骤（一页纸）

适用范围：每发一个新版本（例如 0.4.0 → 0.4.1）。由统筹执行，推送、打标签、发布每一步都要先得到用户同意。

**禁止**：没得到用户同意就推送、打标签、建 Release；提交签名密钥、keystore、APK 或本机路径。

## 顺序总览

改版本号 → 写更新说明 → 合并到本地 `main` → 公开前检查 → 推送 `main` → 看 CI → 手动试跑发布流程 → 打标签 → 看发布与附件 → 手机上验证。

## 1. 改版本号

- 文件：`app/build.gradle.kts`，只改这两个值：
  - `versionCode`：每次发布 **+1**，只能涨，不能一样或变小（发版闸门会拦）。
  - `versionName`：与标签去掉 `v` 后一致（标签 `v0.4.1` ↔ `versionName = "0.4.1"`）。
- 版本号规则：功能包升中间位（0.5.0），只修 bug 升末位（0.4.1）；1.0.0 留到记忆和模型管理都做完。
- 不要顺手改 ABI 过滤、签名配置或依赖版本——发版提交越窄越好审。

## 2. 写更新说明

- 新文件 `docs/releases/v<版本>.md`，中文，写给用户看：这一版能做什么、怎么升级、已知问题。
- 这个文件就是 GitHub Release 的正文，也是 App 更新卡片里显示的「更新说明」，写的时候按用户能看懂的话写，不写内部术语。
- 文件不存在、不是普通文件或内容为空 → 发版闸门当场中止（闸门 2）。

## 3. 合并到本地 `main`

- 由统筹审过（方案审 + 全量审）再合并。合并前确认：`scripts/check-all.ps1` 在全新 worktree、已提交的版本上跑过，零失败零警告。
- 本机跑门禁前要先设好 `JAVA_HOME`（Android Studio 自带 JBR）和 `ANDROID_HOME`，否则脚本开头就会退出。

## 4. 公开前检查

- 跑 `scripts/check-public.ps1`（扫描 HEAD 的所有被跟踪文件，以及 `origin/main..HEAD` 每个提交的改动；只在历史里出现过也算）。
- 「一定失败」的项必须先清理再推：私钥样式、`sk-` / `hf_` / `ghp_` / `github_pat_` 这类长令牌、带用户名的路径（`C:\Users\<名字>\` 之类）、被跟踪的 keystore / `*.gguf` / `*.apk`。
- 「只列出、不失败」的本机盘符路径（本机开发目录之类）要逐条看过，把会公开的内容清单给用户看，用户同意后才推送。
- 本地补充词表放 `.local/public-denylist.txt`（`.local/` 已在 `.gitignore` 里，不进仓库），用户名、设备序列号这类词由统筹自己往里写；脚本和仓库里都不出现这些词本身。
- 提醒：这个脚本是「推之前」的门禁，不是「推之后」的补救——已经推上去的历史收不回来。

## 5. 推送 `main`

- 用户同意后才推。推完 `main` 上看 `check-all` 的 CI 结果。

## 6. 看 CI

- `main` 的门禁 CI 全绿（测试、lint、正式包未签名等）。
- 注意：P2-1b 加的 NDK 与 llama.cpp 准备步骤此前**从未在 GitHub 上跑过**，第一次推送后要专门看一遍这个任务的日志。

## 7. 手动试跑发布流程（`workflow_dispatch`）

- GitHub → Actions → **Release APK** → **Run workflow**，分支选 `main`。
- 试跑会照常装 NDK、编 llama.cpp、构建、用正式 keystore 签名、跑同一个发版闸门（发布说明、versionCode、签名指纹都在内），然后算 sha256，把版本号、APK 大小、sha256 和证书指纹打进日志与任务摘要。
- 试跑**不建 Release、不上传 APK**（构建 job 只有 `contents: read`；发布是另一个 job，试跑时不会跑）。
- 用途：确认 GitHub 上能编出带 llama.cpp 的**签名**包，且指纹对得上。试跑里的标签是从 `versionName` 推出来的 `v<版本>`。
- 试跑不能验证「Release 附件齐全」——那要等真正打标签那次。

## 8. 打标签

- 用户同意后：`git tag v<版本>`，再推送这个标签。**标签推送才会真正发布**。
- 标签和 `versionName` 必须一致，否则闸门 1 中止。

## 9. 看发布与附件

- Release 里应当有 `mycelium-v<版本>.apk` 和 `mycelium-v<版本>.apk.sha256`，正文是仓库里的中文发布说明（自动生成的英文提交列表已经去掉）。
- 两个附件缺任何一个 —— App 只会显示「有新版本」和发布页链接，不给应用内安装。所以附件必须齐全。
- 发完立刻在 release 页面上核对附件名和大小。

## 10. 手机上验证

- 用手机浏览器打开 release 页下载安装，或在 App 里走一遍「下载并安装 → 系统确认 → 安装」。
- 装完确认版本号变了、数据还在（人物、对话、本地模型登记、云端服务）。
- 0.1.1 → 0.4.0 这一次只能走浏览器下载或数据线；0.4.0 → 0.4.1 才是第一次真正的应用内升级，用它做最终验收。

## 发版闸门（`scripts/release-guard.sh` 四项）

`.github/workflows/release.yml` 在签名之后调用它，本地也可以用 Git for Windows 的 bash 演示。四项任一不过就 `exit 1`，并打印中文原因：

1. **标签与 versionName 一致**；
2. **发布说明存在**：`docs/releases/<标签>.md` 是普通文件且非空；
3. **versionCode 严格大于上一个版本标签里的值**（依赖完整历史，所以 CI 的 `actions/checkout` 必须 `fetch-depth: 0`，工作流里还会 `git fetch --tags --force`）；
4. **签名证书 SHA-256 指纹一致**：用 `apksigner verify --print-certs` 读出的指纹与固定值比对，签名者数量不是 1 也中止。

CI 里的用法（不带任何覆盖参数）：

```bash
bash scripts/release-guard.sh --tag "$RELEASE_TAG" --apk "mycelium-$RELEASE_TAG.apk"
```

本地演示可以用 `--previous-tag`、`--version-code`、`--expected-cert-file`、`--release-notes` 覆盖默认取值；一旦检测到 `CI` 或 `GITHUB_ACTIONS`，这些覆盖参数会被**直接拒绝**，防止流水线绕过指纹和版本号检查。

### 签名指纹文件的位置

- `docs/releases/signing-cert-sha256.txt`——公开信息，不是密钥，也不是能用它签名的东西。
- 来源：GitHub 上 v0.1.1 的 `mycelium-v0.1.1.apk`，用 build-tools 36.0.0 的 `apksigner verify --print-certs` 读出的证书 SHA-256；证书 `CN=Mycelium Android Release`，RSA 4096，只用了 v2 签名方案。
- 现值写在文件里（一行 64 位十六进制）。CI 与本地闸门都读它，改一处即可。
- **换签名 = 已装 0.1.1 及以后版本的手机无法覆盖安装，只能卸载重装，会丢数据**。真要换，必须同时改这个文件，并在发布说明里提前告诉用户。

## 出错时怎么办

- **闸门中止**：照中文原因改——版本号没涨就改 `versionCode`；发布说明缺失就补 `docs/releases/v<版本>.md`；签名指纹不一致就查 CI 里的 keystore secrets 是不是正式签名那份。
- **标签已推、发布不对**：不要删标签重推（会造成混乱）。先定位并修好问题，发一个补丁版本（0.4.1）。
- **CI 里编不出带 llama.cpp 的包**：先看「Prepare pinned llama.cpp source」与 NDK 安装两步的日志；本地离线构建能过不代表联网 runner 能过。
- **0.1.1 上的用户收不到更新提示**：0.1.1 的「检查更新」只会用浏览器打开发布页，这是预期行为，不是故障。