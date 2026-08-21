import { reactive } from 'vue'

export const toastState = reactive({ items: [] })

let seed = 0
export function showToast(msg, type = 'info') {
  const id = ++seed
  toastState.items.push({ id, msg, type })
  setTimeout(() => {
    const i = toastState.items.findIndex((t) => t.id === id)
    if (i > -1) toastState.items.splice(i, 1)
  }, 3200)
}
