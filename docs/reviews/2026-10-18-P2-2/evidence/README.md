# P2-2 审计原文附件（复审第 7 条要求归档）

归档时间：2026-10-19（P2-2 二次整改，只做证据归档与文档更正，未改源码）。
归档人：本次整改执行者（Windows 侧，未连接设备）。

## 一、这些文件是什么

设备上「我的 → 隐私记录」的审计原始导出（JSON，单行）。每条记录的字段固定为九个：
`at` / `host` / `purpose` / `sentCharacters` / `sentBody` / `status` / `succeeded` / `promptTokens` / `completionTokens`。

| 文件 | 条数 | 来源（绝对路径，未入库） | SHA256 |
|---|---|---|---|
| `audit-raw.json` | 6 | `D:\Agent\Worktrees\Mycelium-Android\p2-2-work\audit-raw.json` | `cbcde0ac687e3b8bfd4a15584ce44505c675ccdfe6e83cda506f24d104042675` |
| `audit-raw2.json` | 7 | `D:\Agent\Worktrees\Mycelium-Android\p2-2-work\audit-raw2.json` | `7fedc83cb35d2bfad3d9df34386e7b581ae54ede554ad851c3e541c1659cc38b` |
| `audit-raw3.json` | 8 | `D:\Agent\Worktrees\Mycelium-Android\p2-2-work\audit-raw3.json` | `cfb718d2f9051c310eb9440b810ec6829d76d20f499e22646d96555ee9cc30a3` |

三份是**同一台设备上依次导出的三次快照**，后一份包含前一份的全部条目：`raw2` = `raw` + 1 条测试模式记录，
`raw3` = `raw2` + 1 条沉浸记录。复制为逐字节相同（SHA256 已比对一致），未做任何改写。

**编码说明（复核时必读）**：三份都是 **UTF-16LE + BOM（`FF FE`）+ CRLF**、正文只有一行，所以 `git show`
会把它们显示成 `Bin`；不是内容损坏。直接读法（Windows PowerShell）：

```powershell
$dir = 'docs\reviews\2026-10-18-P2-2\evidence'
(Get-Content "$dir\audit-raw3.json" -Raw | ConvertFrom-Json).entries |
  Select-Object at, purpose, sentCharacters, status, succeeded,
                @{n = 'bodyLen'; e = { $_.sentBody.Length } }
```

（`Get-Content` 会按 BOM 自动按 UTF-16 解码；用不支持 BOM 的工具打开要显式指定 UTF-16LE。）

## 二、复审 `REVIEW-r2.md` 第 7 条关心的那一条

`audit-raw2.json` 与 `audit-raw3.json` 都含这条 `HearthChatTest` 记录：

```
at=2026-10-18T08:05:55.032494Z  host=127.0.0.1  purpose=HearthChatTest
sentCharacters=206  sentBody=""（正文长度 0）  status=200  succeeded=true
promptTokens=12  completionTokens=8
```

即：**测试模式出网时，字数照记 206，正文一个字都不留**。这直接证明交付报告第五节
「测试模式不记正文」的规则在设备上确实生效，也证明此前「测试模式那条当时没存下来」的说法与实物不符
（该说法已在 `DELIVERY.md` 撤回）。

同文件里另有沉浸模式的对照记录（正文照记）：

- `audit-raw.json` / `raw2` / `raw3`：`HearthChat`，`2026-10-18T07:25:15.894297Z`，`sentCharacters=377`，正文长度 377。
- 仅 `audit-raw3.json`：`HearthChat`，`2026-10-18T08:07:09.576873Z`，`sentCharacters=470`，正文长度 470。

其余条目是 2026-10-17 的圆桌（`RoundtableChat` 3 条）与 `FetchModels`（2 条，其中 1 条 `succeeded=false`、`status=null`）。

## 三、入库前的敏感字段排查（做过的检查）

对三份原文做了正则与字段级扫描，结论是**可以入库**：

- JSON 字段只有上表九个，**没有** `apiKey` / `token` / `secret` / `password` / `serial` 之类字段
  （出现的 `promptTokens` / `completionTokens` 是计数，不是凭据）；
- `host` 21 处**全部是 `127.0.0.1`**，没有局域网或真实地址；全文没有其他 IP 字面量；
- 没有 `C:\`、`Users`、本机用户名（`87953` 等）、Windows 路径、邮箱、HTTP(S) URL；
- 没有设备序列号（`NX809J` 等型号串也未出现）、没有 `android_id` / IMEI。

需要留意的**非敏感但属个人内容**：`sentBody` 里是用户自己在本机假服务上试聊的正文
（人物设定「老周」与问句「我很累 / 你好吗」等，以及 2026-10-17 圆桌的记账问答）。
这些是用户自己的测试对话，且服务端是 `127.0.0.1` 的进程内假服务；按复审要求原样归档，不做删改。

## 四、用途与局限

- 用途：给复核者一份**仓库内可定位**的审计原文，替代此前只有摘录的 `09-audit-hearth-entries.txt`
  （那份在 Windows 证据目录 `D:\Agent\Worktrees\OMNIX-Mycelium\main\.omx\development\reviews\2026-10-18-android-P2-2\` 下）。
- 局限：这是**当时导出的快照**，不是重新在设备上跑出来的结果；本次整改**没有连接设备、没有推送、没有跑 Gradle**，
  所以只证明「当时设备上确实存在这些记录」，不证明当前 HEAD 的行为。