@file:OptIn(ExperimentalMaterial3Api::class)

package com.eazyradius.crm.ez

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/* ---------------- loading state ---------------- */

sealed class EzState<out T> {
    object Loading : EzState<Nothing>()
    class Ok<T>(val data: T) : EzState<T>()
    class Err(val msg: String) : EzState<Nothing>()
}

/** Loads data, shows a spinner / error+retry, then renders content. Reloads when [key] changes. */
@Composable
fun <T> EzLoad(
    key: Any? = null,
    load: suspend () -> T,
    content: @Composable (data: T, reload: () -> Unit) -> Unit
) {
    var tick by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<EzState<T>>(EzState.Loading) }
    LaunchedEffect(key, tick) {
        state = EzState.Loading
        state = try {
            EzState.Ok(load())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EzState.Err(e.message ?: "Something went wrong")
        }
    }
    when (val s = state) {
        is EzState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is EzState.Err -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(s.msg, color = MaterialTheme.colorScheme.error)
            Button(onClick = { tick++ }, modifier = Modifier.padding(top = 12.dp)) { Text("Try again") }
        }
        is EzState.Ok -> content(s.data) { tick++ }
    }
}

/* ---------------- actions ---------------- */

class EzRunner(private val scope: CoroutineScope, private val ctx: Context) {
    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    fun run(okMsg: String? = null, onDone: () -> Unit = {}, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                if (okMsg != null) toast(okMsg)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: "Failed")
            }
        }
    }
}

@Composable
fun rememberRunner(): EzRunner {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    return remember { EzRunner(scope, ctx) }
}

/* ---------------- building blocks ---------------- */

@Composable
fun EzCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val mod = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = mod) { Column(Modifier.padding(12.dp), content = content) }
    } else {
        Card(modifier = mod) { Column(Modifier.padding(12.dp), content = content) }
    }
}

@Composable
fun EzRow(title: String, subtitle: String = "", trailing: String = "", onClick: (() -> Unit)? = null) {
    EzCard(onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (trailing.isNotBlank()) {
                Text(trailing, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
fun EzHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun EzEmpty(text: String = "Nothing here yet") {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
}

@Composable
fun EzChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
fun EzSearch(hint: String, onSearch: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    OutlinedTextField(
        value = q,
        onValueChange = { q = it },
        label = { Text(hint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(q.trim()) }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

/* ---------------- dialogs ---------------- */

@Composable
fun EzPick(title: String, options: List<Pair<String, String>>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(options) { (v, l) ->
                    TextButton(onClick = { onPick(v) }, modifier = Modifier.fillMaxWidth()) { Text(l) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

data class EzField(
    val key: String,
    val label: String,
    val multiline: Boolean = false,
    val number: Boolean = false,
    val initial: String = ""
)

@Composable
fun EzFormDialog(
    title: String,
    fields: List<EzField>,
    submitLabel: String = "Save",
    onSubmit: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    val values = remember { mutableStateMapOf<String, String>().apply { fields.forEach { put(it.key, it.initial) } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEach { f ->
                    OutlinedTextField(
                        value = values[f.key] ?: "",
                        onValueChange = { values[f.key] = it },
                        label = { Text(f.label) },
                        singleLine = !f.multiline,
                        minLines = if (f.multiline) 3 else 1,
                        keyboardOptions = KeyboardOptions(keyboardType = if (f.number) KeyboardType.Number else KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(values.toMap()) }) { Text(submitLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Read-only details with optional action buttons. */
@Composable
fun EzDetail(
    title: String,
    lines: List<Pair<String, String>>,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    onDismiss: () -> Unit,
    extra: @Composable () -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.filter { it.second.isNotBlank() }.forEach { (k, v) ->
                    Column {
                        Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v)
                    }
                }
                extra()
                actions.forEach { (label, fn) ->
                    OutlinedButton(onClick = fn, modifier = Modifier.fillMaxWidth()) { Text(label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** A dialog whose content is fetched first (e.g. a ticket's full log). */
@Composable
fun EzDialogLoad(
    title: String,
    key: Any?,
    load: suspend () -> JSONObject,
    onDismiss: () -> Unit,
    body: @Composable (JSONObject, () -> Unit) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(Modifier.fillMaxWidth()) {
                EzLoad(key = key, load = load) { d, reload ->
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        body(d, reload)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun EzKV(label: String, value: String) {
    if (value.isBlank()) return
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

/** Quantity entry for a list of store items. Submits only rows with qty > 0. */
@Composable
fun EzItemsDialog(
    title: String,
    items: List<JSONObject>,
    hint: (JSONObject) -> String,
    submitLabel: String = "Submit",
    onSubmit: (List<Map<String, Any>>, String) -> Unit,
    onDismiss: () -> Unit
) {
    val qty = remember { mutableStateMapOf<Int, String>() }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) Text("No items available")
                items.forEach { it0 ->
                    val id = it0.optInt("id")
                    OutlinedTextField(
                        value = qty[id] ?: "",
                        onValueChange = { qty[id] = it },
                        label = { Text(it0.str("name") + " (" + hint(it0) + ")") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val out = items.mapNotNull { it0 ->
                    val id = it0.optInt("id")
                    val q = qty[id]?.toDoubleOrNull() ?: 0.0
                    if (q > 0) mapOf<String, Any>("item_id" to id, "qty" to q) else null
                }
                onSubmit(out, note.trim())
            }) { Text(submitLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
