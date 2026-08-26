package ru.rfidhunter.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.rfidhunter.app.RfidHunterApplication
import ru.rfidhunter.app.domain.ConnectionState
import ru.rfidhunter.app.domain.FakeScenario
import ru.rfidhunter.app.domain.ReaderScanState
import ru.rfidhunter.app.ui.MainViewModel
import ru.rfidhunter.app.ui.components.Button
import ru.rfidhunter.app.ui.components.OutlinedButton
import ru.rfidhunter.app.ui.components.TextButton
import ru.rfidhunter.app.ui.theme.LocalExtendedColors
import ru.rfidhunter.app.voice.VoiceBackend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ServiceScreen(
    viewModel: MainViewModel,
    connection: ConnectionState,
    scanState: ReaderScanState,
    devices: List<ru.rfidhunter.app.domain.RfidDevice>,
    diagnostic: String?,
    readerReady: Boolean,
    recentReads: List<ru.rfidhunter.app.domain.TagRead>,
    pendingSyncCount: Int,
    syncSettings: ru.rfidhunter.app.data.SyncSettingsState,
) {
    val fake = viewModel.fakeGateway
    val extendedColors = LocalExtendedColors.current
    var voiceText by remember { mutableStateOf("") }
    var readerAddress by remember { mutableStateOf(viewModel.readerAddress()) }
    var diagnosticsEnabled by remember { mutableStateOf(viewModel.diagnosticsEnabled()) }
    var recordings by remember { mutableStateOf(viewModel.diagnosticRecordings()) }
    var backupEndpoint by remember(syncSettings.endpoint) { mutableStateOf(syncSettings.endpoint) }
    var accountPassword by remember { mutableStateOf("") }
    var accountPasswordRepeat by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("Samsung RFIDHunter") }
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("SERVICE / DEBUG", fontSize = 20.sp, fontWeight = FontWeight.Bold); TextButton(viewModel::closeService) { Text("Закрыть") } }
        Text("Backend: ${if (fake != null) "FAKE" else "HONEYWELL IH25"}")
        Text("Состояние: ${when {
            scanState == ReaderScanState.SCANNING -> "сканирую"
            connection == ConnectionState.CONNECTING -> "подключаюсь"
            connection == ConnectionState.ERROR -> "ошибка"
            readerReady -> "готов"
            connection == ConnectionState.CONNECTED -> "создаю reader"
            else -> "отключён"
        }}")
        diagnostic?.takeIf { it.isNotBlank() }?.let { Text("Диагностика: $it") }
        Text("Резервное копирование каталога", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("В очереди на отправку: $pendingSyncCount")
        syncSettings.lastSuccessAt?.let { timestamp ->
            Text("Последняя успешная отправка: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))}")
        }
        syncSettings.lastError?.let { Text("Ошибка синхронизации: $it", color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(
            value = backupEndpoint,
            onValueChange = { backupEndpoint = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Адрес сервера (например, https://server)") },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MaterialOutlinedButton({ viewModel.setBackupEndpoint(backupEndpoint) }, modifier = Modifier.weight(1f)) { Text("СОХРАНИТЬ") }
            MaterialOutlinedButton({ viewModel.syncNow() }, modifier = Modifier.weight(1f)) { Text("СИНХРОНИЗИРОВАТЬ") }
        }
        Text("Отправляются только новые и изменённые записи; фото передаются отдельно по SHA-256.", fontSize = 14.sp)
        Text("Device ID: ${viewModel.getApplication<RfidHunterApplication>().syncSettings.deviceId}", fontSize = 13.sp)
        syncSettings.accountId?.let { Text("Каталог: $it", fontSize = 13.sp) }
        Text(
            if (syncSettings.registered) "Устройство зарегистрировано (${syncSettings.scopes.sorted().joinToString(", ")})"
            else "Устройство не зарегистрировано",
            color = if (syncSettings.registered) extendedColors.success else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Резервирование необязательно: без учетной записи каталог хранится только на телефоне. Для нового телефона используйте тот же пароль.",
            fontSize = 14.sp,
        )
        Text("Пароль не отправляется в резервной копии и не восстанавливается сервером — сохраните его отдельно.", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
        OutlinedTextField(
            value = accountPassword,
            onValueChange = { accountPassword = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text("Пароль резервной учетной записи") },
        )
        OutlinedTextField(
            value = accountPasswordRepeat,
            onValueChange = { accountPasswordRepeat = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text("Повторите пароль для создания") },
        )
        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Название устройства") },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MaterialOutlinedButton(
                { viewModel.createBackupAccount(accountPassword, deviceName) },
                modifier = Modifier.weight(1f),
                enabled = accountPassword.length >= 8 && accountPassword == accountPasswordRepeat && !syncSettings.registered,
            ) { Text("СОЗДАТЬ") }
            MaterialOutlinedButton(
                { viewModel.recoverBackupAccount(accountPassword, deviceName) },
                modifier = Modifier.weight(1f),
                enabled = accountPassword.length >= 8,
            ) { Text("ВОССТАНОВИТЬ") }
        }
        /*
         * The password flow above is the only account setup path; no pairing
         * code or administrator action is required.
         */
        /*
        MaterialOutlinedButton(
            { viewModel.enrollBackupDevice(pairingCode, deviceName) },
            modifier = Modifier.fillMaxWidth(),
            enabled = pairingCode.isNotBlank() && !syncSettings.registered,
        ) { Text("ЗАРЕГИСТРИРОВАТЬ УСТРОЙСТВО") }
        */
        MaterialOutlinedButton(
            viewModel::restoreBackup,
            modifier = Modifier.fillMaxWidth(),
            enabled = syncSettings.registered && syncSettings.scopes.contains("RESTORE"),
        ) { Text("ВОССТАНОВИТЬ МОЙ КАТАЛОГ") }
        Button(viewModel::scanReaders, Modifier.fillMaxWidth(), enabled = scanState != ReaderScanState.SCANNING) {
            if (scanState == ReaderScanState.SCANNING) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Сканирую IH25…")
            } else Text("Сканировать IH25")
        }
        if (devices.isNotEmpty()) {
            Text("Найдено устройств: ${devices.size}", fontWeight = FontWeight.Bold)
            devices.forEach { device ->
                val selected = viewModel.readerAddress().equals(device.macAddress, true)
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (selected) extendedColors.successContainer else extendedColors.cardSurface)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(device.name + if (device.likelyHoneywell) " (IH25?)" else "", fontWeight = FontWeight.Bold)
                        Text("${device.macAddress}   RSSI ${device.rssi} dBm")
                        Button({ viewModel.selectReader(device); readerAddress = device.macAddress }, enabled = !selected) { Text(if (selected) "Выбрано" else "Выбрать") }
                    }
                }
            }
        }
        Text("Движок: ${viewModel.currentVoiceBackend()}")
        Text("Whisper model: ${viewModel.currentWhisperVariant()}")
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Сохранять записи для диагностики")
            androidx.compose.material3.Switch(
                checked = diagnosticsEnabled,
                onCheckedChange = { diagnosticsEnabled = it; viewModel.setDiagnosticsEnabled(it) },
            )
        }
        Button({ recordings = viewModel.diagnosticRecordings() }, Modifier.fillMaxWidth()) { Text("ОБНОВИТЬ СПИСОК ЗАПИСЕЙ") }
        recordings.take(8).forEach { file ->
            key(file.absolutePath) {
                OutlinedButton({ viewModel.transcribeDiagnostic(file) }, Modifier.fillMaxWidth()) {
                    Text("ПОВТОРИТЬ: ${file.name}")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton({ viewModel.setVoiceBackend(VoiceBackend.WHISPER) }) { Text("Whisper q5_1") }
            OutlinedButton({ viewModel.setVoiceBackend(VoiceBackend.VOSK) }) { Text("Vosk") }
        }
        OutlinedTextField(readerAddress, { readerAddress = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Bluetooth MAC считывателя") })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton({ viewModel.setReaderAddress(readerAddress); viewModel.simulateConnect() }) { Text("Подключить") }
            OutlinedButton(viewModel::simulateDisconnect) { Text("Отключить") }
            if (fake != null) OutlinedButton(viewModel::simulateTriggerPress) { Text("trigger") }
        }
        Button(
            onClick = { viewModel.startDiagnosticInventory() },
            enabled = fake != null || readerReady,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Проверить метки (5 секунд)") }
        if (recentReads.isNotEmpty()) {
            Text("Последние EPC: ${recentReads.size}", fontWeight = FontWeight.Bold)
            recentReads.takeLast(8).forEach { read -> Text("${read.epc}   RSSI ${read.rssi}") }
        }
        Text("Сценарий первичного чтения:")
        FakeScenario.values().forEach { scenario -> OutlinedButton({ viewModel.setFakeScenario(scenario) }, Modifier.fillMaxWidth()) { Text(scenario.name) } }
        Text("Тест голосовой команды", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(voiceText, { voiceText = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Например: найди очки") })
        Button({ viewModel.submitServiceVoice(voiceText); voiceText = "" }, enabled = voiceText.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("ОТПРАВИТЬ КОМАНДУ") }
    }
}
