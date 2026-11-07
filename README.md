# Нейро-стенографист (Nuclear IT Hack, команда HEX.TEAM)

Android-приложение, которое **полностью на устройстве и без интернета** превращает запись
разговора в структурированную расшифровку: кто говорил, когда и что сказал.
Главный экран — одна большая кнопка записи (и маленькая кнопка импорта файла); все записи
попадают в историю, где расшифровку можно раскрыть, прослушать с переходом к реплике и скачать в JSON.

| Главный экран | История | Расшифровка |
|---|---|---|
| ![](artifacts/demo/home_done.png) | ![](artifacts/demo/history_list.png) | ![](artifacts/demo/history_expanded.png) |

| Этап | Модель / библиотека |
|---|---|
| Шумоподавление | DPDFNet2 (адаптивно по SNR) + high-pass фильтр |
| Поиск речи (VAD) | Silero VAD через sherpa-onnx |
| Распознавание | GigaAM v3 RNNT с пунктуацией (Сбер), int8, beam search, sherpa-onnx; англицизмы латиницей |
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

Помимо кнопки скачивания в истории, каждая расшифровка автоматически сохраняется в
`/sdcard/Android/data/ru.hexteam.stenographer/files/transcripts/<название записи>.json`.

## Документация

- [Архитектура](docs/ARCHITECTURE.md)
- [Ограничения](docs/LIMITATIONS.md)
- [Тестирование](docs/TEST_RESULTS.md)
