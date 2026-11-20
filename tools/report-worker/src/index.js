// Glassio's report relay: takes a problem report from a TV and opens an issue for it (or adds to the issue of the
// same crash). The TV holds no GitHub key; this Worker holds a GitHub App's, in its secrets.
//
//   POST /report   the report as JSON (see check() in report.js)   →  { ok, number, url, added }
//   GET  /         "ok"
//
// Settings (wrangler secret put …): GH_APP_ID, GH_INSTALLATION_ID, GH_PRIVATE_KEY. In wrangler.toml: REPO, and the
// KV namespace LIMITS (counters and the crash → issue map). DRY_RUN=1 answers without touching GitHub (local tests).
import { check, title, body, labels, LIMITS, day } from "./report.js";
import { github } from "./github.js";

const json = (status, data) => new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });

async function sha(text) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 24);
}

/** Counts one more under [key] today; false when that passes [max]. (KV is not exact under a burst: good enough here.) */
async function allow(env, key, max) {
  const k = `n:${day()}:${key}`;
  const n = parseInt((await env.LIMITS.get(k)) || "0", 10);
  if (n >= max) return false;
  await env.LIMITS.put(k, String(n + 1), { expirationTtl: 2 * 24 * 3600 });
  return true;
}

async function report(request, env) {
  const length = parseInt(request.headers.get("Content-Length") || "0", 10);
  if (length > LIMITS.bodyBytes) return json(413, { ok: false, error: "The report is too big" });
  const text = await request.text();
  if (!text.trim()) return json(400, { ok: false, error: "Empty report" });
  if (new TextEncoder().encode(text).length > LIMITS.bodyBytes) return json(413, { ok: false, error: "The report is too big" });
  let raw; try { raw = JSON.parse(text); } catch { return json(400, { ok: false, error: "Not a report" }); }
  const r = check(raw, request.headers.get("User-Agent"));
  if (r.error) return json(400, { ok: false, error: r.error });

  // The address is only ever kept as a hash, for a day, to count.
  const address = await sha("a:" + (request.headers.get("CF-Connecting-IP") || "?"));
  const install = await sha("i:" + r.install);
  // The same words from the same TV twice in ten minutes: a second press of Send.
  const twin = `dup:${await sha(install + r.description)}`;
  const before = await env.LIMITS.get(twin);
  if (before) return json(200, { ok: true, ...JSON.parse(before), added: false });
  if (!(await allow(env, `i:${install}`, LIMITS.perInstallPerDay))) return json(429, { ok: false, error: "Too many reports from this TV today" });
  if (!(await allow(env, `a:${address}`, LIMITS.perAddressPerDay))) return json(429, { ok: false, error: "Too many reports today" });
  if (!(await allow(env, "all", LIMITS.allPerDay))) return json(429, { ok: false, error: "Too many reports today" });

  if (env.DRY_RUN === "1") return json(200, { ok: true, number: 0, url: "dry-run", added: false, title: title(r), labels: labels(r), body: body(r) });

  // The same crash already has an open issue: this report goes under it.
  const sigKey = r.signature && !r.test ? `crash:${r.signature}` : "";
  const known = sigKey ? parseInt((await env.LIMITS.get(sigKey)) || "0", 10) : 0;
  if (known) {
    const issue = await github.get(env, known).catch(() => null);
    if (issue && issue.state === "open") {
      await github.comment(env, known, body(r, true));
      const out = { number: known, url: issue.html_url };
      await env.LIMITS.put(twin, JSON.stringify(out), { expirationTtl: 600 });
      return json(200, { ok: true, ...out, added: true });
    }
  }
  const issue = await github.open(env, title(r), body(r), labels(r));
  const out = { number: issue.number, url: issue.html_url };
  if (sigKey) await env.LIMITS.put(sigKey, String(issue.number), { expirationTtl: 180 * 24 * 3600 });
  await env.LIMITS.put(twin, JSON.stringify(out), { expirationTtl: 600 });
  return json(200, { ok: true, ...out, added: false });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/") return new Response("ok");
    if (request.method !== "POST" || url.pathname !== "/report") return json(404, { ok: false, error: "Not found" });
    try { return await report(request, env); }
    catch (e) { console.log("report failed:", e && e.message); return json(502, { ok: false, error: "Couldn't open the issue" }); }
  },
};
