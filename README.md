# [English](English.md) [中文](README.md)
<div align="center">
  <a href="https://jb.gg/OpenSourceSupport" target="_blank">
    <img width="24" height="24" src="https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.svg" alt="idea" style="vertical-align: middle; margin-right: 10px;"/>
  </a>
  <img src="https://gitee.com/lyc486/yuedu/raw/master/icon_android.png" alt="icon_android" style="vertical-align: middle;"/>
</div>

<div align="center">
<img width="85" height="85" src="https://iili.io/CouAWva.png" alt="legado"/>
<br>
阅读·PP
<br>

阅读 Sigma 继承自 <a href="https://github.com/gedoor/legado" target="_blank">Legado</a>，
本项目又是阅读 Sigma 的一个 fork，在其基础上新增更多功能和修复问题。</div>

## 版本说明
- 测试版(beta)：包名与原版相同，可覆盖更新，版本更新频繁
- 正式版：每到一个稳定阶段进行一次更新
- 目前每次发布包含多个版本：

| 原版 | 共存A | Sigma(Plus) | PlusPlus(本项目) |
| --- | --- | --- | --- |
| release | releaseA | releaseS | releasePP |

签名都是 Legado 原版签名，可选覆盖安装，但是要**谨慎+备份**！

# 目录
[![](https://img.shields.io/badge/-New-F5F5F5.svg)](#New-Features-新功能-)
[![](https://img.shields.io/badge/-Contents:-696969.svg)](#contents) 
[![](https://img.shields.io/badge/-Function-F5F5F5.svg)](#Function-主要功能-) 
[![](https://img.shields.io/badge/-Community-F5F5F5.svg)](#Community-交流社区-) 
[![](https://img.shields.io/badge/-API-F5F5F5.svg)](#API-) 
[![](https://img.shields.io/badge/-Other-F5F5F5.svg)](#Other-其他-) 
[![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-感谢-) 
[![](https://img.shields.io/badge/-Interface-F5F5F5.svg)](#Interface-界面-)


# New Features-新功能 [![](https://img.shields.io/badge/-New-F5F5F5.svg)](#New-Features-新功能-)

新功能欢迎测试，欢迎报告issue。
* **定时任务系统**: 移植了[legadoT](https://github.com/TaiYouWeb/legadoT)的定时任务框架到阅读 Sigma。感谢原作者！
    > 每天再也不用担心忘了追更。

* **章节识别与缓存优化**: 实现目录变动时本地缓存自动重命名，解决级联删除问题。
    > 对收藏党非常有用，防止小黑屋使缓存失效：
* **书源编辑快捷导航**: 在书源编辑界面引入快捷导航功能，将其提取为可复用组件。
    > 书源编辑再也不用划到手酸了。


# Function-主要功能 [![](https://img.shields.io/badge/-Function-F5F5F5.svg)](#Function-主要功能-)
[English](English.md)

<details><summary>中文</summary>

>新用户？<br>
>软件不提供内容，需要您自己手动添加，例如导入书源等。
>看看 [官方帮助文档](https://www.yuque.com/legado/wiki)，也许里面就有你要的答案。

1.自定义书源，自己设置规则，抓取网页数据，规则简单易懂，软件内有规则说明。<br>
2.列表书架，网格书架自由切换。<br>
3.书源规则支持搜索及发现，所有找书看书功能全部自定义，找书更方便。<br>
4.订阅内容,可以订阅想看的任何内容,看你想看<br>
5.支持替换净化，去除广告替换内容很方便。<br>
6.支持本地TXT、EPUB阅读，手动浏览，智能扫描。<br>
7.支持高度自定义阅读界面，切换字体、颜色、背景、行距、段距、加粗、简繁转换等。<br>
8.支持多种翻页模式，覆盖、仿真、滑动、滚动等。<br>
9.软件开源，持续优化，无广告。
</details>

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Community-交流社区 [![](https://img.shields.io/badge/-Community-F5F5F5.svg)](#Community-交流社区-)

#### Telegram
[![Telegram-channel](https://img.shields.io/badge/Σ_Telegram-%E9%A2%91%E9%81%93-blue)](https://t.me/readsigma)

#### WeChat
[![WeChat-channel](https://img.shields.io/badge/Σ_%e5%be%ae%e4%bf%a1-%e5%85%ac%e4%bc%97%e5%8f%b7-green)](https://mp.weixin.qq.com/s/f54f7yP9HQi6P5Wky8wE1A)  
<img src="https://open.weixin.qq.com/qr/code?username=legado_plus" width="100">

#### Discord
[![Discord](https://img.shields.io/discord/560731361414086666?color=%235865f2&label=Discord)](https://discord.gg/VtUfRyzRXn)

#### Other
https://www.yuque.com/legado/wiki/community

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# API [![](https://img.shields.io/badge/-API-F5F5F5.svg)](#API-)
* 阅读3.0 提供了2种方式的API：`Web方式`和`Content Provider方式`。您可以在[这里](api.md)根据需要自行调用。 
* 可通过url唤起阅读进行一键导入,url格式: legado://import/{path}?src={url}
* path类型: bookSource,rssSource,replaceRule,textTocRule,httpTTS,theme,readConfig,dictRule,[addToBookshelf](/app/src/main/java/io/legado/app/ui/association/AddToBookshelfDialog.kt)
* path类型解释: 书源,订阅源,替换规则,本地txt小说目录规则,在线朗读引擎,主题,阅读排版,添加到书架

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Other-其他 [![](https://img.shields.io/badge/-Other-F5F5F5.svg)](#Other-其他-)
##### 免责声明
https://gedoor.github.io/Disclaimer

##### 阅读3.0
* [书源规则](https://mgz0227.github.io/The-tutorial-of-Legado/)
* [更新日志](/app/src/main/assets/updateLog.md)
* [帮助文档](/app/src/main/assets/web/help/md/appHelp.md)
* [web端书架](https://github.com/gedoor/legado_web_bookshelf)
* [web端源编辑](https://github.com/gedoor/legado_web_source_editor)

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Grateful-感谢 [![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-感谢-)
> * org.jsoup:jsoup
> * cn.wanghaomiao:JsoupXpath
> * com.jayway.jsonpath:json-path
> * com.github.gedoor:rhino-android
> * com.squareup.okhttp3:okhttp
> * com.github.bumptech.glide:glide
> * org.nanohttpd:nanohttpd
> * org.nanohttpd:nanohttpd-websocket
> * cn.bingoogolapple:bga-qrcode-zxing
> * com.jaredrummler:colorpicker
> * org.apache.commons:commons-text
> * io.noties.markwon:core
> * io.noties.markwon:image-glide
> * com.hankcs:hanlp
> * com.positiondev.epublib:epublib-core
> * com.github.Moriafly:LyricViewX
> * io.github.rosemoe:editor
<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Interface-界面 [![](https://img.shields.io/badge/-Interface-F5F5F5.svg)](#Interface-界面-)
<img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B1.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B2.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B3.jpg" width="270">
<img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B4.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B5.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B6.jpg" width="270">

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>
