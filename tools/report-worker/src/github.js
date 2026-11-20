// GitHub as a GitHub App: a short-lived token made from the App's private key (kept in the Worker's secrets), good
// for one repository and for issues only. Nothing here ever expires on its own, unlike a personal token.
const API = "https://api.github.com";

const b64url = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const b64urlText = (s) => b64url(new TextEncoder().encode(s));

function der(len) { // an ASN.1 length
  if (len < 128) return [len];
  const out = []; for (let n = len; n > 0; n >>= 8) out.unshift(n & 255);
  return [0x80 | out.length, ...out];
}

/** The key as PKCS#8 bytes. GitHub hands out PKCS#1 ("BEGIN RSA PRIVATE KEY"); that is wrapped here. */
export function keyBytes(pem) {
  const pkcs1 = /BEGIN RSA PRIVATE KEY/.test(pem);
  const raw = Uint8Array.from(atob(pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "")), (c) => c.charCodeAt(0));
  if (!pkcs1) return raw;
  const algorithm = [0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00]; // rsaEncryption
  const inner = [0x02, 0x01, 0x00, ...algorithm, 0x04, ...der(raw.length), ...raw];
  return Uint8Array.from([0x30, ...der(inner.length), ...inner]);
}

let cached = { token: "", until: 0 };

async function token(env) {
  if (cached.token && Date.now() < cached.until) return cached.token;
  const now = Math.floor(Date.now() / 1000);
  const head = b64urlText(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64urlText(JSON.stringify({ iat: now - 60, exp: now + 540, iss: String(env.GH_APP_ID) }));
  const key = await crypto.subtle.importKey("pkcs8", keyBytes(env.GH_PRIVATE_KEY), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${head}.${claims}`));
  const r = await fetch(`${API}/app/installations/${env.GH_INSTALLATION_ID}/access_tokens`, {
    method: "POST",
    headers: { Authorization: `Bearer ${head}.${claims}.${b64url(sig)}`, Accept: "application/vnd.github+json", "User-Agent": "glassio-report-relay" },
    body: JSON.stringify({ repositories: [env.REPO.split("/")[1]], permissions: { issues: "write" } }),
  });
  if (!r.ok) throw new Error(`GitHub token: HTTP ${r.status}`);
  const j = await r.json();
  cached = { token: j.token, until: Date.now() + 50 * 60 * 1000 };
  return cached.token;
}

async function call(env, method, path, json) {
  const r = await fetch(`${API}/repos/${env.REPO}${path}`, {
    method,
    headers: { Authorization: `Bearer ${await token(env)}`, Accept: "application/vnd.github+json", "User-Agent": "glassio-report-relay", "X-GitHub-Api-Version": "2022-11-28" },
    body: json ? JSON.stringify(json) : undefined,
  });
  if (!r.ok) throw new Error(`GitHub ${method} ${path.split("?")[0]}: HTTP ${r.status}`);
  return r.json();
}

export const github = {
  open: (env, title, body, labels) => call(env, "POST", "/issues", { title, body, labels }),
  get: (env, number) => call(env, "GET", `/issues/${number}`),
  comment: (env, number, body) => call(env, "POST", `/issues/${number}/comments`, { body }),
};
