# Using RFIDHunter

[Русская версия](USAGE.RU.md) · [Overview](../README.md) · [Build and mock walkthrough](DEVELOPMENT.md)

## Add a tagged item

1. Attach an RFID tag to the item, point the IH25 at it and briefly press the trigger.
2. A clear nearby unknown tag starts registration. Say the item's name in Russian, then confirm it.
3. The camera opens with the name. Either volume key takes a photo; `ПЕРЕДЕЛАТЬ` retakes it and `ГОТОВО` accepts it.
4. The app saves the name, photo and EPC locally and briefly shows success.

If the tag is already in the catalog, the app opens its item card. If several tags have similar strength, move the intended item away from the others. A missing or inconclusive tag starts voice search. The explicit command `добавь очки` can start with a name instead; after accepting the photo, the app waits for an unused tag.

Camera permission can be granted on the capture screen without reopening it. Feedback confirms an accepted capture request; fast repeated presses do not create extra photos. Leaving the screen cancels the active request, and a late result cannot open photo review in another workflow.

## Find an item

When the contextual check finds no clear nearby tag, the app asks `Скажите, что найти?` and opens the microphone. A bare name (`очки`, `ключи`, `пульт от телевизора`) or a command such as `найди очки` selects the item. Fuzzy matches ask for confirmation. Missing or ambiguous matches request another phrase.

A disconnected reader opens the preparation screen. Discovery, connection and preparation repeat automatically while this workflow is active; readiness opens the target without starting a scan. If the blue reader light is already blinking, keep the reader near the phone. Otherwise hold the reader's power button until its tone.

Hold the physical trigger and move the reader. The display combines an item photo, a signal meter, a percentage and secondary raw RSSI. Faster clicks help follow a strengthening signal; the percentage is a signal indication, not a distance. Releasing the trigger pauses search. In pause, a short press performs another contextual check; holding for one second resumes the selected search during the same press. `Завершить поиск` ends it and returns home.

## Catalog and item management

The catalog is a continuous list of large photo/name cards. Volume Up moves upward, Volume Down downward. Tapping a card opens its item card; dragging scrolls the list. The item card provides search, tag replacement, rename, photo replacement and delete actions.

Tag replacement retains the old association until a suitable unused tag has been read. A tag already assigned to another item shows that item's name. Delete requires confirmation. A trigger press on an item card always performs a fresh contextual check, so a different nearby tag can open its own card or begin registration.

## Buttons and feedback

| Control | Action |
| --- | --- |
| Short trigger press | Contextual nearby-tag check |
| Held trigger on a search target | Search while held |
| Either volume key in camera | Take photo |
| Volume keys in catalog | Navigate up/down |
| Large screen buttons | Confirm, edit or finish the current step |

Screen buttons tolerate small finger movements. Catalog cards have a separate gesture handler so scrolling does not select an item. After a logical screen transition, a brief touch gate limits accidental activation; trigger and volume keys remain available. [Timing and input policy](../ARCHITECTURE.md#camera-touch-and-feedback)

The app sets phone volume to maximum and uses prerecorded prompts when supplied. Missing prompt assets leave visible text. Voice recognition runs locally with a packaged Russian Vosk model. The service screen also supports text commands. [Models and diagnostics](DEVELOPMENT.md)

## Background operation and backup

In normal mode, leaving the app or turning the screen off stops inventory and disconnects the reader; returning starts preparation again. Permission dialogs preserve the connection. Kiosk mode keeps the foreground reader service active and handles returning to the app from the trigger; it is configured on the service screen. Vendor battery settings can affect background operation; Huawei EMUI may need autostart and battery-optimization settings adjusted.

On Android 12+ the reader uses nearby-device Bluetooth permission; Android 9–11 also needs location permission and enabled system location for BLE scanning. The hardware wake path has an optional overlay route on Android 16+, with a notification fallback. These paths require device testing.

Backup is optional. From the service screen, configure the server endpoint and register a password-protected account. The catalog continues to work offline, with pending changes retained locally. On a replacement phone, use the same password to restore the account and catalog; previous device keys are revoked. [Server setup](../server/README.md) · [Restore and synchronization rules](../BACKUP_SYNC.md)
