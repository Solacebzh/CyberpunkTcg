/**
 * Animations GSAP du plateau.
 *
 * Le frontend étant passif, les animations ne *produisent* rien : elles réagissent
 * aux différences entre deux états serveur (`gameStore.changes`, calculé à partir
 * de `state` + `newEvents`). Chaque carte du plateau porte un attribut
 * `data-instance-id` qui sert de point d'accroche.
 *
 * `prefers-reduced-motion` est respecté : les animations sont alors ignorées
 * (l'état final est déjà rendu par Vue).
 */
import { getCurrentScope, onScopeDispose, type Ref } from 'vue'
import gsap from 'gsap'

export type BoardSide = 'me' | 'opponent'

const NEON = {
  cyan: 'rgba(5, 217, 232, 0.75)',
  magenta: 'rgba(255, 42, 109, 0.75)',
  yellow: 'rgba(252, 238, 10, 0.7)',
}

export function useGameAnimations(root: Ref<HTMLElement | null>) {
  const reducedMotion =
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
      : false

  let ctx: gsap.Context | null = null

  function ensureContext(): gsap.Context | null {
    if (reducedMotion) return null
    if (ctx) return ctx
    ctx = gsap.context(() => undefined, root.value ?? undefined)
    return ctx
  }

  /**
   * Exécute une animation dans le contexte GSAP (nettoyé au démontage).
   * Les types de gsap déclarent `add()` comme renvoyant `ReturnType<T>` alors que
   * le runtime renvoie la fonction encapsulée : d'où la conversion explicite.
   */
  function scope(animation: () => void): void {
    const context = ensureContext()
    if (!context) return
    const run = context.add(animation) as unknown as () => void
    run()
  }

  /** `CSS.escape` manque dans certains environnements (SSR, jsdom ancien). */
  function escapeId(value: string): string {
    return typeof CSS !== 'undefined' && typeof CSS.escape === 'function'
      ? CSS.escape(value)
      : value.replace(/["\\]/g, '\\$&')
  }

  function element(instanceId: string): HTMLElement | null {
    if (!root.value) return null
    return root.value.querySelector<HTMLElement>(`[data-instance-id="${escapeId(instanceId)}"]`)
  }

  function zone(side: BoardSide, name: string): HTMLElement | null {
    if (!root.value) return null
    return root.value.querySelector<HTMLElement>(`[data-anim="${name}"][data-side="${side}"]`)
  }

  /** Pioche : les cartes glissent depuis la pioche vers la main. */
  function drawCards(instanceIds: string[], side: BoardSide = 'me'): void {
    const targets = instanceIds.map(element).filter((node): node is HTMLElement => !!node)
    if (targets.length === 0) return
    const from = side === 'me' ? { x: 90, y: 30 } : { x: -90, y: -30 }

    scope(() => {
      gsap.fromTo(
        targets,
        { ...from, opacity: 0, scale: 0.82, rotateZ: 6 },
        { x: 0, y: 0, opacity: 1, scale: 1, rotateZ: 0, duration: 0.45, ease: 'power2.out', stagger: 0.06 },
      )
    })
  }

  /** Pose de carte : la carte « claque » sur le Field avec un halo néon. */
  function playCards(instanceIds: string[], side: BoardSide = 'me'): void {
    const targets = instanceIds.map(element).filter((node): node is HTMLElement => !!node)
    if (targets.length === 0) return
    const offsetY = side === 'me' ? 70 : -70
    const glow = side === 'me' ? NEON.cyan : NEON.magenta

    scope(() => {
      gsap.fromTo(
        targets,
        { y: offsetY, scale: 0.7, opacity: 0 },
        { y: 0, scale: 1, opacity: 1, duration: 0.42, ease: 'back.out(1.7)', stagger: 0.05 },
      )
      gsap.fromTo(
        targets,
        { boxShadow: `0 0 0px ${glow}` },
        { boxShadow: `0 0 26px ${glow}`, duration: 0.22, yoyo: true, repeat: 1, ease: 'power1.inOut' },
      )
    })
  }

  /** Retournement d'une Legend (FLIP). */
  function flipCards(instanceIds: string[]): void {
    const targets = instanceIds.map(element).filter((node): node is HTMLElement => !!node)
    if (targets.length === 0) return

    scope(() => {
      gsap.fromTo(
        targets,
        { rotateY: 90, scale: 0.9, opacity: 0.4 },
        { rotateY: 0, scale: 1, opacity: 1, duration: 0.5, ease: 'power3.out', stagger: 0.08 },
      )
    })
  }

  /** Attaque : l'assaillant se jette vers le camp adverse puis revient. */
  function attack(attackerInstanceId: string, side: BoardSide = 'me', targetInstanceId?: string | null): void {
    const attacker = element(attackerInstanceId)
    if (!attacker) return
    const target = targetInstanceId ? element(targetInstanceId) : null
    const thrust = side === 'me' ? -86 : 86

    scope(() => {
      const timeline = gsap.timeline()
      timeline.to(attacker, { y: thrust, scale: 1.06, duration: 0.16, ease: 'power2.in' })
      if (target) {
        timeline.to(
          target,
          {
            x: side === 'me' ? 14 : -14,
            boxShadow: `0 0 30px ${NEON.magenta}`,
            duration: 0.12,
            ease: 'power1.out',
          },
          '<0.12',
        )
        timeline.to(target, { x: 0, boxShadow: '0 0 0px rgba(0,0,0,0)', duration: 0.34, ease: 'power2.out' })
      }
      timeline.to(attacker, { y: 0, scale: 1, duration: 0.34, ease: 'power2.out' }, '<0.05')
    })
  }

  /** Unit vaincue : effondrement + flash rouge sur la zone de défausse. */
  function defeat(side: BoardSide): void {
    const trash = zone(side, 'trash')
    if (!trash) return

    scope(() => {
      gsap.fromTo(
        trash,
        { scale: 1, boxShadow: `0 0 0px ${NEON.magenta}` },
        { scale: 1.12, boxShadow: `0 0 22px ${NEON.magenta}`, duration: 0.18, yoyo: true, repeat: 1 },
      )
    })
  }

  /** Gig gagné/volé : les dés pulsent. */
  function gigPulse(side: BoardSide): void {
    const tracker = zone(side, 'gigs')
    if (!tracker) return

    scope(() => {
      gsap.fromTo(
        tracker,
        { scale: 1, filter: 'brightness(1)' },
        { scale: 1.09, filter: 'brightness(1.5)', duration: 0.24, yoyo: true, repeat: 1, ease: 'power2.inOut' },
      )
      const pips = tracker.querySelectorAll<HTMLElement>('[data-anim="gig-pip"]')
      if (pips.length > 0) {
        gsap.fromTo(pips, { y: -6, opacity: 0.2 }, { y: 0, opacity: 1, duration: 0.3, stagger: 0.03, ease: 'power2.out' })
      }
    })
  }

  /** Changement de phase : balayage de l'indicateur. */
  function phaseSweep(): void {
    const indicator = root.value?.querySelector<HTMLElement>('[data-anim="phase"]')
    if (!indicator) return

    scope(() => {
      gsap.fromTo(
        indicator,
        { xPercent: -18, opacity: 0.35 },
        { xPercent: 0, opacity: 1, duration: 0.4, ease: 'power3.out' },
      )
    })
  }

  /** Bannière « À ton tour » / « Tour adverse ». */
  function turnBanner(side: BoardSide): void {
    const banner = root.value?.querySelector<HTMLElement>('[data-anim="turn-banner"]')
    if (!banner) return
    const label = side === 'me' ? 'À ton tour' : "À l'adversaire"
    banner.textContent = label
    banner.dataset['side'] = side

    scope(() => {
      gsap.fromTo(
        banner,
        { opacity: 0, y: -14, scale: 0.96 },
        { opacity: 1, y: 0, scale: 1, duration: 0.32, ease: 'power3.out' },
      )
      gsap.to(banner, { opacity: 0, y: -10, duration: 0.4, delay: 1.25, ease: 'power2.in' })
    })
  }

  /** Pioche adverse : le dos de carte glisse vers la main cachée. */
  function opponentDraw(): void {
    const hand = zone('opponent', 'hand')
    if (!hand) return
    const last = hand.lastElementChild
    if (!last) return

    scope(() => {
      gsap.fromTo(last, { x: 70, opacity: 0, rotateZ: -8 }, { x: 0, opacity: 1, rotateZ: 0, duration: 0.4, ease: 'power2.out' })
    })
  }

  /** Libère les tweens en cours (démontage du plateau). */
  function destroy(): void {
    ctx?.revert()
    ctx = null
  }

  if (getCurrentScope()) onScopeDispose(destroy)

  return {
    reducedMotion,
    drawCards,
    playCards,
    flipCards,
    attack,
    defeat,
    gigPulse,
    phaseSweep,
    turnBanner,
    opponentDraw,
    destroy,
  }
}

export type GameAnimations = ReturnType<typeof useGameAnimations>
