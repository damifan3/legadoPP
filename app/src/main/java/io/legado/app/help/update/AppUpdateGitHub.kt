package io.legado.app.help.update

import androidx.annotation.Keep
import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope

@Keep
@Suppress("unused")
object AppUpdateGitHub : AppUpdate.AppUpdateInterface {

    /**
     * 检查是否是 Beta 版本
     */
    private val checkIsBetaChannel: Boolean
        get() = when (AppConfig.updateToVariant) {
            "beta_release_version" -> true // 强制测试版通道
            "release_version" -> false // 强制正式版通道
            else -> AppConst.isBetaBuild
        }

    /**
     * 获取是哪一种构建变体
     */
    private val checkVariant: AppVariant
        get() = AppConst.appInfo.appVariant

    private suspend fun getLatestRelease(): List<AppReleaseInfo> {
        // 根据检查的版本类型选择对应的 GitHub API 地址（damifan3/legadoPP）
        val isBeta = checkIsBetaChannel
        val lastReleaseUrl = if (isBeta) {
            "https://api.github.com/repos/damifan3/legadoPP/releases/tags/beta"
        } else {
            "https://api.github.com/repos/damifan3/legadoPP/releases/latest"
        }
        io.legado.app.constant.AppLog.put("GitHubUpdate: checking URL: $lastReleaseUrl, isBetaChannel: $isBeta, variant: $checkVariant")
        
        // 获取包括直连和所有镜像在内的 URL 列表
        val urlsToTry = GithubMirrorHelper.getAllDownloadUrls(lastReleaseUrl)
        var lastException: Exception? = null

        for (url in urlsToTry) {
            try {
                val res = okHttpClient.newCallResponse {
                    url(url)
                }
                if (!res.isSuccessful) {
                    throw NoStackTraceException("获取新版本出错(${res.code})")
                }
                val body = res.body.text()
                if (body.isBlank()) {
                    throw NoStackTraceException("获取新版本出错")
                }

                //GSON 库自动将构造 GithubRelease 所需的参数传入并返回构造好的对象
                return GSON.fromJsonObject<GithubRelease>(body)
                    .getOrElse {
                        throw NoStackTraceException("获取新版本出错 " + it.localizedMessage)
                    }
                    .gitReleaseToAppReleaseInfo()
                    .sortedByDescending { it.createdAt }
            } catch (e: Exception) {
                lastException = e
                // 如果当前 URL 失败，继续尝试下一个镜像
                continue
            }
        }
        
        // 如果所有 URL 都失败了，抛出最后一个异常
        throw lastException ?: NoStackTraceException("获取新版本出错")
    }

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            getLatestRelease()
                .filter { it.appVariant == checkVariant }
                .firstOrNull { it.versionName > AppConst.appInfo.versionName }
                ?.let {
                    return@async AppUpdate.UpdateInfo(
                        it.versionName,
                        it.note,
                        it.downloadUrl,
                        it.name
                    )
                }
                ?: throw NoStackTraceException("已是最新版本")
            //时间不能太短，否则来不及尝试所有 url
        }.timeout(30000)
    }
}
