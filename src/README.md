# Сборка и запуск

Проект Android находится в [`android/`](android).

## Требования

- macOS / Linux / Windows с Android Studio
- JDK 17–21 для сборки из терминала (JDK 25 из Android Studio Gradle 8.14 не поддерживает): `brew install openjdk@21`, затем `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
- Android SDK 36, NDK `29.0.13113456`, CMake `3.31.6` (Android Studio предложит установить)
- ~6 ГБ свободного места (модели, сборка llama.cpp)

## 1. Зависимости

```bash
cd src/android
./scripts/fetch_deps.sh
```

Скрипт скачивает в `app/src/main/assets/models/` модели GigaAM v3, Silero VAD, pyannote,
3D-Speaker, Qwen3-1.7B (GGUF), а в `third_party/llama.cpp` — исходники llama.cpp
(зафиксированный коммит). Повторный запуск ничего не скачивает заново.

## 2. Сборка APK

```bash
./gradlew :app:assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk` (~1.4 ГБ, все модели внутри).
Release подписан debug-ключом, чтобы ставиться без дополнительной настройки.

## 3. Запуск на эмуляторе (Mac на Apple Silicon)

1. Android Studio → Device Manager → Create device → Pixel 8 → образ **API 36, arm64-v8a**.
2. В настройках AVD: RAM **≥ 6 ГБ**, Internal storage **≥ 8 ГБ** (APK + распакованная LLM).
3. Запустить эмулятор и установить APK:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

4. Тестовое аудио можно перетащить мышью в окно эмулятора (попадёт в Downloads)
   или загрузить командой `adb push dialog.wav /sdcard/Download/`.

Первый запуск занимает ~30 с: LLM (1 ГБ) распаковывается во внутреннюю память.

## Использование

1. **Импорт аудио** (wav, mp3, m4a, ogg, flac) или **Записать** с микрофона.
2. При необходимости задать число говорящих (по умолчанию «Авто»).
3. Дождаться окончания обработки — прогресс показывается по этапам.
4. Нажать на реплику — воспроизведение с этого места.
5. **Экспорт JSON** — сохранить расшифровку в выбранное место.

## Тесты

```bash
./gradlew :app:testDebugUnitTest
```
