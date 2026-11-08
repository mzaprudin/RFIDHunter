# Сборка и проверки

[English version](DEVELOPMENT.md) · [Обзор](../README.RU.md) · [Пользовательские сценарии](USAGE.RU.md)

## Условия сборки

Используются JDK 17, Android SDK Platform 35 и Gradle Wrapper 8.9. Путь к SDK задаётся через `ANDROID_HOME` или строку `sdk.dir=/absolute/path/to/android-sdk` в игнорируемом файле `local.properties`. Недостающие компоненты и их лицензии устанавливаются через Android Studio или SDK Manager. Минимальная версия устройства — Android 9 (API 28).

У shell-скрипта в репозитории нет права исполнения: в Linux/macOS он запускается через `bash ./gradlew`. В PowerShell те же задачи запускаются через `.\gradlew.bat`. Для mock debug не нужны ключ релизной подписи, Honeywell SDK и речевая модель.

```bash
bash ./gradlew :app:assembleMockDebug :app:testMockDebugUnitTest
```

APK: `app/build/outputs/apk/mock/debug/app-mock-debug.apk`. Его идентификатор `ru.rfidhunter.app.mock` отличается от аппаратной сборки. Установка на тестовый эмулятор или телефон с разрешённой USB-отладкой:

```bash
adb install -r app/build/outputs/apk/mock/debug/app-mock-debug.apk
```

## Маршрут mock

Интерфейс и команды приложения — на русском. С главного экрана сервисный режим открывается пятью нажатиями `Громкость−` с удержанием пятого не менее 800 мс; весь жест занимает до трёх секунд. На сервисном экране доступны `Подключить`, `trigger`, сценарии меток и поле `Тест голосовой команды`.

1. Сценарий `UNKNOWN` и кнопка `trigger` запускают добавление близкой метки. Её постоянный EPC — `E2000017221101441890DEMO`.
2. Для знакомства с добавлением без речевой модели можно вернуться на главный экран, открыть сервисный режим и отправить `добавь очки` в текстовом поле. Далее — подтверждение названия, снимок и подтверждение фото. В этом маршруте первым вводится название. После принятия фото приложение ждёт метку: при выбранном `UNKNOWN` кнопка `ДЕМО: СЧИТАТЬ МЕТКУ` привязывает метку и сохраняет вещь.
3. Сохранённая вещь выбирается из каталога или командой `найди очки` с сервисного экрана. В новой базе нет заранее добавленных вещей.
4. С главного экрана снова открывается сервисный режим. Сначала нужна кнопка `Подключить`: диагностическое чтение требует готового считывателя. Кнопка `Проверить метки (5 секунд)` показывает переданные EPC и RSSI. Тестовый считыватель выдаёт короткие повторяемые серии чтений для проверки сценариев приложения.

| Сценарий | Какие чтения выдаёт |
| --- | --- |
| `NONE` | Меток нет; контекстная проверка переводит к голосовому поиску |
| `UNKNOWN` | Одна близкая метка с окончанием `DEMO` |
| `KNOWN` | Одна метка с окончанием `AB01`; она считается известной, только если такой EPC уже есть в каталоге |
| `MULTIPLE` | Две метки с близкими RSSI, без явного лидера |
| `SEARCH_FOUND` | Заданная из кода метка либо та же `AB01` с более сильным RSSI |

Названия сценариев обозначают фиксированные входные данные. После сохранения EPC метка из `UNKNOWN` станет известной. `SEARCH_FOUND` соответствует выбранной вещи только при совпадении EPC.

## Аппаратная сборка и модели

Honeywell SDK предоставляется отдельно. Файл размещается по пути `Honeywell_RFID_SDK_v0.5.0/libs/honeywell_rfid_sdk.aar` от корня репозитория:

```bash
bash ./gradlew :app:assembleHardwareDebug
```

Результат: `app/build/outputs/apk/hardware/debug/app-hardware-debug.apk`.

Совместимая русская модель Vosk скачивается и распаковывается отдельно. Путь задаётся в `local.properties` или параметром Gradle `-PvoskModelDir=models/vosk-model-small-ru-0.22`. Относительный путь считается от корня проекта:

```properties
voskModelDir=models/vosk-model-small-ru-0.22
```

Сборка копирует каталог в генерируемые assets, первый запуск устанавливает модель в приватное хранилище. Без модели доступны текстовые команды сервисного экрана. Записанные подсказки лежат в `app/src/main/assets/voice`; при их отсутствии остаётся текст. Необязательный скрипт `scripts/generate-elevenlabs-audio.ps1` создаёт подсказки на машине разработчика с переменными `ELEVENLABS_API_KEY` и `ELEVENLABS_VOICE_ID`.

Whisper.cpp выбирается вручную для диагностики. Для его сборки нужны NDK `26.3.11579264`, CMake `3.22.1` и локальная копия Whisper.cpp тега `v1.9.2` в `third_party/whisper.cpp`. Нативная сборка предназначена для `arm64-v8a`. Gradle включает её, если `ANDROID_NDK_HOME` указывает на NDK с файлом `source.properties`; в Windows также проверяется стандартный путь NDK внутри SDK. Если NDK найден, а исходников Whisper нет, CMake остановит сборку. Основной Vosk работает без этого необязательного слоя.

Параметр `whisperModelDir=models/whisper` включает в APK `ggml-base-q5_1.bin`. Скрипт `scripts/download-whisper-models.ps1` скачивает диагностические модели и проверяет хеши; приложение упаковывает только q5_1. Модели, SDK, сторонние исходники и ключи подписи исключены из Git.

## Релизная подпись и проверки на устройстве

Релиз требует существующего keystore и четырёх параметров в игнорируемом `local.properties` или локальных Gradle properties:

```properties
rfidhunter.storeFile=keystore/rfidhunter-release.jks
rfidhunter.storePassword=<keystore-password>
rfidhunter.keyAlias=rfidhunter
rfidhunter.keyPassword=<key-password>
```

```bash
bash ./gradlew :app:assembleHardwareRelease
```

Результат: `app/build/outputs/apk/hardware/release/app-hardware-release.apk`. Для обновлений нужен тот же ключ, которым подписано установленное приложение. Удаление приложения стирает локальный каталог и фотографии.

`TargetApkSmokeRunner` проверяет загрузку JNA/Vosk из оптимизированного APK и флаг BLE `neverForLocation` на Android 12+. Он распаковывает временную модель и распознаёт тишину без микрофона. На Android 9–11 проверка флага пропускается. Нужны Honeywell SDK и упакованная модель Vosk.

```bash
bash ./gradlew :app:assembleHardwareRelease :app:assembleHardwareReleaseAndroidTest -PandroidTestBuildType=release
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/hardware/release/app-hardware-release.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/hardware/release/app-hardware-release-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -r ru.rfidhunter.app.test/ru.rfidhunter.app.TargetApkSmokeRunner
```

Успех: `status=PASS`, `vosk=PASS`, `bluetooth=PASS` (или пропуск на Android 9–11), `INSTRUMENTATION_CODE: -1`. На телефоне с нужными данными используется ручная установка обновлений: `connectedAndroidTest` может удалить приложение после проверки.

Ручная регрессия камеры: выдача разрешения прямо на экране съёмки и снимок без повторного входа; обе кнопки громкости и быстрые повторы; уход во время съёмки без перехода по запоздалому результату. При подключённом IH25 запрос разрешения сохраняет соединение в обычном режиме, Home закрывает его, возврат запускает переподключение. Теги логов: `RfidCamera`, `RfidLifecycle`, `RfidVoice`, `HoneywellRfidGateway`.

## Диагностика звука

Диагностическая запись включается на сервисном экране. Исходные моно PCM16 WAV сохраняются в приватном каталоге `files/voice-diagnostics`. Из debug-установки известный файл можно выгрузить в Bash: `adb exec-out run-as ru.rfidhunter.app.mock cat files/voice-diagnostics/capture_TIMESTAMP.wav > capture.wav`. Для этого подходят тестовая установка и записи, разрешённые к передаче.

Команды из корня репозитория:

```bash
python3 scripts/vad_reference.py capture.wav --output-dir artifacts/vad --summary-csv artifacts/vad/summary.csv
python3 scripts/analyze_voice_wav.py capture.wav --csv-dir artifacts/levels
```

Эталонный детектор создаёт JSON-отчёт, CSV с шумовым фоном, порогами и состояниями каждого кадра, а при обнаружении речи — обрезанный WAV. Анализатор уровня использует фиксированный порог. Его параметр `--plot artifacts/levels.png` требует `matplotlib` и строит график первого файла. У Python и Kotlin разный шаг кадров; текущие реализации описывают родственные алгоритмы, без утверждения о совпадении результатов до семпла. [Алгоритм обработки](../ARCHITECTURE.RU.md#audio-pipeline)

<a id="verification"></a>
## Проверки

```bash
bash ./gradlew :app:testMockDebugUnitTest :app:assembleMockDebug
python3 scripts/test_vad_reference.py
cd server
uv sync --dev
uv run pytest
```

Результаты этого прохода документации, 08.11.2026: Python VAD — **5 тестов прошли**, сервер — **14 тестов прошли** (CPython 3.14.3). Серверные тесты выдали одно предупреждение Starlette об устаревающей интеграции с httpx. Android JVM — **38 тестов прошли**, без ошибок и пропусков; `assembleMockDebug` собран с OpenJDK 21.0.12.1, Gradle 8.9, Platform 35 и Build Tools 34.0.0. Речевые модели и Honeywell SDK не использовались.

Аппаратная сборка, smoke-тесты на телефоне и распознавание с микрофона требуют соответствующего устройства, SDK и моделей. Они проверяются отдельно от mock и JVM.
