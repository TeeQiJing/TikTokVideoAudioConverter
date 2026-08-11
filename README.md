# TikTok Video Audio Converter 🎵

A simple Windows desktop app that turns TikTok videos into MP3 songs —
perfect for building a music library for your car radio or offline player.

- **Batch download** an entire public TikTok **collection** with one link
- Or convert **individual video links**, one or many at a time
- Audio only — no video files, no online converter websites
- Songs are saved with proper **title and artist tags** (shown on car radios)
- **Remembers what it already downloaded** — run it again anytime and only
  new songs are fetched
- **Auto-retries** flaky TikTok responses
- **Self-updating download engine** — when TikTok changes something and
  breaks downloading, the fix arrives automatically, no reinstall needed

![App flow](https://img.shields.io/badge/TikTok-→%20MP3-brightgreen)

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

### Option B — Individual video links

1. On any TikTok video: **Share → Copy link**, and send it to your PC.
2. In the app choose **"Video link(s)"**, paste one or more links
   (messy text around the links is fine — they're detected automatically).
3. Click **Download MP3s**.

### Putting songs on a pendrive (car radio)

Copy the MP3s from your songs folder onto the pendrive. Tips:
- Most car radios want the pendrive formatted as **FAT32**.
- If the radio can't see the songs, try putting them in the pendrive's
  top-level (root) folder rather than a subfolder.

## Troubleshooting

- **"Unable to extract..." / some songs failed** — TikTok's servers are
  moody; the app already retries 3 times per run. Just run it again later,
  already-downloaded songs are never re-downloaded.
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
  handles talking to TikTok (including its JS challenge and browser
  impersonation), reads collection playlists, and picks a download
  format. The app runs its built-in self-updater (`yt-dlp -U`) before
  every download, so extractor fixes arrive automatically.
- [ffmpeg](https://ffmpeg.org/) — converts the audio track to MP3 and
  embeds title/artist metadata

A hidden archive file in your songs folder records every downloaded video
ID, which is what makes incremental syncing and cheap retries possible.

## Building from Source

```bat
git clone https://github.com/TeeQiJing/TikTokVideoAudioConverter.git
cd TikTokVideoAudioConverter

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

For personal use only — download music you could listen to on TikTok
anyway, for your own offline listening. Respect artists and TikTok's
Terms of Service.
