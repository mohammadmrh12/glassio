// The same rules as the app's Report.scrub (app/src/main/java/com/glasslauncher/tv/data/Report.kt), applied again
// here: an issue is public, so nothing is trusted to arrive clean (an old app version, or someone posting by hand).
const HIDDEN = "[hidden]";
const VALUE = `(\\[hidden\\]|"[^"]*"|'[^']*'|[^\\s,;&}\\])]+)`;

const RULES = [
  [/\b(rtsps?|rtmps?|onvif|smb|ftp):\/\/\S+/gi, "[stream link]"],
  [/\b([a-z][a-z0-9+.-]*:\/\/)[^\s/@:]+(:[^\s/@]*)?@/gi, `$1${HIDDEN}@`],
  [/[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+/g, "[email]"],
  [/\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}/g, "[token]"],
  [/\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}/gi, "$1 [token]"],
  [new RegExp(`(["']?[A-Za-z0-9_.-]*(token|secret|passw(or)?d|pwd|api[_-]?key|apikey|auth|cookie|session|credential|client[_-]?id|\\bkey|\\bpin|\\bcode)[A-Za-z0-9_.-]*["']?\\s*[=:]\\s*)${VALUE}`, "gi"), `$1${HIDDEN}`],
  [new RegExp(`(\\b[A-Za-z0-9_.-]*(?<!version)(Code|Pin|Key)\\b["']?\\s*[=:]\\s*)${VALUE}`, "g"), `$1${HIDDEN}`],
  [/(\b(b?ssid|wi-?fi name|network name)\b["']?\s*[=:]?\s*)(\[hidden\]|"[^"]*"|'[^']*'|[^\s,;]+)/gi, `$1${HIDDEN}`],
  [/\b[0-9a-f]{2}([:-][0-9a-f]{2}){5}\b/gi, "[mac]"],
  [/\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d{1,5})?\b/g, "[address]"],
  [/(?<![0-9a-f:.])(?=[0-9a-f:]*::|([0-9a-f]{1,4}:){4})[0-9a-f]{0,4}(:[0-9a-f]{0,4}){2,7}(%[a-z0-9]+)?(?![0-9a-f:])/gi, "[address]"],
  [/\b[a-z0-9-]+\.(local|lan|home|internal)\b/gi, "[local name]"],
  [/\b[A-Za-z0-9_-]{32,}\b/g, HIDDEN],
  [/(\/Users\/|\/home\/|C:\\Users\\)[^/\\\s]+/g, `$1${HIDDEN}`],
];

export function scrub(text) {
  let t = String(text ?? "");
  for (const [re, to] of RULES) t = t.replace(re, to);
  return t;
}
