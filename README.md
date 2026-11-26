# Jarvis for Android

A native Android voice assistant. Say "Jarvis, ..." and it talks back (Jarvis-style), and controls your phone.
Brain = Claude API. No third-party libraries.

## Get the APK (no Android Studio needed)

You need a free GitHub account and a computer for the upload step (about 5 minutes).

1. Unzip this folder on your computer.
2. On github.com click **New repository**, name it `jarvis`, keep it **Private**, click Create.
3. On the new repo page click **uploading an existing file**, then drag in EVERYTHING inside the
   unzipped folder (including the `app` folder, `.github` folder, and the .kts files).
   - If the `.github` folder does not upload, click **Add file > Create new file**, type
     `.github/workflows/build.yml` as the name, paste the contents of that file from this zip, and commit.
4. Click **Commit changes**. Open the **Actions** tab: "Build Jarvis APK" starts by itself (about 3-5 minutes).
5. When it shows a green tick, open the run and download **Jarvis-APK** at the bottom. Unzip it to get `app-debug.apk`.
6. Send the APK to your phone, open it, and allow "Install unknown apps" when asked.

If the run goes red, open it, copy the error text and send it to Claude. It will be a small fix.

## First run on the phone

1. Open **Jarvis**, paste your Anthropic API key (console.anthropic.com), your name and language.
2. Tap buttons 1 to 4 once (permissions, overlay, accessibility, battery).
   - Overlay is needed so Android lets Jarvis open apps while it is in the background.
   - Accessibility lets Jarvis press Back/Home, tap buttons, scroll, type and read the screen.
3. Tap **START JARVIS**. Say "Jarvis, what's the battery?"

## Things to say

- "Jarvis, open WhatsApp" / "play Arijit Singh" / "navigate to Ahmedabad railway station"
- "Call Mom" / "send an SMS to Rahul saying I'll be late" (it asks you to say yes first)
- "Set an alarm for 6:30" / "timer for 10 minutes" / "turn on the flashlight" / "volume to 40"
- "Scroll down" / "tap Settings" / "go home" / "take a screenshot" / "what's on the screen?"
- "What's the news today?" / "weather in Ahmedabad" (web search)
- "Remember my sister's birthday is 3 March"
- "Go to sleep" (stops responding until you say Jarvis) / "Shut down"

## Notes

- Replies cost a small amount of API credit.
- Some phones (Xiaomi, Oppo, Vivo, Samsung) kill background apps: set Jarvis to "No restrictions"/"Autostart" in battery settings.
- The speech recogniser may beep on some phones. That is Android's own sound.
- The APK is a debug build signed with a debug key. It is fine for personal use.
