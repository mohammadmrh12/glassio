// What a report must look like, and the issue made from it. No network here: these are tested with node.
import { scrub } from "./scrub.js";

export const LIMITS = {
  bodyBytes: 48 * 1024,
  description: [10, 500],
  log: 16000, crash: 4000, settings: 2000, line: 200,
  perInstallPerDay: 5, perAddressPerDay: 10, allPerDay: 150,
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The report cleaned and cut to size, or { error } with what is wrong with it. */
export function check(raw, userAgent) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return { error: "Not a report" };
  if (!/^Glassio\/\d+\.\d+/.test(userAgent || "")) return { error: "Not from Glassio" };
  const text = (v, max) => scrub(typeof v === "string" ? v : "").replace(/\r/g, "").slice(0, max).trim();
  const description = text(raw.description, LIMITS.description[1]);
  // Letters, not a row of dots or one key held down.
  const letters = description.replace(/[^\p{L}\p{N}]/gu, "");
  if (description.length < LIMITS.description[0] || letters.length < 6 || new Set(letters.toLowerCase()).size < 4) return { error: "Describe the problem in a few words" };
  const version = text(raw.version, 40);
  if (!/^\d+\.\d+\.\d+/.test(version)) return { error: "No version" };
  const install = typeof raw.install === "string" && UUID.test(raw.install) ? raw.install.toLowerCase() : "";
  if (!install) return { error: "No install id" };
  const signature = typeof raw.signature === "string" && /^[0-9a-f]{12}$/.test(raw.signature) ? raw.signature : "";
  return {
    description, version, install, signature, test: raw.test === true,
    device: text(raw.device, LIMITS.line), android: text(raw.android, LIMITS.line), profile: text(raw.profile, LIMITS.line),
    settings: text(raw.settings, LIMITS.settings), log: text(raw.log, LIMITS.log), crash: text(raw.crash, LIMITS.crash),
  };
}

// A report can't close its own code block or call anyone: no fences, no @mentions, no issue references.
const quiet = (s) => s.replace(/```/g, "'''").replace(/@(?=[A-Za-z0-9])/g, "@\u200b").replace(/#(?=\d)/g, "#\u200b");
const block = (s) => "```text\n" + quiet(s) + "\n```";

export function title(r) {
  const first = r.description.split("\n")[0].replace(/[`*_<>\[\]#@]/g, "").trim().slice(0, 70);
  return (r.test ? "[test] " : "[TV] ") + (first || "Problem report");
}

export function labels(r) { return r.test ? ["test", "report-from-tv"] : ["bug", "report-from-tv"]; }

/** The issue's text. [again]: the same crash was already reported, so this goes under it as a comment. */
export function body(r, again = false) {
  const parts = [
    again ? "**The same crash, reported again from a TV**" : "**Reported from a TV** (Settings → About → Report a problem)",
    "> " + quiet(r.description).split("\n").join("\n> "),
    block([`Glassio ${r.version}`, r.device, r.android, r.profile].filter(Boolean).join("\n")),
  ];
  if (r.crash) parts.push("<details open><summary>Last crash</summary>\n\n" + block(r.crash) + "\n</details>");
  if (!again && r.settings) parts.push("<details><summary>Settings</summary>\n\n" + block(r.settings) + "\n</details>");
  if (r.log) parts.push("<details><summary>Log</summary>\n\n" + block(r.log) + "\n</details>");
  if (r.signature) parts.push(`<!-- crash:${r.signature} -->`);
  return parts.join("\n\n");
}

export const day = (now = Date.now()) => new Date(now).toISOString().slice(0, 10);
