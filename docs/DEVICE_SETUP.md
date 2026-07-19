# Device setup — Android Studio & wireless debugging

From [Define device, test, and release acceptance](../.wayfinder/tickets/007-define-device-test-and-release-acceptance.md).

## On this Windows PC

1. Install [Android Studio](https://developer.android.com/studio) (stable).
2. Open **SDK Manager** and install:
   - Android SDK Platform **35**
   - Android SDK Platform-Tools
   - Android SDK Build-Tools 35.x
3. Note JDK path (usually `C:\Program Files\Android\Android Studio\jbr`).
4. Create `local.properties` from the example:

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

## On your phone (Android 11+)

1. **Settings → About phone** → tap **Build number** seven times.
2. **Settings → Developer options** → enable **Wireless debugging**.
3. Keep phone and PC on the same Wi‑Fi/LAN (or VPN that routes LAN).

## Pair (one-time)

On the phone: Wireless debugging → **Pair device with pairing code**.

On the PC:

```powershell
adb pair <phone-ip>:<pairing-port>
# enter the six-digit code
adb connect <phone-ip>:<debug-port>
adb devices
```

## Install debug build

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:installDebug
```

App id: `com.cupcakecomics.app.debug`

## Agent notes

- Agent can run Gradle/adb after Studio+SDK exist and after you complete pairing.
- Agent cannot tap Developer Options or accept the phone pairing dialog for you.
