# RFIDHunter: handoff по IH25, поиску и запуску по курку

Этот файл — краткая карта текущей реализации для продолжения работы в новой
сессии. Ссылки ведут в исходники репозитория; номера строк являются ориентиром
и могут сдвигаться после следующих изменений.

Состояние зафиксировано коммитом `7238a00`:
`Stabilize RFID search feedback and trigger launch`. Предыдущие связанные
коммиты: `1935ea0` (continuous search), `c5aa2a9` (phone signal feedback),
`50e0e41` (dismiss trigger notification), `90e44ca` (proximity decision).

## Главная идея UX

Приложение рассчитано на одного пользователя, которому не нужно выбирать
считыватель или разбираться в RSSI. После выдачи разрешений приложение само
сканирует BLE, выбирает однозначный IH25, сохраняет MAC и подключается. На
главном экране при готовом reader показывается только понятная инструкция:
«Нажмите курок чтобы проверить метку или начать поиск»; технические статусы
остаются в сервисном режиме.

Главная цепочка по короткому нажатию курка:

1. Быстрая проверка выполняется на минимальной мощности (5 dBm) примерно 850 ms.
2. `RfidDecisionEngine` группирует EPC, берёт лучший RSSI каждой метки и
   выбирает только явно близкую метку (RSSI не слабее `-48 dBm` и преимущество
   над второй меткой не менее 8 dB).
3. Известный EPC открывает карточку вещи; неизвестный запускает добавление
   вещи голосом; отсутствие метки, слабый сигнал или неоднозначность запускают
   голосовой поиск вещи.

См. [MainViewModel.kt, обработчики курка и initial check](app/src/main/kotlin/ru/rfidhunter/app/ui/MainViewModel.kt#L316-L448),
[RfidDecisionEngine.kt](app/src/main/kotlin/ru/rfidhunter/app/domain/RfidDecisionEngine.kt)
и [SearchPowerPolicy.kt](app/src/main/kotlin/ru/rfidhunter/app/domain/SearchPowerPolicy.kt).

## Автопоиск и подключение IH25

- Единственный экземпляр gateway создаётся в
  [RfidHunterApplication.kt](app/src/main/kotlin/ru/rfidhunter/app/RfidHunterApplication.kt#L9-L17),
  поэтому foreground service и UI не регистрируют дублирующиеся SDK-listener'ы.
- [MainActivity.kt](app/src/main/kotlin/ru/rfidhunter/app/MainActivity.kt#L170-L227)
  сначала запрашивает BLE-разрешения (Android 12+: `BLUETOOTH_SCAN` и
  `BLUETOOTH_CONNECT`; старые версии: location), затем запускает
  `RfidForegroundService`. На Android 13+ дополнительно запрашивается
  `POST_NOTIFICATIONS`.
- После разрешений `MainActivity` вызывает
  `MainViewModel.startAutomaticReaderSetup()` при создании и возобновлении
  Activity. [MainViewModel.kt#L175-L231](app/src/main/kotlin/ru/rfidhunter/app/ui/MainViewModel.kt#L175-L231):
  BLE-сканирование длится 5 секунд; сохранённый MAC имеет приоритет, иначе
  автоматически выбирается ровно один Honeywell/IH25 (или единственное
  найденное устройство). При нескольких устройствах выбор остаётся в сервисном
  режиме. Холодный `createReader()` может занимать до 30 секунд, после timeout
  выполняется повторная попытка.
- MAC сохраняется в `SharedPreferences` ключом `reader_mac` через
  [HoneywellRfidGateway.readerAddress](app/src/hardware/kotlin/ru/rfidhunter/app/hardware/HoneywellRfidGateway.kt#L65-L69).
- BLE scan и фильтрация имени (`Honeywell`, `IH25`, `IH...`) находятся в
  [HoneywellRfidGateway.scanDevices](app/src/hardware/kotlin/ru/rfidhunter/app/hardware/HoneywellRfidGateway.kt#L136-L216).
  В список всё равно попадают все устройства, чтобы диагностика не скрывала
  неожиданное имя.

### Жизненный цикл SDK

[HoneywellRfidGateway.kt](app/src/hardware/kotlin/ru/rfidhunter/app/hardware/HoneywellRfidGateway.kt):

- В `init` один раз регистрируется `EventListener`, включается auto-reconnect и
  `TriggerMode.RFID` ([L71-L133](app/src/hardware/kotlin/ru/rfidhunter/app/hardware/HoneywellRfidGateway.kt#L71-L133)).
- Перед каждым connect listener сначала удаляется и добавляется снова, чтобы
  SDK не потерял callback и не накопил дубликаты. Boolean-результат
  `manager.connect()` не считается окончательным: результат приходит через
  `onDeviceConnected`/`onDeviceDisconnected` ([L218-L247](app/src/hardware/kotlin/ru/rfidhunter/app/hardware/HoneywellRfidGateway.kt#L218-L247)).
- `onDeviceConnected` сохраняет MAC, переводит состояние в `CONNECTED` и
  вызывает `createReader()`. `onReaderCreated` проверяет `available()` и только
  после этого выставляет `readerReady=true`; регион пытается установить в
  `Russian` ([L71-L118](app/src/hardware/kotlin/ru/rfidhunter/app/hardware/HoneywellRfidGateway.kt#L71-L118)).
- Неуспешное создание reader публикует `ConnectionState.ERROR`; disconnect
  останавливает inventory, очищает reader и публикует `DISCONNECTED`.
- `release()` снимает listener и безопасно останавливает чтение; вызывающие
  сервисы должны вызывать его при окончательном освобождении процесса.

Состояния и диагностика отображаются в
[ServiceScreen](app/src/main/kotlin/ru/rfidhunter/app/ui/RfidHunterApp.kt#L860-L941):
`сканирую → подключаюсь → создаю reader → готов`, а также `ошибка` и
`отключён`. В основном UX надпись «Нажмите курок…» скрывается на время
подключения.

## Аппаратное чтение и мощность

- `startInventory()` запрещён до успешного `reader.created` и
  `reader.available()` ([HoneywellRfidGateway.kt#L258-L292](app/src/hardware/kotlin/ru/rfidhunter/app/hardware/HoneywellRfidGateway.kt#L258-L292)).
- Мощность домена задаётся в dBm от 5 до 30. SDK получает значение в сотых
  долях dBm (`power * 100`). `TagReadOption` запрашивает RSSI и `stopPercent=0`,
  чтобы inventory шёл с максимально возможной частотой.
- На одну сессию регистрируется один `OnTagReadListener`. Поток
  `tagObservations` сохраняет каждый EPC/RSSI для живого поиска; поток
  `tagReads` дедуплицирует EPC и выдаёт первый или более сильный RSSI
  ([L354-L378](app/src/hardware/kotlin/ru/rfidhunter/app/hardware/HoneywellRfidGateway.kt#L354-L378)).
- `stopInventory()` всегда вызывает `stopRead()` и снимает listener, даже если
  локальный флаг активности потерян.
- В непрерывном поиске inventory запускается длинными отрезками. При изменении
  ступени мощности выполняется контролируемая последовательность
  `stopRead → setAntennaPower → read`; это единственное ожидаемое короткое окно
  переключения, а не пауза между каждым RFID callback.

## Непрерывный поиск и единая шкала сигнала

Поиск вещи из каталога стартует на `30 dBm` ([MainViewModel.kt#L450-L523](app/src/main/kotlin/ru/rfidhunter/app/ui/MainViewModel.kt#L450-L523)).
Каждые 500 ms оцениваются окна сигнала:

- RSSI сильнее `-55 dBm` в трёх устойчивых окнах подряд → мощность уменьшается
  на 5 dBm, чтобы сузить зону и приблизиться к ближайшей метке.
- Четыре окна без наблюдений → мощность увеличивается на 5 dBm, максимум 30.
- Пока условия не подтверждены, мощность не меняется. Интерфейс обновляется
  примерно каждые 50 ms.

[SearchSignalTracker.kt](app/src/main/kotlin/ru/rfidhunter/app/domain/SearchSignalTracker.kt)
  приводит измерение к общей шкале, как если бы оно было получено при 30 dBm:

```text
referenceRssi = rawRssi + (30 - currentPower) * 0.4
```

Коэффициент `0.4` аппаратно подобран для IH25: снижение мощности на 5 dBm в
среднем меняет RSSI примерно на 2 dB. После компенсации применяется EMA с
`smoothingFactor=0.24`; сигнал считается устаревшим через 900 ms без наблюдений.
Это предотвращает скачок стрелки вниз при каждом переключении мощности и
сохраняет глобальную шкалу направления. Реализация также хранит лучший RSSI
текущего окна для принятия решения о следующей ступени.

Сама политика отображения — [SearchSignalPolicy.kt](app/src/main/kotlin/ru/rfidhunter/app/domain/SearchSignalPolicy.kt):
диапазон `-82…-42 dBm`, минимальное видимое отклонение `0.08`, нелинейная
кривая `pow(0.55)` усиливает различимость слабого сигнала.

## Стрелка и «дозиметрический» звук телефона

- Экран поиска рисует аналоговую шкалу с цветными зонами и стрелкой,
  отображает фото вещи и текст «Сигнала нет / Слабый сигнал / Сигнал
  усиливается / Метка близко» ([RfidHunterApp.kt#L585-L701](app/src/main/kotlin/ru/rfidhunter/app/ui/RfidHunterApp.kt#L585-L701)).
- Кнопка «Удерживать курок» удалена. После отпускания появляется только
  «ЗАВЕРШИТЬ ПОИСК», которая возвращает сразу в `Idle`, без карточки.
- В `SearchPaused` новое нажатие, удерживаемое 1 секунду, возобновляет поиск
  без отпускания и повторного нажатия ([MainViewModel.kt#L332-L377](app/src/main/kotlin/ru/rfidhunter/app/ui/MainViewModel.kt#L332-L377)).
- IH25 beeper оставлен для простых подтверждений; динамический «треск» звучит
  на телефоне через [GeigerClickPlayer.kt](app/src/main/kotlin/ru/rfidhunter/app/ui/GeigerClickPlayer.kt).
  [GeigerClickFeedback](app/src/main/kotlin/ru/rfidhunter/app/ui/RfidHunterApp.kt#L704-L744)
  работает собственным таймером, не привязанным к приходу RFID-пакетов:
  `1100 ms` (нет сигнала) → `85 ms` (максимум), нелинейная кривая, быстрое
  сглаживание при усилении (`0.18`) и медленное при ослаблении (`0.06`).
  В debug log используется тег `GeigerClick`.

## Курок, экран и фоновые состояния Android

[RfidForegroundService.kt](app/src/main/kotlin/ru/rfidhunter/app/hardware/RfidForegroundService.kt)
  держит Bluetooth-события в foreground service и слушает `PRESSED`:

- если `MainActivity` действительно на переднем плане (lifecycle resumed **и**
  process importance `FOREGROUND`), запуск UI пропускается, поэтому не возникает
  лишнего «Открываю приложение»;
- при погашенном экране берётся короткий wake lock на 8 секунд;
- для заблокированного экрана или скрытого приложения запускается
  `TriggerWakeActivity`, маленький trampoline с `showWhenLocked/turnScreenOn`,
  который переводит задачу в `MainActivity`;
- полноэкранное уведомление используется только когда экран интерактивен
  неверно/заблокирован. Оно отменяется сразу в trampoline и через 15 секунд как
  страховка.

Цепочка описана в [RfidForegroundService.kt#L86-L243](app/src/main/kotlin/ru/rfidhunter/app/hardware/RfidForegroundService.kt#L86-L243),
[TriggerWakeActivity.kt](app/src/main/kotlin/ru/rfidhunter/app/TriggerWakeActivity.kt)
и [MainActivity.onNewIntent](app/src/main/kotlin/ru/rfidhunter/app/MainActivity.kt#L111-L133).
`MainViewModel.onTriggerWakeIntent()` дедуплицирует повторное событие в течение
750 ms, чтобы один физический нажим не запустил сценарий дважды.

Системный экран может гаснуть по обычным настройкам Android. Отдельный
«перевод приложения в сон» не удерживает экран: после 60 минут бездействия
`InactivityPolicy` останавливает inventory/голос и возвращает состояние в Idle
([MainViewModel.kt#L931-L945](app/src/main/kotlin/ru/rfidhunter/app/ui/MainViewModel.kt#L931-L945),
[InactivityPolicy.kt](app/src/main/kotlin/ru/rfidhunter/app/domain/InactivityPolicy.kt)).

## Устойчивые нажатия интерфейса

Локальные обёртки `Button`, `OutlinedButton` и `TextButton` в
[RfidHunterApp.kt#L209-L293](app/src/main/kotlin/ru/rfidhunter/app/ui/RfidHunterApp.kt#L209-L293)
используют один `resilientButtonInput`: первоначальный палец определяет кнопку,
небольшое движение не отменяет действие, второй палец блокирует активацию, а
повторное срабатывание подавляется debounce 500 ms. Добавлен semantics `onClick`
для accessibility. Экран проверки фото использует тот же принцип в
`resilientReviewInput` ([RfidHunterApp.kt#L539-L562](app/src/main/kotlin/ru/rfidhunter/app/ui/RfidHunterApp.kt#L539-L562)).

## Сборка, устройство и проверки

В `app/build.gradle.kts` Android/Kotlin target остаётся JVM 17, но для запуска
Gradle рекомендуется JDK 21: `C:\Users\M\.jdks\jbr-21.0.11` (или Android
Studio JBR 21). README исторически говорит «JDK 17» — это compile target, а не
обязательная версия runtime.

PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Users\M\.jdks\jbr-21.0.11'
.\gradlew.bat :app:testMockDebugUnitTest
.\gradlew.bat :app:assembleHardwareDebug
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" connect 192.168.1.135:45161
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
```

Последняя аппаратная smoke-проверка на Samsung подтвердила подключение IH25 и
получение меток. После исправления независимого таймера фактические интервалы
щелчков при присутствующей метке были примерно `139–165 ms`; длинные интервалы
остаются только при реальной потере метки на минимальной мощности. AAR подключён
только через `hardwareImplementation`, поэтому в mock APK Honeywell SDK не
попадает ([app/build.gradle.kts#L127-L140](app/build.gradle.kts#L127-L140)).

Unit-тесты домена находятся в
[DomainBehaviorTest.kt](app/src/test/kotlin/ru/rfidhunter/app/domain/DomainBehaviorTest.kt)
и [RfidDecisionEngineTest.kt](app/src/test/kotlin/ru/rfidhunter/app/domain/RfidDecisionEngineTest.kt):
дедупликация EPC, выбор по RSSI/lead margin, power compensation, сглаживание,
stale timeout и нелинейная частота щелчков.

## Что проверить в следующей сессии

1. Не менять пороги `-48/-55 dBm`, коэффициент `0.4` и тайминги без новой
   аппаратной серии измерений.
2. При проблемах запуска из фона сначала смотреть `adb logcat` по тегам
   `RfidForegroundService`, `HoneywellRfidGateway`, `GeigerClick`; особое
   внимание уделять `lifecycleResumed` и `importance`.
3. Проверить разрешение Android «Полноэкранные уведомления» на Samsung, если
   trampoline не появляется на lock screen.
4. Проверить, что reader действительно готов (`readerReady=true`), а не только
   Bluetooth-состояние `CONNECTED`.
5. Не добавлять в коммит посторонний пользовательский файл
   `scripts/generate_reader_trigger_gif.py`, если он не относится к задаче.
