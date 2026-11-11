# 微信缓存清理（WeChatCacheCleaner）

一个需要 ROOT 的 Android 小工具：一键清理微信的**网页 / 小程序缓存**，同时保留浏览器内核组件和聊天数据。

> ⚠️ 清理会清除微信网页登录态（网页、小程序内的网页登录），但**不会删除聊天记录**。

## 功能

- 打开 app 立即自动清理（无需点按钮、无确认框）
- 桌面快捷方式「清理微信」，点一下直接清理
- 自动识别多用户 / 多开（主用户 0 + XSpace / 工作资料等用户）
- 固定目录清理，不扫描缓存子目录
- 只清网页数据，保留浏览器内核组件

## 清理范围

### 会删除

| 路径 | 内容 |
|---|---|
| `app_webview*` | WebView 的 Cookie / LocalStorage / IndexedDB / Cache / Service Worker |
| `app_webviewcache` | WebView 磁盘缓存 |
| `app_xweb_data/Default`、`app_xweb_data/Profile*`、`pref_store` 等 | XWeb 的 Cookie / LocalStorage / IndexedDB / Session Storage |
| `cache/xweb_cache` | XWeb 网页 HTTP 缓存（JS / CSS / 图片） |
| `cache/webview_com_tencent_mm*` | WebView 网页 HTTP 缓存 |
| `cache/appbrand/{jscache,snapshotjscache,wasmcache,shadercache}` | 小程序 JS / WASM / 渲染缓存 |
| `cache/skyline_cache` | 小程序 Skyline 渲染缓存 |
| `MicroMsg/jscache` | 小程序 JS 缓存 |
| `MicroMsg/CronetCache` | 小程序网络缓存 |
| `MicroMsg/appbrand/web_renderingcache` | 各小程序渲染缓存 |
| `MicroMsg/mmslot/webcached` | 小程序网页缓存 |

### 会保留

- **浏览器内核组件**：`app_xweb_data/xweb_*`（约 250 MB）、`app_xwalk*`、`app_tbs`、`app_x5`、`app_WxaCronet`、`files/tbs`、`files/x5`
- **聊天数据**：`MicroMsg`（除上述明确列出的 cache 子目录外，其它内容全部保留）
- **小程序包本体**：`MicroMsg/appbrand`（只删其中的 `web_renderingcache`）
- **其它缓存**：`cache/LocalCache`、`cache/ILink/Cache`

## 环境要求

- Android 7.0+（minSdk 24）
- 已 ROOT：Magisk / KernelSU

## 使用

1. 安装 APK（见 [Releases](https://github.com/liu269569205/WeChatCacheCleaner/releases)）
2. 打开 app，按提示授予 ROOT 权限
3. 打开后会自动清理；也可点「删除微信浏览器缓存」再次清理
4. 点「创建桌面快捷方式（一键清理）」生成「清理微信」图标，以后点桌面图标即可直接清理

## 构建

- JDK 17
- Gradle 8.10.2
- Android Gradle Plugin 8.7.3
- compileSdk 34 / targetSdk 34 / minSdk 24

```bash
# local.properties 中配置 sdk.dir 指向 Android SDK
gradle assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 常见问题

### 打开 app 提示「未获取 root 权限」或 `Cannot run program "su": error=13`

常见原因是 **Zygisk Next 的排除策略**把本应用屏蔽了，导致 app 的 mount namespace 里看不到 `su`。请到 Zygisk Next 的排除列表中移除本应用。

可以用下面的命令检查：

```bash
adb shell su -c "ls -l /proc/\$(pidof com.lj.wxcache)/root/system/bin/su"
```

能列出 `su -> ./magisk` 说明正常；提示 `No such file or directory` 说明被屏蔽。

### MIUI / HyperOS 创建桌面快捷方式失败

到「应用权限 → 桌面快捷方式」中允许本应用。

## 免责声明

本项目仅供个人学习与自用。清理操作不可恢复（网页登录态会丢失），请自行确认后使用。
