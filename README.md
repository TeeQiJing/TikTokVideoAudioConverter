# TikTok / Douyin Video Audio Converter 🎵

A simple Windows desktop app that turns **TikTok** and **抖音 (Douyin)**
videos into MP3 songs — perfect for building a music library for your car
radio or offline player.

- **Batch download** an entire public TikTok **collection** with one link
- **Batch download your own 抖音 收藏夹** — pick the folder once, then it
  syncs like the TikTok one
- Or convert **individual video links** from TikTok *or* 抖音, one or many
  at a time
- Audio only — no video files, no online converter websites
- Songs are saved with proper **title and artist tags** (shown on car radios)
- **Remembers what it already downloaded** — run it again anytime and only
  new songs are fetched
- **Auto-retries** flaky TikTok responses
- **Self-updating download engine** — when TikTok changes something and
  breaks downloading, the fix arrives automatically, no reinstall needed
- **Simple, senior-friendly interface** in **English** and **简体中文**
  (switch anytime with one click)

![App flow](https://img.shields.io/badge/TikTok%20%2F%20抖音-→%20MP3-brightgreen)

## Download & Install

1. Go to the [**Releases**](https://github.com/TeeQiJing/TikTokVideoAudioConverter/releases) page.
2. Download the latest `TikTokVideoAudioConverter-Setup-x.x.x.exe`.
3. Run it. If Windows SmartScreen shows *"Windows protected your PC"*,
   click **More info → Run anyway** (the app is unsigned, not harmful —
   you can read all the source code in this repository).
4. Follow the installer — no admin rights needed. A desktop shortcut is
   created if you tick the option.

Everything needed (including the ffmpeg audio converter) is bundled — no
other software to install.

## How to Use

### Option A — Collection link (recommended ⭐)

Set it up once, then getting new songs is a single click forever.

**On your phone (one-time setup):**
1. In TikTok, when you find a song you like, tap the **bookmark icon** 🔖.
2. Tap **Manage** → **Create new collection** (e.g. *"Car Songs"*) and set
   it to **Public**. (Your TikTok account must not be Private for this to
   work — if it is, use Option B instead.)
3. From now on, just save songs to that collection while browsing.

**Get the link (one-time):**
1. **Profile → Saved (🔖 tab) → open your collection → Share → Copy link.**
2. Send it to your PC (e.g. WhatsApp/email to yourself).

**In the app:**
1. Choose **"Collection link (recommended)"**.
2. Paste the collection link (the app remembers it for next time).
3. Pick where to save MP3s (default: `Music\TikTok Songs`).
4. Click **Download MP3s**.

Every time you run it, only songs added since last time are downloaded.

### Option B — Individual video links (TikTok **or** 抖音)

1. On any TikTok video: **Share → Copy link**. On 抖音: **分享 → 复制链接**.
   Send the links to your PC.
2. In the app choose **"Single videos"**, paste one or more links
   (messy text around the links is fine — they're detected automatically,
   and you can mix TikTok and 抖音 links in the same paste).
3. Click **Download MP3s**.

### Option C — your own 抖音 收藏夹 ⭐

抖音 collections cannot be shared as a link (see below), but the app can read
**your own** 收藏夹 straight from your account:

1. Choose **"My 抖音 collection (收藏夹)"**.
2. Click **Choose collection…**. A browser window opens on douyin.com — log
   in there (**扫码登录** with the 抖音 app on your phone is quickest). You
   only ever do this once; the login stays in a profile belonging to the app.
3. Pick the 收藏夹 from the list and click **Use this one**.
4. From then on it is a single click: **Download MP3s** fetches everything in
   that folder, skipping songs you already have.

A browser window appears briefly each run while the app reads the folder —
that is normal, and it closes by itself.

Two things worth knowing:

- The login is kept in a **separate profile from the downloading**. Songs are
  fetched with anonymous guest cookies, so your 抖音 account is never
  attached to the downloads themselves.
- A folder often lists **fewer videos than its count** — 抖音 counts saved
  slots, including videos since deleted or made private, which it no longer
  serves. The app reports this (e.g. *"33 of 50 saved videos are still
  available"*).

### 抖音 (Douyin) — why there's no collection link

TikTok collections have a public web URL, so the app can read the whole
collection in one go. **抖音 收藏夹 do not.** Sharing a 收藏夹 in the 抖音
app produces an in-app share code (a `##…##` 口令), not a URL, and it only
opens inside the 抖音 app. 抖音's web site shows *only your own* saved
folders, behind a login and a runtime-signed API — and since August 2026
抖音's anti-bot gate returns HTTP 403 for the favourites/collection
endpoints to anything that isn't a real browser session.

That is why **Option C** does not use a link at all. Instead the app opens
douyin.com in a browser you log into once, and reads the folder listing the
same way the web site itself does — recording the response the page already
fetched. Nothing is forged, and no password passes through the app.

If you would rather not log in at all, **Option B** still works for 抖音:
tap **分享 → 复制链接** on each video, send them to yourself in one
WeChat/WhatsApp message, and paste that whole message into the app.

The app handles the 抖音 cookie problem for you: 抖音's web API refuses
requests without the signature cookies its own JavaScript sets, so before
抖音 downloads the app quietly loads `douyin.com` once in **headless
Edge** (or Chrome) and reuses those *guest* cookies. **No 抖音 account or
login is involved**, and Edge ships with Windows, so nothing extra to
install. The cookies are refreshed automatically when they go stale.

### Putting songs on a pendrive (car radio)

Copy the MP3s from your songs folder onto the pendrive. Tips:
- Most car radios want the pendrive formatted as **FAT32**.
- If the radio can't see the songs, try putting them in the pendrive's
  top-level (root) folder rather than a subfolder.

## Troubleshooting

- **"Unable to extract..." / some songs failed** — the servers are moody;
  the app already retries up to 5 times per run. Just run it again later,
  already-downloaded songs are never re-downloaded.
- **抖音: "Fresh cookies … are needed"** — the cookie refresh could not
  run. Make sure Microsoft Edge (or Chrome) is installed, then try again.
- **Downloads suddenly stop working entirely** — TikTok changed something
  and broke the downloader for everyone. The app updates its download
  engine ([yt-dlp](https://github.com/yt-dlp/yt-dlp)) automatically before
  every run, so once the yt-dlp team ships a fix (usually within days),
  it just starts working again — try again a day or two later.
- **A saved video won't convert** — photo/slideshow posts and private or
  region-locked videos can't always be downloaded.

## How It Works

The app is a small Python/Tkinter GUI around two excellent open-source
tools, both bundled by the installer:

- [yt-dlp](https://github.com/yt-dlp/yt-dlp) (official standalone exe) —
  handles talking to TikTok and 抖音 (including TikTok's JS challenge and
  browser impersonation), reads collection playlists, and picks a download
  format. The app runs its built-in self-updater (`yt-dlp -U`) before
  every download, so extractor fixes arrive automatically. TikTok's
  August 2026 bot detection
  ([yt-dlp#17403](https://github.com/yt-dlp/yt-dlp/issues/17403)) is fixed
  upstream as of **yt-dlp 2026.08.19**, which is what the installer now
  bundles; the older user-agent workaround is kept only as a retry
  fallback.
- [ffmpeg](https://ffmpeg.org/) — converts the audio track to MP3 and
  embeds title/artist metadata

- **Edge/Chrome** — driven over the DevTools protocol for two separate jobs:
  headless, to mint 抖音's anonymous guest signature cookies for downloading;
  and visible, to read your own 收藏夹 from a profile you logged into. The
  two use different profiles and never share cookies.

TikTok and 抖音 links are downloaded in two separate yt-dlp passes, so the
抖音 cookies are never sent to TikTok.

A hidden archive file in your songs folder records every downloaded video
ID, which is what makes incremental syncing and cheap retries possible.

## Android app — 抖音歌曲下载 (`android/`)

A companion Android app for the 抖音 side, for when carrying the songs
around shouldn't involve a PC at all. It reads a 收藏夹 from the phone and
writes the MP3s **straight onto a pendrive** over USB-OTG.

**Why it exists:** the desktop app needs a laptop, and the songs are
destined for a USB stick anyway. On the phone the whole trip is one tap.

**How to use it**

1. Once: open the app → **选择收藏夹…** → log in to Douyin in the window
   that appears → pick the folder.
2. Every time: plug the pendrive in (Type-C adapter), check it says
   **✅ U 盘已插入**, tap **⬇ 下载我的歌曲**.

Songs land in `DouyinSongs/` on the pendrive, and anything already there
is skipped, so re-running only fetches what's new.

**If it can't see the pendrive** — on OPPO/realme phones OTG is off by
default *and turns itself off again after about 10 minutes idle*. Turn it
back on under **设置 → 其他设置 → OTG连接**. The app shows that path on
screen when no drive is found.

**How it works.** Douyin's collection listing already contains, for each
saved video, a `music.play_url` pointing at the track as a finished MP3
(44.1 kHz stereo, typically 128 kbps). So there is no transcoding step at
all: no ffmpeg, no NDK, and nothing is written to phone storage — the
bytes go from Douyin's CDN straight onto the pendrive. The listing itself
comes from a WebView the user logs into once, capturing the response the
page fetches for itself, the same trick the desktop app uses. USB access
goes through [libaums](https://github.com/magnusja/libaums), which finds
the drive on its own when it is plugged in (FAT32 only — which is what
car stereos want anyway).

**Trade-offs versus the desktop app:** 128 kbps rather than 192, no
loudness normalisation (that needs ffmpeg), and videos whose audio Douyin
does not publish as a music track are skipped.

**Building**

```bat
cd android
:: point it at your SDK (forward slashes, or escape the backslashes)
echo sdk.dir=C:/Users/you/AppData/Local/Android/Sdk> local.properties
gradlew assembleDebug
:: appuild\outputspk\debugpp-debug.apk
```

Needs JDK 17+ and an Android SDK with platform 35. The app is unsigned in
debug; build a release variant with your own signing config to share it.

## Building from Source

```bat
git clone https://github.com/TeeQiJing/TikTokVideoAudioConverter.git
cd TikTokVideoAudioConverter

:: the GUI also needs the websockets package:  pip install websockets

:: fetch the tools the app drives (searched in installer\bin and installer\ffmpeg
:: when running from source):
::   installer\bin\yt-dlp.exe      https://github.com/yt-dlp/yt-dlp/releases
::   installer\ffmpeg\ffmpeg.exe   https://www.gyan.dev/ffmpeg/builds/
::   installer\ffmpeg\ffprobe.exe

:: run directly
python app.py

:: or build the standalone exe
pip install pyinstaller
python -m PyInstaller --noconfirm --onefile --windowed --name TikTokVideoAudioConverter app.py
```

To build the installer, compile `installer\installer.iss` with
[Inno Setup 6](https://jrsoftware.org/isinfo.php) (it bundles the exe
plus the `bin` and `ffmpeg` tool folders).

`download_songs.py` is a no-GUI command-line version of the same pipeline
(reads links from `links.txt`, needs `pip install yt-dlp "curl_cffi<0.16"`)
— handy for scripting.

## Disclaimer

For personal use only — download music you could listen to on TikTok or
抖音 anyway, for your own offline listening. Respect artists and the
platforms' Terms of Service.
