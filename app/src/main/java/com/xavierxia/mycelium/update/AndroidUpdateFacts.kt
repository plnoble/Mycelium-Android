package com.xavierxia.mycelium.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager

/**
 * 真机实现：包名 / versionCode / 签名指纹都来自 `PackageManager`。
 *
 * 这一段**只能在真机或 Robolectric 之外验证**（简报 D1：各 ROM 上 `getPackageArchiveInfo` 与
 * `GET_SIGNING_CERTIFICATES` 的实际表现，v0.1.1 是 v2-only 签名）。抽在接口后面就是为了
 * 让状态机不绑死在影子行为上，白天走查时单独看这一层。
 */
class PackageManagerApkFactsReader(private val context: Context) : ApkFactsReader {
    override fun read(path: String): ApkFacts? =
        context.packageManager
            .getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?.let(::factsOf)
}

/** 当前装着的这个 App 的包名 / versionCode / 签名指纹。 */
class CurrentAppFacts(private val context: Context) : InstalledAppFacts {
    override fun installed(): ApkFacts =
        factsOf(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
}

private fun factsOf(info: PackageInfo): ApkFacts {
    // apkContentsSigners：APK 文件里实际带着的签名者。多签名时它就有多个，这里如实上报个数，
    // 由校验器按「只接受单签名」拒绝——不偷偷只取第一个（C4 第 6 条）。
    val signers = info.signingInfo?.apkContentsSigners.orEmpty()
    return ApkFacts(
        packageName = info.packageName,
        versionCode = info.longVersionCode,
        signerSha256 = signers.firstOrNull()?.toByteArray()?.sha256Hex(),
        signerCount = signers.size,
    )
}