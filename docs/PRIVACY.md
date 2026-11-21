# Glassio: privacy policy

Last updated: 2026-11-20. This covers Glassio from GitHub (`com.glasslauncher.tv`) and Glassio from Google Play
(`com.glasslauncher.tv.play`).

**In short:** Glassio has no account of its own, no ads and no analytics. The developer receives nothing from your
TV unless you choose to send a problem report, and you see all of it before it goes.

## What stays on your TV

- Your settings, your dock and icons, your Library and Continue Watching lists, your profiles.
- The sign-in tokens of the services you connect (Nuvio, Stremio, Trakt, Simkl) and the keys you type in (TMDB,
  Unsplash, Reddit, Philips Hue, Home Assistant, camera links). A password typed to sign in is sent to that service
  only and is not kept.
- The list of apps installed on the TV: it is what a home screen shows. It is never sent anywhere.
- A backup is a file on your TV (Downloads), made when you ask for one.

## What Glassio asks other services for

Glassio talks directly from your TV to the services whose content it shows. Each one sees your TV's network address,
as any website does, and what is asked for:

| Service | What is asked |
|---|---|
| The catalogs and add-ons you added (Stremio add-ons, Cinemeta), TMDB | Lists of titles, their pictures and details |
| Nuvio, Stremio, Trakt, Simkl (only when you sign in) | Your own lists and progress there, with your token |
| Wikidata, JustWatch (only with *Play opens the streaming app* on) | A title's id and your country, to find its page in a streaming app |
| Open-Meteo, OpenStreetMap Nominatim | The city you typed, for the weather and the place name |
| Unsplash, Reddit, Bing, Wikimedia Commons, Apple and GitHub (aerial videos and their list) | Background pictures and videos, when you choose that source |
| wsrv.nl | Simkl's posters, resized |
| YouTube (oEmbed and thumbnails) | The artwork of a video playing in a music app |
| Hugging Face | A one-time download of the depth model for the clock behind a photo |
| GitHub (Glassio from GitHub only) | The latest release, to offer an update; an icon pack you choose to install (also F-Droid) |
| YouTube, Google Translate (Glassio from GitHub only) | A title's trailer, and its subtitles put into your language |
| Philips Hue's discovery page (only when you search for a bridge) | Nothing but the request itself: it answers with the bridges on your network |
| Philips Hue, Home Assistant, your cameras, your other TVs | Only on your home network, only what you set up |

Glassio sends none of this to the developer.

## Permissions and what they are used for

- **Accessibility service** (optional, turned on by you): to notice the buttons of your remote and when the TV's own
  home screen comes up, so the Home button opens Glassio, the remote buttons you choose do what you set, and a
  camera or the doorbell can show over another app. It does not read what is on the screen or what you type, and
  nothing is collected or shared.
- **Notification access** (optional): to follow what a music app is playing (the song, the artist, its artwork) and
  show it. It stays on the TV.
- **Usage access** (optional): to list the apps you opened recently. It stays on the TV.
- **Display over other apps** (optional): for the camera and doorbell windows, and to open Glassio when the TV starts.
- **Photos**: to pick a picture from the TV as an icon or a background.
- **TV channels**: to show the rows other apps publish for the home screen.
- **TV access (ADB)** (optional, allowed by you on the TV's own question): used on the TV itself for things only
  its own shell can do, such as switching inputs or starting the screensaver.

## Problem reports (only when you send one)

Settings → About → *Report a problem* sends what you typed with: Glassio's version, the TV's make and model,
Android version, a short list of look and mode settings, the last lines of Glassio's log and the last crash.
Addresses, links, names, emails, tokens and keys are removed first, and the whole report is shown to you before
you press Send. It becomes a public issue at github.com/mohammadmrh12/glassio. A random number made for your
install, and your network address, are used by the relay only to limit how many reports one TV can send; they are
not published.

## Children

Glassio is a home screen for a TV and is not directed to children.

## Deleting your data

Everything is on your TV: clearing Glassio's data or uninstalling it removes it. To have a problem report removed,
ask in an issue at github.com/mohammadmrh12/glassio/issues.

## Contact

github.com/mohammadmrh12/glassio/issues
