/**
 * ZBuilder - Admin Panel (Render.com free tier)
 * ---------------------------------------------------------------
 * The "database" is a single JSON file in a GitHub repo.
 * Reads  -> GET  /repos/{owner}/{repo}/contents/{path}
 * Writes -> PUT  /repos/{owner}/{repo}/contents/{path}  (commit)
 *
 * Requires Node 18+ (global fetch). No Octokit dependency needed.
 */

require("dotenv").config();

const express = require("express");
const path = require("path");
const crypto = require("crypto");

// ---------------------------------------------------------------- config
const PORT = process.env.PORT || 3000;
const OWNER = process.env.GITHUB_OWNER;
const REPO = process.env.GITHUB_REPO;
const BRANCH = process.env.GITHUB_BRANCH || "main";
const FILE_PATH = process.env.CONFIG_PATH || "ad-config.json";
const TOKEN = process.env.GITHUB_TOKEN;
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD;
const SESSION_SECRET = process.env.SESSION_SECRET;
const COOKIE_NAME = "zb_admin_session";

const missing = [];
if (!OWNER) missing.push("GITHUB_OWNER");
if (!REPO) missing.push("GITHUB_REPO");
if (!TOKEN) missing.push("GITHUB_TOKEN");
if (!ADMIN_PASSWORD) missing.push("ADMIN_PASSWORD");
if (!SESSION_SECRET) missing.push("SESSION_SECRET");
if (missing.length) {
  console.error("[FATAL] Missing env vars: " + missing.join(", "));
  console.error("        Copy .env.example -> .env and fill it in.");
  process.exit(1);
}

const API = "https://api.github.com";
const RAW_URL = `https://raw.githubusercontent.com/${OWNER}/${REPO}/${BRANCH}/${FILE_PATH}`;

const app = express();
app.use(express.json({ limit: "32kb" }));

// ------------------------------------------------------------- security
// Tiny rate limiter for the login endpoint (in-memory, per IP).
const loginAttempts = new Map();
function rateLimited(ip) {
  const rec = loginAttempts.get(ip);
  if (!rec) return false;
  if (Date.now() > rec.resetAt) {
    loginAttempts.delete(ip);
    return false;
  }
  return rec.count >= 5;
}
function bumpAttempt(ip) {
  const now = Date.now();
  const rec = loginAttempts.get(ip);
  if (!rec || now > rec.resetAt) {
    loginAttempts.set(ip, { count: 1, resetAt: now + 15 * 60 * 1000 });
  } else {
    rec.count += 1;
  }
}
function resetAttempts(ip) {
  loginAttempts.delete(ip);
}

// Constant-time compare so the password can't be brute-forced by timing.
function safeEqual(a, b) {
  const ab = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  if (ab.length !== bb.length) return false;
  return crypto.timingSafeEqual(ab, bb);
}

// Session cookie = random id + HMAC signature, signed with SESSION_SECRET.
function signSession(id) {
  return crypto.createHmac("sha256", SESSION_SECRET).update(id).digest("hex");
}
function createSession() {
  const id = crypto.randomBytes(24).toString("hex");
  return `${id}.${signSession(id)}`;
}
function sessionValid(token) {
  if (typeof token !== "string" || !token.includes(".")) return false;
  const [id, sig] = token.split(".");
  return safeEqual(sig, signSession(id));
}
function parseCookies(req) {
  const out = {};
  const header = req.headers.cookie;
  if (!header) return out;
  for (const part of header.split(";")) {
    const i = part.indexOf("=");
    if (i > -1) out[part.slice(0, i).trim()] = part.slice(i + 1).trim();
  }
  return out;
}
function requireAuth(req, res, next) {
  const cookies = parseCookies(req);
  if (sessionValid(cookies[COOKIE_NAME])) return next();
  res.status(401).json({ ok: false, error: "Not authenticated" });
}

// Strip the token before it can ever reach a response body.
function scrub(err) {
  let msg = err && err.message ? String(err.message) : "Unknown error";
  if (TOKEN) msg = msg.split(TOKEN).join("[REDACTED]");
  return msg;
}

// ------------------------------------------------------------ GitHub API
async function gh(path, init = {}) {
  const res = await fetch(API + path, {
    ...init,
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${TOKEN}`,
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "zbuilder-admin-panel",
      ...(init.headers || {}),
    },
  });

  const text = await res.text();
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch (_) {
    /* non-JSON body */
  }

  if (!res.ok) {
    const e = new Error(
      (body && body.message) || `GitHub API returned ${res.status}`
    );
    e.status = res.status;
    throw e;
  }
  return body;
}

const encodePath = () => FILE_PATH.split("/").map(encodeURIComponent).join("/");
const contentsUrl = () =>
  `/repos/${encodeURIComponent(OWNER)}/${encodeURIComponent(REPO)}/contents/${encodePath()}`;

/** GET ad-config.json (and its blob sha, which PUT needs for updates). */
async function getConfig() {
  const data = await gh(`${contentsUrl()}?ref=${encodeURIComponent(BRANCH)}`);
  const json = Buffer.from(data.content, "base64").toString("utf8");
  let parsed;
  try {
    parsed = JSON.parse(json);
  } catch (_) {
    const e = new Error("ad-config.json on GitHub is not valid JSON");
    e.status = 502;
    throw e;
  }
  return { sha: data.sha, config: parsed };
}

/** PUT a new version of ad-config.json (creates a commit). */
async function putConfig(config, sha, message) {
  const content = Buffer.from(JSON.stringify(config, null, 2) + "\n").toString(
    "base64"
  );
  const payload = {
    message: message || `chore: update ad config (${new Date().toISOString()})`,
    content,
    branch: BRANCH,
  };
  if (sha) payload.sha = sha; // omit on first-time creation

  return gh(contentsUrl(), {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
}

/** Validate + normalise whatever the dashboard sent. */
function sanitize(input) {
  const errors = [];
  const asBool = (v) =>
    v === true || v === "true" || v === 1 || v === "1" || v === "on";

  const isActive = asBool(input.isActive);

  const adImageUrl = String(input.adImageUrl || "").trim();
  const redirectLink = String(input.redirectLink || "").trim();

  const isHttp = (u) => /^https?:\/\/\S+$/i.test(u);
  if (adImageUrl && !isHttp(adImageUrl))
    errors.push("adImageUrl must start with http:// or https://");
  if (redirectLink && !isHttp(redirectLink))
    errors.push("redirectLink must start with http:// or https://");

  if (errors.length) {
    const e = new Error(errors.join(" | "));
    e.status = 400;
    throw e;
  }

  // NOTE: no timerSeconds here on purpose. The wait is decided by the app
  // from the reward being claimed (20s -> +1 build, 60s -> +3 builds), so a
  // panel-controlled timer would contradict what the reward pays out.
  return { isActive, adImageUrl, redirectLink };
}

// ------------------------------------------------------------- dashboard
const ADMIN_HTML = path.join(__dirname, "public", "index.html");

app.get("/", (_req, res) => res.sendFile(ADMIN_HTML));

// Health probe (Render uses this).
app.get("/healthz", (_req, res) => res.json({ ok: true, ts: Date.now() }));

// --- auth -------------------------------------------------------------
app.post("/api/login", (req, res) => {
  const ip = req.ip || "unknown";
  if (rateLimited(ip)) {
    return res
      .status(429)
      .json({ ok: false, error: "Too many attempts. Try again in 15 min." });
  }

  const password = req.body && req.body.password;
  if (typeof password !== "string" || !safeEqual(password, ADMIN_PASSWORD)) {
    bumpAttempt(ip);
    return res.status(401).json({ ok: false, error: "Wrong password" });
  }

  resetAttempts(ip);
  const token = createSession();
  const secure = process.env.NODE_ENV === "production" ? "; Secure" : "";
  res.setHeader(
    "Set-Cookie",
    `${COOKIE_NAME}=${token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=43200${secure}`
  );
  res.json({ ok: true });
});

app.post("/api/logout", (_req, res) => {
  res.setHeader(
    "Set-Cookie",
    `${COOKIE_NAME}=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0`
  );
  res.json({ ok: true });
});

app.get("/api/session", (req, res) => {
  const cookies = parseCookies(req);
  res.json({ ok: sessionValid(cookies[COOKIE_NAME]) });
});

// --- config -----------------------------------------------------------

// Read current settings (used by the dashboard on load).
app.get("/api/config", requireAuth, async (_req, res) => {
  try {
    const { sha, config } = await getConfig();
    res.json({ ok: true, config, sha, rawUrl: RAW_URL });
  } catch (err) {
    res.status(err.status || 500).json({ ok: false, error: scrub(err) });
  }
});

// Save settings -> commits to GitHub.
app.post("/api/config", requireAuth, async (req, res) => {
  try {
    const config = sanitize((req.body && req.body.config) || {});

    // Re-read the latest sha so we never commit against a stale blob.
    let sha = null;
    try {
      sha = (await getConfig()).sha;
    } catch (err) {
      // 404 => file doesn't exist yet; PUT without sha will create it.
      if (err.status !== 404) throw err;
    }

    const saved = await putConfig(config, sha, req.body && req.body.message);
    res.json({
      ok: true,
      config,
      commit: saved.commit && saved.commit.sha,
      rawUrl: RAW_URL,
    });
  } catch (err) {
    res.status(err.status || 500).json({ ok: false, error: scrub(err) });
  }
});

// Preview what the Android app will actually fetch (public, uncached-ish).
app.get("/api/preview", async (_req, res) => {
  try {
    const { config, sha } = await getConfig();
    res.json({ ok: true, config, sha, rawUrl: RAW_URL });
  } catch (err) {
    res.status(err.status || 500).json({ ok: false, error: scrub(err) });
  }
});

// --- boot -------------------------------------------------------------
app.listen(PORT, () => {
  console.log(`ZBuilder admin panel listening on :${PORT}`);
  console.log(`  repo   : ${OWNER}/${REPO} @ ${BRANCH}`);
  console.log(`  file   : ${FILE_PATH}`);
  console.log(`  rawUrl : ${RAW_URL}`);
});
