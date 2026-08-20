# TvRecommendationBridge

App for Android TV / Google TV that lets you customize how recommendations behave on the Google TV launcher.

When the user selects a compatible movie or show card, TvRecommendationBridge identifies the content and lets you open its page in **Nuvio** or **Stremio**, depending on the app configured by the user.

TvRecommendationBridge is an independent automation and redirection tool. **It does not host, store, distribute, or provide movies, series, streams, torrents, or any other audiovisual content.** It has no relationship with Nuvio, Stremio, or the origin or legality of any content the user accesses through those apps — that depends entirely on which apps and add-ons each user has installed and configured, under their own responsibility.

## Requirements

- A device with the **Google TV** launcher (Chromecast with Google TV, or Google TV editions from Sony, TCL, Hisense, etc.).
- **Nuvio** and/or **Stremio** installed on the device.
- An active TvRecommendationBridge subscription (see [Subscription](#subscription) below).

> **Note:** TvRecommendationBridge is designed specifically for devices using the Google TV launcher. It is not designed for Android TV devices using other launchers, nor for Fire TV.

## Installation

TvRecommendationBridge is not currently distributed through Google Play. The app is installed manually ("sideloaded") using the APK file available in this repository's releases section.

### Option A: from your phone, using Send Files to TV

1. On the TV, install **[Send Files to TV](https://play.google.com/store/apps/details?id=com.jstenpal.sendfilestotv)** from Google Play.
2. Open the app on the TV. It will show an address or a QR code to connect from your phone.
3. From your phone's browser, go to that address and select the `app-release.apk` file, which you can download from the releases section:
   👉 **[Download the latest version](https://github.com/Yushetf33/TvReccomendationBridge/releases/latest)**
4. Once the file has transferred, the TV will let you start the installation.
5. If Android TV shows a warning about installing from an unknown source, you'll need to temporarily allow installation from that source.

### Option B: via ADB

1. Download the APK from the releases section.
2. Enable developer options on the TV:
   **Settings → Device Preferences → About → tap "Build" 7 times**.
3. Enable **USB debugging** or **Network debugging**, depending on the device.
4. From your computer:

```bash
adb connect <tv-ip>:5555
adb install app-release.apk
```

### Activating the service

Once installed, open the **TvRecommendationBridge** app from the TV's
launcher and tap **"Enable the service in Accessibility"** (it will take
you straight to the right screen). Enable it there.

#### The toggle turns itself off immediately

On Android 13 and newer, the system blocks any sideloaded app (anything
not installed from Google Play — which includes this one) from enabling
Accessibility by default, as a security measure. If the toggle turns
itself back off right after you enable it, this is why. To fix it:

**Settings → Apps → See all apps → TvRecommendationBridge → tap the
3-dot menu (top right) → "Allow restricted setting"**.

Then go back into Accessibility and enable the service again — it
should stick this time.

#### Google TV Streamer: toggle appears to turn on, then silently turns itself off

On the Google TV Streamer, the app info screen doesn't expose the "Allow
restricted setting" option shown above at all — there's no 3-dot menu or
equivalent. You can accept the permission dialog and see the toggle turn
on, but it flips back off shortly after, with no warning or notification.
This is the same Restricted Settings protection, just with no way to
lift it from the TV's own UI on this device.

The workaround needs ADB access to the device:

1. On the TV: **Settings → System → About → click the build number a
   few times** to enable Developer options, then enable **Network
   debugging**.
2. From a computer with `adb` installed:

```bash
adb connect <tv-ip>:5555
adb shell settings put secure enabled_accessibility_services com.tunombre.tvbridge/com.tunombre.tvbridge.TvRecommendationAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

This writes the setting directly at the system level, which isn't
subject to the same restriction as the Settings app toggle. Note the
second command **replaces** the whole list of enabled accessibility
services — if you already use another one (like TalkBack), separate
them with a colon (`:`) instead of overwriting it.

On some devices (especially Chinese-manufacturer Android TV boxes with
aggressive battery managers) you may also need to exclude the app from
any manufacturer "optimizer"/RAM cleaner, or the system will kill the
service's process after a few seconds. If the service shows as enabled
in Settings but stops detecting clicks after a while, that's usually the
reason.

#### Service is enabled, but tapping a recommendation doesn't open Nuvio/Stremio

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

#### TCL devices: service stops working after a while, no visible setting to fix it

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

## Subscription

TvRecommendationBridge requires a paid subscription to work:

| Plan | Price |
|---|---|
| Monthly | €2.50 / month |
| Semiannual | €7.99 / 6 months |
| Annual | €12.99 / year |

Inside the app you'll see a QR code for each plan — scan it with your
phone and pay using the same email you'll later enter on the TV (card,
Google Pay, Apple Pay... whatever the payment page offers). Once paid,
go back to the app, enter that email, and tap **"Verify subscription"**.

The same email can be linked to up to **3 devices** at once (in case you
have several TVs at home).

## How it works

The Google TV launcher exposes the title of each recommendation when you
select it. The app picks up on that click, looks up the title in a
public movie/show database to identify the content, and opens its page
directly in Nuvio or Stremio (whichever you've chosen in the app's
settings).

## Known limitations

- Occasionally, a title may match a lesser-known movie or show with the
  same name and open the wrong page.

## Legal notice

TvRecommendationBridge is an independent navigation and automation tool for Android TV / Google TV devices.

The app does not host, store, distribute, or provide movies, series, streams, torrents, or audiovisual content sources.

Its function is limited to detecting certain recommendations shown by the Google TV launcher, identifying the selected content, and facilitating the opening of its page through third-party apps installed and configured by the user.

TvRecommendationBridge does not provide or control the content sources available within those apps.

The user is responsible for their use of third-party apps, and for making sure that use complies with applicable law and the corresponding terms of service.

TvRecommendationBridge is not affiliated with, sponsored by, authorized by, or endorsed by Google, Google TV, Nuvio, or Stremio.

Google, Google TV, Android TV, Nuvio, and Stremio are trademarks or products of their respective owners.

## Credits

TvRecommendationBridge uses the **The Movie Database (TMDB)** API to identify movies and shows.

<img src="https://www.themoviedb.org/assets/v4/logos/v2/blue_long_2-9665a76b1ae401a510ec1e0ca40ddcb3b0cfe45f1d51b77a308fea0845885648.svg" alt="TMDB" width="180">

> This product uses the TMDB API but is not endorsed or certified by TMDB.

The Movie Database (TMDB) and its logo are trademarks of their respective owners.
