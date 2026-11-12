# Google Play Store Compliance & Policy Guide for TeamAccess Remote

Google Play Store maintains very strict policies for applications requesting **AccessibilityService API** and **MediaProjection / Foreground Service**.

Agar aap ye 4 zaroori steps follow karenge, to aapki app 100% Google Play Store par approve hogi bina kisi rejection ke.

---

### 1. Mandatory In-App Prominent Disclosure (Already Implemented)
Google Play policy requires that before requesting Accessibility or Screen Sharing, a full-screen or popup dialog MUST appear explaining:
1. Exactly what data is accessed (Screen video, touch gestures).
2. Exactly why it is needed (To allow remote support and control by partner).
3. That no keystrokes, personal passwords, or sensitive data are recorded or sold.

> **Status in Project:** This is already implemented in `MainActivity.kt` and `res/layout/dialog_disclosure.xml` (`checkProminentDisclosure()` runs on very first app launch).

---

### 2. Google Play Console: Accessibility Declaration Form
Jab aap Google Play Console par app submit karenge, to **App Content -> Sensitive App Permissions -> Accessibility API** section me aapse questions pooche jayenge:

* **Question 1: Does your app use the AccessibilityService API?**
  * **Answer:** YES.
* **Question 2: Select the purpose of Accessibility API:**
  * **Select:** "Remote control / Technical support tool" (or "Device Automation / Support").
* **Question 3: Provide a demonstration video (YouTube Link):**
  * **Action Required:** Aapko ek short 30-45 second ka unlisted YouTube video record karke link daalna hoga.
  * **Video Content:**
    1. App open karte hi Prominent Disclosure accept karte hue dikhayein.
    2. "Open Accessibility Settings" button click karke system settings me "TeamAccess Remote" ko ON karte hue dikhayein.
    3. Doosre phone se touch/click simulate hote hue screen par dikhayein.
* **Question 4: Text Explanation:**
  > *"TeamAccess Remote is a peer-to-peer remote support tool. It uses the AccessibilityService API solely to perform touch gestures (taps, drags, swipes) and navigation keys (Back, Home) received from an authenticated remote user who has been granted permission with a session PIN. No window content, keystrokes, or private user data is collected, stored, or transmitted."*

---

### 3. Foreground Service Declaration (Android 14 / targetSdk 34)
Android 14 me `FOREGROUND_SERVICE_MEDIA_PROJECTION` ke liye Play Console me permission declaration required hoti hai:
* **Service Type:** Media Projection
* **Reasoning:**
  > *"The foreground service with mediaProjection type is used to capture the device's screen and stream it live via encrypted WebRTC to an authorized partner for remote desktop support. A persistent notification is displayed with an instant Stop button to allow the user to terminate the session at any moment."*

---

### 4. Data Safety Form Answers
Google Play Console ke **Data Safety** form me:
* **Does your app collect or share user data?** -> **No**.
* All screen frames and touch coordinates are streamed direct P2P (Peer-to-Peer) via WebRTC end-to-end encrypted tunnels (DTLS-SRTP) and are not stored on any server.
* **Ephemeral Processing:** Yes, all data in transit is processed ephemerally.

---

### 5. Privacy Policy Template (Website URL required by Google)
Aapko kisi bhi free host (e.g. GitHub Pages ya Netlify) par Privacy Policy page daalna hoga:
* **Privacy Policy Summary:**
  * TeamAccess Remote collects 0 personal identifiers.
  * Peer ID is a randomly generated 9-digit session number.
  * Accessibility and screen capture are only enabled with explicit user interaction.
  * No logs of user screens, messages, or keystrokes are recorded.
