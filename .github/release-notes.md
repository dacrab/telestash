## Installation

Download the APK for your phone and install it
(enable "install from unknown sources" if asked).

Nearly all modern phones are `arm64-v8a`; older 32-bit ones are
`armeabi-v7a`. If you are not sure, take the universal build —
it works everywhere but is much larger.

| File | Use on |
| --- | --- |
| `telestash-VERSION-arm64-v8a.apk` | almost all modern phones (recommended) |
| `telestash-VERSION-armeabi-v7a.apk` | older 32-bit phones |
| `telestash-VERSION-x86_64.apk` | emulators |
| `telestash-VERSION.apk` | any device, ~95 MB |

> All APKs are signed with the project release key.
> Verify you trust the source before installing.

### Debug builds

Each ABI also has a `-debug` build (`telestash-VERSION-arm64-v8a-debug.apk`
and so on). These are the same code without R8 minification, so stack traces
and logs use the real class and method names — useful when reporting a crash.

They are signed with the same key as the stable builds, so either one installs
over the other without uninstalling, and they are **not** debuggable. They are
larger, slower to start, and not recommended for everyday use.
