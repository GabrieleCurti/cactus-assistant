# Cactus Assistant for Android (Whistle + Needle 3)

An on-device Android Digital Assistant powered by **Cactus Compute**:
- **Whistle (`whistle.cact`, 16.9 MB)**: On-device 16 kHz speech-to-text (ASR) with keyword beam biasing and silence gating.
- **Needle 3 (`needle3.cact`, 8–29 MB)**: On-device grammar-constrained tool-calling foundation model running in the same <1 MB C++ engine (`libcactus.a`).

## Features

1. **Android Default Digital Assistant (`VoiceInteractionService` + `ACTION_ASSIST`)**:
   - Registers `CactusVoiceInteractionService`, `CactusVoiceInteractionSessionService`, `CactusRecognitionService`, and `AssistActivity` (`android.intent.action.ASSIST`, `VOICE_SEARCH_HANDS_FREE`, `WEB_SEARCH`).
   - One-tap role request (`RoleManager.ROLE_ASSISTANT` / `Settings.ACTION_VOICE_INPUT_SETTINGS`) from the dashboard UI.
2. **On-Device Voice-to-Tool-Call Pipeline (JNI / CMake)**:
   - Memory-maps (`mmap`) `whistle.cact` and `needle3.cact` in place via `cactus_load` and runs unified (`cactus_complete`) and two-step (`cactus_transcribe` → `cactus_complete`) inference (`app/src/main/cpp/cactus_jni.cpp`) with adaptive microphone noise-floor capture and quiet-speech normalization.
3. **Silent Timers & Alarms (`AlarmClock`)**:
   - `set_timer` (`time_human`, optional `label`) → `AlarmClock.ACTION_SET_TIMER` with `EXTRA_LENGTH`, optional `EXTRA_MESSAGE`, and `EXTRA_SKIP_UI = true`.
   - `set_alarm` (`time_human`, optional `label`) → `AlarmClock.ACTION_SET_ALARM` with `EXTRA_HOUR`, `EXTRA_MINUTES`, optional `EXTRA_MESSAGE`, and `EXTRA_SKIP_UI = true`.
4. **Google Tasks Integration (`com.google.android.apps.tasks`)**:
   - `create_todo` (`title`) → Opens Google Tasks pre-filled with the task title via `Intent.ACTION_SEND`.
5. **Google Gemini Fallback (`com.google.android.apps.bard`)**:
   - Any query outside the declared tools returns Needle 3's calibrated empty refusal `function_calls: []` and forwards the transcript pre-filled to the installed Google Gemini Android app (`com.google.android.apps.bard`).

## Quick Start

### 1. Download the `android-arm64` Engine & `.cact` Weights

Run the included download script to fetch `libcactus.a`, `cactus.h`, `whistle.cact`, and `needle3.cact` from Hugging Face (`Cactus-Compute/cactus`, `Cactus-Compute/whistle`, and `Cactus-Compute/needle3`):

```bash
./scripts/download_cactus.sh
```

Or if you have the `cactus` Python CLI installed (`pip install -U cactus-compute`) and want to slice Needle 3 to a specific layer depth (e.g., 8 layers):

```bash
./scripts/download_cactus.sh --use-cli --layers 8
```

### 2. Build & Test

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```
