@file:Suppress("unused", "UNUSED_PARAMETER")

package android.content.pm

import compat.DesktopPlatform

class PackageInfo {
    var versionName: String? = DesktopPlatform.appVersion
    var packageName: String = "at.techgaisi.farbwerk"
    @Suppress("DEPRECATION") var versionCode: Int = 0
    val longVersionCode: Long get() = versionCode.toLong()
}

class PackageManager {
    fun getPackageInfo(packageName: String, flags: Int): PackageInfo = PackageInfo()
    fun hasSystemFeature(name: String): Boolean = false

    class NameNotFoundException(msg: String? = null) : Exception(msg)

    companion object {
        const val FEATURE_CAMERA_ANY = "android.hardware.camera.any"
    }
}
