# Сборка и запуск

Проект Android находится в [`android/`](android).

## Требования

- macOS / Linux / Windows с Android Studio
- JDK 17-21 для сборки из терминала (JDK 25 из Android Studio Gradle 8.14 не поддерживает): `brew install openjdk@21`, затем `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
- Android SDK 36, NDK `29.0.13113456`, CMake `3.31.6` (Android Studio предложит установить)
- ~6 ГБ свободного места (модели, сборка llama.cpp)

## 1. Зависимости

```bash
cd src/android
./scripts/fetch_deps.sh
```

Скрипт скачивает в `app/src/main/assets/models/` модели GigaAM v3, Silero VAD, pyannote,
3D-Speaker, Qwen3-1.7B (GGUF), а в `third_party/llama.cpp` - исходники llama.cpp
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

Первый запуск занимает ~30 с: LLM (1 ГБ) и модель отделения вокала распаковываются во внутреннюю память.
Разрешите уведомления: в них показывается прогресс долгой обработки, пока приложение свёрнуто.

Готовый APK можно не собирать: он приложен к релизу репозитория на GitHub.

## Использование

0. Вверху главного экрана выбрать профиль: **Подкаст** (по умолчанию, подходит и для коротких
   диалогов), **Музыка** или **Презентация**.

1. Большая кнопка - запись с микрофона (повторное нажатие - стоп), маленькая кнопка под ней - импорт файлов (wav, mp3, m4a, ogg, flac); можно выбрать несколько - например, весь альбом, они встанут в очередь.
2. После обработки появится галочка, а на вкладке истории - значок «+1».
3. В истории раскрыть запись стрелкой: протокол, реплики, плеер.
4. Нажать на реплику - воспроизведение с этого места.
5. Кнопка скачивания внизу карточки - сохранить JSON. Настройки (число говорящих, шумоподавление, англицизмы, LLM) - на вкладке с шестерёнкой.

## Тесты

```bash
./gradlew :app:testDebugUnitTest
```
