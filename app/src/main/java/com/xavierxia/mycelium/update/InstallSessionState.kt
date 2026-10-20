package com.xavierxia.mycelium.update

/**
 * 安装会话的阶段（方案审 C5「建议做法」定稿）：
 * `Created → Written → Committed → Pending / Succeeded / Failed / Cancelled`。
 *
 * 阶段**落盘**而不是只放内存：成功安装一定会终结旧进程，新进程必须凭 [InstallSessionRecord]
 * 把结果认回来（C5 第 3 条）。
 */
enum class InstallPhase {
    Created,
    Written,
    Committed,
    Pending,
    Succeeded,
    Failed,
    Cancelled,
    ;

    /** 终态：成功 / 失败 / 用户明确取消。到终态之后任何迟到、重复的回调都不作数。 */
    val isTerminal: Boolean
        get() = this == Succeeded || this == Failed || this == Cancelled
}

/**
 * 一次安装会话的落盘状态（C5 第 3 条要求持久化 sessionId、目标包名、versionCode、tag 和阶段）。
 *
 * [baselineVersionCode] 是提交那一刻**装着的** versionCode：回前台时只要 `longVersionCode`
 * 比它大，就是装上了——这是**权威判定**，不依赖任何广播送达（简报 §5.1）。
 */
data class InstallSessionRecord(
    val sessionId: Int,
    val tagName: String,
    val packageName: String,
    val baselineVersionCode: Long,
    val phase: InstallPhase,
    val statusCode: Int = 0,
    val updatedAtMs: Long = 0L,
    /** 这个包里读出来的版本号：进程重建后用它确认「这一版还没装上」（M7）。 */
    val targetVersionCode: Long = 0L,
)

/**
 * 会话状态的落盘口。生产实现是 [UpdatePreferences]（普通偏好存储，不是个人数据）。
 *
 * 三条约定：
 *  * [advance] **幂等**：会话 id 对不上、或当前已经是终态，就拒绝写入（迟到/重复的回调不作数）；
 *  * [clear] 只清 [sessionId] 所属的那一条，绝不误删别的会话；
 *  * 上一次会话还挂着（非终态）时开始新会话，调用方要先把旧记录 `clear` 掉，否则新记录会被幂等规则挡下。
 */
interface InstallSessionState {
    fun read(): InstallSessionRecord?

    fun advance(record: InstallSessionRecord): Boolean

    fun clear(sessionId: Int): Boolean
}

/**
 * 幂等**且单调**：会话 id 对不上、当前已经是终态、或新阶段并不比当前更靠后，就拒绝写入。
 *
 * 「单调」是 M7 补的：迟到的 `Committed` 不能把已经落盘的 `Pending` 盖回去，终态也永远盖不回
 * `Pending` —— 否则界面会在「等用户确认」和「正在安装」之间来回跳。
 */
internal fun installSessionAccepts(current: InstallSessionRecord?, next: InstallSessionRecord): Boolean {
    if (current == null) return true
    if (current.sessionId != next.sessionId) return false
    if (current.phase.isTerminal) return false
    return installPhaseRank(next.phase) > installPhaseRank(current.phase)
}

/** 阶段只能往前走：Created → Written → Committed → Pending → 终态。 */
internal fun installPhaseRank(phase: InstallPhase): Int = when (phase) {
    InstallPhase.Created -> 0
    InstallPhase.Written -> 1
    InstallPhase.Committed -> 2
    InstallPhase.Pending -> 3
    InstallPhase.Succeeded -> 4
    InstallPhase.Failed -> 5
    InstallPhase.Cancelled -> 6
}

/** 进程内实现：测例默认用它，也是「装配时忘了注入偏好存储」时的安全默认。 */
class MemoryInstallSessionState : InstallSessionState {
    private var record: InstallSessionRecord? = null

    @Synchronized
    override fun read(): InstallSessionRecord? = record

    @Synchronized
    override fun advance(record: InstallSessionRecord): Boolean {
        if (!installSessionAccepts(this.record, record)) return false
        this.record = record
        return true
    }

    @Synchronized
    override fun clear(sessionId: Int): Boolean {
        if (record?.sessionId != sessionId) return false
        record = null
        return true
    }
}

/**
 * `PackageInstaller.STATUS_*` 的取值（android-37.0 的 android.jar 实测）。
 *
 * 单独列一份常量不是抄文档：真机实现拿它在**同一份判定**里对照系统常量，「哪些状态算成功 /
 * 取消 / 哪一种失败」这套分类才能在 JVM 上直接测（简报测例 5/6/7）。
 */
object InstallStatusCode {
    const val SUCCESS = 0
    const val FAILURE = 1
    const val FAILURE_BLOCKED = 2

    /** 用户在系统界面上点了取消。**只有这个状态**能判成取消（C5 第 4 条）。 */
    const val FAILURE_ABORTED = 3
    const val FAILURE_INVALID = 4
    const val FAILURE_CONFLICT = 5
    const val FAILURE_STORAGE = 6
    const val FAILURE_INCOMPATIBLE = 7
    const val FAILURE_TIMEOUT = 8
    const val PENDING_USER_ACTION = -1
}

/** 系统状态码 → 落盘阶段。 */
fun installPhaseOf(statusCode: Int): InstallPhase = when (statusCode) {
    InstallStatusCode.SUCCESS -> InstallPhase.Succeeded
    InstallStatusCode.PENDING_USER_ACTION -> InstallPhase.Pending
    InstallStatusCode.FAILURE_ABORTED -> InstallPhase.Cancelled
    else -> InstallPhase.Failed
}

/** 系统状态码 → 结果类型。其中四种失败的**中文文案**各不相同，由界面层给。 */
fun installStatusOf(statusCode: Int): InstallStatus = when (statusCode) {
    InstallStatusCode.SUCCESS -> InstallStatus.Succeeded
    InstallStatusCode.FAILURE_ABORTED -> InstallStatus.Cancelled
    InstallStatusCode.FAILURE_STORAGE -> InstallStatus.InsufficientStorage
    InstallStatusCode.FAILURE_INCOMPATIBLE -> InstallStatus.Incompatible
    InstallStatusCode.FAILURE_INVALID -> InstallStatus.Invalid
    InstallStatusCode.FAILURE_BLOCKED -> InstallStatus.Blocked
    InstallStatusCode.FAILURE_CONFLICT -> InstallStatus.Conflict
    else -> InstallStatus.Failed(statusCode)
}