# 📿 Japa Mala Counter - Android App

## Features
- 🎤 **Voice Recognition** - Audio fingerprint calibration, detects your word
- 👆 **Tap to Count** - Tap screen or press any area
- 🔊 **VOLUME BUTTON** - Press volume up/down to count (Android native!)
- 📿 **Two-Level Mala Counter** - Chants per Mala × Total Malas
- 🔔 **Voice Announcements** - "Mala 3 of 16 complete"
- 🎉 **Celebration** on target completion
- 📱 **Screen stays on** during chanting
- 🎧 **Works with earbuds**

## How to Build APK (3 minutes)

### Option A: Using Android Studio (Easiest)
1. Download & install [Android Studio](https://developer.android.com/studio)
2. Open Android Studio → **File → Open** → select this `JapaMalaCounter` folder
3. Wait for Gradle sync (1-2 minutes first time)
4. Click **Build → Build Bundle(s) / APK(s) → Build APK(s)**
5. APK will be at: `app/build/outputs/apk/debug/app-debug.apk`
6. Transfer APK to your phone and install

### Option B: Using Command Line
```bash
cd JapaMalaCounter
chmod +x gradlew
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

### Option C: Free Online Builder (No install needed)
1. Go to https://appetize.io or use GitHub Actions
2. Upload this project as a ZIP
3. Build online

## How to Install APK on Phone
1. Transfer the APK file to your phone (email, Google Drive, USB)
2. On phone: Settings → Security → Enable "Install from unknown sources"
3. Open the APK file → Install
4. Open "Japa Counter" app

## How to Use
1. **Capture Silence** - Stay quiet for 2 seconds
2. **Record Your Word** - Say your mantra word 5 times
3. **Set counters** - Words per chant, Chants per Mala, Total Malas
4. **Start Chanting** - Use voice detection OR tap OR volume buttons!

## Volume Button Counting
In the Android app, pressing **Volume Up** or **Volume Down** counts as a tap.
This is the main advantage over the web version - perfect for counting with eyes closed!
The volume buttons are intercepted by the app and won't change your phone's volume.

## Project Structure
```
JapaMalaCounter/
├── build.gradle              (root build file)
├── settings.gradle           (project settings)
├── gradle.properties         (build properties)
├── app/
│   ├── build.gradle          (app build config)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   └── index.html    (the full japa counter web app)
│       ├── java/com/japa/counter/
│       │   └── MainActivity.java  (WebView + volume button capture)
│       └── res/
│           ├── layout/       (not used - WebView is fullscreen)
│           ├── values/       (theme, strings)
│           └── mipmap-*/     (app icons)
```
