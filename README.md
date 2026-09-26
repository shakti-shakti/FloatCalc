# FloatCalc

FloatCalc is the standalone, service-only copy of PW's floating calculator.

It intentionally has no Activity, `MAIN` intent, or launcher category. The only
entry point is the `Floating Calculator` Quick Settings tile. Add the tile
manually, grant overlay permission when Android opens the system settings page,
then tap the tile again to start or stop the foreground overlay service.

The calculator panel, parser/evaluator, icons, scale preference, clipboard
behavior, drag/resize behavior, and Quick Settings state are copied from the PW
implementation. FloatCalc uses its own application ID and preference sandbox.

## Release build

The project uses the same Gradle/Android/Kotlin toolchain and signing defaults as
PW. From this directory:

```bash
./gradlew assembleRelease
```

The release APK is written to `app/build/outputs/apk/release/app-release.apk`.
The repository workflow is at `.github/workflows/build-floatcalc.yml`; the
standalone project also contains a copy at `FloatCalc/.github/workflows/build.yml`
so the folder can be moved into its own repository without changing the build.