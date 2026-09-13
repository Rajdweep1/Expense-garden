# The Design Pass — Chrome That Matches the Garden: Design

**Status:** drafted 2026-09-13 from a live UI audit on `Pixel_8_API_35`, pending approval.

**Goal:** Give the app's chrome the same deliberateness the island already has — one colour
system derived from `GardenPalette`, window insets handled correctly, and status colours that
mean what they say — so the surfaces around the garden stop being Material 3 factory defaults.

**Not a rewrite.** Every screen keeps its layout, its copy and its behaviour. This changes what
those screens are painted with, and where their edges sit.

---

## 1. What the audit found

Measured on a booted emulator (1080×2400 @ 420dpi, SDK 35, hardware GL), not inferred from
source. Raw framebuffer sampling, so every colour below is a rendered pixel.

| # | Finding | Evidence |
|---|---|---|
| 1 | **Status bar glyphs are invisible** | Glyphs render `#FFFFFF`. On dashboard surface `#FEF7FF` that is **1.05:1**. Over the home sky `#ACBAC4` it is **1.99:1**. WCAG floor for UI is 3:1. |
| 2 | **Nav bar occludes the primary actions** | 3-button nav inset `[0,2274][1080,2400]`. Both FABs span y 2211–2357. **84 of 147px — 57% of each button — is behind the system bar.** On gesture nav they still reach 21px into the inset. |
| 3 | **Dark mode is ignored** | `cmd uimode night yes` → surface stays `#FEF7FF`. `isSystemInDarkTheme()` appears nowhere in the codebase. |
| 4 | **Home breaks at font scale 2.0** | Three ways: greenhouse/settings row overlaps the stats strip by **21px**; the strip's two texts collide; "Log manually" wraps to two lines while "Scan & pay" does not, so the FABs render 98px and 196px tall. |
| 5 | **The chrome is Material baseline purple** | FAB container `#EADDFF`, FAB label `#21005D`, "Log it" `#6750A4`, surface `#FEF7FF`. Decoded from `material3-android:1.3.0`: `PaletteTokens.Primary40 = #6750A4`, `Primary90 = #EADDFF`, `Primary10 = #21005D`. Exact match — nothing is themed. |
| 6 | **Budget states are indistinguishable** | `severityColor()` maps OK→`primary` `#6750A4` and PACE_WARNING→`tertiary` `#7D5260`. Two desaturated purples, shown as a 4dp bar. |
| 7 | **Stock launcher icon** | `android:icon="@android:drawable/sym_def_app_icon"`. |

Findings 1–4 are defects. 5–7 are the absence of design.

## 2. Root cause: two files that were never written

`res/values/` **does not exist**. There is no `themes.xml`, no `colors.xml`.
`MainActivity.kt:56` is `MaterialTheme { }` with no arguments, which resolves
`LocalColorScheme` to its default `lightColorScheme()` — the baseline purple — in every
composition, in both light and dark system modes.

Meanwhile `GardenPalette.kt` holds ~50 hand-picked colours: three sky gradients keyed by
weather, grass, ocean, soil, five petal shades, wood. The palette exists. It just stops at the
canvas boundary.

The manifest compounds it: `android:theme="@android:style/Theme.Material.Light.NoActionBar"` is
the *platform* Material theme from Android 5.0. It predates `windowLightStatusBar`, which is
exactly why finding 1 happens on every screen.

So: one Compose theme file and one Android theme file close findings 1, 3, 5 and 6 outright.

## 3. The colour system

### 3.1 Where the colours come from

The scheme is **derived from `GardenPalette`, not invented**. Each role names its source so a
future change to the garden's look can be traced into the chrome.

| Role | Light | Source |
|---|---|---|
| `primary` | `#3F7A33` | `hedgeDark #4F9140` darkened for 4.5:1 on white |
| `onPrimary` | `#FFFFFF` | |
| `primaryContainer` | `#C7E7AE` | `grassA #A7DD7F` lightened |
| `onPrimaryContainer` | `#12300B` | |
| `secondary` | `#7C5233` | `wallLeft` — the soil/wood family, verbatim |
| `onSecondary` | `#FFFFFF` | |
| `tertiary` | `#2F6C8C` | `sky SUNNY #8FD3FF` darkened for contrast |
| `error` | `#A4342B` | a warmed red, nearer `mushroomCap` than Material's `#B3261E` |
| `surface` / `background` | `#FAF8F2` | warm paper, replacing lavender `#FEF7FF` |
| `onSurface` | `#1C1B17` | warm near-black |
| `surfaceVariant` | `#E7E3D6` | |
| `onSurfaceVariant` | `#4A4739` | |
| `outline` | `#7C7767` | |

Dark is the same garden at dusk: `surface #14171A`, `primary #9BD374` (`grassB` verbatim — a
light green reads correctly on dark), `onPrimary #11300A`, `surfaceVariant #2A2E28`.

### 3.2 The canvas does not follow the theme

**Decision:** `GardenCanvas` keeps its own palette in both light and dark mode. The island is a
window into a world with its own weather and time of day; it is not an app surface. A "dark
mode garden" is a feature (night weather), not a theming concern, and conflating them would put
the chrome in charge of the world's sky.

Consequence: the home stats strip floats over the canvas, so it stays a **light** scrim in both
modes. It is painted against grass, not against `surface`. This is deliberate and is the one
place where a chrome element ignores the scheme.

### 3.3 Semantic status colours

Material 3 has no slot for "on pace / warning / over", which is why they were borrowed from
`primary` and `tertiary` and ended up meaning nothing. Add a small extension carried on its own
composition local:

```kotlin
@Immutable
data class GardenStatusColors(val onPace: Color, val warning: Color, val over: Color)
```

| State | Light | Dark |
|---|---|---|
| `onPace` | `#3F7A33` green | `#9BD374` |
| `warning` | `#8F6300` amber | `#E3B057` |
| `over` | `#A4342B` red | `#FF8A80` |

`severityColor()` in `DashboardScreen.kt` reads from this instead of the scheme. Green / amber /
red is the one colour convention a budget app may assume its user already knows.

Note the dark `over`: Material's stock dark error is `#F2B8B5`, a pale pink whose chroma is 22.7
— below the floor in §3.4. `#FF8A80` is used instead because it reads as *red* on a dark surface
rather than as a desaturated blush.

### 3.4 Three assertions, and what each one actually catches

Every colour the theme declares is checked by a **JVM unit test**, not a review step.
`ContrastMath` is pure Kotlin over `Int` RGB — no `android.graphics`, no Compose `Color` — so it
runs without an emulator.

| Assertion | Floor | What it catches |
|---|---|---|
| **Contrast** vs its background | 4.5:1 text, 3:1 UI | Finding 1. White glyphs on `#FEF7FF` score **1.05:1** and fail loudly. |
| **Chroma** (CIE Lab) of a status colour | ≥ 25 | Finding 6. The old `tertiary` `#7D5260` has chroma **20.1** — it is nearly grey, which is *why* it read as nothing. This is the assertion that catches it. |
| **Hue separation** between status colours | ≥ 30° | Guards against the triad drifting into one hue family later. |

**Being honest about the limits.** Three metrics were tried against the actual defect before
settling on these:

- *Contrast ratio between the two status colours* — rejected. It measures luminance, so it
  scores green-vs-amber at **1.11:1** and would fail a perfectly readable pair.
- *CIE76 ΔE* — rejected. The broken `#6750A4`/`#7D5260` pair scores **ΔE 42.5**, comfortably
  above any sane threshold, so it would have passed the bug.
- *Hue separation alone* — insufficient. That same broken pair is **53.1°** apart and passes.

Only the chroma floor catches the real defect. No single number proves "these two colours
communicate different states"; the chroma floor proves the weaker, checkable thing — that a
status colour is saturated enough to read as a colour at all. The deeper fix is structural:
`primary` and `tertiary` are *brand* slots and were never status slots. Having dedicated status
colours is the fix; these assertions only stop them from rotting.

The numbers in §3.1 and §3.3 are verified against these floors, not proposed.

## 4. The inset rule

One rule, one exception.

**Rule:** every screen's root container uses `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)`.
`safeDrawing` is the union of system bars, display cutout **and** IME, so it replaces both
`statusBarsPadding()` and `imePadding()` and cannot double-pad. This closes finding 2 for
Dashboard, Greenhouse, Settings and Entry.

**Exception — home.** The canvas must bleed to all four edges; insetting it would letterbox the
island. So `GardenHomeScreen`'s `Box` and the `GardenCanvas` inside it stay edge-to-edge, and
the *floating chrome* is inset individually:

- top chrome (strip + nav row): `safeDrawing.only(Top + Horizontal)`
- bottom chrome (digest, pending card, FABs): `safeDrawing.only(Bottom + Horizontal)`

`Horizontal` is included for landscape and cutouts, which nothing currently handles.

## 5. The window theme

Create `res/values/themes.xml` and `res/values-night/themes.xml` with a real app theme, and
point the manifest at it. Transparent system bars (required anyway under targetSdk 35's
edge-to-edge enforcement) plus a window background matching `surface`, so the launch window no
longer flashes `#FAFAFA` before Compose draws.

### Status bar icons follow what is behind them

A single XML value cannot be right on every screen: in dark mode the dashboard is dark (wants
light icons) while home still shows a bright daytime sky (wants dark icons). So the XML theme
sets the sensible default and each screen declares its own:

```kotlin
@Composable fun LightStatusBars(light: Boolean)   // WindowCompat, from androidx.core-ktx
```

- Home: `LightStatusBars(true)` **always** — the sky is always light.
- Every other screen: `LightStatusBars(!isSystemInDarkTheme())`.

No new dependency: `WindowCompat` is already available via `androidx.core.ktx`.

## 6. Launcher icon

An adaptive icon, `mipmap-anydpi-v26` only — `minSdk 26` means the legacy fallback is dead code.
Three layers: `background` a flat garden green, `foreground` a vector sprout, `monochrome` the
same path so Android 13+ themed icons work.

Drawn as a `VectorDrawable` path, not raster: no new art pipeline, no asset-size cost, and it
scales to every density for free. It is a **mark, not a masterpiece** — a two-leaf sprout — and
is explicitly replaceable later from the FLUX pipeline that made the plant sprites.

## 7. Layout robustness

Finding 4 is three separate bugs and gets three separate fixes:

1. **The 64dp magic number.** `GardenHomeScreen.kt:129` positions the greenhouse/settings row at
   a hardcoded `top = 64.dp` to clear the strip above it. Replace absolute positioning with a
   single `Column` aligned `TopCenter` holding the strip *and* the nav row, so they stack. The
   magic number disappears rather than growing.
2. **The strip's texts collide.** `Arrangement.SpaceBetween` with two unbounded `Text`s. Give
   the right-hand `Text` `Modifier.weight(1f)` with `TextAlign.End`; it then wraps inside its
   own slot instead of overrunning the left one.
3. **Mismatched FABs.** Give both `ExtendedFloatingActionButton`s `Modifier.weight(1f)` in their
   `Row` so they are always equal width, and wrap or not as a pair.

## 8. Progress bars

`DashboardScreen.kt:195` uses the lambda-progress `LinearProgressIndicator`, which draws exactly
what it is handed — so budget bars snap while the money figure directly above them does a spring
odometer roll. Wrap the fraction in `animateFloatAsState` with the same spring the odometer
uses, so two adjacent widgets stop disagreeing about whether this app animates.

## 9. What this deliberately does not do

| Deferred | Why |
|---|---|
| **Redesign the gate dialog** | The gate is the product's thesis, and "which button gets emphasis" is a behavioural decision about loss aversion, not a styling one. It needs its own brainstorm. A stock dialog painted in garden colours is honest in the meantime. |
| **FAB hierarchy (scan vs manual)** | The spec's acceptance bar says scan *is* the product, so the two actions arguably should not look identical — but changing which action looks primary is Rajdweep's call, not a design-pass side effect. |
| **Typography** | Roboto is neutral and legible. A display face is a real design commitment with asset weight behind it; the purple is the problem, not the letterforms. |
| **Shimmer skeletons** | `DashboardScreen.kt:71` fades its skeleton and `GreenhouseScreen.kt:65` does not. Worth making consistent, not worth building a shimmer for. The plan makes them match and stops there. |
| **A `Scaffold` / `TopAppBar` refactor** | Every screen hand-rolls `Row { TextButton("← garden"); Text(title) }`. It is consistent and it works. Introducing `Scaffold` now would touch every screen for no user-visible gain beyond what §4 already fixes. |

## 10. Testing

**JVM (`app/src/test`)** — everything here is Android-free by construction:

- `ContrastMathTest` — WCAG relative luminance, contrast ratio and CIE Lab chroma/hue against
  known values (black/white = 21:1, a colour against itself = 1:1, pure grey has chroma ≈ 0).
- `GardenThemeTest` — every declared `onX`/`X` pair in both schemes clears its §3.4 contrast
  floor. This is the test that would have caught finding 1 before it shipped.
- `StatusColorTest` — each `Severity` maps to a distinct status colour; all three clear chroma
  ≥ 25 and are ≥ 30° apart in hue, in both schemes. Includes a **regression case asserting the
  retired `#7D5260` fails the chroma floor**, so the test is demonstrably capable of catching
  the bug it exists for.

**On device** — insets and window flags cannot be asserted from a unit test, so the plan
verifies them by measurement, with the exact commands and expected numbers:

- FAB bottom edge `<` nav bar inset top, under **both** gesture and 3-button nav.
- Status bar glyph contrast ≥ 3:1 on home and on dashboard.
- `cmd uimode night yes` changes the rendered surface colour.
- Font scale 2.0: strip bottom `<` nav row top; both FABs the same height.

## 11. Risks

- **Every screen changes colour at once.** Mitigated by the contrast test: nothing can regress
  below AA silently, and the layouts are untouched.
- **`safeDrawing` on a `verticalScroll` root** pads outside the scroll, so content can no longer
  scroll under the bars. That is the intent, but it slightly shortens the scroll viewport on
  Entry and Settings — worth a look during verification.
- **The strip stays light in dark mode** (§3.2). If it looks wrong against a dusk garden later,
  that is a weather feature, not a theming bug.
- **Room, sync, the fold and the AI layer are untouched.** This pass cannot affect money,
  events, or the garden's state — it changes paint and padding only.
