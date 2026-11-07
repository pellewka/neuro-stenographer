# Нейро-стенографист (Nuclear IT Hack, команда HEX.TEAM)

Android-приложение, которое **полностью на устройстве и без интернета** превращает запись
разговора в структурированную расшифровку: кто говорил, когда и что сказал.
Результат можно посмотреть в приложении, прослушать с переходом к реплике и экспортировать в JSON.

| Этап | Модель / библиотека |
|---|---|
| Поиск речи (VAD) | Silero VAD через sherpa-onnx |
| Распознавание | GigaAM v3 RNNT с пунктуацией (Сбер), int8, sherpa-onnx |
| Диаризация | pyannote segmentation 3.0 + 3D-Speaker ERes2NetV2, sherpa-onnx |
| Локальная LLM | Qwen3-1.7B Q4_K_M через llama.cpp (протокол заседания, редактура пунктуации) |

В манифесте **нет разрешения INTERNET**: аудио и текст физически не могут покинуть устройство.

## Быстрый старт

```bash
cd src/android
./scripts/fetch_deps.sh        # модели (~1.3 ГБ) и llama.cpp
./gradlew :app:assembleRelease # APK: app/build/outputs/apk/release/app-release.apk
```

Подробно о сборке и запуске на эмуляторе: [src/README.md](src/README.md).

## Формат экспорта

```json
{
  "transcript": [
    { "speaker_id": "speaker_1", "start_ms": 1250, "end_ms": 4380, "text": "Добрый день!" }
  ]
}
```

Помимо ручного экспорта, каждая расшифровка автоматически сохраняется в
`/sdcard/Android/data/ru.hexteam.stenographer/files/transcripts/<имя файла>.json`.

## Документация

- [Архитектура](docs/ARCHITECTURE.md)
- [Ограничения](docs/LIMITATIONS.md)
- [Тестирование](docs/TEST_RESULTS.md)
