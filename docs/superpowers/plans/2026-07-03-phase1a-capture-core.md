# Phase 1A — Capture Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A daily-usable local Android app: scan a UPI QR → type amount → persona gate → launch the real UPI app → confirm → logged. Plus manual entry, an overall monthly budget, and a minimal home screen.

**Architecture:** Single-activity Jetpack Compose app, local-first. Room (SQLite) is the source of truth; every logged transaction also appends a `game_event` row so the garden (Plan 1C) can replay history later. Pure-Kotlin domain logic (URI parser, gate evaluator) is JVM-unit-tested; DAOs get one instrumented test; screens are verified manually on device. Manual dependency wiring via an `AppContainer` — no Hilt (YAGNI for a solo app; less magic for an Android newcomer).

**Tech Stack:** Kotlin 2.0.20, Jetpack Compose (BOM 2024.09.03, Material3), Room 2.6.1 (KSP), Navigation-Compose, zxing-android-embedded 4.3.0 (QR), JUnit4. minSdk 26, target/compileSdk 35, AGP 8.5.2, JDK 17 (bundled with Android Studio).

**Phase 1 plan roadmap** (this is plan 1 of 5; each later plan gets its own doc):
- **1A (this plan):** capture core — the app becomes your default way to pay offline merchants.
- **1B:** per-category budgets + dashboard (breakdown, graphs, pace) + backdate picker for manual entries.
- **1C:** garden — fold `game_event` history into world state + placeholder-art Canvas renderer.
- **1D:** AI — `LlmClient` (Gemini free tier), categorization fallback, daily digest, LLM-generated quip refresh.
- **1E:** Fortune City CSV import (verify export exists in FC settings first — spec §12).

**Version-pin rule:** the versions above are a known-coherent matrix. When Android Studio offers upgrades, decline them during Phase 1A. Stability first; upgrade in one deliberate pass later.

**Worktree note:** repo currently contains only docs — execute directly on `main`.

**Commit rule (user preference):** plain commit messages, **no Co-Authored-By lines, never push.**

**Guardrails for the implementing agent:**
- Do NOT upgrade any version, add any dependency, or "fix" deprecation warnings unless a step says so. The pinned matrix is deliberate; deprecations called out in steps are known-acceptable.
- If a step's actual output doesn't match its Expected line, STOP and report — do not improvise a workaround.
- Implement only what the task lists. No extra abstractions, no speculative helpers, no renames.

---

## File structure

```
settings.gradle.kts                  # project name + repos
build.gradle.kts                     # root plugin declarations
gradle/libs.versions.toml            # single source of version truth
gradle.properties                    # jvmargs, androidx flags
app/build.gradle.kts                 # app module config + deps
app/src/main/AndroidManifest.xml     # camera feature, upi <queries>
app/src/main/java/com/expensegarden/app/
  GardenApp.kt                       # Application; owns AppContainer
  MainActivity.kt                    # single activity + NavHost
  core/Money.kt                      # paise Long <-> display/parse
  capture/UpiUriParser.kt            # pure-Kotlin upi:// parser
  capture/UpiIntents.kt              # build + launch payment chooser intent
  gate/GateEvaluator.kt              # pure severity function (OK/PACE_WARNING/BREACH)
  data/Entities.kt                   # Room entities (FKs explicit, ON DELETE deliberate)
  data/Daos.kt                       # all DAOs
  data/AppDatabase.kt                # Room db + seed callback (categories, quips)
  data/LedgerRepository.kt           # save/confirm/discard + game_event emission
  data/QuipRepository.kt             # least-recently-used quip picker
  ui/MainViewModel.kt                # draft state + flows + actions
  ui/HomeScreen.kt                   # month card, txn list, confirm sheet, FABs
  ui/EntryScreen.kt                  # amount/payee/category form + GateDialog
app/src/test/java/com/expensegarden/app/
  core/MoneyTest.kt
  capture/UpiUriParserTest.kt
  gate/GateEvaluatorTest.kt
app/src/androidTest/java/com/expensegarden/app/
  data/LedgerDaoTest.kt
```

---

### Task 0: Environment

**Files:** none (machine setup)

- [ ] **Step 1: Install Android Studio** (latest stable) from https://developer.android.com/studio. During first-run setup accept the SDK licenses; ensure SDK Platform 35 and Android SDK Build-Tools are installed (Settings → Languages & Frameworks → Android SDK).

- [ ] **Step 2: Enable USB debugging on your phone.** Settings → About phone → tap "Build number" 7× → Developer options → enable "USB debugging". Plug in via USB, accept the RSA prompt.

- [ ] **Step 3: Create an emulator (recommended default for Tasks 1–11).** Android Studio → Device Manager → Create Virtual Device → Pixel 8 → an ARM64 API 35 image → Finish, then boot it. `installDebug` and `connectedDebugAndroidTest` treat a running emulator exactly like a plugged-in phone. QR scanning works on it too: Extended Controls (⋯) → Camera lets you set a custom image as a poster in the virtual scene — generate a QR for your own VPA (`brew install qrencode` or any UPI QR generator) and point the in-app scanner at it. The only thing the emulator cannot do is the real payment leg (no UPI apps run on it) — that's Task 12, on the phone.

- [ ] **Step 4: Verify adb sees a target (emulator and/or phone)**

Run: `~/Library/Android/sdk/platform-tools/adb devices`
Expected: one line per target ending in `device` (not `unauthorized`). The physical phone is only strictly required from Task 12.

Optionally add to PATH in `~/.zshrc`: `export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"`

---

### Task 1: Project scaffold

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/expensegarden/app/MainActivity.kt`, `.gitignore`

Create the Gradle wrapper right after writing the files: `brew install gradle && gradle wrapper --gradle-version 8.7` (one-time bootstrap; the generated `gradlew` + wrapper jar get committed, and brew's gradle is never used again). Opening the folder in Android Studio also generates it, but that path isn't scriptable.

- [ ] **Step 1: Write `.gitignore`**

```gitignore
.gradle/
build/
local.properties
.idea/
*.iml
.DS_Store
/captures
.kotlin/
```

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "expense-garden"
include(":app")
```

- [ ] **Step 3: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 4: Write `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.5.2"
kotlin = "2.0.20"
ksp = "2.0.20-1.0.25"
coreKtx = "1.13.1"
lifecycle = "2.8.6"
activityCompose = "1.9.2"
composeBom = "2024.09.03"
navigation = "2.8.1"
room = "2.6.1"
zxing = "4.3.0"
junit = "4.13.2"
androidxJunit = "1.2.1"
androidxTestRunner = "1.6.2"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
zxing-embedded = { group = "com.journeyapps", name = "zxing-android-embedded", version.ref = "zxing" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxJunit" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 5: Write root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 6: Write `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.expensegarden.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.expensegarden.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.zxing.embedded)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}
```

- [ ] **Step 7: Write `app/src/main/AndroidManifest.xml`**

The `<queries>` block is load-bearing: Android 11+ package visibility hides UPI apps from the chooser without it.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="true" />

    <queries>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="upi" />
        </intent>
    </queries>

    <application
        android:label="Expense Garden"
        android:icon="@android:drawable/sym_def_app_icon"
        android:theme="@android:style/Theme.Material.Light.NoActionBar"
        android:name=".GardenApp">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

(`android:name=".GardenApp"` forward-references Task 5's Application class — for this task only, comment that attribute out, and restore it in Task 5.)

- [ ] **Step 8: Write placeholder `MainActivity.kt`**

`app/src/main/java/com/expensegarden/app/MainActivity.kt`:

```kotlin
package com.expensegarden.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { Text("expense-garden: it compiles") }
            }
        }
    }
}
```

- [ ] **Step 9: Build and run on device**

With the wrapper generated (see task preamble), from the repo root:

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

Run: `./gradlew installDebug` then open the app on the phone.
Expected: screen showing "expense-garden: it compiles".

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "chore: android project scaffold (compose, room deps, upi queries manifest)"
```

---

### Task 2: Money utilities (TDD)

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/core/Money.kt`
- Test: `app/src/test/java/com/expensegarden/app/core/MoneyTest.kt`

All amounts are **paise as `Long`** (never floats — spec §11 `CHECK (amount > 0)` becomes runtime validation here).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test fun `parses plain rupees`() = assertEquals(45000L, Money.parseToPaise("450"))
    @Test fun `parses rupees with paise`() = assertEquals(45050L, Money.parseToPaise("450.50"))
    @Test fun `parses single decimal digit`() = assertEquals(45050L, Money.parseToPaise("450.5"))
    @Test fun `rejects garbage`() = assertNull(Money.parseToPaise("45a"))
    @Test fun `rejects zero and negative`() {
        assertNull(Money.parseToPaise("0"))
        assertNull(Money.parseToPaise("-5"))
    }
    @Test fun `rejects sub-paise precision`() = assertNull(Money.parseToPaise("450.505"))
    @Test fun `formats display rupees`() = assertEquals("₹450.50", Money.display(45050L))
    @Test fun `formats intent amount`() = assertEquals("450.50", Money.intentAmount(45050L))
    @Test fun `formats intent amount whole`() = assertEquals("450.00", Money.intentAmount(45000L))
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "com.expensegarden.app.core.MoneyTest"`
Expected: FAIL — `Unresolved reference: Money` (compile error counts as the red step).

- [ ] **Step 3: Implement `Money.kt`**

```kotlin
package com.expensegarden.app.core

import java.math.BigDecimal
import java.util.Locale

object Money {
    /** "450.50" -> 45050 paise. Null on garbage, zero, negative, or sub-paise precision. */
    fun parseToPaise(input: String): Long? {
        val value = input.trim().toBigDecimalOrNull() ?: return null
        if (value <= BigDecimal.ZERO) return null
        return try {
            value.movePointRight(2).longValueExact()
        } catch (e: ArithmeticException) {
            null
        }
    }

    fun display(paise: Long): String =
        String.format(Locale.US, "₹%d.%02d", paise / 100, paise % 100)

    /** NPCI intent `am` param format: strictly two decimals. */
    fun intentAmount(paise: Long): String =
        String.format(Locale.US, "%d.%02d", paise / 100, paise % 100)
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests "com.expensegarden.app.core.MoneyTest"`
Expected: PASS (9 tests). `longValueExact()` throws on `"450.505"` (fractional paise) → null, exactly what the sub-paise test demands.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/core/Money.kt app/src/test/java/com/expensegarden/app/core/MoneyTest.kt
git commit -m "feat: money utils - paise-as-long parse/display/intent formats"
```

---

### Task 3: UPI URI parser (TDD)

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/capture/UpiUriParser.kt`
- Test: `app/src/test/java/com/expensegarden/app/capture/UpiUriParserTest.kt`

Pure Kotlin (no `android.net.Uri` — that class is a stub in JVM unit tests). Every UPI QR encodes `upi://pay?pa=<vpa>&pn=<name>&am=<amount>&tn=<note>`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpiUriParserTest {
    @Test fun `parses full merchant qr`() {
        val p = UpiUriParser.parse("upi://pay?pa=chaiwala@ybl&pn=Sharma%20Chai&am=20.00&tn=chai")
        assertEquals("chaiwala@ybl", p?.vpa)
        assertEquals("Sharma Chai", p?.name)
        assertEquals(2000L, p?.amountPaise)
        assertEquals("chai", p?.note)
    }
    @Test fun `parses minimal qr with only vpa`() {
        val p = UpiUriParser.parse("upi://pay?pa=someone@oksbi")
        assertEquals("someone@oksbi", p?.vpa)
        assertNull(p?.name)
        assertNull(p?.amountPaise)
    }
    @Test fun `plus sign decodes as space in name`() {
        val p = UpiUriParser.parse("upi://pay?pa=x@upi&pn=Tea+Stall")
        assertEquals("Tea Stall", p?.name)
    }
    @Test fun `scheme is case insensitive`() {
        assertEquals("x@upi", UpiUriParser.parse("UPI://PAY?pa=x@upi")?.vpa)
    }
    @Test fun `rejects non-upi qr`() {
        assertNull(UpiUriParser.parse("https://example.com/pay?pa=x@upi"))
        assertNull(UpiUriParser.parse("hello world"))
    }
    @Test fun `rejects missing vpa`() {
        assertNull(UpiUriParser.parse("upi://pay?pn=NoVpa&am=10.00"))
    }
    @Test fun `garbage amount becomes null not crash`() {
        val p = UpiUriParser.parse("upi://pay?pa=x@upi&am=abc")
        assertEquals("x@upi", p?.vpa)
        assertNull(p?.amountPaise)
    }
    @Test fun `malformed percent encoding does not crash`() {
        val p = UpiUriParser.parse("upi://pay?pa=x@upi&pn=100%offer")
        assertEquals("x@upi", p?.vpa)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "com.expensegarden.app.capture.UpiUriParserTest"`
Expected: FAIL — `Unresolved reference: UpiUriParser`.

- [ ] **Step 3: Implement `UpiUriParser.kt`**

```kotlin
package com.expensegarden.app.capture

import com.expensegarden.app.core.Money
import java.net.URLDecoder

data class UpiPayee(
    val vpa: String,
    val name: String?,
    val amountPaise: Long?,
    val note: String?,
)

object UpiUriParser {
    /** Returns null when [raw] is not a upi://pay URI with a payee address. */
    fun parse(raw: String): UpiPayee? {
        val trimmed = raw.trim()
        if (!trimmed.lowercase().startsWith("upi://pay")) return null
        val query = trimmed.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        val params = query.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = pair.substring(0, idx).lowercase()
            val value = safeDecode(pair.substring(idx + 1))
            key to value
        }.toMap()
        val vpa = params["pa"]?.takeIf { it.isNotBlank() } ?: return null
        return UpiPayee(
            vpa = vpa,
            name = params["pn"]?.takeIf { it.isNotBlank() },
            amountPaise = params["am"]?.let { Money.parseToPaise(it) },
            note = params["tn"]?.takeIf { it.isNotBlank() },
        )
    }

    /** Merchant QRs sometimes carry raw '%' — never let decoding crash a scan. */
    private fun safeDecode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        s
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests "com.expensegarden.app.capture.UpiUriParserTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/capture/UpiUriParser.kt app/src/test/java/com/expensegarden/app/capture/UpiUriParserTest.kt
git commit -m "feat: pure-kotlin upi://pay uri parser"
```

---

### Task 4: Gate evaluator (TDD)

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/gate/GateEvaluator.kt`
- Test: `app/src/test/java/com/expensegarden/app/gate/GateEvaluatorTest.kt`

Spec §5.1: severity is computed locally, instantly. Rule: BREACH if this payment pushes the month over budget; PACE_WARNING if it pushes spend past the day-proportional budget share with 15% grace; OK otherwise (and OK **skips the gate dialog entirely** — the persona silence rule applied to the gate).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.gate

import org.junit.Assert.assertEquals
import org.junit.Test

class GateEvaluatorTest {
    // budget ₹10,000.00 = 1_000_000 paise; 30-day month
    private val budget = 1_000_000L

    @Test fun `no budget set means OK`() =
        assertEquals(Severity.OK, GateEvaluator.evaluate(999_999L, null, 50_000L, 15, 30))

    @Test fun `over budget is BREACH`() =
        assertEquals(Severity.BREACH, GateEvaluator.evaluate(950_000L, budget, 100_000L, 20, 30))

    @Test fun `exactly at budget on last day is not breach`() =
        assertEquals(Severity.OK, GateEvaluator.evaluate(900_000L, budget, 100_000L, 30, 30))

    @Test fun `ahead of pace is PACE_WARNING`() {
        // day 10/30: allowance = 10000 * 10/30 * 1.15 = ₹3,833.33. Spent 3000 + paying 1000 = 4000 > allowance
        assertEquals(Severity.PACE_WARNING, GateEvaluator.evaluate(300_000L, budget, 100_000L, 10, 30))
    }

    @Test fun `under pace is OK`() {
        // day 20/30: allowance = 10000 * 20/30 * 1.15 = ₹7,666.67. Spent 5000 + paying 1000 = 6000 < allowance
        assertEquals(Severity.OK, GateEvaluator.evaluate(500_000L, budget, 100_000L, 20, 30))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "com.expensegarden.app.gate.GateEvaluatorTest"`
Expected: FAIL — `Unresolved reference: Severity`.

- [ ] **Step 3: Implement `GateEvaluator.kt`**

```kotlin
package com.expensegarden.app.gate

enum class Severity { OK, PACE_WARNING, BREACH }

object GateEvaluator {
    private const val PACE_GRACE = 1.15

    fun evaluate(
        spentThisMonthPaise: Long,
        monthBudgetPaise: Long?,
        candidatePaise: Long,
        dayOfMonth: Int,
        daysInMonth: Int,
    ): Severity {
        if (monthBudgetPaise == null || monthBudgetPaise <= 0) return Severity.OK
        val afterPayment = spentThisMonthPaise + candidatePaise
        if (afterPayment > monthBudgetPaise) return Severity.BREACH
        val paceAllowance = monthBudgetPaise.toDouble() * dayOfMonth / daysInMonth * PACE_GRACE
        return if (afterPayment > paceAllowance) Severity.PACE_WARNING else Severity.OK
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests "com.expensegarden.app.gate.GateEvaluatorTest"`
Expected: PASS (5 tests). (Last-day check: allowance = budget × 30/30 × 1.15 > budget, so an at-budget payment is OK, not PACE_WARNING.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/gate/ app/src/test/java/com/expensegarden/app/gate/
git commit -m "feat: gate severity evaluator (breach / pace-warning with 15% grace)"
```

---

### Task 5: Room database — entities, DAOs, seed

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/data/Entities.kt`
- Create: `app/src/main/java/com/expensegarden/app/data/Daos.kt`
- Create: `app/src/main/java/com/expensegarden/app/data/AppDatabase.kt`
- Create: `app/src/main/java/com/expensegarden/app/GardenApp.kt`
- Modify: `app/src/main/AndroidManifest.xml` (restore `android:name=".GardenApp"` if commented in Task 1)
- Test: `app/src/androidTest/java/com/expensegarden/app/data/LedgerDaoTest.kt`

Every FK explicit with deliberate `ON DELETE` (spec §11 / user's DB standards). Room enforces declared FKs at runtime.

- [ ] **Step 1: Write `Entities.kt`**

```kotlin
package com.expensegarden.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Stored as TEXT via EnumConverters.
enum class TxnSource { QR_GATE, MANUAL, IMPORT }
enum class TxnStatus { PENDING_CONFIRM, LOGGED, DISCARDED }
enum class Regret { UNRATED, WORTH_IT, REGRET }

@Entity(tableName = "category")
data class CategoryEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val parentId: Long?,          // self-FK deliberately omitted in 1A: rows are seed-only; enforced in Postgres later
    val isNecessity: Boolean,
)

@Entity(
    tableName = "payee",
    indices = [Index(value = ["vpa"], unique = true), Index("defaultCategoryId")],
    foreignKeys = [ForeignKey(
        entity = CategoryEntity::class,
        parentColumns = ["id"],
        childColumns = ["defaultCategoryId"],
        onDelete = ForeignKey.SET_NULL,
    )],
)
data class PayeeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val vpa: String?,             // null for cash payees
    val defaultCategoryId: Long?,
)

@Entity(
    tableName = "txn",
    indices = [Index("payeeId"), Index("categoryId"), Index("status"), Index("occurredAt")],
    foreignKeys = [
        ForeignKey(
            entity = PayeeEntity::class,
            parentColumns = ["id"],
            childColumns = ["payeeId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
data class TransactionEntity(
    @PrimaryKey val uuid: String,          // client-generated; survives future sync
    val amountPaise: Long,
    val payeeId: Long,
    val categoryId: Long,
    val source: TxnSource,
    val status: TxnStatus,
    val regret: Regret = Regret.UNRATED,
    val breachedAtLogging: Boolean,        // weed rule input, frozen at capture time (spec §9.3)
    val note: String?,
    val occurredAt: Long,                  // epoch millis; user-settable backdating UI lands in 1B
    val createdAt: Long,
)

@Entity(
    tableName = "budget",
    indices = [Index(value = ["categoryId", "month"], unique = true)],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long?,                 // null = overall budget (only kind used in 1A)
    val month: String,                     // "2026-07"
    val amountPaise: Long,
)

@Entity(
    tableName = "game_event",
    indices = [Index("transactionUuid")],
    foreignKeys = [ForeignKey(
        entity = TransactionEntity::class,
        parentColumns = ["uuid"],
        childColumns = ["transactionUuid"],
        onDelete = ForeignKey.SET_NULL,
    )],
)
data class GameEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,                      // "transaction.logged", "gate.dodged", ...
    val payloadJson: String,
    val transactionUuid: String?,
    val createdAt: Long,
)

@Entity(tableName = "quip", indices = [Index("severity")])
data class QuipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val severity: String,                  // Severity.name — PACE_WARNING or BREACH
    @ColumnInfo(defaultValue = "STATIC") val origin: String = "STATIC", // LLM refresh comes in 1D
    val text: String,
    val usedAt: Long?,                     // null = never used; picker prefers these
)
```

- [ ] **Step 2: Write `Daos.kt`**

```kotlin
package com.expensegarden.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category ORDER BY isNecessity DESC, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun byId(id: Long): CategoryEntity?
}

@Dao
interface PayeeDao {
    @Query("SELECT * FROM payee WHERE vpa = :vpa LIMIT 1")
    suspend fun byVpa(vpa: String): PayeeEntity?

    @Query("SELECT * FROM payee WHERE vpa IS NULL AND name = :name LIMIT 1")
    suspend fun cashPayeeByName(name: String): PayeeEntity?

    @Insert
    suspend fun insert(payee: PayeeEntity): Long

    @Query("UPDATE payee SET defaultCategoryId = :categoryId WHERE id = :payeeId")
    suspend fun setDefaultCategory(payeeId: Long, categoryId: Long)
}

data class TxnRow(
    val uuid: String,
    val amountPaise: Long,
    val payeeName: String,
    val categoryName: String,
    val occurredAt: Long,
)

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(txn: TransactionEntity)

    @Query("UPDATE txn SET status = :status WHERE uuid = :uuid")
    suspend fun setStatus(uuid: String, status: TxnStatus)

    @Query("SELECT * FROM txn WHERE status = 'PENDING_CONFIRM' ORDER BY createdAt")
    fun observePendingConfirm(): Flow<List<TransactionEntity>>

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM txn WHERE status = 'LOGGED' AND occurredAt BETWEEN :fromMillis AND :toMillis")
    suspend fun loggedSumBetween(fromMillis: Long, toMillis: Long): Long

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM txn WHERE status = 'LOGGED' AND occurredAt BETWEEN :fromMillis AND :toMillis")
    fun observeLoggedSumBetween(fromMillis: Long, toMillis: Long): Flow<Long>

    @Query(
        """SELECT t.uuid, t.amountPaise, p.name AS payeeName, c.name AS categoryName, t.occurredAt
           FROM txn t JOIN payee p ON p.id = t.payeeId JOIN category c ON c.id = t.categoryId
           WHERE t.status = 'LOGGED' ORDER BY t.occurredAt DESC LIMIT 50"""
    )
    fun observeRecent(): Flow<List<TxnRow>>
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budget WHERE categoryId IS NULL AND month = :month LIMIT 1")
    suspend fun overallForMonth(month: String): BudgetEntity?

    @Query("SELECT * FROM budget WHERE categoryId IS NULL AND month = :month LIMIT 1")
    fun observeOverallForMonth(month: String): Flow<BudgetEntity?>

    @Query("DELETE FROM budget WHERE categoryId IS NULL AND month = :month")
    suspend fun deleteOverallForMonth(month: String)

    @Insert
    suspend fun insert(budget: BudgetEntity)
}

@Dao
interface GameEventDao {
    @Insert
    suspend fun insert(event: GameEventEntity)
}

@Dao
interface QuipDao {
    @Query("SELECT * FROM quip WHERE severity = :severity ORDER BY usedAt IS NOT NULL, usedAt ASC LIMIT 1")
    suspend fun leastRecentlyUsed(severity: String): QuipEntity?

    @Query("UPDATE quip SET usedAt = :now WHERE id = :id")
    suspend fun markUsed(id: Long, now: Long)
}
```

- [ ] **Step 3: Write `AppDatabase.kt` with the seed callback**

```kotlin
package com.expensegarden.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

class EnumConverters {
    @TypeConverter fun sourceToString(v: TxnSource) = v.name
    @TypeConverter fun stringToSource(v: String) = TxnSource.valueOf(v)
    @TypeConverter fun statusToString(v: TxnStatus) = v.name
    @TypeConverter fun stringToStatus(v: String) = TxnStatus.valueOf(v)
    @TypeConverter fun regretToString(v: Regret) = v.name
    @TypeConverter fun stringToRegret(v: String) = Regret.valueOf(v)
}

@Database(
    entities = [
        CategoryEntity::class, PayeeEntity::class, TransactionEntity::class,
        BudgetEntity::class, GameEventEntity::class, QuipEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(EnumConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun payeeDao(): PayeeDao
    abstract fun transactionDao(): TransactionDao
    abstract fun budgetDao(): BudgetDao
    abstract fun gameEventDao(): GameEventDao
    abstract fun quipDao(): QuipDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "garden.db")
                .addCallback(SeedCallback)
                .build()
    }
}

object SeedCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        // Categories: (id, name, parentId, isNecessity). Parents first.
        val categories = listOf(
            "(1,'Food & Drinks',NULL,0)", "(2,'Groceries',NULL,1)", "(3,'Transport',NULL,1)",
            "(4,'Housing',NULL,1)", "(5,'Health',NULL,1)", "(6,'Entertainment',NULL,0)",
            "(7,'Shopping',NULL,0)", "(8,'Personal',NULL,0)", "(9,'Family',NULL,1)",
            "(10,'Investments',NULL,1)", "(11,'Misc',NULL,0)",
            "(101,'Restaurants',1,0)", "(102,'Delivery',1,0)", "(103,'Chai & Snacks',1,0)",
            "(301,'Fuel',3,1)", "(302,'Cab & Auto',3,0)", "(303,'Metro & Bus',3,1)",
            "(401,'Rent',4,1)", "(402,'Utilities',4,1)",
            "(601,'Streaming',6,0)", "(602,'Outings',6,0)",
        )
        db.execSQL("INSERT INTO category (id, name, parentId, isNecessity) VALUES ${categories.joinToString(",")}")

        // Sharp-but-fair static quip bank. Gate shows nothing on OK.
        val quips = listOf(
            "PACE_WARNING" to "You're spending like it's the 1st. It's not the 1st.",
            "PACE_WARNING" to "The budget is watching. It's not angry, just doing the math.",
            "PACE_WARNING" to "At this pace the month outlives the money. Proceed?",
            "PACE_WARNING" to "Bold pace. The garden's getting thirsty though.",
            "PACE_WARNING" to "This one's fine. The next three are the problem.",
            "BREACH" to "That's a weed and you know it. Plant it anyway?",
            "BREACH" to "Budget's already gone. This is just archaeology now.",
            "BREACH" to "This is how droughts start. Your call.",
            "BREACH" to "Somewhere, a future you is squinting at this line item.",
            "BREACH" to "The compost heap has room. Just saying.",
        )
        quips.forEach { (severity, text) ->
            db.execSQL(
                "INSERT INTO quip (severity, origin, text, usedAt) VALUES (?, 'STATIC', ?, NULL)",
                arrayOf(severity, text),
            )
        }
    }
}
```

- [ ] **Step 4: Write `GardenApp.kt` (Application + container shell)** and restore `android:name=".GardenApp"` in the manifest

```kotlin
package com.expensegarden.app

import android.app.Application
import com.expensegarden.app.data.AppDatabase

class GardenApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    val db: AppDatabase = AppDatabase.build(app)
}
```

- [ ] **Step 5: Write the instrumented DAO test**

`app/src/androidTest/java/com/expensegarden/app/data/LedgerDaoTest.kt`:

```kotlin
package com.expensegarden.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback)
            .allowMainThreadQueries()
            .build()
    }

    @After fun teardown() = db.close()

    @Test fun seed_categories_present() = runBlocking {
        // Any query forces onCreate; seed row 2 = Groceries (necessity)
        val groceries = db.categoryDao().byId(2)
        assertNotNull(groceries)
        assertEquals(true, groceries!!.isNecessity)
    }

    @Test fun insert_and_sum_logged_transaction() = runBlocking {
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "Chaiwala", vpa = "chai@ybl", defaultCategoryId = 103))
        db.transactionDao().insert(
            TransactionEntity(
                uuid = UUID.randomUUID().toString(), amountPaise = 2000, payeeId = payeeId,
                categoryId = 103, source = TxnSource.QR_GATE, status = TxnStatus.LOGGED,
                breachedAtLogging = false, note = null, occurredAt = 1000L, createdAt = 1000L,
            )
        )
        assertEquals(2000L, db.transactionDao().loggedSumBetween(0L, 2000L))
        assertEquals(0L, db.transactionDao().loggedSumBetween(3000L, 4000L))
    }

    @Test(expected = SQLiteConstraintException::class)
    fun fk_rejects_transaction_with_unknown_category(): Unit = runBlocking {
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "X", vpa = null, defaultCategoryId = null))
        db.transactionDao().insert(
            TransactionEntity(
                uuid = UUID.randomUUID().toString(), amountPaise = 100, payeeId = payeeId,
                categoryId = 999_999, source = TxnSource.MANUAL, status = TxnStatus.LOGGED,
                breachedAtLogging = false, note = null, occurredAt = 0L, createdAt = 0L,
            )
        )
    }

    @Test fun quip_picker_prefers_unused_then_lru() = runBlocking {
        val first = db.quipDao().leastRecentlyUsed("BREACH")!!
        db.quipDao().markUsed(first.id, now = 100L)
        val second = db.quipDao().leastRecentlyUsed("BREACH")!!
        // second must be a different, still-unused quip
        assertEquals(null, second.usedAt)
    }
}
```

- [ ] **Step 6: Run instrumented tests (device connected)**

Run: `./gradlew connectedDebugAndroidTest`
Expected: `BUILD SUCCESSFUL`, 4 tests passing. (Add `androidTestImplementation("androidx.test:core-ktx:1.6.1")` to `app/build.gradle.kts` dependencies if `ApplicationProvider` is unresolved.)

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: room schema with fks, seed categories + static quip bank, dao tests"
```

---

### Task 6: Repositories — ledger and quips

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt`
- Create: `app/src/main/java/com/expensegarden/app/data/QuipRepository.kt`
- Modify: `app/src/main/java/com/expensegarden/app/GardenApp.kt` (wire into container)

The repository is where the spec's invariants live: **every transition to LOGGED emits a `game_event` in the same DB transaction** (spec §9.2 — the world is replayable). No TDD here (thin orchestration over tested DAOs); the end-to-end check comes in Task 12.

- [ ] **Step 1: Write `LedgerRepository.kt`**

```kotlin
package com.expensegarden.app.data

import androidx.room.withTransaction
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class LedgerRepository(private val db: AppDatabase) {
    private val zone: ZoneId = ZoneId.systemDefault()

    data class Draft(
        val vpa: String?,            // null => cash/manual payee
        val payeeName: String,
        val amountPaise: Long,
        val categoryId: Long,
        val note: String?,
        val occurredAt: Long,
    )

    /** QR path: saved as PENDING_CONFIRM before the UPI intent fires. Returns txn uuid. */
    suspend fun savePending(draft: Draft, breachedAtLogging: Boolean): String =
        save(draft, TxnSource.QR_GATE, TxnStatus.PENDING_CONFIRM, breachedAtLogging)

    /** Manual path: post-hoc, money already spent — straight to LOGGED (spec §5.1). */
    suspend fun saveManualLogged(draft: Draft, breachedAtLogging: Boolean): String =
        save(draft, TxnSource.MANUAL, TxnStatus.LOGGED, breachedAtLogging)

    suspend fun confirm(uuid: String) {
        db.withTransaction {
            db.transactionDao().setStatus(uuid, TxnStatus.LOGGED)
            db.gameEventDao().insert(loggedEvent(uuid))
        }
    }

    suspend fun discard(uuid: String) = db.transactionDao().setStatus(uuid, TxnStatus.DISCARDED)

    /** User backed out at the gate — record the dodge; the game rewards it later (1C). */
    suspend fun recordGateDodge(amountPaise: Long, categoryId: Long) {
        val payload = JSONObject().put("amountPaise", amountPaise).put("categoryId", categoryId)
        db.gameEventDao().insert(
            GameEventEntity(type = "gate.dodged", payloadJson = payload.toString(), transactionUuid = null, createdAt = now())
        )
    }

    fun observePendingConfirm(): Flow<List<TransactionEntity>> = db.transactionDao().observePendingConfirm()
    fun observeRecent(): Flow<List<TxnRow>> = db.transactionDao().observeRecent()
    fun observeMonthSpent(): Flow<Long> {
        val (from, to) = currentMonthBounds()
        return db.transactionDao().observeLoggedSumBetween(from, to)
    }

    suspend fun monthSpentPaise(): Long {
        val (from, to) = currentMonthBounds()
        return db.transactionDao().loggedSumBetween(from, to)
    }

    fun currentMonthKey(): String = YearMonth.now(zone).toString()          // "2026-07"
    fun today(): Pair<Int, Int> {
        val d = LocalDate.now(zone)
        return d.dayOfMonth to d.lengthOfMonth()
    }

    private suspend fun save(draft: Draft, source: TxnSource, status: TxnStatus, breached: Boolean): String {
        val uuid = UUID.randomUUID().toString()
        db.withTransaction {
            val payeeId = resolvePayee(draft)
            db.transactionDao().insert(
                TransactionEntity(
                    uuid = uuid, amountPaise = draft.amountPaise, payeeId = payeeId,
                    categoryId = draft.categoryId, source = source, status = status,
                    breachedAtLogging = breached, note = draft.note,
                    occurredAt = draft.occurredAt, createdAt = now(),
                )
            )
            db.payeeDao().setDefaultCategory(payeeId, draft.categoryId)      // payee->category map learns
            if (status == TxnStatus.LOGGED) db.gameEventDao().insert(loggedEvent(uuid))
        }
        return uuid
    }

    private suspend fun resolvePayee(draft: Draft): Long {
        val existing = if (draft.vpa != null) db.payeeDao().byVpa(draft.vpa)
                       else db.payeeDao().cashPayeeByName(draft.payeeName)
        if (existing != null) return existing.id
        return db.payeeDao().insert(
            PayeeEntity(name = draft.payeeName, vpa = draft.vpa, defaultCategoryId = draft.categoryId)
        )
    }

    private fun loggedEvent(uuid: String): GameEventEntity =
        GameEventEntity(
            type = "transaction.logged",
            payloadJson = JSONObject().put("uuid", uuid).toString(),
            transactionUuid = uuid,
            createdAt = now(),
        )

    private fun currentMonthBounds(): Pair<Long, Long> {
        val ym = YearMonth.now(zone)
        val from = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return from to to
    }

    private fun now(): Long = System.currentTimeMillis()
}
```

- [ ] **Step 2: Write `QuipRepository.kt`**

```kotlin
package com.expensegarden.app.data

import com.expensegarden.app.gate.Severity

class QuipRepository(private val db: AppDatabase) {
    /** Least-recently-used line for the severity; unused quips win first. */
    suspend fun pick(severity: Severity): String {
        val quip = db.quipDao().leastRecentlyUsed(severity.name)
            ?: return "Budget says no. You say...?"       // unreachable with seed bank; safe default
        db.quipDao().markUsed(quip.id, System.currentTimeMillis())
        return quip.text
    }
}
```

- [ ] **Step 3: Wire the container in `GardenApp.kt`**

Replace the `AppContainer` class:

```kotlin
class AppContainer(app: Application) {
    val db: AppDatabase = AppDatabase.build(app)
    val ledger: LedgerRepository = LedgerRepository(db)
    val quips: QuipRepository = QuipRepository(db)
}
```

(imports: `com.expensegarden.app.data.LedgerRepository`, `com.expensegarden.app.data.QuipRepository`)

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: ledger + quip repositories; game_event emitted atomically with LOGGED"
```

---

### Task 7: UPI intent launcher

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/capture/UpiIntents.kt`

- [ ] **Step 1: Write `UpiIntents.kt`**

We deliberately don't parse the activity result — PSP apps return it inconsistently (spec §5.1); the confirm sheet is the source of truth.

```kotlin
package com.expensegarden.app.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.expensegarden.app.core.Money

object UpiIntents {
    /** Launches the UPI app chooser. Returns false when no UPI app is installed. */
    fun launchPayment(context: Context, vpa: String, payeeName: String?, amountPaise: Long, note: String?): Boolean {
        val uri = Uri.Builder()
            .scheme("upi").authority("pay")
            .appendQueryParameter("pa", vpa)
            .apply { if (!payeeName.isNullOrBlank()) appendQueryParameter("pn", payeeName) }
            .appendQueryParameter("am", Money.intentAmount(amountPaise))
            .appendQueryParameter("cu", "INR")
            .apply { if (!note.isNullOrBlank()) appendQueryParameter("tn", note) }
            .build()
        val pay = Intent(Intent.ACTION_VIEW, uri)
        // createChooser never throws ActivityNotFoundException — probe explicitly instead.
        // (resolveActivity's deprecation is acceptable; the replacement needs API 33+.)
        if (context.packageManager.resolveActivity(pay, 0) == null) {
            Toast.makeText(context, "No UPI app found", Toast.LENGTH_LONG).show()
            return false
        }
        context.startActivity(Intent.createChooser(pay, "Pay with"))
        return true
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/capture/UpiIntents.kt
git commit -m "feat: upi payment chooser intent (no result parsing by design)"
```

---

### Task 8: MainViewModel

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt`

One shared ViewModel holding the entry draft (avoids nav-argument encoding), flows for home, and all actions. Gate decision flow: `prepareGate()` computes severity + picks a quip; the UI decides whether to show the dialog (OK skips it).

- [ ] **Step 1: Write `MainViewModel.kt`**

```kotlin
package com.expensegarden.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.expensegarden.app.AppContainer
import com.expensegarden.app.capture.UpiPayee
import com.expensegarden.app.core.Money
import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.LedgerRepository
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnRow
import com.expensegarden.app.gate.GateEvaluator
import com.expensegarden.app.gate.Severity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EntryDraft(
    val fromScan: Boolean = false,
    val vpa: String? = null,
    val payeeName: String = "",
    val amountText: String = "",
    val categoryId: Long? = null,
    val note: String = "",
    val occurredAt: Long = System.currentTimeMillis(),
)

data class GatePrompt(val severity: Severity, val quip: String)

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val ledger = container.ledger

    val draft = MutableStateFlow(EntryDraft())

    val categories: StateFlow<List<CategoryEntity>> =
        container.db.categoryDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val monthSpent: StateFlow<Long> =
        ledger.observeMonthSpent()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val monthBudget: StateFlow<BudgetEntity?> =
        container.db.budgetDao().observeOverallForMonth(ledger.currentMonthKey())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val pendingConfirm: StateFlow<List<TransactionEntity>> =
        ledger.observePendingConfirm()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recent: Flow<List<TxnRow>> = ledger.observeRecent()

    fun startScanDraft(payee: UpiPayee) {
        draft.value = EntryDraft(
            fromScan = true,
            vpa = payee.vpa,
            payeeName = payee.name ?: payee.vpa.substringBefore('@'),
            amountText = payee.amountPaise?.let { Money.intentAmount(it) } ?: "",
        )
        viewModelScope.launch { prefillCategoryFromPayee(payee.vpa) }
    }

    fun startManualDraft() {
        draft.value = EntryDraft(fromScan = false)
    }

    /** Compute severity + quip. OK never shows a dialog (silence rule at the gate). */
    suspend fun prepareGate(amountPaise: Long): GatePrompt {
        val spent = ledger.monthSpentPaise()
        val budget = container.db.budgetDao().overallForMonth(ledger.currentMonthKey())?.amountPaise
        val (day, days) = ledger.today()
        val severity = GateEvaluator.evaluate(spent, budget, amountPaise, day, days)
        val quip = if (severity == Severity.OK) "" else container.quips.pick(severity)
        return GatePrompt(severity, quip)
    }

    /** QR path: persist pending + record breach flag; caller then fires the intent. */
    suspend fun savePendingFromDraft(amountPaise: Long, severity: Severity): String =
        ledger.savePending(
            draft = draft.value.toRepoDraft(amountPaise),
            breachedAtLogging = severity == Severity.BREACH,
        )

    fun saveManualFromDraft(amountPaise: Long) {
        val d = draft.value
        viewModelScope.launch {
            val spent = ledger.monthSpentPaise()
            val budget = container.db.budgetDao().overallForMonth(ledger.currentMonthKey())?.amountPaise
            val (day, days) = ledger.today()
            val severity = GateEvaluator.evaluate(spent, budget, amountPaise, day, days)
            ledger.saveManualLogged(d.toRepoDraft(amountPaise), breachedAtLogging = severity == Severity.BREACH)
        }
    }

    fun recordDodge(amountPaise: Long) {
        val categoryId = draft.value.categoryId ?: return
        viewModelScope.launch { ledger.recordGateDodge(amountPaise, categoryId) }
    }

    fun confirmPending(uuid: String) = viewModelScope.launch { ledger.confirm(uuid) }
    fun discardPending(uuid: String) = viewModelScope.launch { ledger.discard(uuid) }

    fun setOverallBudget(amountPaise: Long?) {
        viewModelScope.launch {
            val month = ledger.currentMonthKey()
            container.db.withTransaction {
                container.db.budgetDao().deleteOverallForMonth(month)
                if (amountPaise != null) {
                    container.db.budgetDao().insert(BudgetEntity(categoryId = null, month = month, amountPaise = amountPaise))
                }
            }
        }
    }

    private suspend fun prefillCategoryFromPayee(vpa: String) {
        val known = container.db.payeeDao().byVpa(vpa) ?: return
        known.defaultCategoryId?.let { catId -> draft.value = draft.value.copy(categoryId = catId) }
    }

    private fun EntryDraft.toRepoDraft(amountPaise: Long) = LedgerRepository.Draft(
        vpa = vpa,
        payeeName = payeeName.ifBlank { vpa ?: "Unknown" },
        amountPaise = amountPaise,
        categoryId = categoryId!!,
        note = note.ifBlank { null },
        occurredAt = occurredAt,
    )

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt
git commit -m "feat: main viewmodel - draft state, gate preparation, budget + confirm actions"
```

---

### Task 9: Entry screen with gate dialog

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/ui/EntryScreen.kt`

- [ ] **Step 1: Write `EntryScreen.kt`**

```kotlin
package com.expensegarden.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.expensegarden.app.capture.UpiIntents
import com.expensegarden.app.core.Money
import com.expensegarden.app.gate.Severity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(vm: MainViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft by vm.draft.collectAsState()
    val categories by vm.categories.collectAsState()
    var gate by remember { mutableStateOf<GatePrompt?>(null) }
    var categoryMenuOpen by remember { mutableStateOf(false) }

    fun fireAndFinish(amountPaise: Long, severity: Severity) {
        scope.launch {
            vm.savePendingFromDraft(amountPaise, severity)
            UpiIntents.launchPayment(context, draft.vpa!!, draft.payeeName, amountPaise, draft.note.ifBlank { null })
            onDone()
        }
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (draft.fromScan) "Paying ${draft.payeeName}" else "Log an expense",
            style = MaterialTheme.typography.headlineSmall,
        )

        OutlinedTextField(
            value = draft.amountText,
            onValueChange = { vm.draft.value = draft.copy(amountText = it) },
            label = { Text("Amount (₹)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        if (!draft.fromScan) {
            OutlinedTextField(
                value = draft.payeeName,
                onValueChange = { vm.draft.value = draft.copy(payeeName = it) },
                label = { Text("Paid to") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ExposedDropdownMenuBox(expanded = categoryMenuOpen, onExpandedChange = { categoryMenuOpen = it }) {
            OutlinedTextField(
                value = categories.find { it.id == draft.categoryId }?.name ?: "Pick a category",
                onValueChange = {},
                readOnly = true,
                label = { Text("Category") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuOpen) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = categoryMenuOpen, onDismissRequest = { categoryMenuOpen = false }) {
                categories.forEach { cat ->
                    DropdownMenuItem(
                        text = { Text(if (cat.parentId == null) cat.name else "   ${cat.name}") },
                        onClick = {
                            vm.draft.value = draft.copy(categoryId = cat.id)
                            categoryMenuOpen = false
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = draft.note,
            onValueChange = { vm.draft.value = draft.copy(note = it) },
            label = { Text("Note (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                val amountPaise = Money.parseToPaise(draft.amountText)
                when {
                    amountPaise == null -> Toast.makeText(context, "Enter a valid amount", Toast.LENGTH_SHORT).show()
                    draft.categoryId == null -> Toast.makeText(context, "Pick a category", Toast.LENGTH_SHORT).show()
                    draft.fromScan -> scope.launch {
                        val prompt = vm.prepareGate(amountPaise)
                        if (prompt.severity == Severity.OK) fireAndFinish(amountPaise, prompt.severity)
                        else gate = prompt
                    }
                    else -> {
                        vm.saveManualFromDraft(amountPaise)
                        onDone()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (draft.fromScan) "Continue to pay" else "Log it")
        }
    }

    gate?.let { prompt ->
        val amountPaise = Money.parseToPaise(draft.amountText) ?: return@let
        AlertDialog(
            onDismissRequest = { gate = null },
            title = { Text(if (prompt.severity == Severity.BREACH) "Over budget" else "Ahead of pace") },
            text = { Text(prompt.quip) },
            confirmButton = {
                TextButton(onClick = {
                    gate = null
                    fireAndFinish(amountPaise, prompt.severity)
                }) { Text("Pay anyway") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.recordDodge(amountPaise)
                    gate = null
                    onDone()
                }) { Text("Nope, saved") }
            },
        )
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/EntryScreen.kt
git commit -m "feat: entry screen - shared scan/manual form with severity-gated pay flow"
```

---

### Task 10: Home screen — month card, list, confirm sheet, budget dialog

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/ui/HomeScreen.kt`

- [ ] **Step 1: Write `HomeScreen.kt`**

```kotlin
package com.expensegarden.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.expensegarden.app.core.Money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(vm: MainViewModel, onScan: () -> Unit, onManual: () -> Unit) {
    val monthSpent by vm.monthSpent.collectAsState()
    val budget by vm.monthBudget.collectAsState()
    val pending by vm.pendingConfirm.collectAsState()
    val recent by vm.recent.collectAsState(initial = emptyList())
    var budgetDialogOpen by remember { mutableStateOf(false) }
    val dateFmt = remember { DateTimeFormatter.ofPattern("dd MMM") }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("This month", style = MaterialTheme.typography.labelMedium)
                    Text(Money.display(monthSpent), style = MaterialTheme.typography.headlineMedium)
                    val b = budget
                    TextButton(onClick = { budgetDialogOpen = true }) {
                        Text(if (b == null) "Set a budget" else "Budget: ${Money.display(b.amountPaise)}")
                    }
                }
            }

            pending.firstOrNull()?.let { txn ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Did ${Money.display(txn.amountPaise)} go through?", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { vm.confirmPending(txn.uuid) }) { Text("Log it") }
                            OutlinedButton(onClick = { vm.discardPending(txn.uuid) }) { Text("Discard") }
                        }
                    }
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(recent, key = { it.uuid }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(row.payeeName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${row.categoryName} · ${dateFmt.format(Instant.ofEpochMilli(row.occurredAt).atZone(ZoneId.systemDefault()))}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(Money.display(row.amountPaise), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExtendedFloatingActionButton(onClick = onScan) { Text("Scan & pay") }
            ExtendedFloatingActionButton(onClick = onManual) { Text("Log manually") }
        }
    }

    if (budgetDialogOpen) {
        var text by remember { mutableStateOf(budget?.let { Money.intentAmount(it.amountPaise) } ?: "") }
        AlertDialog(
            onDismissRequest = { budgetDialogOpen = false },
            title = { Text("Monthly budget") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Amount (₹)") })
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setOverallBudget(Money.parseToPaise(text))
                    budgetDialogOpen = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { budgetDialogOpen = false }) { Text("Cancel") } },
        )
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/HomeScreen.kt
git commit -m "feat: home screen - month card, recent list, confirm sheet, budget dialog"
```

---

### Task 11: Wire navigation + QR scan launcher in MainActivity

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/MainActivity.kt` (full replacement below)

- [ ] **Step 1: Replace `MainActivity.kt`**

```kotlin
package com.expensegarden.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.expensegarden.app.capture.UpiUriParser
import com.expensegarden.app.ui.EntryScreen
import com.expensegarden.app.ui.HomeScreen
import com.expensegarden.app.ui.MainViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels {
        MainViewModel.factory((application as GardenApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { GardenNav(vm) }
            }
        }
    }
}

@Composable
private fun GardenNav(vm: MainViewModel) {
    val nav = rememberNavController()
    val context = LocalContext.current

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        val payee = UpiUriParser.parse(contents)
        if (payee == null) {
            Toast.makeText(context, "Not a UPI QR", Toast.LENGTH_SHORT).show()
        } else {
            vm.startScanDraft(payee)
            nav.navigate("entry")
        }
    }

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm = vm,
                onScan = {
                    scanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan a UPI QR")
                            .setBeepEnabled(false)
                            .setOrientationLocked(true)
                    )
                },
                onManual = {
                    vm.startManualDraft()
                    nav.navigate("entry")
                },
            )
        }
        composable("entry") {
            EntryScreen(vm = vm, onDone = { nav.popBackStack("home", inclusive = false) })
        }
    }
}
```

(zxing-embedded's `CaptureActivity` handles the camera runtime permission itself.)

- [ ] **Step 2: Full build + install**

Run: `./gradlew installDebug`
Expected: `BUILD SUCCESSFUL`, app opens to the home screen.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/MainActivity.kt
git commit -m "feat: navigation + qr scan launcher wiring"
```

---

### Task 12: End-to-end verification on device (the real thing)

**Files:** none

- [ ] **Step 1: Full test suite**

Run: `./gradlew test connectedDebugAndroidTest`
Expected: all unit + instrumented tests pass.

- [ ] **Step 2: Manual E2E script — run every line on your phone**

1. Fresh install (`./gradlew installDebug`). Home shows ₹0.00.
2. Set a monthly budget via the card (e.g. ₹10,000).
3. **Manual path:** Log manually → ₹50, "Chaiwala", category Chai & Snacks → Log it. Home shows ₹50.00 and the row.
4. **QR path, OK severity:** Scan & pay → scan any real UPI QR (a shop's, or generate one for your own VPA) → amount ₹1 → category → Continue to pay → **no gate dialog** (OK skips it) → UPI chooser opens → complete or cancel the ₹1 payment in the UPI app → return to the app → the pending card appears at the top of Home → Log it (or Discard if you cancelled). Verify home total.
5. **Gate, BREACH:** set budget to ₹100 (spent ₹51 already) → Scan & pay → ₹60 → gate dialog appears with a quip → "Nope, saved" → back home, nothing logged.
6. **Quip rotation:** repeat step 5 twice — different quip each time.
7. **Payee memory:** scan the same QR again — category pre-selected from last time.
8. Kill the app mid-flow after firing an intent (before confirming), reopen — the pending card is still there (pending survives restart).

- [ ] **Step 3: Fix anything the script surfaces, then tag**

```bash
git add -A
git commit -m "chore: e2e verification fixes" # only if fixes were needed
git tag v0.1-capture-core
```

---

## Self-review (done at write time)

- **Spec coverage (1A scope):** capture loop §5.1 → Tasks 3, 4, 7, 9, 11; manual post-hoc path → Tasks 6, 9; gate + silence-on-OK → Tasks 4, 8, 9; quip cache (static seed, LRU, unused-first) → Tasks 5, 6; `game_event` emission §9.2 → Task 6; payee→category learning §8.2 → Task 6; budget (overall only — the 1A trim) → Tasks 5, 8, 10; `breachedAtLogging` weed-rule input §9.3 → Tasks 5, 6, 8. Backdating UI, per-category budgets, dashboard, garden, LLM, FC import → deferred to 1B–1E as headed in the roadmap.
- **Placeholder scan:** none — every step has complete code or an exact command with expected output.
- **Type consistency:** `Money.parseToPaise/display/intentAmount`, `UpiPayee(vpa, name, amountPaise, note)`, `Severity.{OK, PACE_WARNING, BREACH}`, `LedgerRepository.Draft`, `GatePrompt(severity, quip)`, `TxnRow` — cross-checked across Tasks 5–11; DAO method names in Task 5 match all call sites in Tasks 6 and 8.

## Hardening review (2026-07-04, pre-execution)

Second pass focused on crash paths, silent misbehavior, and hallucination bait for the implementing agent. Fixes applied above:

1. **Crash:** malformed %-encoding in a merchant QR crashed `URLDecoder.decode` mid-scan → `safeDecode` falls back to the raw value; regression test added (parser suite is now 8 tests).
2. **Crash-adjacent UX:** `ModalBottomSheet` with a no-op `onDismissRequest` can wedge half-dismissed → pending-confirm is now an inline card pinned above the transaction list (nothing to dismiss; visible while pending exists).
3. **Dead code / wrong behavior:** `createChooser` never throws `ActivityNotFoundException`, so the "No UPI app found" toast could never fire → explicit `resolveActivity` probe before launching (the manifest `<queries>` block makes UPI handlers visible to it). `resolveActivity`'s deprecation is known-acceptable — its replacement needs API 33+.
4. **Atomicity:** `setOverallBudget` was delete-then-insert without a transaction → wrapped in `db.withTransaction`.
5. **Correctness:** month bounds ended at 23:59:59, dropping the last ~1s of each month → end bound is now next-month-start − 1 ms.
6. **Manifest:** CAMERA permission declared explicitly rather than relying on zxing's manifest merge.
7. **Build noise:** `exportSchema = true` without `room.schemaLocation` warns every build (bait for an agent to "fix" it wrong) → KSP arg added; `schemas/` gets committed, giving schema history in git.
8. **Deprecation bait:** no-arg `Modifier.menuAnchor()` is deprecated in Material3 1.3 → switched to `menuAnchor(MenuAnchorType.PrimaryNotEditable, true)`.
9. **Non-executable instruction:** Gradle wrapper creation was an Android-Studio side effect → now an exact CLI command in the Task 1 preamble.
10. **Polish:** LazyColumn got `contentPadding(bottom = 96.dp)` so the FABs don't cover the last rows.
11. **Process:** header gained explicit agent guardrails — no version bumps, no new dependencies, no deprecation chasing, stop-and-report on any Expected-line mismatch.

## Execution amendments (2026-07-04, found while executing — approved by Rajdweep)

12. **Missing test runner (Task 5 Step 6):** the androidTest dependency set had no path to `androidx.test:runner` — the Studio template gets it transitively via espresso-core, which this leaner matrix deliberately omits — so `connectedDebugAndroidTest` crashed at instrumentation-bind with `ClassNotFoundException: androidx.test.runner.AndroidJUnitRunner` before running any test. Fix: pin `androidx.test:runner:1.6.2` (same Aug-2024 androidx.test release train as ext-junit 1.2.1 / core 1.6.1) and declare it `androidTestImplementation`. Dependency blocks above amended to match.
13. **Filtered-test command syntax (Task 2 Step 2):** in Android modules `test` is an umbrella lifecycle task and rejects `--tests`; single-class runs must target the variant task: `./gradlew testDebugUnitTest --tests "..."`. Bare `./gradlew test` (CLAUDE.md, Task 12) is unaffected. The `--tests` commands in Tasks 2–4 were executed with `testDebugUnitTest`.
14. **Emulator QR-scan recipe (Task 0 Step 3, refined at execution):** (a) AVDs created via `avdmanager` default to `hw.camera.back=emulated` (test pattern) — set `hw.camera.back=virtualscene` in `~/.android/avd/<name>.avd/config.ini`; Studio-created AVDs have it already. (b) The wall-poster image is simply `$SDK/emulator/resources/poster.png` — replace it with a QR PNG (`qrencode -o poster.png -s 16 -m 4 "upi://pay?pa=..."`) and restart the emulator; no Extended Controls needed for the image itself. (c) Pointing the camera at the poster is GUI-bound: with the in-app scanner open, hold ⌥ Option and drag/WASD in the emulator window until the wall QR is in frame — zxing decodes instantly. (The gRPC `rotateVirtualSceneCamera`/`setVirtualSceneCameraVelocity` API does move the camera, but relative-only with no pose readback — not practical for scripted aiming.)
