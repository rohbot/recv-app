# Third-party dependencies

- **AndroidX Core 1.16.0** and its transitive AndroidX dependencies — Apache 2.0.
  <https://android.googlesource.com/platform/frameworks/support/>
  <https://www.apache.org/licenses/LICENSE-2.0>
- **Kotlin standard library** (transitive dependency) — Apache 2.0.
  <https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt>

The BLE transport uses Android's platform APIs; there is no USB serial driver
library in version 2. JUnit 4 and AndroidX Test are test-only dependencies and
are not included in the app APK. The app's Open-source licenses dialog includes
the Apache 2.0 license text. Inspect resolved runtime dependencies with:

```sh
./gradlew :app:dependencies --configuration debugRuntimeClasspath
```
