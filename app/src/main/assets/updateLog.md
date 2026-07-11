# 更新日志

## cronet版本: 128.0.6613.40

**2026.07.11**
- [优化]：书籍详情页右上角定时任务快捷入口改进
- [优化]：自动任务解析的健壮性提高：url中含有分号等
- [功能 优化]：本地缓存迁移：增加章节名md5匹配，增加本地缓存文件md5直接匹配的逻辑
- [优化]：修复自动任务刷新目录时没有缓存自动迁移的问题；`refreshToc`方法中直接加入`migrateTocCache`方法，覆盖本地（除txt）和在线书籍
- [修复]：将缓存行为改成懒加载，防止协程竞态导致任务栏通知无法关闭的问题
- [修复]：为自动任务注册专用的通知渠道，修复应用通知管理中不显示自动任务的问题

**2026.06.16**
- [功能]：成功移植定时任务功能
- [修复]：修复使用 url 判断是否是新章节，而不是绝对index，并修复缓存等逻辑，防止章节进出小黑屋导致新章节识别和数量信息混乱
- [功能 修复]：实现书籍目录和章节名变动时的本地缓存自动重命名，防止缓存失效；并优化目录刷新逻辑，避免底层级联删除问题
- [功能]：书源编辑界面迁移快捷导航功能；将快捷导航栏提取为可复用组件

**2026/03/07**
- 优化代码，修复问题

**2026/03/03**
- 优化代码，修复问题
- 视频悬浮窗播放时进行系统媒体播放通知
- 净化规则使用js时支持调用java.log
- 代码编辑器搜索替换内容支持$符号
- 优化书架滚动位置记忆
- 增加搜索结果排序时对书籍分类信息进行判断
- 增加自动检查app更新功能

**2026/02/16**
- 优化代码，修复问题
- 让小说朗读走系统媒体播放通道
- 更新内置字典规则
- 新增java.refreshBookToc函数
- java.reLoginView函数增加deltaUp参数
- 新增@webjs:规则类型
- 文件类书源支持下载链接type指定文件后缀
- 提升购买按钮权限

**2026/01/31**
- 优化代码，修复一些问题
- 正文增加锁定反向横屏

**2026/01/28**
- 新增java.reLoginView()函数，刷新登录界面
- 书源发现支持更多丰富的按钮类型
- 新增java.refreshExplore()函数
- java.open函数支持打开登录界面
- 书源简介支持html标签包裹，显示html样式
- 书籍简介和字典支持gif动态图和svg图data链接
- 书籍简介和字典支持button按钮
- 支持源控制图片显示尺寸
- 书籍简介支持markdown语法编写
- 新增java.showBrowser函数，能进行半屏显示段评
- 支持图片链接click键，不推荐继续使用旧方式
- 支持双击响应段评图
- 新增chapter.update()函数
- 新增java.showPhoto函数
- 新增java.refreshContent()函数
- 支持订阅源启动页html用js返回空
- 提升webview函数获取js结果速度
- 其余优化与修复

**2026/01/13**
- 软件自定义背景图支持.9.png格式
- 背景图导入支持直接输入图片在线链接
- 主题分享支持在线背景图链接
- 背景图支持跟随主题切换
- 主题设置支持透明操作栏，提升图片背景视觉效果
- 支持分组封面自定义图片恢复默认
- 登录UI的select类型支持action键
- 提升内置浏览器打开速度（例：订阅源、段评 打开速度大概快100毫秒左右）
- 支持正文下划线设为虚线类型
- cache.get函数新增onlyDisk参数
- tts源支持jslib规则
- tts源登录界面新增java.clearTtsCache()函数
- 支持导出单个tts源
- 编辑tts源、字典规则、TXT目录规则时误触空白区域会提示保存
- 新增正文边缘点击阈值设置，防止曲面屏误触
- 实现订阅源的登录检查规则
- 在链接访问出错时，也能执行一次登录检查规则
- StrResponse对象支持callTime()获取响应时间
- 并发访问函数支持skipRateLimit参数，绕过源并发率限制
- 视频播放器支持记录函数调用时的播放进度
- 其余细节优化与bug修复


## **必读**
来源于fork仓库 [Luoyacheng/legado](https://github.com/Luoyacheng/legado)　  
[查看实时详细日志](https://gitee.com/lyc486/legado/commits/main)　 

【温馨提醒】 *更新前一定要做好备份，以免数据丢失！*  
* 阅读只是一个转码工具，不提供内容，第一次安装app，需要自己手动导入书源。
* 正文出现缺字漏字、内容缺失、排版错乱等情况，有可能是净化规则或简繁转换出现问题。
----

* [2025年日志](https://github.com/Luoyacheng/legado/blob/record2025/app/src/main/assets/updateLog.md)　
* [2023年日志](https://github.com/gedoor/legado/blob/record2023/app/src/main/assets/updateLog.md)　
* [2022年日志](https://github.com/gedoor/legado/blob/record2022/app/src/main/assets/updateLog.md)　
* [2021年日志](https://github.com/gedoor/legado/blob/record2021/app/src/main/assets/updateLog.md)　
