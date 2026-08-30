# TvRecommendationBridge

App for Android TV / Google TV that lets you customize how recommendations behave on the Google TV launcher.

When the user selects a compatible movie or show card, TvRecommendationBridge identifies the content and lets you open its page in **Nuvio**, **Stremio**, **WuPlay**, **Plex**, **Jellyfin**, or **Wholphin**, depending on the app configured by the user.

TvRecommendationBridge is an independent automation and redirection tool. **It does not host, store, distribute, or provide movies, series, streams, torrents, or any other audiovisual content.** It has no relationship with Nuvio, Stremio, or the origin or legality of any content the user accesses through those apps — that depends entirely on which apps and add-ons each user has installed and configured, under their own responsibility.

## Requirements

- A device with the **Google TV** launcher (Chromecast with Google TV, or Google TV editions from Sony, TCL, Hisense, etc.), **or a Fire TV device** (Fire TV Stick, Fire TV Cube, etc.) — Fire TV works differently under the hood, see [Fire TV](#fire-tv) below.
- **Nuvio**, **Stremio**, **WuPlay**, **Plex**, **Jellyfin**, and/or **[Wholphin](https://github.com/damontecres/Wholphin)** installed on the device — pick whichever you want recommendations to open in.
- **SmartTube** installed (optional, Google TV only) if you want YouTube recommendations redirected there too — see [How it works](#how-it-works).
- An active TvRecommendationBridge license (see [Pricing](#pricing) below).

> **Note:** support for Android TV launchers other than Google TV's and Amazon's own Fire TV launcher is not planned.

## Installation

TvRecommendationBridge is not currently distributed through Google Play. The app is installed manually ("sideloaded") using the APK file available in this repository's releases section.

👉 **[Visual step-by-step install guide](https://yushetf33.github.io/TvReccomendationBridge/install.html)** — the same steps below, laid out more clearly.

### Option A: using Downloader (easiest, no phone or computer needed)

1. On the TV, install **[Downloader](https://play.google.com/store/apps/details?id=com.esaba.downloader)** from Google Play (the "by AFTVnews" one).
2. Open Downloader and enter this code: **4414843**
3. It'll download the APK and offer to install it straight away.
4. If Android TV shows a warning about installing from an unknown source, you'll need to temporarily allow installation from that source.

This code always points to the latest version — no need to look up a new one for future updates.

### Option B: from your phone, using Send Files to TV

1. On the TV, install **[Send Files to TV](https://play.google.com/store/apps/details?id=com.jstenpal.sendfilestotv)** from Google Play.
2. Open the app on the TV. It will show an address or a QR code to connect from your phone.
3. From your phone's browser, go to that address and select the `app-release.apk` file, which you can download from the releases section:
   👉 **[Download the latest version](https://github.com/Yushetf33/TvReccomendationBridge/releases/latest)**
4. Once the file has transferred, the TV will let you start the installation.
5. If Android TV shows a warning about installing from an unknown source, you'll need to temporarily allow installation from that source.

### Option C: via ADB

1. Download the APK from the releases section.
2. Enable developer options on the TV:
   **Settings → Device Preferences → About → tap "Build" 7 times**.
3. Enable **USB debugging** or **Network debugging**, depending on the device.
4. From your computer:

```bash
adb connect <tv-ip>:5555
adb install app-release.apk
```

### Updates

The app checks for new versions periodically in the background (roughly
whenever the TV wakes up, at most once every few hours) and downloads
them automatically — you'll get a notification once a new version is
ready, and just need to tap it to install (Android always requires that
last confirmation for anything installed outside Google Play).

### Activating the service

Once installed, open the **TvRecommendationBridge** app from the TV's
launcher and tap **"Enable the service in Accessibility"** (it will take
you straight to the right screen). Enable it there.

> Stuck on any of this? The app has a built-in **"Having trouble? Get
> help"** button right at the top of Settings, with the same
> troubleshooting steps below and direct shortcuts to the right system
> screens — no need to leave the TV to read this file.

#### Error codes

Each known issue below has a short code (E01, E02...) — the app's own
"Having trouble? Get help" screen and any error message it shows use
the same codes, so you can jump straight to the matching section here
or mention the code if you ask for help elsewhere.

| Code | Symptom |
|---|---|
| E01 | The toggle turns itself off immediately |
| E02 | Google TV Streamer: no visible option to allow it |
| E03 | Service enabled, but tapping a recommendation doesn't open Nuvio/Stremio |
| E04 | TCL devices: service stops working after a while |
| E05 | Fire TV: nothing works at all |
| E06 | Voice search doesn't work |

#### E01 — The toggle turns itself off immediately

On Android 13 and newer, the system blocks any sideloaded app (anything
not installed from Google Play — which includes this one) from enabling
Accessibility by default, as a security measure. If the toggle turns
itself back off right after you enable it (sometimes right away,
sometimes a few seconds later, with no warning), this is why.

On some devices, you can lift this from **Settings → Apps →
TvRecommendationBridge → app info screen**, look for an option along the
lines of "Allow restricted setting" (its exact wording and location
varies by device — Google TV's Settings UI isn't consistent about this
across manufacturers). If you find it, enable it, then go back into
Accessibility and enable the service again.

**If you can't find any such option** (this is the case on the Google
TV Streamer, for example — see below), you'll need the ADB workaround
instead.

#### E02 — Google TV Streamer: no visible option to allow it

On the Google TV Streamer specifically, the app info screen doesn't
expose any "Allow restricted setting" option at all. You can accept the
permission dialog and see the toggle turn on, but it flips back off
shortly after, with no warning or notification. This is the same
Restricted Settings protection, just with no way to
lift it from the TV's own UI on this device.

The workaround needs ADB access to the device, but it's a **one-time**
step — after this, the app enables itself directly, no need to ever
touch ADB or Settings again (even after reinstalling):

1. On the TV: **Settings → System → About → click the build number a
   few times** to enable Developer options, then enable **Network
   debugging**.
2. From a computer with `adb` installed:

```bash
adb connect <tv-ip>:5555
adb shell appops set com.tunombre.tvbridge ACCESS_RESTRICTED_SETTINGS allow
adb shell pm grant com.tunombre.tvbridge android.permission.WRITE_SECURE_SETTINGS
```

On **Android 14** (confirmed on the Google TV Streamer), the first line
is required, not optional — without it, Android kills/unbinds the
accessibility service the instant it tries to enable, even with
`WRITE_SECURE_SETTINGS` granted. On older Android versions the second
line alone may already be enough, but running both is harmless either
way.

3. Go back into the app and tap **"Enable the service in
   Accessibility"** again — this time it enables directly, without
   opening Settings at all.

This grants the app permission to write that setting directly at the
system level, which isn't subject to the same restriction as the
Settings app toggle — and unlike writing the setting by hand, the app
adds itself to the list instead of replacing it, so it won't wipe out
another accessibility service (like TalkBack) you might already have
enabled.

If setting up ADB from a computer sounds like a hassle, **[atvTools](https://play.google.com/store/apps/details?id=dev.vodik7.atvtools)**
(free, on Google Play) does the same thing from your phone — install it
on the TV, enable Network debugging as above, connect from the atvTools
app, and use it to manage the app's permissions directly, no computer
needed.

On some devices (especially Chinese-manufacturer Android TV boxes with
aggressive battery managers) you may also need to exclude the app from
any manufacturer "optimizer"/RAM cleaner, or the system will kill the
service's process after a few seconds. If the service shows as enabled
in Settings but stops detecting clicks after a while, that's usually the
reason.

#### E03 — Service is enabled, but tapping a recommendation doesn't open Nuvio/Stremio

If the accessibility service is confirmed enabled but nothing happens
when you tap a recommendation, check whether Nuvio/Stremio is up to
date. Outdated or unofficial builds (common since neither app is on
Google Play) sometimes don't register their deep link scheme
(`nuvio://`, `stremio://`) correctly, so the app opens the link but
nothing responds to it. Updating Nuvio/Stremio to their latest version
has resolved this in the past.

To narrow it down (needs ADB access): test the deep link directly,
bypassing this app entirely —

```bash
adb shell am start -a android.intent.action.VIEW -d "nuvio://movie/tt0371746"
```

If that doesn't open Nuvio on Iron Man's page, the issue is with your
Nuvio build, not this app. If it works, the click likely isn't being
detected at all — you can check for that with:

```bash
adb logcat -s TvRecService:D StremioLauncher:D TmdbClient:D
```

**Specifically the top mixed row on the home screen** ("Recommended for you" /
whatever your launcher calls the row that mixes shows, movies, and other
content together, as opposed to a dedicated single-source row): on some
devices (confirmed on TCL) the accessibility APIs simply don't expose a
title for cards in that specific row, no matter how long you wait — this
is a platform limitation on those devices, not a bug in how the app reads
the row. Turning on **"Enable voice search (experimental)"** in Settings
also fixes this row specifically, even if you don't use voice search at
all — it enables an OCR fallback that reads the title directly off the
screen instead of relying on the broken accessibility data. Costs a
persistent screen-recording notification while enabled (see
[Voice search](#voice-search-google-tv) below), which is why it isn't on
by default.

#### E04 — TCL devices: service stops working after a while, no visible setting to fix it

On some TCL units, background auto-start permissions for third-party
apps are locked down at the OS level with no toggle exposed anywhere in
Settings. If the service shows as enabled but silently stops detecting
clicks after some time, and you can't find any autostart/battery
exception option for the app, this is likely why.

The fix requires ADB access to the TV:

```bash
adb connect <tv-ip>:5555
adb shell appops set com.tunombre.tvbridge APP_AUTO_START allow
adb shell appops set com.tunombre.tvbridge APP_ASSOC_START allow
```

Note this may need to be repeated after reinstalling or updating the
app, since reinstalling can reset these permissions.

## Fire TV

*(Nothing working at all here? That's error code **E05** — see [Error codes](#error-codes) above.)*

Fire TV works a bit differently from Google TV: instead of opening
automatically the moment you select a recommendation, you need to
**hold still on it for a couple of seconds**, then a prompt appears —
**"Open '\<title\>' in \<your chosen app\>?"** — and you confirm by
pressing **OK** on the remote, or dismiss it with the on-screen
**"Back"** button next to it (use that instead of the remote's physical
Back button — on some Fire TV units, the physical button can also send
an extra Back action to whatever's underneath). If you don't respond,
the prompt disappears on its own after a few seconds and nothing opens.

If the Fire TV goes into standby (screen off), Fire TV mode is still
active when it wakes back up. A real power loss or reboot clears it,
though — screen capture consent doesn't survive that, and there's no
automatic re-activation on boot, so you'll need to open the app and
tap **"Activate Fire TV mode"** again in that case.

### Activating it

Open the app and tap the single activation button (it detects it's
running on Fire TV automatically and shows the right option). You'll be
asked for two permissions, once:

1. A **screen recording** permission (the standard Android system
   dialog). While active, Android shows a persistent notification —
   that's expected and can't be hidden.
2. **Usage access**: Settings → Apps → Special app access → Usage
   access → TvRecommendationBridge → enable it.

### Known Fire TV limitations

- Only works while you're on the actual home screen — inside another
  app, nothing happens.
- Needs the extra couple of seconds of holding still, plus the OK
  confirmation — it's not instant like on Google TV.
- The screen-recording notification is unavoidable; it's an Android
  requirement, not something this app adds.

## Voice search (Google TV)

*(Not working? That's error code **E06** — see [Error codes](#error-codes) above.)*

By default, recommendations opened through voice search (either by
holding the remote's mic button or saying "Ok Google, open \<title\>")
aren't picked up automatically the way a regular click is — this is an
**optional, opt-in** feature you can turn on separately.

### Activating it

Open the app and tap **"Enable voice search (experimental)"** (only
shown on Google TV, not Fire TV — Fire TV already covers this as part
of its own mode). You'll be asked for a **screen recording** permission
(the standard Android system dialog). While active, Android shows a
persistent notification for it — that's expected and can't be hidden,
which is why this is opt-in rather than always on.

### Known limitations

- Keeps the screen-recording notification visible the whole time it's
  enabled.
- Only helps when a voice search lands you on a movie/show detail page —
  it doesn't add voice search itself, just makes recommendations opened
  that way work the same as a regular click.

## Pricing

TvRecommendationBridge requires a one-time payment to work — not a
subscription, you pay once and it's yours — with a free trial to test
it first:

| Plan | Price |
|---|---|
| Free trial | 2 days, no card needed (once per device) |
| Lifetime | €4.99 one-time |

Inside the app you'll see a QR code for each — scan it with your
phone and pay using the same email you'll later enter on the TV (card,
Google Pay, Apple Pay... whatever the payment page offers). Once paid,
go back to the app, enter that email, and tap **"Verify subscription"**.

The same email can be linked to up to **5 devices** at once (in case you
have several TVs at home). From the app, tap **"Manage devices"** to see
which ones are currently linked and remove any you don't need anymore —
no need to wait 30 days or contact support to free up a slot. Those
devices also share watch history for **"Recommended for you"** — see
below.

## How it works

On **Google TV**, the launcher exposes the title of each recommendation
when you select it. The app picks up on that click, looks up the title
in a public movie/show database to identify the content, and opens its
page directly in Nuvio, Stremio, WuPlay, Plex, Jellyfin, or Wholphin
(whichever you've chosen in the app's settings).

On **Fire TV** it works a bit differently — see the [Fire TV](#fire-tv) section above for the details.

Recommendations opened via voice search on Google TV need a separate,
optional feature to be turned on first — see [Voice search](#voice-search-google-tv) above.

If the app you've chosen isn't installed, TvRecommendationBridge takes
you straight to its Play Store listing instead of doing nothing.

For Plex specifically, this only works for titles available in Plex's
own free, ad-supported streaming catalog (the "Movies & Shows" section
of the Plex app) — not a personal Plex Media Server library, since
there's no universal ID to link to someone's private collection.

Jellyfin and **[Wholphin](https://github.com/damontecres/Wholphin)**
(an alternative open-source Jellyfin client) work differently from the
other three: since Jellyfin is self-hosted, there's no universal ID or
shared catalog to link to either — each user's server has its own
library with its own internal IDs. Instead of opening straight to the
title's page, TvRecommendationBridge opens whichever of the two you've
picked with a **search** for that title already triggered, so you land
on the search results instead of the exact page.

### Check my Jellyfin first (optional)

Independent of whichever app you've picked above as your main
destination, you can optionally have TvRecommendationBridge check your
own Jellyfin server **first** — if a recommendation is already in your
personal library, it opens straight to that title's page (no search
needed) instead of going to Nuvio/Stremio/WuPlay/Plex; if it's not
there, it falls back to your normal destination app as usual. This
works for every trigger (click, voice search, Fire TV), since they all
funnel through the same check. It opens the exact page in **Wholphin**
if that's the app you've picked as your main destination, or in the
official Jellyfin app otherwise.

To enable it, go to Settings → **"Check my Jellyfin first"**, turn it
on, and fill in:
- **Server URL** (e.g. `http://192.168.1.10:8096`) — the same address
  you use in the Jellyfin app itself.
- **API key** — generate one from your server's admin dashboard
  (Dashboard → API Keys → **+**), not your username/password.

Leave it off if you don't run your own Jellyfin server.

### Confirm before opening (optional)

By default, a recommendation opens straight away as soon as it's
identified. If you'd rather double-check first, Settings →
**"Confirm before opening (Watch now)"** shows a **"Watch now in
{App}"** button instead of opening immediately. Dismissing it with
Back only hides it — it reappears a few seconds later and keeps doing
so until you either confirm it or navigate away (back to the launcher's
home screen, open something else, etc.), so a stray Back press doesn't
lose it for good. Off by default.

### Ambiguous matches

When a search turns up two or more titles with the **exact same name**
but different years (a remake, for example), TvRecommendationBridge
now asks which one you meant instead of guessing — Settings →
**"Ask when a match is ambiguous"** (on by default, turn it off if
you'd rather it always pick automatically).

YouTube recommendations work the same way, but redirect to
**[SmartTube](https://github.com/yuliskov/SmartTube)** or
**[TizenTube Cobalt](https://github.com/reisxd/TizenTubeCobalt)**
(whichever you've picked in the app's settings, independent of the
movie/show app above) with a search for that same video's title,
since the launcher doesn't expose the video ID directly.

TizenTube Cobalt needs an extra piece to receive that search —
**[TizenTube Bridge](https://github.com/TobiPeterG/tizentube-bridge)**
— which takes the place of the official YouTube TV app on your device
(they can't be installed at the same time; some devices won't let you
remove YouTube TV completely, in which case this option won't work
there).

### Recommended for you (optional, Google TV only)

Independent of the click-redirect feature above, TvRecommendationBridge
can build its own "Recommended for you" screen based on what you've
actually opened through the app — with **Movies** and **Series** tabs
at the top, each showing up to 5 "Because you watched X" rows, powered
by TMDB's own recommendation engine (no AI, so it never suggests a
title that doesn't exist). Turn it on from Settings →
**"Enable 'Recommended for you'"**; once you've opened a few titles,
it'll show up right when you open the app, or you can jump to it
anytime with **"View recommendations"**. Tapping a card opens it the
same way as a regular recommendation — in whichever app you've picked
above. If a card has a trailer, **holding down the select button on it**
plays it directly (via TMDB, in your device's language when available)
in whichever YouTube app you've picked — no separate button to navigate
to, works on any card regardless of position.

The app also tries to publish this same list as a native row on the
Android TV home screen (the same mechanism Netflix/HBO use), but many
Google TV launchers silently refuse to show it — the in-app screen
above is the reliable way to see it either way.

Watch history is shared across every device linked to your email (up to
5, see [Pricing](#pricing) above) — open something on the living room
TV, and the bedroom TV's recommendations pick up on it too.

Off by default, and needs a few opens before there's anything to
recommend.

## Known limitations

- Occasionally, a title may match a lesser-known movie or show with the
  same name and open the wrong page — this is caught when the two
  candidates have different release years (see
  [Ambiguous matches](#ambiguous-matches) above), but not when they
  share both name and year.
- If you've chosen Plex and a title isn't in Plex's free catalog,
  nothing happens — there's no fallback to another app.
- If you've chosen Jellyfin, you land on a search results page, not the
  exact title — search accuracy depends on how closely your library's
  naming matches the title TvRecommendationBridge found.
- The YouTube redirect only works for rows with their own YouTube
  branding (e.g. "Recommended videos", "Tourist destinations"). Videos
  that show up mixed into "Recommended for you" open the regular
  YouTube app instead — YouTube's own app is already loaded in memory
  by the time the click fires, so it wins the race to the foreground.

## Legal notice

TvRecommendationBridge is an independent navigation and automation tool for Android TV / Google TV devices.

The app does not host, store, distribute, or provide movies, series, streams, torrents, or audiovisual content sources.

Its function is limited to detecting certain recommendations shown by the Google TV launcher, identifying the selected content, and facilitating the opening of its page through third-party apps installed and configured by the user.

TvRecommendationBridge does not provide or control the content sources available within those apps.

The user is responsible for their use of third-party apps, and for making sure that use complies with applicable law and the corresponding terms of service.

TvRecommendationBridge is not affiliated with, sponsored by, authorized by, or endorsed by Google, Google TV, Nuvio, Stremio, WuPlay, or Wholphin.

Google, Google TV, Android TV, Nuvio, Stremio, WuPlay, and Wholphin are trademarks or products of their respective owners.

## Credits

TvRecommendationBridge uses the **The Movie Database (TMDB)** API to identify movies and shows.

<img src="https://www.themoviedb.org/assets/v4/logos/v2/blue_long_2-9665a76b1ae401a510ec1e0ca40ddcb3b0cfe45f1d51b77a308fea0845885648.svg" alt="TMDB" width="180">

> This product uses the TMDB API but is not endorsed or certified by TMDB.

The Movie Database (TMDB) and its logo are trademarks of their respective owners.
