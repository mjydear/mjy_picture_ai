<template>
  <div id="basicLayout">
    <a-layout style="min-height: 100vh">
      <a-layout-header class="header">
        <GlobalHeader />
      </a-layout-header>
      <a-layout>
        <GlobalSider class="sider" />
        <a-layout-content class="content">
          <router-view />
        </a-layout-content>
      </a-layout>
      <a-layout-footer class="footer">
        <div class="footer-main">
          <span class="footer-brand">云图库</span>
          <span class="footer-dot">·</span>
          <span class="footer-slogan">智能协同 · 海量图片素材自由管理</span>
        </div>
        <div class="footer-copy">© 2026 云图库 CloudGallery</div>
      </a-layout-footer>
    </a-layout>
  </div>
</template>

<script setup lang="ts">
import GlobalHeader from '@/components/GlobalHeader.vue'
import GlobalSider from "@/components/GlobalSider.vue";
</script>

<style scoped>
#basicLayout {
  background: #f4f5fb;
}

#basicLayout .header {
  padding-inline: 24px;
  color: unset;
  background: rgba(255, 255, 255, 0.75);
  backdrop-filter: saturate(180%) blur(14px);
  -webkit-backdrop-filter: saturate(180%) blur(14px);
  border-bottom: 1px solid rgba(0, 0, 0, 0.04);
  box-shadow: 0 4px 20px rgba(31, 38, 135, 0.06);
  position: sticky;
  top: 0;
  z-index: 100;
}

#basicLayout .sider {
  background: rgba(255, 255, 255, 0.6);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  border-right: 1px solid rgba(0, 0, 0, 0.04);
  padding-top: 20px;
}

#basicLayout :deep(.ant-menu-root) {
  border-bottom: none !important;
  border-inline-end: none !important;
  background: transparent;
}

#basicLayout .content {
  padding: 28px;
  margin: 24px 24px 96px;
  background: #ffffff;
  border-radius: 18px;
  box-shadow: 0 10px 40px rgba(31, 38, 135, 0.06);
  min-height: 62vh;
}

#basicLayout .footer {
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-top: 1px solid rgba(0, 0, 0, 0.04);
  padding: 14px 16px;
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  text-align: center;
}

.footer-main {
  font-size: 14px;
}

.footer-brand {
  font-weight: 700;
  background: linear-gradient(120deg, #4f46e5, #8b5cf6 45%, #06b6d4);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

.footer-dot {
  margin: 0 8px;
  color: #c4c7d4;
}

.footer-slogan {
  color: #8a8fa3;
}

.footer-copy {
  margin-top: 4px;
  font-size: 12px;
  color: #b0b3c0;
  letter-spacing: 0.5px;
}
</style>
