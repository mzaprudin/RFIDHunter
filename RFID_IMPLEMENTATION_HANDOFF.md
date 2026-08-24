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
считыватель или интерпретировать RSSI. После выдачи разрешений приложение само
сканирует BLE, выбирает однозначный IH25, сохраняет MAC и подключается. На
главном экране при готовом reader показывается только понятная инструкция:
«Нажмите курок чтобы добавить метку или начать поиск»; технические статусы
остаются в сервисном режиме.

Главная цепочка по короткому нажатию курка:

1. Быстрая проверка выполняется на минимальной мощности (5 dBm) примерно 850 ms.
2. `RfidDecisionEngine` группирует EPC, берёт лучший RSSI каждой метки и
   выбирает только явно близкую метку (RSSI не слабее `-48 dBm` и преимущество
   над второй меткой не менее 8 dB).
3. Известный EPC открывает карточку вещи; неизвестный запускает добавление
   вещи голосом; отсутствие метки, слабый сигнал или неоднозначность запускают
   голосовой поиск вещи.

На открытой `ItemCard` новое нажатие курка снова запускает эту цепочку с
минимальной мощностью; сообщение «Выберите действие на карточке» для курка не
используется. Это позволяет считать рядом другую известную или новую метку без
предварительного возврата в `Idle`.

См. [MainViewModel.kt, обработчики курка и initial check](app/src/main/kotlin/ru/rfidhunter/app/ui/MainViewModel.kt#L316-L448),
[RfidDecisionEngine.kt](app/src/main/kotlin/ru/rfidhunter/app/domain/RfidDecisionEngine.kt)
и [SearchInventoryPolicy](app/src/main/kotlin/ru/rfidhunter/app/domain/SearchPowerPolicy.kt).

Основной офлайн-движок голоса — Vosk: его модель устанавливается и
прогревается при старте. Whisper доступен только как ручной диагностический
выбор в сервисном экране. На голосовом экране статусы «Слушаю» и «Распознаю
речь» показываются около центральной анимации и не дублируются верхним banner.

Повторное считывание новой метки хранит структурированную причину ожидания.
Занятый EPC показывает связанную вещь, несколько меток дают инструкцию отойти
вместе с вещью, а успешное добавление на 3 секунды показывает отдельный экран
«Вещь добавлена» перед возвратом на главный экран.

Главный экран закреплён в три зоны в
[IdleScreen](app/src/main/kotlin/ru/rfidhunter/app/ui/RfidHunterApp.kt#L336-L415):
заголовок сверху, единый центральный слот статуса/инструкции и группа кнопок
снизу. Верхний технический banner в `Idle` скрыт, поэтому статус подключения
не дублируется и не сдвигает текст. Под центральным текстом зарезервировано
место под GIF-анимацию курка; renderer без внешней библиотеки находится в
[TriggerGif.kt](app/src/main/kotlin/ru/rfidhunter/app/ui/TriggerGif.kt), а файл
лежит в `app/src/main/assets/rfid-reader-trigger.gif` (исходник —
`artifacts/rfid-reader-trigger.gif`). Генератор использует фон приложения
`#F7F7F2`, 32 кадра 480×320 по 50 ms. Кнопки главного экрана имеют высоту 88 dp
и расстояние 10 dp — они остаются крупными, но не разнесены чрезмерно.

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
  Activity. Один cancellable workflow делает стартовую попытку: BLE-сканирование
  длится 5 секунд; сохранённый MAC имеет приоритет, иначе автоматически
  выбирается ровно один Honeywell/IH25 (или единственное найденное устройство).
  Тот же workflow используется экраном `ReaderPreparation`: при ручном выборе
  вещи без `readerReady` он повторяет поиск/подключение через 5 секунд, пока
  пользователь не выйдет назад. После неожиданного разрыва уже готового IH25
  workflow также продолжает retry из `Idle`, пока `readerReady` снова не станет
  `true`; явное отключение в сервисе это восстановление подавляет. Параллельный
  автоматический scan/connect не создаётся. Холодный `createReader()` может
  занимать до 30 секунд.
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
- Непрерывный поиск запускает один inventory на фиксированных `30 dBm` и не
  вызывает `stopRead/read` для адаптации мощности. `stopInventory()` остаётся
  только для отпускания курка, завершения поиска и смены сценария.

## Непрерывный поиск и единая шкала сигнала

Поиск вещи из каталога всегда работает на `30 dBm`
([MainViewModel.kt](app/src/main/kotlin/ru/rfidhunter/app/ui/MainViewModel.kt)).
Мощность не меняется во время удержания курка; UI обновляется примерно каждые
50 ms. Быстрая первоначальная проверка метки по-прежнему отдельно использует
`5 dBm` примерно 850 ms.

[SearchSignalTracker.kt](app/src/main/kotlin/ru/rfidhunter/app/domain/SearchSignalTracker.kt)
сглаживает только исходный RSSI целевого EPC (EMA с `smoothingFactor=0.24`).
Сигнал считается устаревшим через 900 ms без наблюдений. Компенсации RSSI между
ступенями мощности и таймера адаптации больше нет.

Сама политика отображения — [SearchSignalPolicy.kt](app/src/main/kotlin/ru/rfidhunter/app/domain/SearchSignalPolicy.kt):
диапазон `-82…-42 dBm`. `normalizedStrength()` даёт точную позицию `0…1` для
шкалы и процента; `strength()` сохраняет минимальное audible-отклонение `0.08`
для нелинейной кривой щелчков `pow(0.55)`.

## Стабильный экран поиска и «дозиметрический» звук телефона

- Экран поиска рисует аналоговую шкалу с цветными зонами, стрелкой и отметками
  `0`, `50`, `100`. Фото (`140 dp`), ограниченная шкала (`148 dp`) и показания
  образуют одну группу с фиксированными отступами `12 dp`; это не позволяет
  пустому полю Canvas оттолкнуть строку силы сигнала вниз. После этой группы
  остаётся гибкий слот, затем `92 dp` инструкции, зазор `16 dp` и резервированный
  `74 dp`-слот кнопки. Готовность, активный поиск и пауза ничего не сдвигают.
- Главный показатель — цветной процент `0–100%` справа в фиксированной колонке
  `128 dp`; стрелка и процент получают одно анимированное нормализованное
  значение. Слева остаётся подпись «Сила сигнала», а исходный RSSI — вторичная
  техническая строка, выровненная вправо. Для отсутствующего или stale-сигнала
  выводятся `0%` и `— dBm`.
- Инструкция использует `21 sp` с межстрочным интервалом `27 sp`, чтобы текст
  был читаемее и не обрезался в резервированном слоте.
- Включение IH25 объясняется только во время BLE-сканирования на главном экране
  и на отдельном `ReaderPreparation`: сначала нужно проверить, мигает ли слева
  синяя лампочка; если она не мигает, удерживать кнопку питания справа до
  сигнала, затем держать считыватель рядом с телефоном. На готовом `SearchTarget` остаётся только
  инструкция удерживать курок. При неожиданном `readerReady=false` поисковые
  экраны явно показывают «Считыватель отключён», а inventory не запускается.
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
- при выключенном или заблокированном экране сохраняется notification-ветка с
  `TriggerWakeActivity`, маленьким trampoline с `showWhenLocked/turnScreenOn`,
  который переводит задачу в `MainActivity`;
- на Android 16+ при скрытом разблокированном приложении нужен `SYSTEM_ALERT_WINDOW`:
  `MainActivity` показывает на главном экране кнопку `РАЗРЕШИТЬ ЗАПУСК ПО КУРКУ`
  и обновляет её в `onResume`. После разрешения service прикрепляет короткую
  нетактильную `TYPE_APPLICATION_OVERLAY`-плашку «Открываю Искатель вещей…»,
  затем запускает `TriggerWakeActivity`. Это даёт допустимый видимый контекст
  вместо блокируемого Android 16 фонового старта (`BAL_BLOCK`);
- overlay удаляется после запуска, при старте trampoline и при остановке service;
  повторный курок при активной плашке не создаёт второе окно. При отсутствии
  разрешения или ошибке `WindowManager` service пишет диагностический лог и
  использует полноэкранное уведомление как fallback;
- на Android 15 и ниже остаётся прежний прямой `PendingIntent`-запуск.

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
[RfidHunterApp.kt](app/src/main/kotlin/ru/rfidhunter/app/ui/RfidHunterApp.kt)
используют один `resilientButtonInput`: первоначальный палец определяет кнопку,
небольшое движение не отменяет действие, второй палец блокирует активацию, а
повторное срабатывание подавляется debounce 500 ms. Поверх этого действует
общий touch-gate: после любого изменения логического экрана экранные кнопки,
карточки каталога и accessibility-click незаметно игнорируются 1000 ms. Ключ
экрана не включает RSSI и выбранную позицию каталога, поэтому частые обновления
данных не продлевают блокировку. Аппаратный курок и `Громкость+/-` идут напрямую
во ViewModel и gate не проверяют. Все пользовательские кнопки имеют форму пилюли; на
подтверждении названия и фото используются оранжевая кнопка изменения и зелёная
кнопка готовности. `UserScreen` задаёт единые безопасные отступы от системных
панелей, а `ThingName` показывает «Вещь:» и жирное синее название. Исключение —
поиск, где в отдельном фиксированном слоте выводится только название. Карточка
вещи сохраняет фото 210 dp и интервалы 10 dp, использует пять кнопок по 68 dp,
нижний запас 12 dp и вертикальную прокрутку.

Подсказка камеры использует русские названия `Громкость+` и `Громкость−`,
выделенные жирным светло-синим на тёмной подложке. В каталоге эти названия
выделены фирменным синим в строке навигации, а «Нажмите на вещь» вынесено на
отдельную строку.

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
остаются только при реальной потере метки во время поиска на 30 dBm. AAR подключён
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
