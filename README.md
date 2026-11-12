# TeamAccess Remote - TeamViewer Alternative for Android

**TeamAccess Remote** ek ultra-low latency, production-ready, all-in-one Remote Control application hai jo Google Play Store policies ke saath 100% compliant hai.

Is application ke andar **Host (screen share + remote control allow karna)** aur **Client (partner ki screen dekhna aur control karna)** dono features ek hi single APK me integrate kiye gaye hain.

---

## ⚡ Key Highlights & Architecture

1. **All-in-One Architecture:**
   * Alag-alag apps download karne ki zaroorat nahi.
   * **Tab 1 ("Allow Control"):** Apna 9-digit Device ID aur 4-digit PIN dikhata hai. MediaProjection aur Accessibility Service handle karta hai.
   * **Tab 2 ("Control Partner"):** Partner ka 9-digit ID aur PIN enter karke direct remote screen dekhne aur interact karne deta hai.

2. **C++ NDK Engine (Sub-50ms Ultra Low Latency):**
   * **Native Binary Input Serialization (`input_processor.cpp`):** Touch events (down, move, up, gestures) ko heavy JSON me bhejne ke bajaye **16-byte compact binary packet** me pack kiya jata hai, jisse input delay 2-3ms tak ghat jata hai.
   * **Native Video Processing (`video_streamer.cpp`):** Hardware-accelerated color conversions aur frame throttling mechanism battery aur frame drops ko prevent karta hai.

3. **WebRTC Direct P2P Tunnel:**
   * Video stream aur touch data direct peer-to-peer (DTLS/SRTP encrypted) chalta hai.
   * Built-in Google STUN servers use hote hain taaki NAT/Firewall ke peeche bhi direct link ban sake.

4. **Remote Touch & Gesture Injection:**
   * Android ke native `AccessibilityService` ka use karke `dispatchGesture()` API ke zariye remote tap, drag, swipe aur navigation buttons (Back, Home, Recents) simulate kiye jaate hain.

5. **100% Google Play Store Compliant:**
   * Prominent Disclosure Dialog built-in.
   * `FOREGROUND_SERVICE_MEDIA_PROJECTION` compliant for Android 14+ (targetSdk 34).
   * Strict privacy: Screen text/window content read nahi hota (`canRetrieveWindowContent="false"`).

---

## 📁 Project Directory Structure

```
teamacces remote/
├── server/                               # WebSocket Signaling & ID Matching Server
│   ├── package.json
│   ├── server.js                         # 9-digit ID generator & WebRTC signaling
│   └── Dockerfile
├── android/                              # Complete Android Studio Gradle Project
│   ├── build.gradle
│   ├── settings.gradle
│   ├── gradle.properties
│   └── app/
│       ├── build.gradle                  # NDK, WebRTC, Jetpack dependencies
│       ├── CMakeLists.txt                # C++ Native build configuration
│       ├── proguard-rules.pro
│       └── src/main/
│           ├── AndroidManifest.xml       # Foreground Service & Accessibility declarations
│           ├── cpp/                      # C++ High-Performance Native Layer
│           │   ├── native_bridge.cpp     # JNI Interface
│           │   ├── input_processor.h/.cpp# Compact binary touch packing
│           │   └── video_streamer.h/.cpp # Frame & color pipeline
│           ├── res/                      # UI layouts, dialogs, styles
│           └── java/com/teamaccess/remote/
│               ├── MainActivity.kt       # Unified Host/Client dashboard
│               ├── RemoteViewActivity.kt # Partner control viewer & touch canvas
│               ├── service/
│               │   ├── ScreenCaptureService.kt           # 60fps MediaProjection
│               │   └── RemoteInputAccessibilityService.kt# dispatchGesture simulation
│               ├── webrtc/               # WebRtcClient & SignalingClient
│               └── utils/                # 9-digit IdGenerator & PermissionHelper
├── PLAYSTORE_COMPLIANCE.md               # Play Store submission, declaration & video guide
└── README.md
```

---

## 🚀 How to Run & Test

### Step 1: Start Signaling Server
Signaling server dono phones ke beech initial handshake aur ID pairing karwata hai:
```bash
cd server
npm install
npm start
```
Server `ws://localhost:9090` par start ho jayega.
*(Production me aap ise Render, Railway, AWS EC2, ya DigitalOcean par deploy karke public WebSocket URL use karein).*

### Step 2: Open in Android Studio
1. Android Studio open karein aur `teamacces remote/android` folder ko select karein.
2. Gradle sync hone dein (NDK aur WebRTC libraries automatically download hongi).
3. `MainActivity.kt` aur `RemoteViewActivity.kt` me `signalingServerUrl` ko apne server ke IP ya domain se update karein (Local emulator ke liye default `ws://10.0.2.2:9090` set hai).

### Step 3: Run on Device / Emulator
1. App install hone par **Prominent Disclosure** accept karein.
2. **"Open Accessibility Settings"** par click karke **TeamAccess Remote** ko toggle ON karein.
3. Host phone par **"Start Remote Sharing"** click karein (Screen cast permission allow karein).
4. Doosre phone se **Control Partner** tab me jakar Host ka **9-digit ID** aur **PIN** daal kar **Connect** dabayein.
5. Instant remote control start ho jayega!

---

## 🔒 Google Play Store Deployment
Play Store par upload karte waqt Google Console forms bharne ke liye `PLAYSTORE_COMPLIANCE.md` file dekhein.
