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
Legado·PP
<br>

Legado Sigma is inherited from <a href="https://github.com/gedoor/legado" target="_blank">Legado</a>,
and this project is a fork of Legado Sigma, adding more features and bug fixes on top of it.</div>

## Version Description
- Beta Version: Has the same package name as the original version, can overwrite the original app, frequent updates.
- Official Version: Updated whenever it reaches a stable stage.
- Currently, multiple versions are included in each release:

| Original | Coexisting A | Sigma (Plus) | PlusPlus (This Project) |
| --- | --- | --- | --- |
| release | releaseA | releaseS | releasePP |

The signatures are all the original Legado signatures, allowing for optional overwrite installation. However, please be **cautious and back up your data**!

# Contents
[![](https://img.shields.io/badge/-New-F5F5F5.svg)](#New-Features-)
[![](https://img.shields.io/badge/-Contents:-696969.svg)](#contents) 
[![](https://img.shields.io/badge/-Function-F5F5F5.svg)](#Function-Main-Features-) 
[![](https://img.shields.io/badge/-Community-F5F5F5.svg)](#Community-) 
[![](https://img.shields.io/badge/-API-F5F5F5.svg)](#API-) 
[![](https://img.shields.io/badge/-Other-F5F5F5.svg)](#Other-) 
[![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-) 
[![](https://img.shields.io/badge/-Interface-F5F5F5.svg)](#Interface-)

# New Features [![](https://img.shields.io/badge/-New-F5F5F5.svg)](#New-Features-)

New features are welcome for testing, feel free to report issues.
* **Scheduled Task System**: Ported the scheduled task framework from [legadoT](https://github.com/TaiYouWeb/legadoT) to Legado Sigma. Thanks to the original author!
    > Never worry about forgetting to follow up on latest chapters every day.

* **Chapter Identification & Cache Optimization**: Automatically renames the local cache when the table of contents changes, solving the cascade deletion issue.
    > Very useful for collectors, prevents the cache from becoming invalid when a chapter enters the black room.
* **Shortcut Navigation for Source Editing**: Introduced a shortcut navigation feature in the book source editing interface, extracting it as a reusable component.
    > No more sore thumbs scrolling up and down while editing book sources.


# Function-Main Features [![](https://img.shields.io/badge/-Function-F5F5F5.svg)](#Function-Main-Features-)
[中文](README.md)

> New User?<br>
> This app does not provide content. You need to manually add content yourself, such as importing book sources.
> Check out the [Official Help Documentation](https://www.yuque.com/legado/wiki) (in Chinese), maybe you can find the answers you need there.

1. Custom book sources: Set your own rules to scrape web data. The rules are easy to understand and explained within the app.<br>
2. Switch freely between list and grid bookshelf views.<br>
3. Source rules support searching and discovering. All finding and reading functions are fully customizable to make finding books easier.<br>
4. Subscribe to content: You can subscribe to whatever you want to read, read whatever you like.<br>
5. Supports text replacement and purification, easily removing ads or replacing specific content.<br>
6. Supports local TXT and EPUB reading, manual browsing, and intelligent scanning.<br>
7. Highly customizable reading interface: Change fonts, colors, backgrounds, line spacing, paragraph spacing, bolding, Simplified/Traditional Chinese conversion, etc.<br>
8. Supports multiple page-turning modes: Overlap, Simulation, Slide, Scroll, etc.<br>
9. Open-source software, continuously optimized, ad-free.

<a href="#readme">
    <img src="https://img.shields.io/badge/-Back%20To%20Top-orange.svg" alt="#" align="right">
</a>

# Community [![](https://img.shields.io/badge/-Community-F5F5F5.svg)](#Community-)

#### Telegram
[![Telegram-channel](https://img.shields.io/badge/Σ_Telegram-Channel-blue)](https://t.me/readsigma)

#### WeChat
[![WeChat-channel](https://img.shields.io/badge/Σ_WeChat-Official_Account-green)](https://mp.weixin.qq.com/s/f54f7yP9HQi6P5Wky8wE1A)  
<img src="https://open.weixin.qq.com/qr/code?username=legado_plus" width="100">

#### Discord
[![Discord](https://img.shields.io/discord/560731361414086666?color=%235865f2&label=Discord)](https://discord.gg/VtUfRyzRXn)

#### Other
https://www.yuque.com/legado/wiki/community

<a href="#readme">
    <img src="https://img.shields.io/badge/-Back%20To%20Top-orange.svg" alt="#" align="right">
</a>

# API [![](https://img.shields.io/badge/-API-F5F5F5.svg)](#API-)
* Legado 3.0 provides two types of APIs: `Web API` and `Content Provider API`. You can call them as needed [here](api.md).
* You can invoke the app via URL for one-click import. URL format: `legado://import/{path}?src={url}`
* Path types: `bookSource`, `rssSource`, `replaceRule`, `textTocRule`, `httpTTS`, `theme`, `readConfig`, `dictRule`, [`addToBookshelf`](/app/src/main/java/io/legado/app/ui/association/AddToBookshelfDialog.kt)
* Path type explanation: book source, RSS source, replace rule, local TXT novel TOC rule, online TTS engine, theme, reading layout, add to bookshelf.

<a href="#readme">
    <img src="https://img.shields.io/badge/-Back%20To%20Top-orange.svg" alt="#" align="right">
</a>

# Other [![](https://img.shields.io/badge/-Other-F5F5F5.svg)](#Other-)
##### Disclaimer
https://gedoor.github.io/Disclaimer

##### Legado 3.0
* [Book Source Rules](https://mgz0227.github.io/The-tutorial-of-Legado/)
* [Update Log](/app/src/main/assets/updateLog.md)
* [Help Documentation](/app/src/main/assets/web/help/md/appHelp.md)
* [Web Bookshelf](https://github.com/gedoor/legado_web_bookshelf)
* [Web Source Editor](https://github.com/gedoor/legado_web_source_editor)

<a href="#readme">
    <img src="https://img.shields.io/badge/-Back%20To%20Top-orange.svg" alt="#" align="right">
</a>

# Grateful [![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-)
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
    <img src="https://img.shields.io/badge/-Back%20To%20Top-orange.svg" alt="#" align="right">
</a>

# Interface [![](https://img.shields.io/badge/-Interface-F5F5F5.svg)](#Interface-)
<img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B1.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B2.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B3.jpg" width="270">
<img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B4.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B5.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B6.jpg" width="270">

<a href="#readme">
    <img src="https://img.shields.io/badge/-Back%20To%20Top-orange.svg" alt="#" align="right">
</a>
