<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'

const props = defineProps<{
  imageUrl: string
  alt: string
}>()

const emit = defineEmits<{
  (event: 'close'): void
}>()

function close(): void {
  emit('close')
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') close()
}

onMounted(() => {
  document.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <div
      class="fixed inset-0 z-[1000] grid place-items-center bg-black/90 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      :aria-label="`Inspection de ${props.alt}`"
      @click.self="close"
    >
      <button
        type="button"
        class="absolute right-4 top-4 grid h-11 w-11 place-items-center rounded-full border border-cyber-cyan/70 bg-black/70 text-2xl text-white transition hover:border-cyber-magenta hover:text-cyber-magenta focus-visible:outline-2 focus-visible:outline-cyber-cyan"
        aria-label="Fermer l’inspection"
        @click="close"
      >
        ×
      </button>
      <img
        :src="props.imageUrl"
        :alt="props.alt"
        class="max-h-[92dvh] max-w-[92vw] rounded-xl object-contain shadow-[0_0_45px_rgba(5,217,232,0.35)]"
      />
    </div>
  </Teleport>
</template>
