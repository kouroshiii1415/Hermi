# Hermi 🤖

A **native Android voice assistant** (Kotlin) in the spirit of Siri — with a free GLM brain.

> Built as a real Android app: system assistant service, wake word, microphone, TTS,
> and deep device control (calls, SMS, alarms, apps, flashlight…). Not a web app.

## ✨ Features

| Area | What Hermi can do |
|---|---|
| 🎙 Voice | "Hey Hermi" wake word (background), long-press-Home assistant, English STT + TTS |
| 🧠 Brain | GLM via **Hermi Bridge (free, no key)**, Z.AI free `glm-4.5-flash`, BigModel free `glm-4-flash`, or any OpenAI-compatible API |
| 📞 Phone | Place calls (number or contact name), send SMS |
| ⏰ Time | Alarms, countdown timers, reminders (notifications) |
| 📱 Device | Open apps, flashlight, volume, media play/pause/next/prev, Wi-Fi/BT panels |
| 🌐 Web | Open URLs, Google search, live weather (wttr.in) |
| 📝 Misc | Contacts lookup, notes, battery status, time |
| 💬 Chat | Multi-turn conversation with memory (function calling agent loop) |
| 🔌 Offline | Rule-based mode works with **no internet / no API** for core commands |

## 🧠 Connect a free brain

1. **Bridge mode (default, free):** run the Hermi Bridge web service, copy its URL
   (`https://<your-bridge>/api/hermi/brain`) into Hermi → Settings → Brain → Bridge.
2. **Z.AI (free):** create an account at [z.ai](https://z.ai) → API Keys → paste key in
   Hermi settings → provider *Z.AI*, model `glm-4.5-flash` (free tier).
3. **BigModel (free):** [open.bigmodel.cn](https://open.bigmodel.cn) → key → provider
   *BigModel*, model `glm-4-flash` (free tier).
4. **Custom:** any OpenAI-compatible `/chat/completions` URL + key + model.

## 📲 Install

1. Download `Hermi-debug.apk` from the GitHub Actions artifacts (`Build Hermi APK` run).
2. Enable *Install unknown apps* for your browser/file manager.
3. Install, open Hermi, grant: Microphone, Phone, SMS, Contacts, Notifications,
   Overlay, and set Hermi as **Digital assistant app**.
4. Toggle **Wake word** and say *"Hey Hermi"*, or long-press Home.

## 🔨 Building yourself

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

CI: every push to `main` builds the APK via GitHub Actions.

## ⚠️ Notes

- Wake-word listening uses Android's SpeechRecognizer in a foreground service —
  it works while the screen is on; battery-friendly enough for daily use.
- SMS/call actions use runtime permissions and are only triggered by the agent's
  tools — check your platform's rules before distributing.

Made with 💜 — Hermi.
