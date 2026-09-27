# Better Endfield

[English](README.en.md) | [简体中文](README.md)

Better Endfield is a modular modding runtime for *Arknights: Endfield*. Features such as custom character appearances (BEM), title screen models & choreography, per-character voice language routing, OmniMix dynamic music replacement, real-time combat stats & rDPS metering, display enhancement (OptiScaler DLSS/FSR/XeSS) and mobile touch HUD emulation are provided as decoupled native DLL modules. The core Host handles dynamic IL2CPP runtime symbol resolution, Hook lifecycle management, configuration persistence, and module discovery.

The Windows desktop build and the Android/LSPosed build share one set of module sources. As of 3.3.0, custom character appearances use the same standard BEM package on both platforms. See [Android (LSPosed)](#android-lsposed) for the attach model, the in-game control panel and the diagnostics channel.

---

## Upstream & Project Origin

This project is derived from [Dr-hydra/Better-Endfield](https://github.com/Dr-hydra/Better-Endfield) (the upstream project). It is **independently maintained and is not an official version of the upstream project, and does not represent the upstream author**. The upstream code, documentation and design remain the work of their original authors; this repository continues to be released under AGPL-3.0-only, with the full license text in [LICENSE](LICENSE) and the upstream and third-party attributions recorded in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

This repository carries independent modifications relative to upstream 3.3.0 (commit `9b1e895`), concentrated on the Android/LSPosed port and its documentation; the details are in the 3.3.20 entry of [CHANGELOG.md](CHANGELOG.md). The upstream commit history is preserved in full so that code provenance — and the original authors' attribution — stays traceable. Upstream changes may still be selectively incorporated when appropriate, but continuous upstream contribution is not the goal.

---

## Architecture

```text
BetterEndfield.exe
  runtime/BetterEndfield.Host.dll
  modules/BetterEndfield.Model.dll
  modules/BetterEndfield.CustomModel.dll
  modules/BetterEndfield.Voice.dll
  modules/BetterEndfield.Music.dll
  modules/BetterEndfield.CombatStats.dll
  modules/BetterEndfield.UiModule.dll
  modules/BetterEndfield.Camera.dll
  modules/BetterEndfield.Actions.dll
  modules/BetterEndfield.Gacha.dll
  loaders/BetterEndfield.Injector.exe
  payloads/xinput1_4.dll
```

- `BetterEndfield.Host.dll`: The in-process host, runtime symbol resolver, and HookBroker.
- `BetterEndfield.Model.dll`: Title screen choreography, custom login characters, asset substitution, and camera animation controls.
- `BetterEndfield.CustomModel.dll`: Custom character appearance (BEM) assembly, material and texture binding, and LOD locking.
- `BetterEndfield.Voice.dll`: Per-character audio routing (Chinese/English/Japanese/Korean), Wwise media redirection, and lip-sync synchronization.
- `BetterEndfield.Music.dll`: OmniMix PCM stream injection, Wwise Audio Input integration, and native game music fallback.
- `BetterEndfield.CombatStats.dll`: Damage number toggles, real-time DirectX combat overlay, team rDPS attribution, and session history recording.
- `BetterEndfield.UiModule.dll`: Native mobile touch UI layout and mouse-to-touch injection.
- `BetterEndfield.Camera.dll`: Free camera, field of view (FOV) scaling, and near-object dither disabling.
- `BetterEndfield.Actions.dll`: Sustained dash and per-character action appearance toggles; disabled by default.
- `BetterEndfield.Gacha.dll`: Gacha record lookup and local statistics.
- `BetterEndfield.Injector.exe`: Default external loader; Host and all modules load directly from the application folder without modifying game files.
- `payloads/xinput1_4.dll`: Optional XInput DLL hijack loader, deployed to the game directory only upon user confirmation.

---

## Source Directory Layout

```text
ui/BetterEndfield.UI/          WinUI 3 desktop controller application
native/modules/model/          Title screen visual, model, and animation module
native/modules/custom_model/   Custom character appearance (BEM) assembly module
native/modules/voice/          Voice language routing and Wwise media module
native/modules/music/          OmniMix music integration module
native/modules/combat_stats/   Combat data metering and in-game DirectX HUD
native/modules/ui/             Mobile touch UI and input injection module
native/modules/camera/         Free camera and viewport enhancement module
native/modules/actions/        Sustained dash and character action module
native/modules/gacha/          Gacha record lookup module
native/loaders/injector/       External standalone injector
native/loaders/xinput/         XInput DLL proxy and in-process bootstrap
native/shared/                 Host, public ABI headers, and third-party dependencies
manifests/                     Resource manifests for models, voices, and dependencies
resources/                     Maintenance inputs for voice and catalog generators
installer/                     Inno Setup installer scripts and localization files
scripts/                       Build, manifest generation, and asset scanning scripts
tools/CustomModel/             BEM conversion, validation, and character profile tooling
android/                       Android/LSPosed release build
docs/                          Runtime interfaces, reverse engineering notes, and docs
```

---

## Key Features

1. **Custom Character Appearances (BEM)**: Import `.bem` packages to replace in-game character appearances. One standard package works on both Windows and Android. See [Custom Character Appearances](#custom-character-appearances-bem).
2. **Title Screen Customization**: Replace the default title screen character with any operator, select custom animations/poses, tweak camera angles, and apply custom theme accent colors.
3. **Voice Language Routing**: Assign custom voice languages (Chinese, English, Japanese, Korean) individually for each character in both combat and story dialogue.
4. **OmniMix Audio Engine**: Dynamically replace in-game music with custom audio sources via OmniMix.
5. **Real-Time Combat Stats Overlay**: High-performance DirectX in-game HUD displaying damage metering, team rDPS contribution, hit counts, crits, and skill breakdown.
6. **Display & Pipeline (OptiScaler)**: Upscaling with DLSS, FSR, or XeSS, frame generation, sharpness control, and free camera adjustments.
7. **Mobile Touch Emulation**: Experience the mobile touch UI on PC with mouse-to-touch conversion (`Ctrl+Alt+T`) and HUD toggling.
8. **Gacha Record Lookup**: Query and locally aggregate gacha history.
9. **Bilingual Localization**: Built-in support for both English (US) and Simplified Chinese with instant, runtime language switching.

---

## Custom Character Appearances (BEM)

Custom appearances are disabled by default. The release extension is `.bem`; one package targets one character and may carry several fixed appearances. Players only import the package - no Python, no source mod injection framework, no character database, and no hand-written `runtime.ini`.

Since 3.3.0 this works on both Windows and Android using **the same standard BEMv1 package**. Android compiles the desktop `native/modules/custom_model` sources directly, so there is no second implementation and no game offsets are introduced; parsing and validation follow the same path on both platforms.

On desktop, packages and their state live in the configuration directory. The distribution ships no appearance assets:

```text
%LocalAppData%\BetterEndfield\catalog\custom-model\
  runtime.ini
  packages\*.bem
```

`runtime.ini` is written by the Character Appearance page and is read-only at runtime:

```ini
[CustomModel]
standalone_lod=false

[Mod.<package_id>]
enabled=true
package=packages/<file>.bem
appearance=<appearance_id>
```

Only one package per character may be enabled at a time; duplicates are disabled in the UI with a prompt to reselect. Package and appearance choices take effect on the next game launch, so import, update and delete with the game closed. Updates reuse the same `package_id`, preserving the local enabled state and any appearance IDs that still exist; removed appearances fall back to the default with a notice. Enabling any package locks LOD at runtime; disabling all of them restores the standalone LOD preference.

The conversion tool reads unpacked directories as well as ZIP, RAR and 7z source packages directly, without pre-extraction or a separate archiver, and never runs programs contained in them. It matches source asset identity against the character profiles shipped with the tool, checking index counts, vertex streams, bones and materials; export is offered only after the full check passes. Unsupported sources produce a report explaining what is missing.

Capability boundaries: component replace/keep/hide, separate bone and material sources, merged bone palettes, per-draw game materials, replacement of explicitly bound native textures, and UInt16/UInt32 geometry indices. Limits are 256 local bones and 256 draws per part, and 32 texture bindings plus a 512 MiB upload budget per selected appearance; exceeding them fails with a report. Source hotkey scripts and arbitrary shaders are not executed, and runtime form switching, blend shapes, automatic LOD generation and automatic splitting are not supported. A successful conversion is not in-game verification.

Android manages the same packages from its own third-party model page, validating every appearance on import and preserving the original package bytes. The one platform difference is textures: mobile GPUs use different texture formats, so a package that looks wrong in game can be run through the mobile texture conversion on its management card. A successful conversion publishes a new generation while preserving the enabled state and selected appearance; failure or cancellation leaves the active package untouched. Packages without verified normal-map encoding metadata can still be imported, but cannot be converted. Both platforms require a game restart after changing packages or appearances.

For the authoring workflow, conversion automation boundaries and the full field reference see [`docs/BEM_CREATOR_GUIDE.md`](docs/BEM_CREATOR_GUIDE.md) and [`docs/BEM_V1_SPEC.md`](docs/BEM_V1_SPEC.md).

---

## Loader Modes

1. **Injector Mode (Recommended)**:
   - Starts the game from the Better Endfield controller or command line.
   - Zero files written to the game directory.
2. **XInput Autostart Mode**:
   - Deploys `xinput1_4.dll` to the game folder for automatic loading when launching the game via official launchers or desktop shortcuts.
   - Clean uninstall supported directly from the Settings page.

---

## Android (LSPosed)

The Android build compiles the same module sources (`android/`) and attaches to the game process through LSPosed. The companion app provides the model, third-party model, voice, display-enhancement and diagnostics pages, and the game hosts a floating control panel; package management and mobile texture conversion are covered in [Custom Character Appearances](#custom-character-appearances-bem) above.

The module attaches to whichever app is selected in the LSPosed scope (main process only), and decides whether to act on evidence: `UnityPlayer.nativeRender` must exist, and every native hook resolves by name from `libil2cpp.so` exports, so the official, international and channel builds share one APK. Three platform differences are handled explicitly: the JNI bridge self-heals a classloader mismatch, and a second copy of the module library only serves JNI symbols instead of installing hooks twice; enum constants go through `System.Enum.Parse` managed reflection rather than the boxing path that fails on some clients; static fields are read straight from their storage, and each value is judged on its own so a shared success flag cannot veto a healthy read.

The in-game panel turns the desktop hotkeys into tappable buttons: hide/restore HUD, free camera, world pause, first person, plus the movement pad and a roll/keyframe group under free camera (motion play/stop, view reset, FOV in/out, roll, keyframe record/play/clear). Key presses do not travel over JNI: the module library is loaded by the game's classloader while the panel's bridge classes belong to the LSPosed module classloader, and Android forbids opening the same `.so` path twice under different classloaders. Keys, runtime commands and status therefore go through plain files in the game's own directory, polled natively.

The bottom of the panel shows the current process's runtime log (a 150-line ring buffer) and offers "save log to file": a system file picker writes to a location the user chooses, no storage permission required, degrading to a text share when no picker is available. The companion app's diagnostics page reads the same log. If the runtime-log section is missing from the panel entirely, the game is still running an older module build - re-toggle the module in LSPosed or reboot the game.

Free-camera extensions default to no mouse-look hook on mobile (there is no cursor to hook), and the camera configuration uses `schema_version=3` with explicit key names, pinning the panel buttons to the codes the desktop module polls.

First-person part hiding has two paths: parts the GPU mesh patch can handle are unchanged; parts it cannot reach (non-skinned renderers, or patch attempts exhausted) switch to a shadow-casting-only renderer mode - nothing drawn in cameras, shadows kept. That property is read and written through the metadata contract system because Android's engine icall table is only partially implemented; a failed mesh-patch init no longer blocks this fallback either. The game can reset renderer state on part or LOD rebuilds, so the module re-asserts it.

The Android version is currently 3.3.20 (versionCode 30320) while desktop remains 3.3.0; the two version numbers are not yet unified.

For platform internals, contract evidence and the desktop-hotkey-to-panel-button mapping see [`android/README.md`](android/README.md).

---

## Building from Source

### Prerequisites
- Windows 10/11 (x64)
- Visual Studio 2022 / MSBuild with C++ (v143) and .NET 9 SDK
- CMake 3.20+
- Inno Setup 6 (for installer packaging)

### Build Steps
```powershell
# 1. Build all native modules and loaders
cmake -B build -S native -A x64
cmake --build build --config Release

# 2. Build the WinUI 3 Controller
dotnet build ui/BetterEndfield.UI/BetterEndfield.UI.csproj -c Release

# 3. Package the full distribution
.\scripts\BuildBetterEndfield.ps1
```

Android debug APKs are built by `.github/workflows/android-build.yml` on GitHub Actions for pushes and pull requests, uploaded as the `better-endfield-debug-apk` artifact. The environment is JDK 21, `platforms;android-37.0`, build-tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1; the Dobby v1.0.5 source is fetched before the build (that dependency is not committed) with its `example/` subdirectory removed, since it needs `DobbyInstrument` / `DobbySymbolResolver`, which Better Endfield disables.

---

## Disclaimer

Better Endfield is an unofficial, experimental open-source project. It is not affiliated with, endorsed by, or associated with Hypergryph, Mountain Contour, or GRYPHLINE. Please use responsibly and adhere to all relevant terms of service.

This repository is an independently maintained derivative of [Dr-hydra/Better-Endfield](https://github.com/Dr-hydra/Better-Endfield); upstream attribution, the derivation statement and the current modification status are in [Upstream & Project Origin](#upstream--project-origin) above.
