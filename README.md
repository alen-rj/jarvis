# Jarvis — free voice assistant for your phone

A cinematic, voice-first assistant that installs on your phone like an app. Every part of it runs on free services. Total cost: **₹0**.

## What it costs

| Piece | Service | Cost |
|---|---|---|
| Speech to text | Browser's built-in recognition (Chrome on Android) | Free |
| Voice replies | Phone's built-in text-to-speech | Free |
| Weather | Open-Meteo (no key) | Free |
| Nearby places | OpenStreetMap / Overpass (no key) | Free |
| Offline answers | Wikipedia + DuckDuckGo (no key) | Free |
| Smart conversation + live Google search (optional) | Gemini API free tier, your own key | Free |
| PDF reading | pdf.js, runs on the phone | Free |
| Cinematic photo grade | Runs on the phone | Free |
| Hosting | GitHub Pages or Netlify | Free |

No server, no database, no subscription. Your memory, reminders and key stay on your phone.

## Put it on your phone (10 minutes)

The app must be served over **https** for the microphone to work. Pick one:

**Option A — GitHub Pages**
1. Create a new public repo, e.g. `jarvis`.
2. Upload every file in this folder (index.html, sw.js, manifest.webmanifest, icons).
3. Repo → Settings → Pages → Source: `main` branch, root → Save.
4. Open `https://<your-username>.github.io/jarvis/` in **Chrome on Android**.

**Option B — Netlify Drop**
Go to app.netlify.com/drop and drag this whole folder in. You get an https link right away.

Then in Chrome: menu ⋮ → **Add to Home screen / Install app**. It opens full screen like a native app.

## Turn on the brain (optional, free)

1. Go to https://aistudio.google.com/apikey and create a key (Google account only, no card).
2. In the app: Settings (gear) → paste it in **Gemini API key**.

The top bar switches from `LOCAL` to `GEMINI`. You now get natural conversation, multi-step tasks, Google-grounded search, PDF and photo understanding. The free tier has daily limits; when they run out the app falls back to local mode automatically. Note: Google may use free-tier requests to improve its models, so avoid sending private data.

## Things to say

- "Hey Jarvis, what's the weather today?"  /  "Weather in Coimbatore"
- "Set a timer for 20 minutes"
- "Remind me to submit my assignment at 8 PM"
- "Wake me up at 6:30 tomorrow"
- "Open Spotify and play my workout playlist"
- "Send a message to John saying I'll be there in 10 minutes" (add "on WhatsApp" for WhatsApp)
- "Call Mom"
- "Find me a good restaurant nearby"
- "What's on my schedule today?"
- "Search for the best Python courses and summarize the top three" (best with Gemini key)
- Attach a PDF (paperclip) → "Explain this PDF"
- Attach a photo → "Make it look cinematic"
- "Remember that my exam is on the 14th" → later: "What do you remember about me?"
- "Stop" / "Cancel"

## Wake word

Settings → **Wake word**. While the app is open and on screen, it listens for "Hey Jarvis" (rename it in settings). The screen stays awake while it listens.

**The limitation, stated plainly:** Android and iOS don't let any web app listen in the background or when the screen is off. That needs a native app with special system permissions. The closest reliable alternatives:
- Keep the app open on a charger/stand with wake word on (desk mode).
- Long-press the home-screen icon → **Talk now** shortcut starts listening instantly.
- "Hey Google, open Jarvis" launches it hands-free.

## What it can and can't do on the phone

Done fully in the app: timers, in-app reminders, memory, weather, search, nearby places, PDF summaries, photo grading, voice in and out.

Prepared for one tap from you (phones require it): calls, SMS/WhatsApp, opening apps, Clock alarms, calendar events. The assistant never says something was sent or called when it only prepared it.

Not possible from a web app: reading your calendar, browsing phone storage on its own, background reminders when the app is closed. For reminders that must ring when closed, tap **Add to Calendar** on the reminder.

Best on: **Chrome for Android**. iPhone Safari works for typing, weather, search and visuals; voice input there is limited.

## Files

- `index.html` — the whole app (UI, voice, core animation, tools, AI)
- `sw.js` — offline shell + notifications
- `manifest.webmanifest`, `icon*` — installable app metadata
