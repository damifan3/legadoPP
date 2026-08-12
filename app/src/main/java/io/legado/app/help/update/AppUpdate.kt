package io.legado.app.help.update

import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope

object AppUpdate {

    // 主更新源：GitHub（damifan3/legadoPP）
    val gitHubUpdate: AppUpdateInterface by lazy {
        AppUpdateGitHub
    }

    // Gitee 更新源已弃用，保留兼容但不再使用
    @Deprecated("已切换到 GitHub 更新源，不再使用 Gitee")
    val giteeUpdate: AppUpdateInterface by lazy {
        AppUpdateGitee
    }


    data class UpdateInfo(
        val tagName: String,
        val updateLog: String,
        val downloadUrl: String,
        val fileName: String
    )

    interface AppUpdateInterface {

        fun check(scope: CoroutineScope): Coroutine<UpdateInfo>

    }

}
