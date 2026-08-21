<template>
  <div>
    <div class="welcome-banner">
      <h2>欢迎使用智能知识库平台</h2>
      <p>
        本系统基于 <b>Spring Boot + Spring AI 2.0</b> 构建 RAG（检索增强生成）应用：上传 PDF 文档自动解析、切片、向量化入库（Milvus），
        随后可通过自然语言向知识库提问，由 DeepSeek 结合检索到的文档内容生成带引用的回答。
      </p>
    </div>

    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-num">🚀</div>
        <div class="stat-label">RAG 检索增强问答</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">📄</div>
        <div class="stat-label">PDF 智能解析 + OCR</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">🧩</div>
        <div class="stat-label">语义切片 + 混合检索 + 重排</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">🔒</div>
        <div class="stat-label">JWT 认证 + RBAC 权限</div>
      </div>
    </div>

    <div class="card" style="margin-top: 20px;">
      <div class="card-title">使用指南</div>
      <ol style="line-height: 2.1; padding-left: 20px; color: #475569;">
        <li>在「上传文档」页选择知识库并上传 PDF 文件，系统将自动完成解析 → 切片 → 向量化 → 入库。</li>
        <li>可在「任务列表」页查看处理进度与详情。</li>
        <li>在「知识问答」页选择知识库并输入问题，即可获得基于文档内容的回答（附引用来源）。</li>
        <li>管理员可在「知识库管理」「用户管理」「角色管理」中进行系统维护。</li>
      </ol>
    </div>
  </div>
</template>

<script setup>
// 静态首页，无交互逻辑
</script>
