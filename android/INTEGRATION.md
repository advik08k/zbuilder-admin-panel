# Android integration — Phase 2 + Phase 3

Drop-in instructions for the ZBuilder Android app. `android/` holds the **new**
files; the three **modifications** to existing files are listed below.

---

## A. New files (copy these into the app)

| Source in this repo | Destination in `D:\ZBuilderPro\app\src\main\` |
|---|---|
| `android/java/com/teamzinfinity/zbuilder/BuildAdActivity.kt` | `java/com/teamzinfinity/zbuilder/BuildAdActivity.kt` |
| `android/res/layout/activity_build_ad.xml` | `res/layout/activity_build_ad.xml` |
| `android/res/drawable/bg_skip_pill.xml` | `res/drawable/bg_skip_pill.xml` |
| `android/res/drawable/bg_ad_gradient.xml` | `res/drawable/bg_ad_gradient.xml` |

---

## B. Modifications to existing files

### B1. `app/src/main/AndroidManifest.xml`

Register the activity (next to `FallbackAdActivity`):

```xml
<activity android:name=".BuildAdActivity"
          android:theme="@style/SplashTheme"
          android:screenOrientation="portrait"
          android:exported="false" />
```

`INTERNET` and `android:usesCleartextTraffic="true"` were already present — no
change needed there.

### B2. `app/build.gradle.kts`

Add the image loader, right after the StartApp dependency:

```kotlin
implementation("com.startapp:inapp-sdk:4.11.0")

// Custom Fallback Ad - loads adImageUrl on BuildAdActivity
implementation("com.github.bumptech.glide:glide:4.16.0")
```

### B3. `SupportActivity.kt` — switch the fallback screen

```kotlin
private fun fallbackToWaitScreen(rewardType: Int) {
    val intent = Intent(this, BuildAdActivity::class.java)   // was FallbackAdActivity
    intent.putExtra("REWARD_TYPE", rewardType)
    startActivity(intent)
}
```

`FallbackAdActivity` is left untouched so reverting is a one-line change.

---

## C. Verify

```powershell
C:\Users\abcd\.gradle\wrapper\dists\gradle-8.4-bin\1w5dpkrfk8irigvoxmyhowfim\gradle-8.4\bin\gradle.bat -p D:\ZBuilderPro assembleDebug
```

Already verified green: **`BUILD SUCCESSFUL in 17s`** (Glide 4.16.0 + R8).

---

## D. Reward matrix (why `finishSafely(grantReward:)` exists)

| Situation | Reward | Reason |
|---|---|---|
| Network fail / bad JSON | **No** | otherwise airplane mode farms free builds |
| Image fails to load | **No** | same anti-abuse reasoning |
| `isActive == false` | Yes | admin turned ads off — nothing to watch |
| Timer finished → skip / back | Yes | the user waited |

---

## E. Day-to-day ad updates

1. Open the Render URL → password
2. Toggle **Ad active**, paste image URL, set redirect + timer
3. **Save & publish** → creates a commit on `ad-config.json` in `ZBuilder-Plugins`
4. In-app effect: immediate after force-close (the code sends a `?cb=<ts>`
   cache-buster retry), otherwise within GitHub's ~5 min raw CDN window
