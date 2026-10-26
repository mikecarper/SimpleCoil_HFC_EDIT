# Release instrumentation tests

Build tests for the signed release app using the existing Gradle release signing
configuration:

```sh
./gradlew -I tools/release-android-test.init.gradle :app:assembleReleaseAndroidTest
```

The test APK is written to
`app/build/outputs/apk/androidTest/release/app-release-androidTest.apk`. Its package
is `com.simplecoil.simplecoil.test`. Verify that its certificate matches the
installed app before running instrumentation. Android 5 also requires a valid
v1 APK signature. This build does not install packages or clear app data.

The init script opts into the release test variant and uses its configured
signer. It also adds the app's DEX inputs to the test L8 task's reference tracing.
This retains the desugared Java APIs needed by both components. During Android
instrumentation, a helper such as `j$.util.Map$-EL` in the test APK can shadow the
app's copy. Shrinking the test helper using only test references can remove an
API still called by the app, producing a test-only `NoSuchMethodError` on
Android 5. Tracing both components keeps their required APIs together.

The wiring is scoped to `l8DexDesugarLibReleaseAndroidTest`; production L8 inputs
and options are unchanged. It uses the `dexFiles` input available in this
project's Android Gradle Plugin 8.1.4. Recheck that task input and the final test
APK when upgrading the plugin. The version-pinned [official AGP 8.1.4 source
archive](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.1.4/gradle-8.1.4-sources.jar)
contains `com/android/build/gradle/internal/tasks/L8DexDesugarLibTask.kt`.
Its `dexFiles` input is traced into keep rules before L8 shrinks the helper DEX.

Run the focused service tests on an idle phone after installing the matching
test APK. The Bluetooth connection and queue tests use API 21/22 GATT objects
with an isolated transport, while TCP client/server session and dispatch tests
use local sockets and controlled service callbacks. These checks do not replace
testing actual guns or multiplayer Wi-Fi traffic.
