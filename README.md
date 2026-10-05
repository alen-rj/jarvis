# Jarvis — Android app

Your Jarvis assistant as a real Android app. Free to build, free to run, no Play Store needed.

## What the app adds over the website

- **"Hey Jarvis" with the screen off.** It listens offline on your phone (open-source Vosk model), with a small notification while it listens.
- **Default assistant.** Long-press the power button, or use the assistant gesture, and Jarvis opens already listening.
- **Real alarms and timers** go straight into your Clock app.
- **Reminders that ring even when Jarvis is closed**, and they survive a reboot.
- **Reads your calendar** for "What's on my schedule today?"
- **Finds people in your contacts** and opens the call or text ready to go. You press call or send.
- **Opens any installed app by name.**
- **Saves cinematic photos** to Pictures › Jarvis.

Everything else works as before: Gemini (free key) for conversation and search, weather, nearby places, PDFs, memory.

## Get the APK (no Android Studio needed)

GitHub builds it for you for free.

1. Upload everything in this folder to your GitHub repo (`alen-rj/jarvis`), including the hidden `.github` folder.
2. Open the repo's **Actions** tab. If asked, click **"I understand my workflows, go ahead and enable them"**.
3. The **Build Jarvis APK** run starts by itself (or click **Run workflow**). It takes about 5–8 minutes.
4. When it's green, open on your phone: `https://github.com/alen-rj/jarvis/releases/latest`
5. Tap **jarvis.apk** to download it, then open it. Allow "Install unknown apps" for Chrome when Android asks.

Every later push builds a new version. Install it over the old one; your data stays.

## First-time setup on the phone

Open Jarvis → **gear icon** → **Phone setup**, and tap each button:

1. **Allow** permissions: microphone, contacts, calendar, calls, texts, alerts.
2. **Download** the offline wake-word model (40 MB, one time).
3. **Allow** "Display over other apps", so Jarvis can open when you call it with the screen off.
4. **Allow** unrestricted battery, so Android doesn't kill it.
5. **Set** default assistant: choose **Jarvis** under *Default digital assistant app*.

Then turn on **Wake word** in the same Settings panel and paste your Gemini key.

Xiaomi, Redmi, Realme, Oppo, Vivo, OnePlus: also open the phone's own Settings → Apps → Jarvis and turn on **Autostart**, and set battery to **No restrictions**. These brands close background apps aggressively.

## Honest limits

- **Battery.** The wake word keeps the microphone on, so expect noticeably higher battery use while it's enabled.
- **After a reboot**, open Jarvis once to restart the wake word. Android doesn't let apps start the microphone by themselves at boot. Reminders come back on their own.
- **Accuracy.** The offline wake-word model is small, so it may sometimes miss "Hey Jarvis" or mishear similar words. Speaking a little louder and slower helps.
- **Ordinary command recognition** uses Google's free speech service on your phone. It needs internet unless you download offline speech in the Google app.
- **Signing key.** The app is signed with a key stored in this repo (`android/app/jarvis.keystore`). That's fine for a personal app. If you ever publish it on the Play Store, make a new private key instead.

## Project layout

- `www/` — the web app (same as the website). Edit `index.html` / `native.js` here.
- `android/` — native Android project (Capacitor 8). Native code: `android/app/src/main/java/com/alenrj/jarvis/`
- `.github/workflows/build-apk.yml` — the free cloud build.
