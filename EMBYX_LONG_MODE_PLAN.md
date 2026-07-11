# EmbyX 长视频模式改造计划

> 为 TikTok 风格的 Emby/Jellyfin 播放器增加完整的长视频体验
> 设计日期：2026-07-11

---

## 目录

1. [背景与动机](#1-背景与动机)
2. [当前架构分析](#2-当前架构分析)
3. [设计原则](#3-设计原则)
4. [模式切换机制](#4-模式切换机制)
5. [长模式浏览架构](#5-长模式浏览架构)
6. [全功能播放器 OSD](#6-全功能播放器-osd)
7. [剧集与连播支持](#7-剧集与连播支持)
8. [技术实现方案](#8-技术实现方案)
9. [分阶段实施计划](#9-分阶段实施计划)
10. [附录：Emby API 参考](#10-附录emby-api-参考)

---

## 1. 背景与动机

### 1.1 EmbyX 现状

EmbyX 是一个高度特化的短视频播放器，核心交互是 TikTok 风格的竖滑 feed：

- 3 个 `<video>` 元素循环复用，通过 `translateY(-index * 100%)` 实现滑动
- 内容以平铺的 feed 流方式消费，适合短视频（几秒到几分钟）
- 没有精细的 seek、字幕切换、音轨选择、章节导航
- 网格视图作为辅助，但交互依然是从网格进 feed

### 1.2 问题

Emby/Jellyfin 媒体库中真正的主力内容是**电影**和**剧集**——时长 45 分钟到 3 小时。在现有交互下看长视频体验很差：

- 滑走就丢失上下文
- 没有精细进度控制
- 没有剧集结构（季/集层级）
- 字幕/音轨/倍速等刚需控制缺失

### 1.3 目标

在不破坏现有短模式体验的前提下，增加一个**长视频模式**，让 EmbyX 成为一个既能刷短视频、又能正经看电影/剧集的全能播放器。

---

## 2. 当前架构分析

### 2.1 文件结构

```
EmbyX/
├── en/                          # 英文版（主版本）
│   ├── index.html               # 单页应用主体（~240KB）
│   ├── lib/                     # 第三方库（离线 fallback）
│   │   ├── hls.min.js
│   │   ├── mpegts.min.js
│   │   ├── lucide.min.js
│   │   └── tailwind.min.js
│   ├── manifest.json            # PWA manifest
│   ├── sw.js                    # Service Worker
│   ├── info.html                # 性能检测页
│   ├── pic.html                 # 图片浏览页
│   └── *.webp / *.png           # 静态资源
├── zh/                          # 中文版（同上结构）
├── live/                        # 直播代理服务
├── docker/                      # Docker 配置
├── Dockerfile
├── docker-compose.yml
├── nginx.conf
└── entrypoint.sh
```

### 2.2 核心类结构

所有逻辑在 `EmbyApp` 类中：

```javascript
class EmbyApp {
    // 状态
    state = { videos, currentIndex, isPlaying, favorites, playMode, viewMode, ... }
    // 配置
    config = { server, user, token, userId, useStatic, autoplay, ... }
    // DOM 缓存
    dom = { videoContainer, videoTitle, progressLine, ... }
    
    // ─── 生命周期 ───
    init()              // 入口：缓存DOM → 加载配置 → 绑定事件 → fetchVideos
    cacheDOM()          // 缓存所有常用 DOM 引用
    bindEvents()        // 事件绑定（点击、触摸、键盘）
    
    // ─── 数据 ───
    fetchVideos()       // 从 Emby API 获取视频列表
    loadVideo(index)    // 加载指定索引的视频到 slide
    playVideo(index)    // 播放指定索引
    
    // ─── UI ───
    renderSlides()      // 创建 3-slide DOM 结构
    renderGridView()    // 渲染网格视图
    switchSlide(index)  // 滑动切换
    
    // ─── 播放控制 ───
    togglePlay()
    prevVideo() / nextVideo()
    toggleScaleMode()
    toggleMute()
    
    // ─── 同步 ───
    reportPlayback(event, videoEl)
    reportCapabilities()
}
```

### 2.3 关键约束

| 约束 | 说明 |
|---|---|
| **单 HTML 文件** | 所有 HTML/CSS/JS 在一个文件中，没有 build 工具链 |
| **无框架** | 原生 JS + Tailwind CDN + Lucide 图标 |
| **3-slide 系统** | 视频在 3 个 slide 间循环，不适用于长视频的精细控制 |
| **PWA 兼容** | 需要保持 Service Worker 和 manifest |

---

## 3. 设计原则

1. **不破坏短模式**——所有改动对短模式零影响
2. **渐进增强**——长模式是附加的，不修改现有核心逻辑
3. **单一 HTML 约束**——所有代码仍在同一个文件中
4. **复用现有引擎**——HLS/mpegts 播放逻辑、Emby API 同步、配置管理都复用
5. **移动优先**——触控友好，兼顾桌面键盘

---

## 4. 模式切换机制

### 4.1 切换入口

底部导航栏从 4 项扩展到 5 项：

```
[ 网格视图 ] [ 播放模式 ] [ 媒体库 ] [ 长短切换 ] [ 个人中心 ]

   短模式显示          长短切换按钮用图标：
   layout-grid    ◀──  video（短）↔ clapperboard（长）
```

### 4.2 状态流转

```
                   点击切换按钮
  ┌──────────────────────────────────┐
  │       短模式 (viewMode='short')  │
  │  ┌─ 竖滑 feed ─────────────────┐ │
  │  │ 3-slide 系统                 │ │
  │  │ 双击快进/快退 15s           │ │
  │  │ 右侧工具栏（收藏/缩放/静音）│ │
  │  │ 底部进度条                  │ │
  │  └────────────────────────────┘ │
  └──────────────┬───────────────────┘
                 │ 切换
                 ▼
  ┌──────────────────────────────────┐
  │       长模式 (viewMode='long')   │
  │  ┌─ Dashboard ─────────────────┐ │
  │  │ 继续观看 + 媒体库 + 最近添加 │ │
  │  ├─ 库浏览 ────────────────────┤ │
  │  │ 网格视图，点击进详情/播放    │ │
  │  ├─ 剧集浏览 ─────────────────┤ │
  │  │ 系列海报 → 季选择 → 集列表 │ │
  │  ├─ 全功能播放器 ─────────────┤ │
  │  │ OSD 控制栏 + 字幕/音轨/章节 │ │
  │  │ + 倍速 + Up Next            │ │
  │  └────────────────────────────┘ │
  └──────────────────────────────────┘
```

### 4.3 切换行为

切换模式时：

1. 如果当前有视频在播放 → 停止并清理
2. 保存当前模式到 `localStorage`
3. 调用 `deactivate()` 清理当前模式 UI
4. 调用 `activate()` 渲染新模式 UI
5. 短模式激活：`renderSlides()` / `fetchVideos()`
6. 长模式激活：`LongModePlayer.renderDashboard()`

---

## 5. 长模式浏览架构

### 5.1 Dashboard 首页

```
┌───────────────────────────────────────────┐
│  EmbyX  [logo]            [搜索] [设置]   │
│                                            │
│  ┌── 继续观看 ──────────────────────────┐  │
│  │  [海报1] [海报2] [海报3] [海报4] →   │  │
│  │  电影A45%  剧集B S2E3 电影C72% ...  │  │
│  └──────────────────────────────────────┘  │
│                                            │
│  ┌── 媒体库 ────────────────────────────┐  │
│  │  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ │  │
│  │  │电影│ │剧集│ │动漫│ │音乐│ │更多│ │  │
│  │  └────┘ └────┘ └────┘ └────┘ └────┘ │  │
│  └──────────────────────────────────────┘  │
│                                            │
│  ┌── 最近添加 ──────────────────────────┐  │
│  │  6 列网格，滚动加载                   │  │
│  └──────────────────────────────────────┘  │
│                                            │
│            [网格][模式][库][切换][我的]      │
└───────────────────────────────────────────┘
```

**数据源：**

| 区块 | Emby API |
|---|---|
| 继续观看 | `GET /emby/Users/{userId}/Items/Resume?Limit=12` |
| 媒体库 | `GET /emby/Library/MediaFolders` |
| 最近添加 | `GET /emby/Users/{userId}/Items/Latest?Limit=24` |

### 5.2 库浏览

点击媒体库 → 进入网格视图（复用现有 `renderGridView()`，增强）：

```
┌───────────────────────────────────────────┐
│ [← 返回]  电影库           [排序▽] [筛选▽] │
│                                            │
│  ┌────┬────┬────┬────┬────┬────┐          │
│  │ 🎬  │ 🎬  │ 🎬  │ 🎬  │ 🎬  │ 🎬  │          │
│  │ 片名 │ 片名 │ 片名 │ 片名 │ 片名 │ 片名 │          │
│  └────┴────┴────┴────┴────┴────┘          │
│  ┌────┬────┬────┬────┬────┬────┐          │
│  │ 🎬  │ 🎬  │ 🎬  │ 🎬  │ 🎬  │ 🎬  │          │
│  └────┴────┴────┴────┴────┴────┘          │
│              [加载更多...]                   │
└───────────────────────────────────────────┘
```

**行为区别：**
- 电影 → 点击海报 → `openPlayer(item)` 直接播放
- 剧集 → 点击海报 → `renderSeries(seriesId)` 进入剧集详情

### 5.3 剧集浏览器

```
┌───────────────────────────────────────────┐
│ [← 返回]  《剧集名称》                     │
│                                            │
│  ┌──────────────────────┐                  │
│  │                      │  年份 · 评分     │
│  │   大尺寸主海报 + 简介 │  类型：剧情/悬疑 │
│  │                      │  主演：...       │
│  └──────────────────────┘                  │
│                                            │
│  [S1] [S2] [S3]  ← 季选项卡               │
│                                            │
│  ┌── 第 1 季 ─────────────────────────┐   │
│  │  E01 标题一              45:12 ▶   │   │
│  │  E02 标题二              44:08     │   │
│  │  E03 标题三              45:30 ▶80%│   │
│  │  E04 标题四              43:55     │   │
│  │  E05 标题五              44:20     │   │
│  │  ...                               │   │
│  └────────────────────────────────────┘   │
└───────────────────────────────────────────┘
```

**数据源：**

| 数据 | Emby API |
|---|---|
| 系列详情 | `GET /emby/Users/{userId}/Items/{seriesId}` |
| 季列表 | `GET /emby/Shows/{seriesId}/Seasons?userId={userId}` |
| 集列表 | `GET /emby/Shows/{seriesId}/Episodes?seasonId={seasonId}&userId={userId}` |

**集条目显示：**
- 集号 + 标题
- 时长
- 播放进度（如果有 UserData.PlayedPercentage）
- ▶ 继续播放 / 已看完勾选

---

## 6. 全功能播放器 OSD

### 6.1 布局

```
┌───────────────────────────────────────────┐
│ [←] 片名·年份       [字幕][音轨][设置]    │ ← 顶部栏
│                                            │
│                                            │
│              视频画面区域                    │
│              (双击快进/快退)               │
│                                            │
│                                            │
│  ──▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░─────       │ ← 进度条(含章节标记)
│  01:23:45 / 02:15:30                      │
│                                            │
│  [⏮章] [⏪10s] [▶⏸] [⏩10s] [⏭章]       │ ← 控制栏
│              [1.0x] [🔊] [⛶全屏]          │
│                                            │
└───────────────────────────────────────────┘
```

### 6.2 控制功能矩阵

| 控件 | 位置 | 交互 | 实现方式 |
|---|---|---|---|
| 返回 | 左上角 | 点击退出播放器 | `closePlayer()` → 显示浏览层 |
| 标题信息 | 顶部 | 显示名称+年份+剧集信息 | `item.Name \| item.ProductionYear` |
| 字幕选择 | 右上 | 点击弹出字幕列表 | `MediaStreams[Type=Subtitle]` |
| 音轨选择 | 右上 | 点击弹出音轨列表 | `MediaStreams[Type=Audio]` |
| 设置 | 右上 | 播放质量、跳过设置 | 扩展菜单 |
| 进度条 | 底部 | 点击跳转，拖拽精确 seek | `video.currentTime = ratio * duration` |
| 章节标记 | 进度条上 | 竖线标记已播放变绿 | `Chapters[].StartPositionTicks` |
| 时间显示 | 进度条旁 | 当前/总时长 HH:MM:SS | `formatTime(video.currentTime)` |
| 上一章 | 控制栏左 | 跳到上一章起点 | 遍历 Chapters |
| 快退 10s | 控制栏左 | 后退 10 秒 | `video.currentTime -= 10` |
| 播放/暂停 | 控制栏中 | 切换 | `video.play() / pause()` |
| 快进 10s | 控制栏右 | 前进 10 秒 | `video.currentTime += 10` |
| 下一章 | 控制栏右 | 跳到下一章起点 | 遍历 Chapters |
| 倍速 | 控制栏右 | 0.5x → 0.75x → 1.0x → 1.25x → 1.5x → 2.0x → 3.0x | `video.playbackRate` |
| 音量 | 控制栏右 | 点击静音/取消，悬停滑块 | `video.volume / video.muted` |
| 全屏 | 右下角 | Fullscreen API | `document.fullscreenElement` |

### 6.3 自动隐藏逻辑

| 触发条件 | 行为 |
|---|---|
| 用户无操作 3 秒 | 控制栏淡出（opacity → 0） |
| 移动鼠标 / 触摸屏幕 | 控制栏淡入（opacity → 1） |
| 视频暂停 | 控制栏常驻 |
| 视频结束 | 显示 Up Next 卡片 |
| 字幕/音轨选择器打开 | 控制栏常驻 |

### 6.4 键盘快捷键（长模式专用）

| 键 | 功能 |
|---|---|
| `Space` / `k` | 播放/暂停 |
| `←` / `→` | 快退/快进 10s |
| `↑` / `↓` | 音量 ±10% |
| `f` | 全屏切换 |
| `m` | 静音切换 |
| `[` / `]` | 上一章 / 下一章 |
| `>` / `<` | 倍速 + / 倍速 - |
| `Esc` | 退出播放器 |
| `0-9` | 跳转到 0%-90% 位置 |

### 6.5 章节标记实现

Emby 返回的章节数据：

```json
"Chapters": [
  { "StartPositionTicks": 0, "Name": "开场" },
  { "StartPositionTicks": 1800000000, "Name": "第一幕" },
  { "StartPositionTicks": 5400000000, "Name": "第二幕" },
  { "StartPositionTicks": 9000000000, "Name": "第三幕" }
]
```

Ticks → 秒：`秒 = Ticks / 10000000`

进度条上的标记：
```javascript
chapters.forEach(ch => {
    const pct = (ch.StartPositionTicks / 10000000) / duration * 100;
    // 在进度条 pct% 位置画一条竖线
    // 已播放的标记变绿
});
```

---

## 7. 剧集与连播支持

### 7.1 剧集自动连播

```
视频结束 →
┌───────────────────────────────────────────┐
│                                            │
│              下一集倒计时 15s               │
│                                            │
│  ┌──────────────────────────────────┐      │
│  │  S02 · E04                       │      │
│  │  ┌──────┐  第四集标题            │      │
│  │  │      │  剩余 12s 自动播放     │      │
│  │  │ 海报 │                         │      │
│  │  │      │  [取消]   [▶ 播放下一集]│      │
│  │  └──────┘                         │      │
│  └──────────────────────────────────┘      │
│                                            │
└───────────────────────────────────────────┘
```

### 7.2 连播逻辑

```javascript
async function playNextEpisode() {
    const ep = this.state.currentEpisode;
    if (!ep || !ep.SeriesId) return;   // 不是剧集，不连播
    
    const next = await this.fetchNextEpisode(ep.SeriesId, ep.SeasonId, ep.IndexNumber);
    if (next) {
        this.openPlayer(next);         // 播放下一个
    } else {
        // 没有下一集，回到剧集页面
        this.renderSeries(ep.SeriesId);
    }
}
```

### 7.3 播放完成状态同步

长视频模式播放结束时，调用 Emby API 标记为已播：

```javascript
// 标记已播
await fetch(`${server}/emby/Users/${userId}/PlayedItems/${item.Id}?api_key=${token}`, {
    method: 'POST'
});
```

---

## 8. 技术实现方案

### 8.1 代码架构

新增 `LongModePlayer` 类，与 `EmbyApp` 平级，通过 `EmbyApp.longPlayer` 引用协作：

```javascript
class LongModePlayer {
    constructor(app) { /* ... */ }
    
    // ─── 生命期 ───
    activate()      // 切换进长模式
    deactivate()    // 退出长模式，清理
    
    // ─── 浏览层 ───
    renderDashboard()       // Dashboard 首页
    renderLibrary(libId)    // 媒体库网格
    renderSeries(seriesId)  // 剧集详情页
    
    // ─── 播放器层 ───
    openPlayer(item)        // 打开全功能播放器
    closePlayer()           // 退出播放器
    
    // ─── OSD ───
    setupOSD()              // 绑定控制栏事件
    showOSD() / hideOSD()   // 显隐控制栏
    updateSeekBar()         // 更新进度条
    showSubtitlePicker()    // 字幕选择
    showAudioPicker()       // 音轨选择
    cycleSpeed()            // 循环切换倍速
    
    // ─── 剧集 ───
    showUpNext()            // 显示下一集卡片
    playNextEpisode()       // 播放下一个
    
    // ─── 数据 ───
    async fetchResumeItems()         // 继续观看
    async fetchMediaLibraries()      // 媒体库列表
    async fetchSeriesDetail(id)      // 系列详情
    async fetchEpisodes(sid, season) // 剧集列表
    async fetchMediaStreams(id)      // 音轨/字幕流
}
```

### 8.2 HTML 结构

新增的 DOM 使用 `position: fixed` 覆盖在原 UI 之上，`z-index: 70`，与现有 UI 隔离：

```html
<!-- 长模式容器 (z-70，覆盖整个页面) -->
<div id="longModeContainer" class="fixed inset-0 z-[70] bg-black hidden">
    <!-- 浏览层 -->
    <div id="longBrowseLayer" class="w-full h-full flex flex-col"></div>
    
    <!-- 播放器层 -->
    <div id="longPlayerLayer" class="absolute inset-0 z-[80] hidden">
        <video id="longVideoPlayer" class="w-full h-full object-contain" playsinline></video>
        <!-- 顶部栏 -->
        <!-- 底部控制栏 -->
        <!-- 字幕/音轨选择器 -->
        <!-- Up Next -->
    </div>
</div>
```

### 8.3 视频播放引擎复用

长模式的视频播放复用现有 `EmbyApp` 中的核心方法：

```javascript
// LongModePlayer 中
playItem(item) {
    const v = document.getElementById('longVideoPlayer');
    
    // 复用 EmbyApp 的 getVideoSrc 构建播放 URL
    const src = this.app.getVideoSrc(item.Id, item, true);
    
    // 复用 EmbyApp 的 HLS/mpegts 初始化
    if (/* HLS */) {
        const hls = new Hls({ /* ... */ });
        hls.loadSource(src);
        hls.attachMedia(v);
    } else {
        v.src = src;
    }
    
    v.play();
    this.showPlayerLayer();
    
    // 绑定 progress 事件
    v.addEventListener('timeupdate', () => this.updateOSD());
}
```

### 8.4 现有代码改动点摘要

| 文件 | 改动 |
|---|---|
| `en/index.html` | 新增 `LongModePlayer` 类 + 长模式 HTML 结构 + CSS |
| `en/index.html` | `EmbyApp.state.viewMode` 新增 `'long'` |
| `en/index.html` | 底部导航新增第 5 个按钮 |
| `en/index.html` | `bindEvents()` 新增模式切换事件 + 长模式快捷键 |
| `en/index.html` | `renderGridView()` 增强长模式下的点击行为 |
| `zh/index.html` | 同上（中文版同步） |

**零改动：**
- `en/lib/*` — 第三方库不动
- `manifest.json` — PWA 配置不变
- `sw.js` — Service Worker 不变
- Docker 相关文件不变

### 8.5 文件体积预估

| 项目 | 当前 | 改造后 |
|---|---|---|
| `en/index.html` | ~240KB | ~280KB（+~40KB） |
| `zh/index.html` | ~260KB | ~300KB（+~40KB） |

---

## 9. 分阶段实施计划

### Phase 1：基础设施 + Dashboard + 库浏览

**目标**：模式可切换，长模式下能看到 Dashboard 和媒体库，能进入网格浏览

**具体改动：**

1. 在 `EmbyApp.state` 新增 `viewMode: 'short' | 'long'`
2. 底部 nav 新增第 5 个按钮（模式切换）
3. 新增 `LongModePlayer` 类骨架
4. 在 HTML 末尾新增长模式容器 `#longModeContainer`
5. 实现 `renderDashboard()`：
   - 继续观看行（调用 `/Items/Resume`）
   - 媒体库网格（调用 `/Library/MediaFolders`）
   - 最近添加网格（调用 `/Items/Latest`）
6. 实现 `renderLibrary(libId)`：基于现有 `renderGridView()` 的逻辑
7. 点击海报行为：长模式下调用 `openPlayer()` 或 `renderSeries()`
8. CSS 样式：长模式容器、Dashboard 卡片、媒体库卡片

### Phase 2：全功能播放器 OSD

**目标**：长视频能播，有完整的播放控制

**具体改动：**

1. 新增播放器层 HTML（video + 顶部栏 + 底部控制栏）
2. 实现 `openPlayer(item)`：
   - 填充视频源（复用 `getVideoSrc()`）
   - 初始化 HLS/mpegts（复用）
   - 显示播放器层
3. 实现 OSD 控制：
   - 播放/暂停
   - 进度条（click to seek）
   - 快进/快退 10s
   - 全屏切换
   - 自动隐藏（3s 无操作 → 淡出）
4. 实现 `closePlayer()`：清理视频源，回到浏览层
5. 实现 `formatTime()` 支持 HH:MM:SS 格式

### Phase 3：剧集浏览器

**目标**：剧集库能进，能看到季/集结构，能选集播放

**具体改动：**

1. 实现 `renderSeries(seriesId)`：
   - 获取系列详情（海报、年份、评分、简介）
   - 获取季列表 → 渲染季选项卡
   - 获取当前季的集列表 → 渲染列表
2. 集条目显示：集号 + 标题 + 时长 + 播放进度
3. 季切换：点击不同季 → 重新获取集列表
4. 点击集 → `openPlayer(episodeItem)`

### Phase 4：字幕/音轨/章节

**目标**：能切换字幕和音轨，进度条有章节标记

**具体改动：**

1. 实现 `fetchMediaStreams(itemId)`：
   - 从 `MediaSources[0].MediaStreams` 提取字幕和音轨
2. 字幕选择器：
   - 弹出底部列表，选择字幕轨道
   - 通过 `video.textTracks` 或 Emby 转码参数切换
3. 音轨选择器：
   - 弹出底部列表，切换音频轨道
   - 通过切换 `audioTracks` 或重建播放 URL
4. 章节标记：
   - 获取 `Chapters[]`
   - 在进度条上渲染竖线标记
   - 已播放的标记变绿

### Phase 5：连播 + 快捷键

**目标**：剧集自动连播，快捷键完整

**具体改动：**

1. 实现 `showUpNext()`：
   - 视频结束触发
   - 15s 倒计时 + 取消 + 立即播放
   - 调用 `playNextEpisode()`
2. 播放完成标记已播
3. 键盘快捷键绑定（长模式专用）

---

## 10. 附录：Emby API 参考

### 继续观看
```
GET /emby/Users/{userId}/Items/Resume
  ?Limit=12
  &api_key={token}
```

### 媒体库列表
```
GET /emby/Library/MediaFolders
  ?api_key={token}
```

### 最近添加
```
GET /emby/Users/{userId}/Items/Latest
  ?Limit=24
  &api_key={token}
```

### 系列详情
```
GET /emby/Users/{userId}/Items/{seriesId}
  ?api_key={token}
```

### 剧集季列表
```
GET /emby/Shows/{seriesId}/Seasons
  ?userId={userId}
  &api_key={token}
```

### 剧集集列表
```
GET /emby/Shows/{seriesId}/Episodes
  ?seasonId={seasonId}
  &userId={userId}
  &api_key={token}
  &Fields=Overview,UserData,MediaSources,Chapters
```

### 标记已播
```
POST /emby/Users/{userId}/PlayedItems/{itemId}
  ?api_key={token}
```

### 标记未播
```
DELETE /emby/Users/{userId}/PlayedItems/{itemId}
  ?api_key={token}
```

### 播放进度上报
```
POST /emby/Sessions/Playing/Progress
  ?api_key={token}
  Body: {
    ItemId, PositionTicks, IsPaused, IsMuted,
    PlayMethod, EventName, MediaSourceId, PlaySessionId
  }
```

---

> 本文档对应 EmbyX 主版本，代码实现在 `en/index.html` 和 `zh/index.html` 中。
