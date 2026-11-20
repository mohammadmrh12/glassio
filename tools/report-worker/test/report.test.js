import test from "node:test";
import assert from "node:assert/strict";
import { scrub } from "../src/scrub.js";
import { check, title, body, labels, LIMITS } from "../src/report.js";
import { keyBytes } from "../src/github.js";

const UA = "Glassio/4.0.0-beta1";
const ok = (over = {}) => ({ description: "The dock freezes after the screensaver", version: "4.0.0-beta1 (68)", install: "0f8fad5b-d9cb-469f-a165-70867728950e",
  device: "Google Streamer", android: "Android 14 (API 34)", profile: "profile 1 of 2", settings: "theme=water", log: "11-19 17:40:12 W x", crash: "", signature: "", ...over });

test("private things are removed", () => {
  const gone = (secret, text) => assert.ok(!scrub(text).includes(secret), `${secret} is still in: ${scrub(text)}`);
  gone("s3cret", "Camera rtsp://admin:s3cret@198.51.100.50:554/stream1 failed");
  gone("198.51.100.50", "Camera rtsp://admin:s3cret@198.51.100.50:554/stream1 failed");
  gone("hunter2", "GET https://bob:hunter2@example.com/x");
  for (const a of ["203.0.113.12", "10.20.30.40", "172.16.4.9", "8.8.4.4"]) gone(a, `Sync with ${a}:8765 failed`);
  gone("fe80::1c2d:3eff:fe4a:5b6c", "bound to fe80::1c2d:3eff:fe4a:5b6c%wlan0");
  gone("AA:BB:CC:11:22:33", "Bluetooth AA:BB:CC:11:22:33 disconnected");
  gone("someone.name+tv@example.com", "Nuvio account connected (someone.name+tv@example.com)");
  gone("abc123def", "Authorization: Bearer abc123def456");
  gone("tok_9f8e7d", "nuvioToken=tok_9f8e7d");
  gone("0123", "lockCode=0123");
  gone("p4ss", '{"password": "p4ss w0rd", "x": 1}');
  gone("abcdef0123456789abcdef0123456789", "tmdb abcdef0123456789abcdef0123456789 refused");
  gone("MyHomeWifi", 'connected, SSID="MyHomeWifi" rssi -50');
  gone("alex", "/Users/alex/work/x.kt");
  gone("livingroom-tv", "Hue bridge at livingroom-tv.local");
});

test("what a report is for stays readable, and scrubbing twice changes nothing", () => {
  const keep = "11-19 17:40:12 W Title link from JustWatch: IllegalStateException: HTTP 503 at com.glasslauncher.tv.data.PlatformLinks.find(PlatformLinks.kt:120)";
  assert.equal(scrub(keep), keep);
  assert.equal(scrub("theme=water_still  versionCode=68  memTier=2"), "theme=water_still  versionCode=68  memTier=2");
  const once = scrub("rtsp://a:b@10.0.0.2/x token=abc mail me@x.io");
  assert.equal(scrub(once), once);
});

test("only a real report from Glassio is taken", () => {
  assert.equal(check(ok(), UA).error, undefined);
  assert.ok(check(ok(), "curl/8").error);
  assert.ok(check(ok(), "").error);
  assert.ok(check(null, UA).error);
  assert.ok(check([], UA).error);
  assert.ok(check(ok({ description: "" }), UA).error);
  assert.ok(check(ok({ description: "   bad   " }), UA).error);
  assert.ok(check(ok({ description: "..........................." }), UA).error);
  assert.ok(check(ok({ description: "aaaaaaaaaaaaaaaaaaaaaaaa" }), UA).error);
  assert.ok(check(ok({ version: "latest" }), UA).error);
  assert.ok(check(ok({ install: "tv-1" }), UA).error);
  assert.equal(check(ok({ description: "مشكلة بالدوك بعد الشاشة الحافظة" }), UA).error, undefined);
});

test("a report is cut to size and cleaned again here", () => {
  const r = check(ok({ description: "x".repeat(40) + " abcdefgh " + "y".repeat(2000), log: "z ".repeat(40000), crash: "c ".repeat(9000),
    device: "TV at 198.51.100.9", signature: "not-a-signature", test: "yes" }), UA);
  assert.ok(r.description.length <= LIMITS.description[1]);
  assert.ok(r.log.length <= LIMITS.log && r.crash.length <= LIMITS.crash);
  assert.ok(!r.device.includes("198.51.100.9"));
  assert.equal(r.signature, "");
  assert.equal(r.test, false);
});

test("the issue can't call anyone or break out of its block", () => {
  const r = check(ok({ description: "Crash @mohammadmrh12 see #12\n```\n# Big title", log: "```\n@someone closes #3", crash: "java.lang.IllegalStateException: x", signature: "0123456789ab" }), UA);
  const b = body(r);
  assert.ok(!/@[A-Za-z0-9]/.test(b), b);
  assert.ok(!/#\d/.test(b));
  assert.equal((b.match(/```/g) || []).length % 2, 0);
  assert.equal((b.match(/```/g) || []).length, 8); // four blocks: about, crash, settings, log
  assert.ok(b.includes("<!-- crash:0123456789ab -->"));
  assert.ok(title(r).startsWith("[TV] Crash"));
  assert.ok(!/[`#@\[\]]/.test(title(r).slice(4)));
  assert.deepEqual(labels(r), ["bug", "report-from-tv"]);
  assert.deepEqual(labels({ ...r, test: true }), ["test", "report-from-tv"]);
  assert.ok(title({ ...r, test: true }).startsWith("[test] "));
  assert.ok(body(r, true).startsWith("**The same crash"));
});

test("GitHub's PKCS#1 key is wrapped as PKCS#8", async () => {
  const { generateKeyPairSync } = await import("node:crypto");
  const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const pkcs1 = privateKey.export({ type: "pkcs1", format: "pem" });
  const pkcs8 = privateKey.export({ type: "pkcs8", format: "der" });
  assert.deepEqual(Buffer.from(keyBytes(pkcs1)), pkcs8);
  const key = await crypto.subtle.importKey("pkcs8", keyBytes(pkcs1), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  assert.equal(key.type, "private");
});
