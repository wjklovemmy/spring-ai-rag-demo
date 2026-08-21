<template>
  <router-view />
  <div class="toast-container">
    <div v-for="t in toastState.items" :key="t.id" class="toast" :class="t.type">{{ t.msg }}</div>
  </div>
</template>

<script setup>
import { toastState } from './utils/toast'
</script>
