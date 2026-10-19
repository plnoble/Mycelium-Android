# 取消正在建立中的连接：按平台限制结案

**范围**：P2-2 复审第 1 条「取消要能中断**正在建立中**的连接」。r2、r3、r4 三轮复审均判不通过。
**基线**：`wp/P2-2-hearth-chat` 的 `5ba43760e72f2cabf82f7b7e1391291a1f2beac7`。
**本轮改动**：`net/src/main/kotlin/com/xavierxia/mycelium/net/Gateway.kt`、`net/src/test/kotlin/com/xavierxia/mycelium/net/GatewayTest.kt`、本文件。
**结论**：这一条**不是本实现的缺陷，是 `HttpURLConnection` 的既有行为**；实现侧只能把它的影响封顶（连接超时），不能消除。裁定选项见第 8 节。

---

## 1. 问题陈述

要求是：用户停止一个正在发出的请求时，如果该请求**已经在建立底层连接**，这次停止应当中断那条连接。

本实现把「停止」做成一个不可逆的开关（`GatewayCall`），并用它把停止与建连启动做成同一个临界区：

- `GatewayCall.cancel()`：置位开关，并 `disconnect()` 已登记的连接（`Gateway.kt:160`）。
- `GatewayCall.startingConnection()`：在锁内判断是否已停止，未停止才**调用**平台的建连入口（`Gateway.kt:186`）。
- 正文字节经 `bodyStream()` / `StoppedBodyStream`，每次 write、flush 前都复检开关。

三轮复审指出的共同点是同一个：**「锁内判断、锁外调用 `connect()`」之间仍然存在可被停止插入的窗口，而平台在这一窗口里不会因为 `disconnect()` 而放弃建连。** 本轮不再试图用检查关闭这个窗口，而是把它作为平台事实记录清楚。

## 2. 平台限制与 AOSP 依据

Android 的 `HttpURLConnection` 实现在 AOSP 的 `external/okhttp`（`okhttp-urlconnection` 的 `huc/HttpURLConnectionImpl`）：

- `disconnect()` 在 `httpEngine == null` 时**直接返回**，不留下任何「已取消/待取消」状态；`httpEngine` 要到 `connect()` 才被创建。
- `getOutputStream()`（以及 `getResponseCode()`）会调用 `connect()` —— 也就是说，**取输出流这一步才真正发起建连**，而它前面没有任何可中断的钩子。
- 因此「先 `disconnect()`、后触发建连」在真实平台上**不会**阻止建连；本实现能做的只有不让建连被发起（保证 A），或者在它发起之后断开它（保证 B），**不能打断已经进入的 `connect()` 调用本身**。

引用位置（**三轮复审共同引用的同一处源码**）：
`https://android.googlesource.com/platform/external/okhttp/+/refs/heads/main/okhttp-urlconnection/src/main/java/com/squareup/okhttp/internal/huc/HttpURLConnectionImpl.java`

**证据边界（诚实说明）**：本轮环境按约定不联网，**没有重新抓取该 AOSP 源码**，因此上述两条行为以三轮复审各自独立引用同一处源码为据，行号未在本环境复核。

三项本机可复核的旁证（均在 JVM 参考实现 `sun.net.www.protocol.http.HttpURLConnection` 上实测，**不是 Android 实现**）：

1. `new URL(...).openConnection()` 后未设置时 `getConnectTimeout()` 返回 **0，即无限等待** —— 这是第 6 节超时兜底的前提。
2. 对尚未建连的连接先 `disconnect()`，随后 `getOutputStream()` **仍然发起连接**并抛出 `ConnectException: Connection refused`（目标 `127.0.0.1:1`，全程 loopback、无外部网络）。即 `disconnect()` 的「空操作」语义在参考实现上可直接观察。
3. 线程 `interrupt()` 不会中止阻塞中的 socket `connect()`；能结束它的只有它自己返回、抛错或超时。

后两项与 AOSP 的行为同构（该实现正是同源代码谱系），可作为**旁证**，不能替代 Android 上的实测。

## 3. 三轮复审意见摘要

| 轮次 | 状态 | 意见要点 |
|---|---|---|
| r2 | 不通过 | 最后检查与实际建连之间仍有窗口；`disconnect()` 对未建立的连接无效，恢复后仍会开始连接。要求「让底层请求在启动连接前就具有持续有效的取消状态」，并补「进入取流、尚未建立连接时取消」的受控测例。 |
| r3 | 不通过 | `bodyStream()` 前置检查返回后、`open()` 真正建连前，取消仍只做一次无效 `disconnect()`。并指出 `FrozenPeer.disconnect()` 只计数、退出时间是测试主动释放闩锁后测的，所以「两秒内返回」**不能**证明取消能中断建连。 |
| r4 | 不通过 | `startingConnection()` 在锁内检查、锁外调用 `connect()`，两者之间仍可取消。并指出夹具「自行保存 `disconnected` 并读取 `call.isCancelled`，替真实底层提供了它没有的取消行为」，因此这些测例不能证明该窗口已修好。 |

三轮的实质诉求是一致的：**要求本实现提供平台没有提供的可中断性**。r4 关于夹具的批评是本轮唯一可以完全照做的部分 —— 已按第 7 节处理。

## 4. 现在保证了什么

| | 内容 | 落点 | 测例 |
|---|---|---|---|
| **保证 A** | 取消发生在建连启动**之前** ⇒ **一次连接都不会发起**。停止与启动的决定在同一临界区，停止先到则平台调用根本不被执行。 | `Gateway.kt:186`（`startingConnection`）、`Gateway.kt:256` 起 KDoc 第 1 条 | `aCancelledRequestNeverOpensAConnection`、`aStopBeforeTheConnectionStartsMeansThePeerNeverSeesAConnect` |
| **保证 B** | 取消发生在建连**已开始之后** ⇒ 立即 `disconnect()` 该连接，且**任何正文字节都不会写出**。取流前、取流后、每次 write/flush 前都复检开关。 | `Gateway.kt:207` 起（`bodyStream`）、`StoppedBodyStream` | `aStopInTheRegistrationWindowNeverWritesTheBody`、`aStopWhileTheConnectionIsBeingStartedEndsThatConnectAtOnce` |
| **做不到** | 取消**无法中断**底层正在进行的 `connect()` 调用本身；该调用只能等它返回、抛出或**超时**。 | `Gateway.kt:273` 起 KDoc 第 3 条 | 无测例，且不应有：任何夹具都不得拥有平台没有的取消能力。 |

**实际暴露面**：残余窗口是「锁内判断通过」到「进入 `connect()`」之间的几条指令宽。停止若恰好落在这里，本次请求不会立即结束，而要等那次 `connect()` 返回、失败或超时。用户可见的最坏情况 = 网络正卡在建连时的**一个连接超时**（默认 15s，上限 30s，见第 6 节）。若建连很快成功，正文字节仍会被保证 B 拦住，不会发出。

## 5. 为什么不能把「取消与建连」放进同一把锁跨住 `connect()`

设想：`startingConnection()` 全程持锁，`connect()` 在锁内执行，`cancel()` 也必须拿同一把锁。

结果是**取消会排在它想中断的那次建连之后**：`cancel()` 阻塞等锁 → 建连（可能卡满一个超时）返回后才置位开关。也就是说，用户按下停止之后，socket 会带着「已请求停止」的状态继续存活到该 ROM 的连接超时为止 —— 比现在多等一整个超时，而且这段时间里连接仍然建立成功。所以正确做法是：**锁只保护「是否允许启动」这个决定，不覆盖阻塞调用**；停止的代价仅为另一个临界区的几条指令。

结论：这一取舍是**正确性优先、取消延迟次要**，不是遗漏。

## 6. 连接/读取超时兜底

`HttpURLConnection` 的 `connectTimeout` 默认 **0 = 无限等待**（本机实测，见第 2 节）。既然「已开始的建连」只能等它超时，**这个值就是该窗口影响的唯一封顶手段**。

| 常量 | 取值 | 理由 |
|---|---|---|
| `DEFAULT_CONNECT_TIMEOUT_MS` | 15 000 | 两端权衡：太小会在慢网/拥塞链路上误杀握手（DNS + TCP + TLS 在差链路上经常超过 10s）；太大则让「已按下停止但建连还在跑」的等待变长。15s 同时是残余窗口的默认最坏等待。 |
| `MAX_CONNECT_TIMEOUT_MS` | 30 000 | 调用方可以更耐心，但**不能无限**：单次建连最多把取消窗口拖到 30s。取默认值的 2 倍。 |
| `DEFAULT_READ_TIMEOUT_MS` | 60 000 | 覆盖非流式补全。它**不影响取消**：停止会 `disconnect()` 连接，从而立刻结束阻塞的读。 |

归一化（`Gateway.kt:351–357`）：**非正值（0 或负）→ 用默认值**（0 在平台上等于「永不到期」，不能让它到达 socket）；**超过上限 → 截到上限**。这样无论调用方怎么填，落到 socket 上的连接超时永远是**有限且有界**的。

各调用点现值：更新检查 10s（`app/.../GitHubReleaseClient.kt:31`）；聊天走默认 15s；模型列表读取超时 30s。

## 7. 测例的边界（证明什么 / 不证明什么）

r4 指出夹具「替真实底层提供了它没有的取消行为」。本轮已删除这两处伪造：

- 删掉 `FrozenPeer.connect()` 中对 `call.isCancelled` 的读取；
- 删掉「`disconnect()` 一旦发生，之后的 `connect()` 必定失败」的**粘性语义**。

改后夹具忠实复现平台的空操作：**未发起建连时到来的 `disconnect()` 不会阻止随后的 `connect()`**。删除后 A/B 的测例仍然全绿，说明这两条保证靠的是网关自己的闸门与检查点，而不是对端配合 —— 这比改动前更能说明问题。

| 测例 | 证明 | 不证明 |
|---|---|---|
| `aCancelledRequestNeverOpensAConnection` | 保证 A 的最早情形：连连接对象都不打开 | 任何「已开始的建连」 |
| `aStopInTheRegistrationWindowNeverWritesTheBody` | 保证 B 的一半：对**真实 loopback 服务器**实测零正文字节 | 请求「提前结束」；此处根本没走到建连 |
| `aStopBeforeTheConnectionStartsMeansThePeerNeverSeesAConnect` | 保证 A 的最晚可胜点：闸门让 `connect()` 一次都没被调用 | 闸门之后的时刻 |
| `aStopWhileTheConnectionIsBeingStartedEndsThatConnectAtOnce` | 网关在停止时确实 `disconnect()` 已登记连接并迅速返回 | **Android 能中断在途建连**。夹具「`disconnect()` 立即结束在途 `connect()`」是对平台的**假设**，不是观测；真机可能一直等到连接超时 |
| `aRequestThatIsNotStoppedGoesThroughTheSameGateAndDeliversTheWholeBody` | 阳性对照：无停止时连接照发、正文照到 | 与取消无关 |

**新增测例（2 条，均为注释级说明 + 断言）**：

- `theGatewayAlwaysSetsFiniteTimeoutsOnTheConnectionItUses`（`GatewayTest.kt:485`）：断言真正落到连接上的 `connectTimeout` / `readTimeout` 等于声明的默认值且连接超时有界，防止将来被改回 0/无限。
- `aTimeoutThatMeansInfiniteOnThePlatformIsReplacedByADeclaredBound`（`GatewayTest.kt:519`）：对 0、负数、600000 三种输入断言落到 socket 上的实际值 —— 无限等待漏不出去，超上限被截断。

**关于 `holdConnect`**：它把「已开始的 `connect()` 卡住、只有 `disconnect()` 能放行」建模为夹具行为。真机上在途调用是被引擎关掉 socket 而结束的，**这在 JVM 测例里无法观测**，因此它是假设而非证据；该测例的注释已明确写出这一点，不得被读作「窗口已关闭」。

## 8. 给统筹的裁定选项

| 选项 | 内容 | 能消除什么 | 代价 |
|---|---|---|---|
| **1. 接受为已知残留（当前状态）** | 保留保证 A/B，把「不能中断在途 `connect()`」作为平台限制记录在案；连接超时封顶最坏等待（默认 15s / 上限 30s）。 | 消除「取消后仍写出正文」与「取消后仍发起建连」；把最坏等待变成有界值。 | 最坏仍需等待一个连接超时；需要一个面向用户的「停止中」反馈来掩盖这段等待，否则用户会认为按钮没生效。残余窗口客观存在，复审若坚持原判据仍会判不通过。 |
| **2. 批准引入 OkHttp 之类可取消的客户端** | 在 `:net` 内用 OkHttp 的 `Call.cancel()` 替换 `HttpURLConnection`。 | 设计上能真正中止在途调用（`cancel()` 关闭底层 socket），从而可能关闭整个窗口；顺带获得连接池与更清晰的超时模型。 | 新增第三方依赖与供应链面；`:net` 内要重做超时/取消/重定向/审计接线，并重跑 `:net` 全部测例；新增依赖需过审；**「`cancel()` 是否立即打断正在 `connect()` 的 socket」仍需在本项目设备上实测确认**，即换库也不等于拿到无条件保证。白名单/审计边界不受影响：HTTP 本来就只允许出现在 `:net`。 |
| **3. 其它（缩小窗口的折中）** | 例如：把默认连接超时从 15s 收紧到 5s；或在 UI 上把「停止」的语义明确为「最多再等 N 秒」。 | 缩短最坏等待，不改架构。 | 5s 会在慢网误杀建连，把「取消慢」换成「联网失败」；UI 措辞不改变任何底层行为，只是把同一限制讲清楚。两者都**不**消除那个窗口。 |

第 2 个选项是唯一可能真正消除该窗口的方向；第 1 个选项是唯一不需要新增依赖的方向。本文件不推荐哪个，只把代价写清。

## 9. 本轮验证与未验证

- **已验证**：`.\gradlew.bat --offline --console=plain :net:test :roundtable:test :contract:test` → **BUILD SUCCESSFUL in 33s**；`net` 19 例、`roundtable` 28 例、`contract`（projectDir = `contract-lib`）22 例，合计 **69 例，0 失败 0 错误 0 跳过**。`GatewayTest` 16 → 18 例。
- **未验证**：AOSP 源码本轮未抓取（离线），行为以三轮复审共同引用为据；Android 真机上「取消能否结束在途 `connect()`」与「停止到结束的真实延迟」本轮未测，也未据其做任何表述。第 2 节的旁证是 JVM 参考实现，不能替代真机结论。
