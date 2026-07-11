# EmbyX - 部署与构建指南

## 推送到 GitHub

```bash
# 1. 先在浏览器打开 https://github.com/new 创建仓库，命名为 EmbyX

# 2. 在终端中执行：
cd D:\AIzy\embyx\EmbyX

# 3. 设置远程仓库地址（替换 YOUR_USERNAME 为你的 GitHub 用户名）
git remote add origin https://github.com/YOUR_USERNAME/EmbyX.git

# 4. 推送到 GitHub
git -c http.sslBackend=openssl push -u origin main
```

如果提示需要登录，GitHub 会弹出浏览器窗口进行 OAuth 认证。

## GitHub Actions 自动构建 APK

推送后，GitHub Actions 会自动运行工作流（`.github/workflows/build-apk.yml`），产生两个 APK：

1. **Android WebView APK** — 原生 Android WebView 壳，加载 EmbyX Web 应用
2. **Cordova APK** — 通过 Cordova 构建的混合应用 APK

构建完成后，APK 文件可以在 GitHub 仓库的 **Actions > 最新运行 > Artifacts** 中下载。

## GitHub Pages 部署（可选）

如果想直接托管 Web 版本：

```yaml
# 在仓库 Settings > Pages > Source 选择 GitHub Actions
# 或者添加以下 workflow 到 .github/workflows/deploy-pages.yml
```

```yaml
name: Deploy to Pages
on:
  push:
    branches: [main]
jobs:
  deploy:
    runs-on: ubuntu-latest
    permissions:
      pages: write
      id-token: write
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/configure-pages@v4
      - uses: actions/upload-pages-artifact@v3
        with:
          path: 'en'
      - id: deployment
        uses: actions/deploy-pages@v4
```

## 项目结构

```
EmbyX/
├── en/                        # 英文版
│   └── index.html             # 主应用（含长视频模式）
├── zh/                        # 中文版
│   └── index.html
├── android/                   # Android WebView 壳（APK 构建源）
│   └── app/src/main/
│       ├── AndroidManifest.xml
│       └── java/com/embyx/app/MainActivity.java
├── .github/workflows/
│   └── build-apk.yml          # CI：自动构建 APK
└── EMBYX_LONG_MODE_PLAN.md    # 长视频模式设计文档
```
