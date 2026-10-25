# 隐私说明

御坂Music 是一个本地优先的 Android 应用。

## 收集的数据

不收集任何个人数据。  
不包含账号系统、统计 SDK、广告 SDK 或崩溃上报服务。

## 网络访问

应用只访问以下 B 站公开接口及 CDN：

- `api.bilibili.com`：公开收藏夹、视频信息、音频地址
- B 站图片 CDN：视频封面
- B 站音频 CDN：`.m4s` 音频流

不会提交 B 站账号 Cookie，也不读取私密收藏夹。

## 本地数据

以下数据保存在应用私有目录：

- 已下载的 `.m4s` 音频；
- 视频封面；
- 收藏夹列表缓存；
- 下载任务队列；
- 播放队列与播放位置。

卸载应用会删除这些数据。

## 权限

- `INTERNET`：加载公开收藏夹与媒体；
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` / `FOREGROUND_SERVICE_DATA_SYNC`：后台播放和自动下载；
- `POST_NOTIFICATIONS`：播放/下载通知；
- `WAKE_LOCK`：后台播放保持唤醒。

不申请存储权限、不申请媒体库权限。
