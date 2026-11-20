#!/bin/sh
# Puts the relay online. Run it yourself, in a terminal, from this folder: ./setup.sh
# It asks Cloudflare to sign you in (a browser page), then asks for the GitHub App's three values and stores them as
# the Worker's secrets. Nothing secret is written to a file in the repository.
set -e
cd "$(dirname "$0")"
W="npx --yes wrangler"

echo "1/5  Cloudflare sign-in (a browser page opens; skip it if you are already signed in)"
$W whoami >/dev/null 2>&1 || $W login
# More than one Cloudflare account under this sign-in: wrangler can't ask which one when it isn't run by hand.
if [ -z "$CLOUDFLARE_ACCOUNT_ID" ] && [ "$($W whoami 2>/dev/null | grep -c '[0-9a-f]\{32\}')" -gt 1 ]; then
  echo "This sign-in has more than one Cloudflare account. Choose one and run again:"
  echo "  CLOUDFLARE_ACCOUNT_ID=<account id> ./setup.sh      (the ids: $W whoami)"
  exit 1
fi

if [ ! -f wrangler.local.toml ]; then
  echo "2/5  A small store for the counters (KV)"
  ID=$($W kv namespace create glassio-report-limits 2>&1 | sed -n 's/.*id = "\([0-9a-f]\{32\}\)".*/\1/p' | head -1)
  [ -n "$ID" ] || { echo "Couldn't create the KV namespace. Run: $W kv namespace list   and put its id in wrangler.local.toml"; exit 1; }
  sed "s/KV_NAMESPACE_ID/$ID/" wrangler.toml > wrangler.local.toml
else
  echo "2/5  The store is already set up (wrangler.local.toml)"
fi

echo "3/5  The GitHub App's values (see README.md → GitHub App). They go to Cloudflare's secrets only."
printf "App ID: ";              read APP_ID
printf "Installation ID: ";     read INST_ID
printf "Path to the private key file (.pem) GitHub gave you: "; read PEM
[ -f "$PEM" ] || { echo "No such file: $PEM"; exit 1; }
printf "%s" "$APP_ID"  | $W secret put GH_APP_ID -c wrangler.local.toml
printf "%s" "$INST_ID" | $W secret put GH_INSTALLATION_ID -c wrangler.local.toml
$W secret put GH_PRIVATE_KEY -c wrangler.local.toml < "$PEM"

echo "4/5  Deploy"
$W deploy -c wrangler.local.toml | tee /tmp/glassio-report-deploy.txt
URL=$(grep -o 'https://[a-z0-9.-]*workers.dev' /tmp/glassio-report-deploy.txt | head -1)
if [ -z "$URL" ]; then
  echo
  echo "Deployed, but no workers.dev address came back, so there is nothing to put in REPORT_URL yet."
  echo "Most likely this account has no workers.dev subdomain: wrangler only asks for one when it is run by hand."
  echo "Register it once (Cloudflare dashboard → Workers & Pages → the subdomain on the right, or run"
  echo "  $W deploy -c wrangler.local.toml    yourself in a terminal and answer its question), then run ./setup.sh again."
  echo "Several Cloudflare accounts: CLOUDFLARE_ACCOUNT_ID=<account id> ./setup.sh"
  exit 1
fi

echo "5/5  The labels the reports carry"
gh label create report-from-tv --repo mohammadmrh12/glassio --color 5319E7 --description "Sent from a TV (Settings → About → Report a problem)" 2>/dev/null || true
gh label create test --repo mohammadmrh12/glassio --color CCCCCC --description "A test report" 2>/dev/null || true

echo
echo "Done. The relay is at: $URL"
echo "Add this line to local.properties at the top of the repository (it is not in git), then build Glassio again:"
echo "REPORT_URL=$URL"
echo "You can delete the .pem file now, or keep it somewhere safe outside the repository."
