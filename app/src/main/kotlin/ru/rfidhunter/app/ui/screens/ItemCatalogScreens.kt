package ru.rfidhunter.app.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.rfidhunter.app.R
import ru.rfidhunter.app.domain.Item
import ru.rfidhunter.app.ui.AppState
import ru.rfidhunter.app.ui.MainViewModel
import ru.rfidhunter.app.ui.components.Button
import ru.rfidhunter.app.ui.components.LocalScreenTouchEnabled
import ru.rfidhunter.app.ui.components.OutlinedButton
import ru.rfidhunter.app.ui.components.PhotoPlaceholder
import ru.rfidhunter.app.ui.components.PillRole
import ru.rfidhunter.app.ui.components.ThingName
import ru.rfidhunter.app.ui.components.catalogCardTapInput
import ru.rfidhunter.app.ui.theme.AppStyles
import ru.rfidhunter.app.ui.theme.Dimens
import ru.rfidhunter.app.ui.theme.LocalExtendedColors

@Composable
internal fun ItemCardScreen(item: Item, viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ItemDetails(item)
        Text(stringResource(R.string.item_card_actions_hint), fontSize = 23.sp, textAlign = TextAlign.Center)
        Button(
            { viewModel.startSearching(item) },
            Modifier.fillMaxWidth().height(Dimens.PillButtonHeightCompact),
            role = PillRole.Success,
            icon = Icons.Filled.Search,
        ) { Text(stringResource(R.string.item_card_search_action), fontSize = 19.sp) }
        OutlinedButton({ viewModel.changeTag(item) }, Modifier.fillMaxWidth().height(Dimens.PillButtonHeightCompact)) { Text(stringResource(R.string.item_card_change_tag_action), style = AppStyles.ButtonLabelSmall) }
        OutlinedButton({ viewModel.rename(item) }, Modifier.fillMaxWidth().height(Dimens.PillButtonHeightCompact), icon = Icons.Filled.Edit) { Text(stringResource(R.string.item_card_rename_action), style = AppStyles.ButtonLabelSmall) }
        OutlinedButton({ viewModel.retakePhoto(item) }, Modifier.fillMaxWidth().height(Dimens.PillButtonHeightCompact), icon = Icons.Filled.Refresh) { Text(stringResource(R.string.item_card_retake_photo_action), style = AppStyles.ButtonLabelSmall) }
        Button(
            { viewModel.delete(item) },
            Modifier.fillMaxWidth().height(Dimens.PillButtonHeightCompact),
            role = PillRole.Danger,
            icon = Icons.Filled.Delete,
        ) { Text(stringResource(R.string.item_card_delete_action), style = AppStyles.ButtonLabelSmall) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ItemDetails(item: Item) {
    ThingName(item.name, fontSize = 30.sp)
    val bitmap = remember(item.photoPath) { item.photoPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    Box(
        Modifier.width(180.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        Alignment.Center,
    ) {
        if (bitmap != null) Image(bitmap, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else PhotoPlaceholder(Modifier.fillMaxSize(), cornerRadius = 14.dp)
    }
}

@Composable
internal fun CatalogScreen(state: AppState.Catalog, items: List<Item>, viewModel: MainViewModel) {
    val listState = rememberLazyListState()
    val screenTouchEnabled = LocalScreenTouchEnabled.current
    val extendedColors = LocalExtendedColors.current
    val scrollbarColor = MaterialTheme.colorScheme.outline
    LaunchedEffect(state.selectedIndex, items.size) { if (items.isNotEmpty()) listState.animateScrollToItem(state.selectedIndex.coerceIn(0, items.lastIndex)) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text(stringResource(R.string.catalog_title), modifier = Modifier.fillMaxWidth(), style = AppStyles.Headline, textAlign = TextAlign.Center)
            Text(
                buildAnnotatedString {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                    append("Громкость+")
                    pop()
                    append(" — вверх    ")
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                    append("Громкость−")
                    pop()
                    append(" — вниз")
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                style = AppStyles.Hint,
                textAlign = TextAlign.Center,
            )
            Text(stringResource(R.string.catalog_tap_item_hint), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), style = AppStyles.Hint, textAlign = TextAlign.Center)
            Box(Modifier.weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        Card(
                            Modifier.fillMaxWidth().height(150.dp).catalogCardTapInput(
                                onClick = { viewModel.selectCatalogItem(item) },
                                screenTouchEnabled = screenTouchEnabled,
                            ),
                            colors = CardDefaults.cardColors(containerColor = if (index == state.selectedIndex) MaterialTheme.colorScheme.primaryContainer else extendedColors.cardSurface),
                        ) {
                            ItemPreview(item, false, Modifier.padding(10.dp))
                        }
                    }
                }
                if (items.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PhotoPlaceholder(Modifier.size(120.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.catalog_empty_title), style = AppStyles.Headline, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (items.size > 1) Canvas(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(8.dp).padding(vertical = 6.dp)) {
                    val track = size.height
                    val thumb = (track / items.size).coerceAtLeast(28f)
                    val offset = if (items.size <= 1) 0f else (track - thumb) * state.selectedIndex / (items.size - 1).toFloat()
                    drawRoundRect(scrollbarColor, androidx.compose.ui.geometry.Offset(0f, offset), androidx.compose.ui.geometry.Size(size.width, thumb), androidx.compose.ui.geometry.CornerRadius(size.width, size.width))
                }
            }
            Text(stringResource(R.string.catalog_position_format, if (items.isEmpty()) 0 else state.selectedIndex + 1, items.size), modifier = Modifier.fillMaxWidth().padding(top = 8.dp), fontSize = 19.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ItemPreview(item: Item, large: Boolean, modifier: Modifier = Modifier) {
    val bitmap = remember(item.photoPath) { item.photoPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier
                .width(if (large) 180.dp else 102.dp)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            Alignment.Center,
        ) { if (bitmap != null) Image(bitmap, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else PhotoPlaceholder(Modifier.fillMaxSize(), cornerRadius = 12.dp) }
        ThingName(item.name, modifier = Modifier.weight(1f), fontSize = if (large) 30.sp else 24.sp, textAlign = TextAlign.Start)
    }
}
