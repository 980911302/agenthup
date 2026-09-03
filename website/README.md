# Agent Java 官网

基于 React + Vite + TypeScript 的产品官网（落地页）。

## 开发

```bash
cd website
npm install
npm run dev
```

默认地址：http://localhost:5173

## 构建

```bash
npm run build
npm run preview
```

产物在 `dist/`，可部署到任意静态托管（Nginx / OSS / GitHub Pages 等）。

## 技术栈

- React 19 + TypeScript
- Vite 8
- Framer Motion（滚动入场动画）
- Lucide React（图标）

## 页面结构

| 区块 | 说明 |
|------|------|
| Hero | 品牌主张 + 实时 Run 终端演示 |
| Features | 9 大核心能力 |
| Architecture | 系统分层鸟瞰图 |
| How it works | 四步 AI Run 流程 |
| Modules | 业务模块卡片 |
| Tech stack | 技术选型 |
| CTA / Footer | 转化与导航 |
