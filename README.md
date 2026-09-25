# ZBuilder · Custom Fallback Ad & Admin Panel

A GitHub-repo-backed ad system. **No database, no cold starts.**
The config lives in a single JSON file; the Android app reads it straight
from `raw.githubusercontent.com` (instant), and the admin dashboard writes
it through the GitHub REST API (from Render.com).

```
┌────────────────┐   POST /api/config    ┌──────────────────┐
│ Admin Dashboard│ ────────────────────► │  Render.com      │
│ (public/index) │                       │  server.js       │
└────────────────┘                       └────────┬─────────┘
                                                  │ GitHub REST API (PAT)
                                                  ▼
                                         ┌──────────────────┐
                                         │  GitHub Repo     │
                                         │  ad-config.json  │  ◄── the "database"
                                         └────────┬─────────┘
                                                  │ raw.githubusercontent.com
                                                  │ (direct, no server)
                                                  ▼
                                         ┌──────────────────┐
                                         │  ZBuilder App    │
                                         │  BuildAdActivity │
                                         └──────────────────┘
```

---

## Phase 1 — Backend

### 1.1 Create the config file (the "database")

Commit this file to the root of your repo — **`ad-config.json`**:

```json
{
  "isActive": true,
  "adImageUrl": "https://raw.githubusercontent.com/advik08k/ZBuilder-Plugins/main/ads/sample-banner.png",
  "redirectLink": "https://github.com/advik08k/ZBuilder-Plugins",
  "timerSeconds": 5
}
```

| Field | Type | Meaning |
|---|---|---|
| `isActive` | bool | `false` → app shows nothing and closes instantly |
| `adImageUrl` | string | Full-screen ad image (`centerCrop`) |
| `redirectLink` | string | Opens when the user taps the image |
| `timerSeconds` | int 1–120 | Countdown before the skip pill becomes clickable |

A ready-to-use copy is at `admin-panel/ad-config.json`.

> The file must be on the **`main` branch** and the repo **public** — otherwise
> `raw.githubusercontent.com` will return 404 to the app.

### 1.2 Personal Access Token

1. GitHub → **Settings → Developer settings → Personal access tokens → Tokens (classic)**
2. **Generate new token (classic)**, expiry as you like
3. Scope: check **`repo`** (full control of private repositories)
4. Copy it — you only see it once

### 1.3 Local run

```bash
cd admin-panel
cp .env.example .env      # Windows: copy .env.example .env
# edit .env -> fill GITHUB_TOKEN, ADMIN_PASSWORD, SESSION_SECRET
npm install
npm start
# -> http://localhost:3000
```

Generate a strong `SESSION_SECRET`:
```bash
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
```

### 1.4 Deploy to Render.com (free tier)

1. Push `admin-panel/` to a GitHub repo
2. Render → **New → Web Service** → connect that repo
3. Settings:
   - **Runtime**: Node
   - **Build Command**: `npm install`
   - **Start Command**: `npm start`
4. **Environment → Add Environment Variable** — copy every key from `.env.example`
   (Render has no `.env` file; set `GITHUB_TOKEN`, `ADMIN_PASSWORD`,
   `SESSION_SECRET`, `GITHUB_OWNER`, `GITHUB_REPO` etc. here)
5. Set `NODE_ENV=production` → this turns on the `Secure` cookie flag
6. **Create Web Service**

Open `https://<your-service>.onrender.com` → password → dashboard.

> Free tier sleeps after inactivity. First request takes ~30s (Render cold start).
> **This does not affect the app** — the app never talks to Render.

### 1.5 Security notes

- The PAT only exists server-side in Render env vars. The browser never sees it.
- The token string is redacted from any error message before it is sent back.
- Login is rate-limited (5 attempts / 15 min per IP) and compared constant-time.
- Session is an HMAC-signed `HttpOnly; SameSite=Strict` cookie (+ `Secure` in prod).
- **Do not commit `.env`.** It is in `.gitignore`.

---

## Phase 2 — Android UI

### Files added

**`app/src/main/res/layout/activity_build_ad.xml`**

Root is a `FrameLayout`. That is what makes the overlap work: children of a
`FrameLayout` stack on the Z-axis, and the **skip pill comes last in source
order**, so it is drawn *on top of* the image regardless of where the image sits.

```xml
<FrameLayout  android:background="#000000">

    <ImageView android:id="@+id/ivAdImage"
               android:scaleType="centerCrop" />          <!-- fills screen -->

    <View      android:background="@drawable/bg_ad_gradient" />  <!-- bottom scrim -->
    <LinearLayout ... >SPONSORED / tap hint</LinearLayout>

    <!-- drawn LAST => sits over the image -->
    <TextView  android:id="@+id/tvSkip"
               android:layout_gravity="top|end"           <!-- top-right -->
               android:background="@drawable/bg_skip_pill"
               android:textColor="#FFFFFF" />
</FrameLayout>
```

Key points:

- `layout_gravity="top|end"` → exact top-right corner
- `bg_skip_pill.xml` → `#B3000000` (70% black) with `24dp` radius + 1dp white
  stroke, so it stays readable over **any** image — light, dark, or busy
- `minWidth="96dp"` + centered text → the pill doesn't jitter as the number
  changes from `10` → `9`
- In `BuildAdActivity`, `applyTopInsetToSkipPill()` adds the status-bar height
  to `topMargin`, so the pill never hides behind the system clock
- `bottomScrim` gradient keeps "SPONSORED" readable over bright images

**`app/src/main/res/drawable/bg_skip_pill.xml`** — the pill shape
**`app/src/main/res/drawable/bg_ad_gradient.xml`** — bottom scrim

### Dependency

`app/build.gradle.kts`:
```kotlin
implementation("com.github.bumptech.glide:glide:4.16.0")
```

### Manifest

```xml
<activity android:name=".BuildAdActivity"
          android:theme="@style/SplashTheme"
          android:screenOrientation="portrait"
          android:exported="false" />
```

(`INTERNET` + `usesCleartextTraffic` were already present.)

---

## Phase 3 — Kotlin logic

**`app/src/main/java/com/teamzinfinity/zbuilder/BuildAdActivity.kt`**

Flow:

```
onCreate
  └─ fetchConfig()                       background thread
       └─ GET raw.githubusercontent.com  (+ cache-busted retry)
            └─ handleConfig(json)
                 ├─ parse failed        -> finish, NO reward
                 ├─ isActive == false   -> finish, reward granted
                 ├- imageUrl blank      -> finish, reward granted
                 └─ ok -> loadImage() + startTimer(N)

startTimer(N)
  onTick   -> tvSkip = "Skip in 5", "Skip in 4", ...
  onFinish -> tvSkip = "Skip Ad", clickable = true, canSkip = true

image tap  -> Intent(ACTION_VIEW, redirectLink)
skip tap   -> finishSafely(reward)
back press -> ignored until canSkip, then same as skip
```

### Design decisions

**1. Direct fetch, Render bypassed.**
```kotlin
private const val CONFIG_URL =
    "https://raw.githubusercontent.com/advik08k/ZBuilder-Plugins/main/ad-config.json"
```
A `HttpURLConnection` on a background thread, 6s connect/read timeouts. It first
tries the plain URL, then retries with `?cb=<timestamp>` — GitHub's raw CDN
caches for ~5 min, so the cache-buster is how you make an update show up
immediately.

**2. Reward is not free.** `finishSafely(grantReward:)`:

| Situation | Reward? | Why |
|---|---|---|
| Network fail / bad JSON | **No** | Otherwise airplane mode farms free builds |
| `isActive == false` | Yes | Admin turned ads off — nothing to watch |
| Image fails to load | **No** | Same anti-abuse reasoning |
| Timer finished → skip | Yes | The user waited |

**3. Back button blocked.**
```kotlin
override fun onBackPressed() {
    if (!canSkip) { toast("Please wait - $remaining"); return }  // super NOT called
    finishSafely(grantReward = true)
}
```
Calling `super` would finish the activity — so we simply don't.

**4. Pill is a label, then a button.** `setSkipEnabled(false)` installs a
no-op listener so early taps do nothing; `setSkipEnabled(true)` swaps in the
real dismiss listener only when the timer hits zero.

**5. Ads change often** → `DiskCacheStrategy.NONE`, so a new image from the
panel always shows.

---

## Wiring it into the app

`SupportActivity.kt` currently falls back to the old screen:

```kotlin
private fun fallbackToWaitScreen(rewardType: Int) {
    val intent = Intent(this, FallbackAdActivity::class.java)   // ← old
    intent.putExtra("REWARD_TYPE", rewardType)
    startActivity(intent)
}
```

Swap `FallbackAdActivity::class.java` → `BuildAdActivity::class.java`.
`REWARD_TYPE` is already honoured (`1` = +1 build, `2` = +3 builds), so it is
a drop-in replacement. I left the old activity untouched so you can switch
back instantly if anything misbehaves.

---

## Updating the ad (day-to-day)

1. Open the Render URL → password
2. Toggle **Ad active**, paste image URL, set redirect + timer
3. **Save & publish** → creates a GitHub commit on `ad-config.json`
4. In-app effect: immediate if you force-close the app (cache-buster), else ≤5 min

Watch the commit history: [ZBuilder-Plugins/commits/main](https://github.com/advik08k/ZBuilder-Plugins/commits/main)

---

## File inventory

```
admin-panel/
├── package.json
├── server.js              Express + GitHub REST API
├── public/index.html      Admin dashboard (single file)
├── .env.example
├── .gitignore
└── ad-config.json         sample of the "database"

app/src/main/
├── AndroidManifest.xml                        (+ BuildAdActivity)
├── java/.../BuildAdActivity.kt                (Phase 3)
├── res/layout/activity_build_ad.xml           (Phase 2)
├── res/drawable/bg_skip_pill.xml
└── res/drawable/bg_ad_gradient.xml

app/build.gradle.kts                           (+ Glide)
```
