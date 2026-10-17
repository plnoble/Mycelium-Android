# P1-6a 改名：「伴侣」空间改为「炉边」

- 仓库 / 基准：安卓仓库 `main`（P1-6 合并后）→ 分支 `wp/P1-6a-rename-hearth`
- 依赖：P1-6 已合并。
- 预计规模：半天以内。
- 版本：版本号不变，仍是 0.2.0（versionCode 3）。0.2.0 还没发布，这次改名随 0.2.0 一起发；发不发、什么时候发由用户决定。
- 背景：
  - 2026-10-17 用户决定：这个空间里的人物性格由用户随意设定，不一定是伴侣，所以改名「炉边」。
  - 代码名用 `hearth`，不再用 `companion`。总计划 v1.4、`docs/MYCELIUM_INTEGRATION.md`、P1-7 任务书已经改过。

## 要改的

1. **界面**：
   - 底栏：标签「伴侣」改成「炉边」，图标字「伴」改成「炉」。
   - 空间页：标题「炉边」；说明改为「炉边在下一阶段开发：只用手机上的模型，不联网。」
   - 这个空间和圆桌在颜色上的区分保持不变。
2. **代码标识符**：`Space.Companion` 改成 `Space.Hearth`，`companionColors` 改成 `hearthColors`，`CompanionScreen` 改成 `HearthScreen`。Kotlin 的 `companion object` 是语言关键字，和这次改名无关，不要动。
3. **测试**：`MainActivityTest` 里的文字断言跟着改。
4. 在 `app/src` 和 `contract-lib/src` 里搜「伴侣」：界面文字里不应再出现。

## 不许碰

- 版本号、更新逻辑、发布签名配置、`release.yml`、`contract/`。
- 历史记录：`docs/reviews/`、`docs/tracks/` 里已有的内容，以及已完成的任务书，不改。

## 验收标准

- [ ] `:contract:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 在全新 worktree 里对已提交版本通过。
- [ ] 界面上找不到「伴侣」。
- [ ] 真机：在红魔上装 debug 包，截两张图：圆桌首页（能看到底栏「桌 / 炉 / 我」），以及炉边页。截图规则同 P1-6；放 Windows 仓库 `.omx/development/reviews/<日期>-android-P1-6a/`，不进本仓库。正式版不动。

## 必交证据

- `docs/reviews/<日期>-P1-6a/DELIVERY.md`

## 审核

- 改动很小，直接交统筹看，不用调 Codex。通过后由统筹合并进本地 `main`，然后再开 P1-7。
