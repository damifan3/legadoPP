package io.legado.app.help.update

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import io.legado.app.exception.NoStackTraceException
import java.time.Instant

data class AppReleaseInfo(
    val appVariant: AppVariant,
    val createdAt: Long,
    val note: String,
    val name: String,
    val downloadUrl: String,
    val assetUrl: String
) {
    val versionName: String = Regex("""\d+\.\d+\.\d+""").find(name)?.value ?: ""
}

enum class AppVariant {
    OFFICIAL,
    RELEASEA,
    RELEASES,
    RELEASEPP,
    RELEASE,
    UNKNOWN
}

@Keep
data class GithubRelease(
    val assets: List<Asset>?,
    val body: String,
    @SerializedName("prerelease")
    val prerelease: Boolean,
) {
    fun gitReleaseToAppReleaseInfo(): List<AppReleaseInfo> {
        assets ?: throw NoStackTraceException("获取新版本出错")
        // assets是List<Asset>，返回的是 List<AppReleaseInfo>
        return assets
            .filter { it.isValid }
            .map { it.assetToAppReleaseInfo(prerelease, body) }
    }
}
@Keep
data class GiteeRelease(
    val assets: List<GiteeAsset>?,
    val body: String,
    @SerializedName("prerelease")
    val prerelease: Boolean,
) {
    fun gitReleaseToAppReleaseInfo(): List<AppReleaseInfo> {
        assets ?: throw NoStackTraceException("获取新版本出错")
        return assets
            .filter { it.isValid }
            .map { it.assetToAppReleaseInfo(prerelease, body) }
    }
}

@Keep
data class Asset(
    @SerializedName("browser_download_url")
    val apkUrl: String,
    @SerializedName("content_type")
    val contentType: String,
    @SerializedName("created_at")
    val createdAt: String,
    @SerializedName("download_count")
    val downloadCount: Int,
    val id: Int,
    val name: String,
    val state: String,
    val url: String
) {
    val isValid: Boolean
        get() = (contentType == "application/vnd.android.package-archive") && (state == "uploaded")

    fun assetToAppReleaseInfo(preRelease: Boolean, note: String): AppReleaseInfo {
        val instant = Instant.parse(createdAt)
        val timestamp: Long = instant.toEpochMilli()

        val appVariant = when {
            name.contains("releaseA") -> AppVariant.RELEASEA
            name.contains("releaseS") -> AppVariant.RELEASES
            name.contains("releasePP") -> AppVariant.RELEASEPP
            name.contains("release") -> AppVariant.RELEASE
            else -> AppVariant.UNKNOWN
        }

        return AppReleaseInfo(appVariant, timestamp, note, name, apkUrl, url)
    }
}

@Keep
data class GiteeAsset(
    @SerializedName("browser_download_url")
    val apkUrl: String,
    @SerializedName("name")
    val name: String
) {
    val isValid: Boolean
        get() = apkUrl.contains(".apk")

    fun assetToAppReleaseInfo(preRelease: Boolean, note: String): AppReleaseInfo {

        val appVariant = when {
            name.contains("releaseA") -> AppVariant.RELEASEA
            name.contains("releaseS") -> AppVariant.RELEASES
            name.contains("releasePP") -> AppVariant.RELEASEPP
            name.contains("release") -> AppVariant.RELEASE
            else -> AppVariant.UNKNOWN
        }

        return AppReleaseInfo(appVariant, 0, note, name, apkUrl, "")
    }
}


