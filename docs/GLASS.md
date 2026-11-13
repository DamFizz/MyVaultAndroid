# Liquid Glass on Android: how MyVault does it

This file covers the glass material, the motion system and the reasons behind them. The code is in
`app/src/main/kotlin/com/damfizz/myvault/ui/glass/`.

## Sources

- Apple, WWDC25 session 219 "Meet Liquid Glass" and session 356 "Get to know the new design
  system", plus the HIG pages *Materials* and *Adopting Liquid Glass*. We used these for the
  principles listed below. We copied no Apple code or assets.
- Kyant0/backdrop (Apache-2.0), as vendored in BitChord. We studied its technique, then wrote the
  shader ourselves: a circular edge profile along the rounded-rect SDF gradient, rim light from the
  SDF normal, and one compiled shader per surface.
- BitChord (GPL-3.0). We studied it for interaction ideas only: the pill lifts into glass on touch,
  stretches on acceleration and squashes on braking. **We copied no code, assets or branding.**
- Haze 2.1.0 / haze-glass (chrisbanes, Apache-2.0). We evaluated it and did not adopt it (see below).

## Principles we followed (from Apple)

| Apple | Here |
|---|---|
| Glass bends and concentrates light (*lensing*), it does not just blur | The AGSL lens refracts along a curved bevel; blur is small and only one ingredient |
| It materialises by modulating lensing, not by fading | `presence` uniform; sheets and alerts bring in refraction and rim as they rise |
| It responds instantly, "flexing and energizing with light"; the glow starts under the fingertip | `GlassInteraction`: energy spring, a touch-located inner glow, a brighter rim near the finger, gel flex, finger pull |
| Glass is for the navigation layer, not content | Vault tiles became solid fills with a touch highlight; glass stays on bars, buttons, sheets and alerts |
| Avoid glass on glass | `LocalInsideGlass`: a glass button placed on a sheet draws as a fill |
| Larger glass reads thicker | `GlassMaterials.Thick` for sheets and alerts: deeper bevel, more depth, deeper shadow |
| Regular vs Clear | `GlassMaterials.Regular`, `Clear` and `Lens` (the tab-bar lens) |
| Scroll edge effect | Content dissolves into the wallpaper under the floating bars |
| Reduce Transparency / Reduce Motion / Increase Contrast | All three are supported, from in-app settings or from the system (see below) |

## Rendering pipeline

`Backdrop` is a `GraphicsLayer` that re-records the real content behind the glass on every frame.

| API level | Glass rendering |
|---|---|
| 33+ | Blur, then the AGSL lens: refraction, edge-only dispersion, vibrancy, tint, rim and sheen, touch glow |
| 31–32 | Blur and saturation (RenderEffect); Compose draws the tint, rim and touch glow |
| 26–30 | A frosted, tinted panel with the same rim and touch glow (these versions have no live blur) |

Each surface compiles the shader once. Per frame it only updates the uniforms. A surface that
leaves the screen returns its compiled program to a small pool, so the next surface reuses it
instead of compiling again. The old engine created a new `RuntimeShader` on every frame.

## Motion

- Every animation is a spring (`GlassMotion`). Animatables keep their velocity when retargeted, so
  rapid taps, fast back gestures and reversals continue smoothly from where they are.
- **Tab bar.** At rest the selection is a flat pill. On touch it lifts into a clear lens that
  magnifies the bar's own icons. The lens:
  - follows a drag, and projects the fling velocity before snapping to the nearest tab;
  - stretches with speed;
  - sinks back into the flat pill after it lands.
- **Search.** The round button expands into a field across the top bar and absorbs the other
  buttons.
  - The keyboard appears only once the field is wide enough to type in.
  - Closing dismisses the keyboard first, then shrinks back. Back also closes it.
  - The query is cleared on close and on lock.
- **Screens.** Going deeper pushes in from the right; going back pops to the left.

## Privacy and security notes

- The backdrop is a live display list of the current frame. **No bitmap of a previous screen is
  kept.** That is the main reason we did not adopt Haze, whose default `KeepLastFrame` retention
  keeps the last frame around.
- **Locking removes vault content instantly**, with no fade-out. Before this change, the vault
  screen faded for about 140 ms after locking.
- Release builds keep `FLAG_SECURE`, so screenshots, screen recording and recents thumbnails are
  blocked.
- The glass draws only what is already on screen. Nothing is sent anywhere, and the glass code
  logs nothing.
- Passwords stay masked by default. Revealing a password is not animated through any cached layer.
- **None of this changes the vault format, the encryption or sync.** The `core` tests and the
  web-compatibility check still pass.

## Why not Haze?

We evaluated Haze 2.1.0 and haze-glass, and decided against adopting them:

- **Retention:** the default `KeepLastFrame` retention is a privacy risk in a password manager.
- **Fallback:** the fallback path drops refraction.
- **Platform backdrop:** it is experimental and needs a newer Compose than this app uses.

Our own engine was already the right shape. The problems were per-frame shader compilation and
the optics, and both are now fixed.

## Accessibility

| Setting | Effect |
|---|---|
| Motion (*System / Reduced / Full*) | *System* follows Android's "Remove animations". Reduced means no overshoot, flex, pull or stretch. |
| Reduce transparency | Frostier, more opaque glass with no refraction and no dispersion. The tab bar shows a plain pill. |
| Increase contrast | Solid outlines and a more opaque tint. Also follows the system contrast setting on Android 14+. |

## Limits

- **The optics are an approximation.** There is no real 3D thickness or caustics, and dispersion
  is a 3-tap shader effect.
- Below Android 12 there is no live blur.
- Frame timings were measured on an emulator, not on a phone. Real devices will differ.

## Measured frame timing (emulator, not a phone)

**Setup:**
- Emulator `Medium_Phone_API_36.1` (Android 16, host GPU).
- `benchmark` build type: release code, not debuggable.
- Numbers come from `dumpsys gfxinfo`.
- Scripted swipes and taps, run against the same demo vault.

**These numbers are noisy.** The same build varied from 5% to 10% jank on home-scroll between
runs, so treat small differences as noise.

**Quiet emulator, one run each.**
- Baseline = v0.2.2, before this work.
- Final = this version.
- Each cell is jank %, then frame-time percentiles p50 / p90 / p95 / p99.

| Scenario | Baseline | Final |
|---|---|---|
| Vault home scroll | 6.2%, 18 / 31 / 32 / 61 ms | 5.5%, 17 / 18 / 30 / 34 ms |
| Tab switching | 4.4%, 21 / 29 / 40 / 93 ms | 3.3%, 21 / 29 / 34 / 65 ms |
| Settings scroll | 3.4%, 17 / 18 / 18 / 18 ms | 3.4%, 17 / 18 / 20 / 30 ms |
| Search open/close ×4 (new) | – | 12.9%, 18 / 31 / 40 / 109 ms |

The search scenario includes the keyboard sliding in and out. That makes the whole screen lay out
again on every frame, which is most of its cost.

**Loaded emulator, three interleaved pairs.** The host was busy, so everything was slower. Each
cell gives the range across the three runs.

| Scenario | Baseline | After | Verdict |
|---|---|---|---|
| Vault home scroll | 210–233 frames rendered, jank 41–43%, p50 42–46 ms | 281–376 frames rendered, jank 26–33%, p50 32–36 ms | Consistently better: the tiles no longer run live blur |
| Tab switching | jank 16–20% | jank 18–21% | No clear difference |
| Settings scroll | jank 15–21% | jank 15–25% | No clear difference |

## Screenshots (debug build, demo vault)

| File | What it shows |
|---|---|
| `docs/shots/1-vault-home.jpg` | Glass bars, solid content tiles, scroll edge effect |
| `docs/shots/2-tab-lens.jpg` | Held tab: the lens lifted off the bar |
| `docs/shots/2b-tab-lens-drag.jpg` | Lens dragged between tabs: stretched, with edge refraction and dispersion |
| `docs/shots/3-search-open.jpg` | Search after the morph, with the keyboard up |
| `docs/shots/4-settings.jpg` | Motion, Reduce transparency and Increase contrast settings |
| `docs/shots/5-unlock-welcome.jpg` | The "Welcome, name" lock screen |
