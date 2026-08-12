package io.legado.app.help.update

/**
 * GitHub 镜像加速帮助类
 *
 * 当直接从 GitHub 下载失败时（通常是网络不通），
 * 提供多个 GitHub 镜像加速服务 URL 供自动重试使用。
 * 镜像列表按优先级排序，依次尝试直到下载成功。
 */
object GithubMirrorHelper {

    /**
     * GitHub 镜像加速服务列表（按优先级排序）
     * 每个镜像服务的使用方式：将原始 GitHub URL 前缀替换为镜像地址
     * 例如：https://ghfast.top/https://github.com/user/repo/releases/download/...
     */
    private val MIRROR_PREFIXES = listOf(
        "https://ghfast.top/",       // ghfast 加速
        "https://gh-proxy.com/",     // gh-proxy 加速
        "https://mirror.ghproxy.com/", // ghproxy 镜像
        "https://ghproxy.net/"       // ghproxy.net 加速
    )

    /**
     * 判断给定 URL 是否为 GitHub 下载链接
     * 只有 github.com 域名的 URL 才需要镜像加速
     *
     * @param url 待判断的 URL
     * @return 是否为 GitHub URL
     */
    fun isGithubUrl(url: String): Boolean {
        return url.contains("github.com", ignoreCase = true)
    }

    /**
     * 获取所有镜像加速 URL 列表
     *
     * 将原始 GitHub 下载 URL 转换为多个镜像加速 URL。
     * 返回的列表不包含原始 URL，仅包含镜像 URL。
     * 非 GitHub URL 返回空列表。
     *
     * @param originalUrl 原始 GitHub 下载 URL
     * @return 镜像加速 URL 列表（按优先级排序）
     */
    fun getMirrorUrls(originalUrl: String): List<String> {
        // 非 GitHub URL 不需要镜像加速
        if (!isGithubUrl(originalUrl)) {
            return emptyList()
        }
        // 将每个镜像前缀拼接到原始 URL 前面，生成镜像 URL
        return MIRROR_PREFIXES.map { prefix -> prefix + originalUrl }
    }

    /**
     * 获取包含原始 URL 在内的所有可用下载 URL 列表
     *
     * 首先返回原始 URL，然后依次返回各镜像 URL。
     * 调用方可按顺序尝试，第一个成功即可。
     *
     * @param originalUrl 原始 GitHub 下载 URL
     * @return 原始 URL + 镜像 URL 的完整列表
     */
    fun getAllDownloadUrls(originalUrl: String): List<String> {
        return listOf(originalUrl) + getMirrorUrls(originalUrl)
    }
}
