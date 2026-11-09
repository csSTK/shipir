---
name: apple-design
description: Apple's approach to interface design and fluid, physical motion, translated for the web. Use when building or reviewing gesture-driven UI, spring animations, drag/swipe/sheet interactions, momentum and interruptible transitions, translucent materials and depth, typography (optical sizing, tracking, leading), reduced-motion, or the design foundations (feedback, spatial consistency, restraint) behind Apple-style interfaces.
---

# Apple Design

## Initial Response

When this skill is first invoked without a specific question, respond only with:

> I'm ready to help you build fluid, Apple-style interfaces on the web, my knowledge comes from Apple's WWDC design talks, translated for the web.

Do not provide any other information until the user asks a question.

How Apple builds interfaces that stop feeling like a computer and start feeling like an extension of you. This knowledge comes from Apple's WWDC design talks — chiefly _Designing Fluid Interfaces_ (WWDC 2018) — distilled and translated into the web platform (CSS, Pointer Events, `requestAnimationFrame`, spring libraries like Motion/Framer Motion).

The through-line: **an interface feels alive when motion starts from the current on-screen value, inherits the user's velocity, projects momentum forward, and can be grabbed and reversed at any instant.** Springs are the tool that makes all of this natural, because they are inherently interruptible and velocity-aware.

## The Core Idea

> "When we align the interface to the way we think and move, something magical happens — it stops feeling like a computer and starts feeling like a seamless extension of us."

An interface is fluid when it behaves like the physical world: things respond instantly, move continuously, carry momentum, resist at boundaries, and can be redirected mid-motion. Everything below is a way to get closer to that.

Apple frames design as serving four human needs: **safety/predictability, understanding, achievement, and joy.** Every rule here serves one of them.

## 1. Response — kill latency

The moment lag appears, the feeling of directness "falls off a cliff." Response is the foundation everything else is built on.

- **Respond on pointer-down, not on release.** Highlight a button the instant it's pressed. Waiting for `click`/touch-up to show feedback feels dead.
- **Be vigilant about every latency.** Audit debounces, artificial timers, transition waits, and the ~300ms tap delay. Anything on the input path that isn't essential is a regression.
- **Feedback must be continuous _during_ the interaction, not just at the end.** For a drag, slider, or drawer, update the UI 1:1 with the pointer the whole way through — never animate only when the gesture completes.

```css
/* Feedback lives on the press, and it's instant */
.button:active {
  transform: scale(0.97);
  transition: transform 100ms ease-out;
}
```

## 2. Direct manipulation — 1:1 tracking

> "Touch and content should move together."

When the user drags something, it must stay glued to the finger — and respect the offset from _where they grabbed it_. Snapping to the element's center on grab breaks the illusion immediately.

- Use Pointer Events with `setPointerCapture` so tracking continues even when the pointer leaves the element's bounds.
- Track a short **velocity/position history** (last few `pointermove` events), not just the current point — you'll need velocity at release.

```js
el.addEventListener('pointerdown', (e) => {
  el.setPointerCapture(e.pointerId);
  const grabOffset = e.clientY - el.getBoundingClientRect().top; // respect where they grabbed
  // ...track position + timestamp history for velocity
});
```

## 3. Interruptibility — the single most important principle

> "The thought and the gesture happen in parallel."

Every animation must be interruptible and redirectable at any moment. A user must be able to grab a moving element mid-flight and reverse it without waiting for the animation to finish. A closing modal the user grabs again should follow the finger — not finish closing first, then reopen.

- **Never lock out input during a transition.**
- **Always animate from the _presentation_ (current) value, never the target value.** On interrupt, read the element's live on-screen transform and start the new animation from there. Starting from the logical/target value causes a visible jump.
- **Avoid CSS transitions and `@keyframes` for anything gesture-driven** — they can't be smoothly grabbed and reversed mid-flight. Springs animate from the current value by default, which is exactly what interruption needs.
- **When a gesture reverses, blend velocity — don't hard-cut it.** Replacing one animation with another at a reversal creates a velocity discontinuity, a "brick wall." Spring libraries that carry velocity through a re-target avoid it. (This is what iOS's _additive animations_ do natively; on the web, choose a spring library that re-targets from the current velocity.)
- **Decompose 2D motion into independent X and Y springs.** A single spring on a 2D distance desyncs when X and Y have different velocities.

## 4. Behavior over animation — use springs

> "Think of animation as a conversation between you and the object, not something prescribed by the interface."

A pre-scripted, fixed-duration animation can't respond to new input. A spring can — new input just changes the target, and the motion stays continuous. Reach for springs for anything a user can touch.

Apple deliberately replaced the physics triplet (mass/stiffness/damping) with two designer-friendly parameters. Think in these:

- **Damping ratio** — controls overshoot. `1.0` = critically damped, no bounce, smooth settle. `< 1.0` = overshoots and oscillates. Lower = bouncier.
- **Response** — how quickly the value reaches the target, in seconds. Lower = snappier. **This is not "duration"** — a spring has no fixed duration; its settle time emerges from the parameters.

**Defaults:**

- Start most UI at **damping `1.0`** (critically damped) — graceful and non-distracting.
- Add bounce (**damping ~`0.8`**) **only when the gesture itself carried momentum** (a flick, a throw, a drag release). Overshoot on a menu that just faded in feels wrong; overshoot on a card you flicked feels right.

**Concrete values Apple ships:**

| Interaction                  | Damping | Response |
| ---------------------------- | ------- | -------- |
| Move / reposition (e.g. PiP) | `1.0`   | `0.4`    |
| Rotation                     | `0.8`   | `0.4`    |
| Drawer / sheet               | `0.8`   | `0.3`    |

**Web mapping (Motion / Framer Motion):** the `bounce` + `duration` spring API maps closely to Apple's damping + response. A safe house style is `damping: 1.0` springs everywhere by default; reserve bounce for momentum-driven, physical interactions.

```js
import { animate } from 'motion';

// Critically damped default (no overshoot)
animate(el, { y: 0 }, { type: 'spring', bounce: 0, duration: 0.4 });

// Momentum interaction — a little bounce, only because a flick preceded it
animate(el, { y: target }, { type: 'spring', bounce: 0.2, duration: 0.4 });
```

## 5. Velocity handoff — the seam between drag and animation

When a gesture ends, the animation must **continue at the finger's exact velocity**, so there's no visible seam between dragging and animating. This is the detail that most separates "fluid" from "fine."

Pass the pointer's release velocity as the spring's initial velocity. Some spring APIs want **relative** velocity — normalize it by the remaining distance to the target:

```
relativeVelocity = gestureVelocity / (targetValue − currentValue)
```

Example: element at `y=50`, target `y=150` (100px to go), finger moving 50px/s → initial spring velocity = `50 / 100 = 0.5`. Framer Motion / Motion take absolute px/s velocity directly (`velocity` option), so you usually hand it the raw value.

## 6. Momentum projection — animate to where the gesture is _going_

> "Take a small input and make a big output."

Don't snap to the nearest boundary from the _release point_. Use velocity to **project the resting position** — exactly like scroll deceleration — then snap to the target nearest that projected point. This is what makes a flick feel like it throws the element.

Apple's exact projection function (from the _Designing Fluid Interfaces_ sample code):

```js
// decelerationRate ≈ 0.998 for normal scroll feel; 0.99 for snappier
function project(initialVelocity /* px/s */, decelerationRate = 0.998) {
  return ((initialVelocity / 1000) * decelerationRate) / (1 - decelerationRate);
}

const projectedEndpoint = currentPosition + project(releaseVelocity);
const target = nearestSnapPoint(projectedEndpoint); // choose target from the projection
animateSpringTo(target, { velocity: releaseVelocity }); // then hand off velocity (§5)
```

Note: the physics-textbook `v²/(2·decel)` is _not_ what Apple ships — use the exponential-decay form above. This is the standard behavior in good bottom-sheets and carousels (Vaul, Embla).

## 7. Spatial consistency — symmetric paths, anchored origins

> "If something disappears one way, we expect it to emerge from where it came."

- **Enter and exit along the same path.** A panel that slides in from the right must dismiss to the right. In-from-right / out-the-bottom feels disconnected and confusing.
- **Anchor interactions to their source.** A menu, popover, or sheet should originate from the element that triggered it — set `transform-origin` to the trigger, so the spatial relationship between button and content is obvious. (This is the same origin-awareness point as popovers scaling from their trigger, not their center.)
- **Mirror the easing on reversible transitions** so the outbound path matches the return path (use inverse cubic-bézier control points for the two directions).

## 8. Hint in the direction of the gesture

Humans predict a final state from a trajectory. Intermediate motion should telegraph where things are going — Control Center modules "grow up and out toward your finger." Make the in-between frames point at the outcome, not just interpolate blindly to it.

## 9. Rubber-banding — soft boundaries

At an edge, resist progressively instead of stopping hard. A hard stop reads as "frozen"; continuous resistance reads as "responsive, but there's nothing more here." Apply damping that increases the further past the boundary the user drags.

```js
// The further past the bound, the less the element follows — real things slow before they stop
function rubberband(overshoot, dimension, constant = 0.55) {
  return (
    (overshoot * dimension * constant) /
    (dimension + constant * Math.abs(overshoot))
  );
}
```

## 10. Gesture design details (the "feel" checklist)

- **Tap:** highlight on touch-_down_ (instant), commit on touch-_up_. Add ~10px of hysteresis/hit padding around the target, and allow cancel-by-dragging-away and back.
- **Drag/swipe:** require a small movement threshold (hysteresis, ~10px) before committing to a direction, then track 1:1.
- **Detect all plausible gestures in parallel from the first move**, then confidently cancel the losers once intent is clear. Avoid recognizers that only report a _final_ state (`swipeleft`-type events) — they throw away the continuous tracking you need for feedback.
- **Minimize disambiguation delays.** Double-tap detection unavoidably delays single taps; only pay that cost where double-tap truly exists.

## 11. Frame-level smoothness

Smoothness is about _what's in the frames_, not just the frame rate.

- Keep the per-frame positional change below the perception threshold to avoid strobing.
- For very fast motion, a subtle **motion blur / stretch** encodes speed and reads better than a hard sharp streak.
- `requestAnimationFrame` is the web's display-synced clock (Apple uses `CADisplayLink`). Animate only compositor-friendly properties — `transform` and `opacity` — and hint with `will-change` where motion is imminent.

## 12. Materials & depth — translucency conveys hierarchy

Apple uses translucent materials as a floating functional layer that brings structure without stealing focus. On the web, approximate with `backdrop-filter`.

- **Build nav/toolbars/sheets as translucent layers** (`backdrop-filter: blur()` + a semi-transparent background) with content scrolling underneath — not opaque bars that consume a fixed strip.
- **Material weight encodes hierarchy:** darker/heavier materials separate structural regions (sidebars); lighter materials draw attention to interactive elements (buttons). **Never stack a light translucent surface on another** — legibility collapses.
- **Bigger surfaces should read as thicker:** stronger blur + a deeper shadow than small chips. Consider context-aware shadow — heavier over busy/text content for separation, lighter over plain backgrounds.
- **Dim to focus, separate to keep flow.** A modal task pairs the surface with a dimming scrim and pushes the background back/down. A parallel, non-blocking panel uses translucency and offset _without_ a scrim so the flow isn't broken. For stacked sheets, progressively dim and push back each parent layer.
- **Vibrancy keeps text legible over changing backgrounds.** Over blurred/translucent surfaces, don't use flat gray text — use higher-contrast, slightly heavier weight, and a small letter-spacing bump. Put color on a solid layer, not the translucent foreground.
- **Scroll edge effects, not hard dividers.** Instead of a 1px border under a sticky header, fade a small blur/gradient mask where content meets floating chrome — only where floating UI actually overlaps content.
- **Materialize, don't just fade.** For glass/blur surfaces, animate blur radius and scale together on enter/exit, so the surface reads as a real material arriving rather than a plain opacity fade.

```css
.toolbar {
  background: rgba(255, 255, 255, 0.6);
  backdrop-filter: blur(20px) saturate(180%);
  border-top: 1px solid rgba(255, 255, 255, 0.4); /* bright top edge = light catching the material */
}
```

## 13. Multimodal feedback — motion + sound + haptics

Three rules for combining senses (from _Designing Audio-Haptic Experiences_):

1. **Causality** — it must be obvious what caused the feedback. Trigger it on the actual causal event (the toggle flipping, the item snapping home), and match its character to the action's physicality.
2. **Harmony** — the visual, the sound, and the haptic must fire on the **same frame**. Latency between them destroys the illusion. Don't let a CSS transition lag the audio/haptic (Vibration API).
3. **Utility** — add feedback only where it earns its place. Reserve haptics/sound for meaningful moments (success, error, commit, snap). Over-feedback trains users to ignore all of it.

## 14. Reduced motion & accessibility

Reduced motion doesn't mean _no_ feedback — it means a gentler, non-vestibular equivalent. Respond to three independent signals and bake them into your components:

- **`prefers-reduced-motion: reduce`** — replace slides/springs/parallax with short opacity **cross-fades or static transitions**. Drop elastic/overshoot. Keep opacity/color changes that aid comprehension.
- **`prefers-reduced-transparency: reduce`** — make translucent surfaces frostier/solid: raise background opacity, drop the blur.
- **`prefers-contrast: more`** — near-solid backgrounds with a defined, contrasting border.

Also: avoid full-viewport moving backgrounds, slow looping oscillations (near 0.2 Hz / one cycle per 5s), and abrupt brightness jumps (ease dark↔light theme changes). Make large moving objects semi-transparent while they travel, and fade big surfaces out during a large reposition and back in once settled.

```css
@media (prefers-reduced-motion: reduce) {
  .sheet {
    transition: opacity 200ms ease;
    transform: none !important;
  }
}
@media (prefers-reduced-transparency: reduce) {
  .toolbar {
    background: white;
    backdrop-filter: none;
  }
}
```

## 15. Typography — optical sizing, tracking, leading

Apple designs type to change shape with size; the same discipline applies on the web. (From _The Details of UI Typography_, WWDC 2020.)

- **Tracking (letter-spacing) is size-specific — never one value for all sizes.** Large display text wants _negative_ tracking (letters read too far apart as they grow); small text wants slightly _positive_ tracking for legibility. A fixed `letter-spacing` is wrong somewhere. Tighten headings, leave body near `0`.
- **Leading (line-height) tracks size inversely.** Tight on large headings, looser on body copy. Increase it for scripts with tall ascenders/descenders; tighten it for dense, information-heavy UI.
- **Build hierarchy from weight + size + leading as a set,** not size alone. Emphasize with weight — it adds presence without taking more space.
- **Respect the user's text-size setting** (Dynamic Type). Scale layout _with_ the text — spacing in `rem`/`em`, not fixed px — so a larger font doesn't break the layout.
- **Default to the platform's system font** before a custom face; it already ships optical sizing, tracking tables, and legibility tuning. Override only with a reason.

```css
:root {
  font:
    100%/1.5 system-ui,
    sans-serif;
} /* body: system font, comfortable leading */

.display {
  font-size: clamp(2rem, 5vw, 4rem);
  line-height: 1.05; /* tight leading for large text */
  letter-spacing: -0.02em; /* negative tracking as it grows */
  font-optical-sizing: auto;
}
```

## 16. Design foundations — the eight principles

The motion and craft above serve Apple's eight design principles (_Principles of Great Design_, WWDC 2026). Use these as the names you reason with:

1. **Purpose.** Make with intention; decide what _not_ to build. Every feature asks for the user's time, attention, and trust — spend that budget only where it pays off.
2. **Agency.** Keep people in control: offer choices, don't force a single path. Back it with forgiveness — easy undo for slips, a confirmation dialog only for genuinely destructive, irreversible actions (use sparingly; overusing it trains people to click through).
3. **Responsibility.** Act in the user's interest. Privacy: ask at the right moment, only for what's needed, transparently. Safety: anticipate misuse and harm — especially with AI (an allergy-aware recipe app must not suggest a harmful ingredient). Add previews, confirmations, disclaimers; cut a feature whose risk outweighs its value.
4. **Familiarity.** Build on what people already know. Use metaphors that are neither too literal nor too abstract (a trash can means delete), and honor their physics. Be consistent: things that look the same must behave the same and live in the same place (close is always top-left on macOS) so people can predict what happens next. Only break a familiar pattern if you can prove it's better — then test it, don't assume.
5. **Flexibility.** Design for different contexts, devices, and the full range of abilities. Adapt to the platform (iPhone = quick touch; desktop = deep workflows with precise pointer control) and to the situation. Design inclusively (age, language, expertise, accessibility). When no single layout fits everyone, let people personalize — rearrange controls, hide what they don't use.
6. **Simplicity — not minimalism.** Strip the unnecessary so the core purpose shines; burying everything in one place looks minimal but isn't simple. Be concise (plain language, no jargon, fewer steps) and clear (use hierarchy — order, spacing, contrast — so the most important thing is the most obvious). Every element earns its place; sometimes _adding_ context simplifies (a video scrubber that shows time remaining). Show the common path first, advanced options one level deeper.
7. **Craft.** Uncompromising attention to detail builds trust. Beautiful typography, colors that adapt to light/dark, clear iconography, and responsive animations that give immediate, natural feedback. Nothing is random — every spacing, timing, and alignment value is a deliberate choice you can defend. Jittery scroll, misaligned icons, and layouts that break on rotation read as carelessness. Craft needs iteration and longevity — keep evolving the design as features and hardware change.
8. **Delight.** The result of getting the other seven right, not confetti tacked on top. Decide the emotion you want people to feel (calm, confident, excited) and reinforce it in every decision.

Tactical rules that serve these:

- **Feedback comes in four kinds:** status, completion, warning, error. Confirm meaningful actions, expose ongoing status, warn before problems, validate inline (not on submit).
- **Wayfinding.** Every screen should answer: Where am I? Where can I go? What's there? How do I get out? Never trap the user.
- **Grouping & mapping.** Proximity implies relationship; place a control near what it affects and arrange controls to mirror what they change. If you need a label to explain a control, the mapping is weak.
- **Direct, specific labels beat safe generic ones.** Name nav items for their contents ("Progress", "Library"), not vague umbrellas ("Home"). Specificity creates predictability.

## 17. Process

- **Prototype interactively — an interactive demo is worth "a million static designs."** You discover the interface by building and playing with it; a working prototype also sets a concrete bar that prevents a mediocre final implementation.
- **Design interaction and visuals together.** "You shouldn't be able to tell where one ends and the other begins." Motion is not a layer added after the pixels.
- **Test with real people in real context**, and review motion with fresh eyes — play it in slow motion / frame-by-frame to catch what's invisible at full speed.

## Quick Reference

| Need                        | Technique                            | Concrete value                                       |
| --------------------------- | ------------------------------------ | ---------------------------------------------------- |
| Default UI spring           | Critically damped, no overshoot      | `damping 1.0`, `response 0.3–0.4`                    |
| Momentum / flick spring     | Under-damped, slight bounce          | `damping ~0.8`, `response 0.3–0.4`                   |
| Gesture → spring velocity   | Hand off release velocity            | `gestureVelocity / (target − current)` if normalized |
| Flick landing point         | Project momentum                     | `current + (v/1000)·d/(1−d)`, `d ≈ 0.998`            |
| Interrupt cleanly           | Start from presentation (live) value | read the on-screen transform                         |
| Avoid reversal "brick wall" | Carry velocity through re-target     | spring that blends velocity                          |
| Reversible transition       | Mirror the easing curve              | inverse cubic-bézier                                 |
| Decide reverse vs. commit   | Use velocity **sign**, not position  | at release                                           |
| 1:1 drag                    | Pointer Events + capture             | respect the grab offset                              |
| Feedback                    | On pointer-down, continuous          | never only at the end                                |
| Boundary                    | Rubber-band, don't hard-stop         | progressive resistance                               |
| Translucent chrome          | `backdrop-filter` layer              | content scrolls under                                |
| Type tracking               | Size-specific, never fixed           | tighten large text (`-0.02em`), body near `0`        |
| Reduced motion              | Cross-fade, not slide/spring         | `@media (prefers-reduced-motion)`                    |

## Shipir visual language

Applies to all Shipir UI. Tokens live in `frontend/src/shared/styles/tokens.css` (base element rules in `base.css`, both imported by `src/index.css`). **Feature CSS uses `var(--token)` only: no hex/rgb colors, no raw px/rem font sizes, radii, shadows or durations.** Tone: friendly and approachable, not techie; generous space, soft corners, color where it carries meaning.

**Tokens**
- Color: `--color-bg/surface/surface-raised/surface-sunken/surface-hover`, `--color-border(-strong)`, `--color-text/-muted/-subtle`, `--color-accent(-hover/-text/-soft)` + `--color-on-accent`, semantic `--color-{ok,warn,bad,info}` (text), `-soft` (background tint), `-solid` (dots, bars, icons, fills). Light default; dark via `prefers-color-scheme`, `[data-theme='light'|'dark']` on `<html>` overrides.
- Categorical colour (service colours in logs, disk usage segments, legends): one palette `--color-series-1..8` (light and dark values, text-safe on sunken surfaces). Use in order; never invent a second set. Not colour-blind safe by design: under simulated protanopia (Machado 2009, severity 1.0, CIE76 ΔE) dark `series-1` and `series-4` nearly merge (ΔE ≈ 2; light ≈ 16), so never tell them apart by colour alone and never claim colour-blind safety for the palette. Colour is never the only signal (names/labels always shown); with more than 8 series colours repeat. Chart lines use `--chart-N`.
- Type (6 steps, `--text-<step>-{size,tracking,leading}`): caption 12, small 13, body 15, title 18, heading 24, display 32 px. Weights 400/500/600/700. Large steps tighten tracking, body is near 0. Mono: `--font-mono` for IDs, logs, paths.
- Space: `--space-1..8` = 4, 8, 12, 16, 24, 32, 48, 64 px (in rem). `--space-half` = 2px (badge/switch insets), `--border-width` = 1px (negative margins that overlap a border), layout sizes `--content-max/-narrow`, `--panel-max-height`. A disk-based test (`tokenUsage.test.ts`) rejects raw px spacing/radii/max-size and `currentColor`/`AccentColor` in all feature CSS. Default gap between related items `--space-3`, between groups `--space-5`, page padding `--space-5`/`--space-6`. Err on the airy side.
- Radii: `--radius-sm` (controls, badges), `--radius-md` (cards, menus), `--radius-lg` (dialogs, large panels), `--radius-pill`.
- Shadow: `--shadow-sm` cards at rest, `--shadow-md` menus/popovers, `--shadow-lg` dialogs, `--shadow-focus` ring. Overlay `--color-overlay`, translucent chrome `--color-material` + `--blur-material` (opaque under `prefers-reduced-transparency`).
- Z: `--z-sticky/nav/dropdown/modal`.
- Motion: press feedback via CSS `transform: scale(var(--press-scale))` over `--duration-press`; hover/color `--duration-fast`/`--duration-base` with `--ease-out`. Anything the user can touch or that enters/leaves (menus, dialogs, sheets, palette) uses springs via `motion`: **snappy** (`stiffness 520, damping 38`) menus, popovers, press; **smooth** (`stiffness 300, damping 30`) dialogs, sheets, layout; **gentle** (`stiffness 180, damping 24`) page and list transitions. No bounce on destructive or dense UI. Reduced motion: `base.css` collapses CSS durations; spring users must check `useReducedMotion` and cross-fade.

**When to use color**
- Neutral surfaces carry the layout; accent marks the one primary action per view, the current nav item, links and focus.
- Semantic color only for state (running/ok, warning, error, info), shown as a soft tinted badge with `-solid` dot, never as the only signal (add text or icon).
- Soft tints (`*-soft`) for backgrounds of status and empty states; avoid saturated large areas.
- Text on a color uses the matching text token (`--color-ok` on `--color-ok-soft`), never `-solid` for text.

**Density and layout**: control height `--control-height`, cards padded `--space-5`, table rows `--space-3` vertical padding; content max width `--content-max`. Prefer fewer, larger targets over compact grids; touch targets at least 40px.

**Icons**: one line-icon set at one stroke weight, 16px inline with text / 20px in nav and buttons, colored with `currentColor`; icon-only buttons need `aria-label`. Use an icon next to a status color so state is not color-only.

**Tables**: no heavy grid lines, only a `--color-border` row separator; header in caption size, `--color-text-muted`, medium weight; row hover `--color-surface-hover`; numbers right-aligned and tabular; IDs in mono; status as badge; row actions grouped on the right, secondary ones in a menu; empty, loading (skeleton) and error (with Retry) states are always designed.

**Checks before done**: light and dark both looked at; text tokens keep contrast AA (4.5:1) on the surface they sit on; `prefers-reduced-motion` and `prefers-reduced-transparency` handled; visible `:focus-visible` ring.

**Hard floor** (adapted from the Hallmark design skill; the rules above cover colors, fonts, focus and reduced motion, these are the additions):
- Never `transition: all`; list the properties. Never animate `width`, `height`, `top` or `left`; use `transform` and `opacity`.
- No `100vw` for layout (it includes the scrollbar and causes horizontal scroll). Grid tracks that hold images, tables or long text use `minmax(0, 1fr)`; long headings and names carry `overflow-wrap: break-word; min-width: 0`.
- No card inside a card; nest with spacing or a `--color-surface-sunken` area instead.
- No emoji as icons; use the icon set. No purple-to-blue gradients.
- Never invent numbers, testimonials or sample data that looks real in shipped UI; empty states say what is missing.
- Focus rings appear instantly (no transition on `:focus-visible`).
