package com.v2ray.ang.amelia

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.R
import kotlinx.coroutines.delay

/**
 * 1.8.0 "Поддержка": the thread as bubbles ("Вы" / "Поддержка" and the time), the text field,
 * "Отправить" and "Написать в Telegram". While the screen is visible the thread is asked for every
 * 30 s ([AmeliaSupport.SCREEN_POLL_MS]); in the background AmeliaSupport.Worker takes over.
 */
@Composable
internal fun SupportScreen(
    support: SupportUi,
    onBack: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onPoll: () -> Unit,
    onTelegram: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        // only while the app is on screen: a hidden screen does not ask
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                onPoll()
                delay(AmeliaSupport.SCREEN_POLL_MS)
            }
        }
    }
    val list = rememberLazyListState()
    LaunchedEffect(support.items.size) {
        if (support.items.isNotEmpty()) list.animateScrollToItem(support.items.size - 1)
    }
    val now = System.currentTimeMillis()
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
    ) {
        ScreenHeader(stringResource(R.string.amelia_support_title), onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                support.items.isEmpty() && support.loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Brand.Sky)
                support.items.isEmpty() -> Text(
                    stringResource(R.string.amelia_support_empty),
                    color = Brand.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                )
                else -> LazyColumn(
                    state = list,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // no item keys: the server's ids are not promised to be unique
                    items(support.items) { Bubble(it, now) }
                }
            }
        }
        if (support.stale && support.items.isNotEmpty()) {
            Text(
                stringResource(R.string.amelia_support_stale),
                color = Brand.Muted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = support.draft,
            onValueChange = onDraft,
            label = { Text(stringResource(R.string.amelia_support_field)) },
            enabled = !support.sending,
            minLines = 2,
            maxLines = 6,
            supportingText = if (support.draft.length > AmeliaSupport.MAX_TEXT - 200) {
                { Text(stringResource(R.string.amelia_support_counter, support.draft.length, AmeliaSupport.MAX_TEXT)) }
            } else {
                null
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Brand.Sky,
                unfocusedBorderColor = Brand.GlassBorder,
                focusedLabelColor = Brand.Sky,
                cursorColor = Brand.Sky,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.amelia_support_attach),
            color = Brand.Muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        support.error?.let {
            Text(stringResource(it), color = Brand.Amber, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        GradientButton(
            text = stringResource(R.string.amelia_support_send),
            enabled = !support.sending && support.draft.isNotBlank(),
            busy = support.sending,
            onClick = onSend,
        )
        TextButton(onClick = onTelegram, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.amelia_support_telegram), color = Brand.Muted)
        }
    }
}

/** Ours on the right ("Вы"), support's on the left ("Поддержка"); the text can be selected and copied. */
@Composable
private fun Bubble(item: AmeliaSupport.Item, now: Long) {
    val mine = !item.fromSupport
    val shape = RoundedCornerShape(
        topStart = 18.dp, topEnd = 18.dp,
        bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp,
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(if (mine) Color(0x5559ABFF) else Brand.Glass, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                stringResource(if (mine) R.string.amelia_support_you else R.string.amelia_support_them) +
                    " · " + AmeliaSupport.time(item.ts, now),
                color = Brand.Muted,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(2.dp))
            SelectionContainer {
                Text(item.x, color = Brand.Text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
