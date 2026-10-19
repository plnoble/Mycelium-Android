# 取消正在建立中的连接：按平台限制结案

**范围**：P2-2 复审第 1 条「取消要能中断**正在建立中**的连接」。r2、r3、r4 三轮复审均判不通过；r5 认可把该窗口记为已知残留，但要求先收窄文档里的取消保证与超时口径。
**基线**：`wp/P2-2-hearth-chat` 的 `5ba43760e72f2cabf82f7b7e1391291a1f2beac7`。
**涉及文件**：`net/src/main/kotlin/com/xavierxia/mycelium/net/Gateway.kt`、`net/src/test/kotlin/com/xavierxia/mycelium/net/GatewayTest.kt`（夹具与测例在前几轮已改，本轮未改）、本文件。
**结论**：按现有证据能支持的限度写——**当前实现不能保证覆盖所有启动窗口，也不能保证立即结束在途连接**；这**不足以排除实现选择的影响，也不足以否定平台已有的取消能力**（同一实现里空引擎分支直接返回、非空引擎分支会调 `httpEngine.cancel()`）。实现侧只能给单次 TCP 建连配上有限超时。裁定选项见第 8 节。

---

## 1. 问题陈述

要求是：用户停止一个正在发出的请求时，如果该请求**已经在建立底层连接**，这次停止应当中断那条连接。

本实现把「停止」做成一个不可逆的开关（`GatewayCall`），并用它把停止与建连启动做成同一个临界区：

- `GatewayCall.cancel()`：置位开关，并 `disconnect()` 已登记的连接（`Gateway.kt:179`）。
- `GatewayCall.startingConnection()`：在锁内判断是否已停止，未停止才**调用**平台的建连入口（`Gateway.kt:207`）。
- 正文字节经 `bodyStream()` / `StoppedBodyStream`，每次 write、flush 前都复检开关。

r2–r5 复审指出的共同点是同一个：**「锁内判断、锁外调用 `connect()`」之间仍然存在可被停止插入的窗口，而平台在这一窗口里不会因为 `disconnect()` 而放弃建连。** 本轮不再试图用检查关闭这个窗口，而是把它作为平台事实记录清楚。

## 2. 平台限制与 AOSP 依据

Android 的 `HttpURLConnection` 实现在 AOSP 的 `external/okhttp`（`okhttp-urlconnection` 的 `huc/HttpURLConnectionImpl`）：

- `disconnect()` 在 `httpEngine == null` 时**直接返回**，不留下任何「已取消/待取消」状态；`httpEngine` 要到 `connect()` 才被创建。
- `getOutputStream()`（以及 `getResponseCode()`）会调用 `connect()` —— 也就是说，**取输出流这一步才真正发起建连**，而它前面没有本实现可用的可中断钩子。
- 因此「先 `disconnect()`、后触发建连」在真实平台上**不会**阻止建连；本实现能做的只有两件更窄的事：取消先于闸门检查时不让建连被发起，或者在连接对象已登记后尝试断开它，**不能由本实现保证覆盖所有启动窗口**。

**这一节的两个方向都要写清，不能只挑一半**：上面是「`httpEngine == null` 时 `disconnect()` 为空操作」，它只支持「那个窗口关闭不了」；它**不能**被推广成「所有在途 `connect()` 都无法中断」。同一个实现的**非空分支**会把 `disconnect()` 引到 `httpEngine.cancel()`，其下层提供了取消连接并关闭原始 socket 的路径。因此准确结论是：**当前实现不保证覆盖所有启动窗口，也不保证立即结束在途建连。** 两条都由同一实现的不同分支支持，第一轮只引用了空分支，才得出了过宽的结论。

引用位置（**r2–r4 复审共同引用的同一处源码，外加 r5 给出的下层路径**）：
`https://android.googlesource.com/platform/external/okhttp/+/refs/heads/main/okhttp-urlconnection/src/main/java/com/squareup/okhttp/internal/huc/HttpURLConnectionImpl.java`

r5 同时给出的下层路径（连同上一条，共三条源码路径；本轮同样未抓取，仅作引用）：
`https://android.googlesource.com/platform/external/okhttp/+/master/okhttp/src/main/java/com/squareup/okhttp/internal/http/StreamAllocation.java`、
`https://android.googlesource.com/platform/external/okhttp/+/master/okhttp/src/main/java/com/squareup/okhttp/internal/io/RealConnection.java`。

**证据边界（诚实说明）**：本轮环境按约定不联网，**没有重新抓取该 AOSP 源码**，因此上述两种分支行为（空引擎时 `disconnect()` 为空操作；引擎存在时转 `cancel()`）以 r2–r4 各自独立引用该处源码、r5 补充下层路径为据，行号未在本环境复核。

三项本机可复核的旁证（均在 JVM 参考实现 `sun.net.www.protocol.http.HttpURLConnection` 上实测，**不是 Android 实现**）：

1. `new URL(...).openConnection()` 后未设置时 `getConnectTimeout()` 返回 **0，即无限等待** —— 这是第 6.1 节有限超时配置的前提。
2. 对尚未建连的连接先 `disconnect()`，随后 `getOutputStream()` **仍然发起连接**并抛出 `ConnectException: Connection refused`（目标 `127.0.0.1:1`，全程 loopback、无外部网络）。即 `disconnect()` 的「空操作」语义在参考实现上可直接观察。
3. 线程 `interrupt()` 不会中止阻塞中的 socket `connect()`；单靠 interrupt 结束不了它，它只能靠自己返回、抛错或超时（平台若另有取消路径则另论）。

后两项与 AOSP 的行为同构（该实现正是同源代码谱系），可作为**旁证**，不能替代 Android 上的实测。

## 3. 历轮复审意见摘要

| 轮次 | 状态 | 意见要点 |
|---|---|---|
| r2 | 不通过 | 最后检查与实际建连之间仍有窗口；`disconnect()` 对未建立的连接无效，恢复后仍会开始连接。要求「让底层请求在启动连接前就具有持续有效的取消状态」，并补「进入取流、尚未建立连接时取消」的受控测例。 |
| r3 | 不通过 | `bodyStream()` 前置检查返回后、`open()` 真正建连前，取消仍只做一次无效 `disconnect()`。并指出 `FrozenPeer.disconnect()` 只计数、退出时间是测试主动释放闩锁后测的，所以「两秒内返回」**不能**证明取消能中断建连。 |
| r4 | 不通过 | `startingConnection()` 在锁内检查、锁外调用 `connect()`，两者之间仍可取消。并指出夹具「自行保存 `disconnected` 并读取 `call.isCancelled`，替真实底层提供了它没有的取消行为」，因此这些测例不能证明该窗口已修好。 |
| r5 | 需改表述 | 认可把残余窗口记为已知残留，但结案文档仍夸大：保证 A 被泛化成「取消后不建连」，保证 B 未限定时点，平台结论只引用了空引擎分支；连接超时被当成整次调用截止时间。要求收窄后再由统筹裁定。 |

r2–r4 的实质诉求是一致的：**要求本实现的停止能覆盖正在建立中的连接**。现有证据只支持更窄的说法——**当前实现不能保证覆盖所有启动窗口，也不能保证立即结束在途连接**（见第 2 节两个分支）；它**不足以把这一诉求归为「平台没有提供的可中断性」**，也不足以排除实现选择的影响。r4 关于夹具的批评是能完全照做的部分 —— 已按第 7 节处理；r5 则要求不再把做不到的事写成保证。

## 4. 现在保证了什么

| | 内容 | 落点 | 测例 |
|---|---|---|---|
| **保证 A** | **取消先于闸门检查** ⇒ 平台的建连调用不会被执行，一次连接都不发起。停止与启动的决定在同一临界区，停止先到则 `connect()` 根本不被调用。 | `Gateway.kt:207`（`startingConnection`）、KDoc 第 1 条（`Gateway.kt:282`） | `aCancelledRequestNeverOpensAConnection`、`aStopBeforeTheConnectionStartsMeansThePeerNeverSeesAConnect` |
| **保证 B** | **取流期间发生的取消**会被后置检查拦住，正文不会写出。取流前、取流后、每次 write/flush 前都复检开关。 | `Gateway.kt:228` 起（`bodyStream`）、`StoppedBodyStream`、KDoc 第 2 条（`Gateway.kt:290`） | `aStopInTheRegistrationWindowNeverWritesTheBody`、`aStopWhileTheConnectionIsBeingStartedEndsThatConnectAtOnce` |
| **不保证** | 闸门检查之后、平台调用真正发出之前到达的取消，无法阻止该调用；同时也**不保证覆盖所有启动窗口、不保证立即结束在途建连**。 | KDoc 第 3 条（`Gateway.kt:298`） | 无测例，且不应有：夹具不得拥有平台没有的取消能力。 |

**实际暴露面**：残余窗口是「锁内检查通过」到「进入 `connect()`」之间的几条指令宽。停止若恰好落在这里，`disconnect()` 对尚无引擎的连接是空操作，本次请求仍会发起建连；之后能否被断开，取决于平台是否已创建引擎。停止到任务结束的时间**没有硬上限**：TCP 之后还有 TLS 握手与响应读取，失败还可能换路由重试（见第 6.2 节）。若建连很快成功，取流后的检查点仍会在写正文前拦住它；但已经写出或正在写出的字节无法撤回。

## 5. 为什么不能把「取消与建连」放进同一把锁跨住 `connect()`

设想：`startingConnection()` 全程持锁，`connect()` 在锁内执行，`cancel()` 也必须拿同一把锁。

结果是**取消会排在它想中断的那次建连之后**：`cancel()` 阻塞等锁 → 建连（可能卡满一次 TCP 建连超时）返回后才置位开关。也就是说，用户按下停止之后，socket 会带着「已请求停止」的状态继续存活到该次建连自己返回或超时为止 —— 比现在多等一次 TCP 建连的时间，而且这段时间里连接仍然可能建立成功。所以正确做法是：**锁只保护「是否允许启动」这个决定，不覆盖阻塞调用**；停止的代价仅为另一个临界区的几条指令。

结论：这一取舍是**正确性优先、取消延迟次要**，不是遗漏。

## 6. 超时口径：有限配置 ≠ 整体截止时间

这里必须拆成两件事。混在一起，就会把连接超时参数误当成整个调用的截止时间。

### 6.1 有限超时配置

`HttpURLConnection` 的 `connectTimeout` 默认 **0 = 无限等待**（本机实测，见第 2 节）。归一化（`Gateway.kt:384–390`）保证落到连接上的连接超时与读取超时都是有限的正数：

| 常量 | 取值 | 理由 |
|---|---|---|
| `DEFAULT_CONNECT_TIMEOUT_MS` | 15 000 | **连接超时为非正时的回退值**。两端权衡：太小会在慢网/拥塞链路上误杀握手（DNS + TCP + TLS 在差链路上经常超过 10s）；太大则让「已按下停止但建连还在跑」的等待变长。 |
| `MAX_CONNECT_TIMEOUT_MS` | 30 000 | 调用方可以更耐心，但**单次 TCP 建连不能无限**：正值的连接超时截到这里。取默认值的 2 倍。 |
| `DEFAULT_READ_TIMEOUT_MS` | 60 000 | **读取超时为非正时的回退值**（正值保持原值），覆盖非流式补全。它是单次读取的界，不是取消或整次调用的截止时间：停止通过 `disconnect()` 尝试结束阻塞的读，能否立即结束取决于平台。 |

规则一句话：**非正连接超时 → 默认 15s；正连接超时 → 截到 30s；读取超时非正 → 60s，正值保持原值。** 这样无论调用方怎么填，落到 socket 上的单次连接超时与单次读取超时都是有限值。

### 6.2 停止到任务结束的总体时限

**没有硬上限。** 连接超时只覆盖一次 TCP 建连；TCP 之后还有 TLS 握手和响应读取（各有自己的等待），建连失败还可能换路由重试。因此：

- 不能写「取消最多再等 15s」或「总体上限 30s」，这两个数只属于单次 TCP 建连；
- 停止后请求何时真正结束，取决于当时停在哪个阶段、平台实现是否响应 `disconnect()`、以及后续是否重试；
- 本实现没有实现整个调用的截止时间；要得到整体时限，需要调用方在更上层自己设置。

各调用点现值：更新检查把连接与读取超时都设为 10s（`app/.../GitHubReleaseClient.kt:31`，经 `ChatService.kt:134–135` 传入）；聊天走默认（连接 15s、读取 60s）；模型列表读取超时 30s（`roundtable/.../ModelList.kt:21`），连接超时用默认 15s。

## 7. 测例的边界（证明什么 / 不证明什么）

r4 指出夹具「替真实底层提供了它没有的取消行为」。这两处伪造已在此前几轮删除（本轮未改测例）：

- 删掉 `FrozenPeer.connect()` 中对 `call.isCancelled` 的读取；
- 删掉「`disconnect()` 一旦发生，之后的 `connect()` 必定失败」的**粘性语义**。

改后夹具忠实复现平台的空操作：**未发起建连时到来的 `disconnect()` 不会阻止随后的 `connect()`**。删除后 A/B 的测例仍然全绿，说明这两条窄保证靠的是网关自己的闸门与检查点，而不是对端配合 —— 这比改动前更能说明问题；但**夹具全绿不等于残余窗口消失**。

| 测例 | 证明 | 不证明 |
|---|---|---|
| `aCancelledRequestNeverOpensAConnection` | 保证 A 的最早情形：取消先于闸门检查，连连接对象都不打开 | 任何「已开始的建连」 |
| `aStopInTheRegistrationWindowNeverWritesTheBody` | 保证 B 的子情形：取流期间取消，对**真实 loopback 服务器**实测零正文字节 | 建连开始后任意时刻取消都零正文；请求「提前结束」；此处根本没走到建连 |
| `aStopBeforeTheConnectionStartsMeansThePeerNeverSeesAConnect` | 保证 A 的边界：取消恰好赶在闸门检查前，`connect()` 一次都没被调用 | 闸门检查之后的时刻 |
| `aStopWhileTheConnectionIsBeingStartedEndsThatConnectAtOnce` | 网关在停止时确实 `disconnect()` 已登记连接并迅速返回 | **Android 能中断在途建连**。夹具「`disconnect()` 立即结束在途 `connect()`」是对平台的**假设**，不是观测；真机可能立即结束，也可能等到该次 TCP 建连超时，本实现不保证前者 |
| `aRequestThatIsNotStoppedGoesThroughTheSameGateAndDeliversTheWholeBody` | 阳性对照：无停止时连接照发、正文照到 | 与取消无关 |

**新增测例（2 条，均为注释级说明 + 断言）**：

- `theGatewayAlwaysSetsFiniteTimeoutsOnTheConnectionItUses`（`GatewayTest.kt:485`）：断言真正落到连接上的 `connectTimeout` / `readTimeout` 等于声明的默认值且连接超时有界，防止将来被改回 0/无限。
- `aTimeoutThatMeansInfiniteOnThePlatformIsReplacedByADeclaredBound`（`GatewayTest.kt:519`）：对 0、负数、600000 三种输入断言落到 socket 上的实际值 —— 无限等待漏不出去，超上限被截断。

**关于 `holdConnect`**：它把「已开始的 `connect()` 卡住、只有 `disconnect()` 能放行」建模为夹具行为。真机上在途调用可能由引擎关闭 socket 而结束，**这在 JVM 测例里无法观测**，因此它是假设而非证据；该测例的注释已明确写出这一点，不得被读作「窗口已关闭」。

## 8. 给统筹的裁定选项

| 选项 | 内容 | 能消除什么 | 代价 |
|---|---|---|---|
| **1. 接受为已知残留（当前状态）** | 保留窄化后的保证 A/B，把「闸门检查后的启动窗口无法覆盖、在途建连不保证立即结束」作为平台限制记录在案；用有限连接超时缩小单次 TCP 建连的等待。 | **能消除**：取消先于闸门检查时的建连发起；取流期间取消时的正文写出。**不能消除**：闸门检查通过后仍会发起建连；在途建连何时结束没有保证，停止到任务结束也没有硬上限。 | 最坏仍需等待在途建连及其后续阶段；需要一个面向用户的「停止中」反馈来掩盖这段等待，否则用户会认为按钮没生效。残余窗口客观存在，复审若坚持原判据仍会判不通过。 |
| **2. 批准引入 OkHttp 之类可取消的客户端** | 在 `:net` 内用 OkHttp 的 `Call.cancel()` 替换 `HttpURLConnection`。 | 设计上能真正中止在途调用（`cancel()` 关闭底层 socket），从而可能关闭整个窗口；顺带获得连接池与更清晰的超时模型。 | 新增第三方依赖与供应链面；`:net` 内要重做超时/取消/重定向/审计接线，并重跑 `:net` 全部测例；新增依赖需过审；**「`cancel()` 是否立即打断正在 `connect()` 的 socket」仍需在本项目设备上实测确认**，即换库也不等于拿到无条件保证。白名单/审计边界不受影响：HTTP 本来就只允许出现在 `:net`。 |
| **3. 其它（缩小窗口的折中）** | 例如：把默认连接超时从 15s 收紧到 5s；或在 UI 上把「停止」的语义明确为「可能仍需等待在途建连结束」。 | 缩短单次 TCP 建连的最坏等待，不改架构。 | 5s 会在慢网误杀建连，把「取消慢」换成「联网失败」；UI 措辞不改变任何底层行为，也不改变「整体没有硬上限」的事实，只是把同一限制讲清楚。两者都**不**消除那个窗口。 |

第 2 个选项是唯一可能真正消除该窗口的方向；第 1 个选项是唯一不需要新增依赖的方向。本文件不推荐哪个，只把代价写清。

## 9. 本轮验证与未验证

- **已验证**：`.\gradlew.bat --offline --console=plain :net:test :roundtable:test :contract:test`（本轮加 `--rerun-tasks` 强制重跑，避免命中缓存）→ **BUILD SUCCESSFUL in 11s**；`net` 19 例（`GatewayTest` 18 + `AuditLogTest` 1）、`roundtable` 28 例、`contract`（projectDir = `contract-lib`）22 例，合计 **69 例，0 失败 0 错误 0 跳过**。
- **未验证**：AOSP 源码本轮未抓取（离线），两种分支行为以 r2–r4 共同引用该处源码、r5 补充下层路径为据；Android 真机上「取消能否结束在途 `connect()`」与「停止到结束的真实延迟」本轮未测，也未据其做任何表述。第 2 节的旁证是 JVM 参考实现，不能替代真机结论。
