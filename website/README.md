# BUCC 官网

独立静态介绍与下载状态页，不提供校园账号登录或业务代理。主飞机素材直接复用 `composeApp/icons/aircraft-cutout.png`，仅调整背景渐变。

```sh
npm run build --prefix website
npm run check --prefix website
npm run preview --prefix website
```

只部署 `website/dist`，禁止部署仓库根目录、个人日志、签名材料或本地测试安装包。平台下载在正式包得到确认后再添加；当前状态页不会伪装成安装包下载。

Cloudflare 项目为 `bucc`，分配的生产域名为 `bucc-6ga.pages.dev`（精确 `bucc.pages.dev` 已被占用）。现有 `bucc-download-1-9-0` 项目保留。

```sh
cd website
npx wrangler@4 pages deploy dist --project-name bucc --branch main
```

凭据由本机 Wrangler 安全登录配置提供，不写入本目录。无统计脚本、第三方字体或 Cookies。`_headers` 限制本页网络连接、表单和设备能力。
