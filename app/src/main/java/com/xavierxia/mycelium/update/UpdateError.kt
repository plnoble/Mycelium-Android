package com.xavierxia.mycelium.update

import java.io.IOException
import org.json.JSONException

/**
 * 检查更新失败的原因。界面只认类型；具体文案在 `res/values/strings.xml`，
 * 改文案不用碰解析逻辑。每种类型都要能指导用户下一步做什么。
 */
sealed interface UpdateError {
    /** 连不上：断网、超时、以及取数这一层的其它失败，用户能做的都是检查网络后再试。 */
    data object Network : UpdateError

    /** GitHub 匿名额度用完了（HTTP 403 / 429）。等一会儿再试就有机会成功。 */
    data object RateLimited : UpdateError

    /** GitHub 自己出问题了（其它非 200）。带原始状态码只为排查，不直接给用户看。 */
    data class Server(val status: Int) : UpdateError

    /** 返回体不是预期的 JSON 形状：解析不了，或者缺了必填字段。 */
    data object Malformed : UpdateError
}

/**
 * 把原因一起抛出来，避免 `GitHub returned HTTP 403` 这类原文漏到界面上。
 * [error] 没有 message：文案由界面按类型取。
 */
class UpdateCheckException(val error: UpdateError, cause: Throwable? = null) : Exception(cause)

/** 线上可能抛出的东西收敛到一处，保证界面永远只能看到 [UpdateError]，看不到英文原文。 */
internal fun Throwable.toUpdateError(): UpdateError = when (this) {
    is UpdateCheckException -> error
    is IOException -> UpdateError.Network
    is JSONException -> UpdateError.Malformed
    // 剩下的（网关拒绝、请求被取消等）也是取数这一层的失败；对用户来说可做的动作同样是稍后重试。
    else -> UpdateError.Network
}
