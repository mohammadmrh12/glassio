# Glassio's report relay

A small Cloudflare Worker. A TV sends it a problem report (Settings → About → Report a problem) and it opens an
issue in `mohammadmrh12/glassio` with the labels `bug` and `report-from-tv`. The TV holds no GitHub key: the Worker
signs in as a GitHub App whose key lives in Cloudflare's secrets.

## What it does with a report

- **Takes only a real one**: from Glassio (its `User-Agent`), JSON, at most 48 KB, a description of 10 to 500
  characters that is words (not dots or one key held down), a version and an install id.
- **Cleans it again** (`src/scrub.js`, the same rules as the app's `Report.scrub`): camera and stream links,
  names and passwords in links, emails, tokens and keys, network names, MAC and network addresses, personal folders.
- **Limits**: 5 a day per TV (a random id made once per install), 10 a day per network address, 150 a day in all.
  Addresses and ids are only ever stored as hashes, for two days, as counters.
- **The same crash** (same exception and same first Glassio line) is added as a comment to its open issue instead of
  a new one. The same words from the same TV within ten minutes are one report.
- **Can't misbehave on GitHub**: everything from the TV is inside code blocks, with `@mentions`, `#numbers` and
  code fences defused.
- A report marked as a test carries the labels `test` and `report-from-tv` (no `bug`).

## Files

| File | What it holds |
|---|---|
| `src/index.js` | The request: limits, the crash → issue map, the answer |
| `src/report.js` | What a report must look like, and the issue's title, labels and text |
| `src/scrub.js` | The cleaning rules |
| `src/github.js` | Signing in as the GitHub App, opening an issue, adding a comment |
| `test/report.test.js` | `npm test` (plain node, no install) |
| `wrangler.toml` | The Worker's settings. No secret. `setup.sh` makes `wrangler.local.toml` from it (not in git) |
| `setup.sh` | Puts it online: sign-in, the counter store, the three secrets, deploy, the labels |

## Putting it online (once)

### 1. GitHub App

1. Open <https://github.com/settings/apps/new>.
2. **GitHub App name**: `glassio-reports` (any free name). **Homepage URL**: `https://github.com/mohammadmrh12/glassio`.
3. **Webhook**: untick **Active**.
4. **Repository permissions** → **Issues**: **Read and write**. Leave everything else at *No access*.
5. **Where can this GitHub App be installed?**: **Only on this account**. Press **Create GitHub App**.
6. On the App's page: note the **App ID** (a number near the top).
7. Scroll to **Private keys** → **Generate a private key**. A `.pem` file downloads: keep it out of the repository.
8. Left menu → **Install App** → **Install** next to your account → **Only select repositories** → `glassio` → **Install**.
9. The page you land on ends in `/installations/<number>`: that number is the **Installation ID**.

### 2. The Worker

```sh
cd tools/report-worker
./setup.sh
```

It opens Cloudflare's sign-in page, creates the counter store, asks for the App ID, the Installation ID and the
path of the `.pem` file, deploys, creates the two labels, and prints the line to add to `local.properties`:

```
REPORT_URL=https://glassio-report.<your-subdomain>.workers.dev
```

Build Glassio again after adding it. A build without that line shows a QR code instead (it opens a new issue with a
short summary on the phone).

Two things stop `setup.sh` when it is not run by hand in a terminal (from a script, an agent, CI):

- **An account without a `workers.dev` subdomain.** The first deploy asks for a subdomain name, and that question
  needs a person. Register it once (Cloudflare dashboard → *Workers & Pages* → the subdomain shown on the right, or
  run `npx wrangler deploy -c wrangler.local.toml` yourself and answer), then run `./setup.sh` again. The script
  stops with this explanation when the deploy gives no address, rather than printing an empty `REPORT_URL=`.
- **More than one Cloudflare account under the same sign-in.** wrangler can't ask which one: say it with
  `CLOUDFLARE_ACCOUNT_ID=<account id> ./setup.sh` (`npx wrangler whoami` lists the ids).

Check it is up: `curl https://glassio-report.<your-subdomain>.workers.dev/` answers `ok`.

## Trying it without GitHub

```sh
sed 's/KV_NAMESPACE_ID/00000000000000000000000000000000/' wrangler.toml > wrangler.local.toml
npx wrangler dev -c wrangler.local.toml --var DRY_RUN:1 --port 8799
```

With `DRY_RUN` it answers with the issue it would have opened and touches nothing.

## Changing it later

Edit, `npm test`, then `npx wrangler deploy -c wrangler.local.toml`. To replace the App's key: generate a new one on
the App's page and run `npx wrangler secret put GH_PRIVATE_KEY -c wrangler.local.toml < new-key.pem`.
