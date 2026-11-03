@file:OptIn(ExperimentalMaterial3Api::class)

package com.eazyradius.crm.ez

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * Admin screen: decide what each role, user type or individual person can see in the app.
 * Levels: none = hidden, mine = only their own/assigned items, view = see everything, manage = see and edit.
 */
@Composable
fun EzAccess() {
    var version by remember { mutableStateOf(0) }
    var scope by remember { mutableStateOf("role") }
    var ref by remember { mutableStateOf("") }
    EzLoad(key = version, load = { EzApi.call("access_get") }) { res, _ ->
        EzAccessEditor(
            res = res,
            scope = scope,
            ref = ref,
            onScope = { scope = it; ref = "" },
            onRef = { ref = it },
            onChanged = { version++ }
        )
    }
}

@Composable
private fun EzAccessEditor(
    res: JSONObject,
    scope: String,
    ref: String,
    onScope: (String) -> Unit,
    onRef: (String) -> Unit,
    onChanged: () -> Unit
) {
    val run = rememberRunner()
    val modules = res.arr("modules")
    val config = res.arr("config")
    val staff = res.arr("staff")
    val roles = res.arr("roles")
    val isUser = scope == "user"

    val configured = config.any { it.str("scope") == scope && it.str("ref") == ref }
    val levels = remember(scope, ref) {
        val m = mutableStateMapOf<String, String>()
        val c = config.firstOrNull { it.str("scope") == scope && it.str("ref") == ref }
        val mm = c?.optJSONObject("modules")
        if (mm != null) mm.keys().forEach { k -> m[k] = mm.optString(k) }
        m
    }
    val staffRow = if (isUser) staff.firstOrNull { it.str("id") == ref } else null
    val roleSel = remember(scope, ref) {
        val m = mutableStateMapOf<Int, Boolean>()
        staffRow?.arr("roles")?.forEach { m[it.optInt("id")] = true }
        m
    }
    var isTech by remember(scope, ref) { mutableStateOf(staffRow?.optBoolean("is_technician") ?: false) }

    val targets: List<Pair<String, String>> = when (scope) {
        "role" -> roles.map { it.str("id") to it.str("name") }
        "user" -> staff.map { it.str("id") to (it.str("fullname").ifBlank { it.str("username") } + " (" + it.str("user_type") + ")") }
        else -> res.strList("user_types").map { it to it }
    }
    val opts = if (isUser) {
        listOf("" to "Inherit", "none" to "None", "mine" to "Mine", "view" to "View", "manage" to "Manage")
    } else {
        listOf("none" to "None", "mine" to "Mine", "view" to "View", "manage" to "Manage")
    }
    val fallback = if (isUser) "" else "none"

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "Choose who to configure, then pick what the app shows them. " +
                    "Mine = only their own or assigned items, View = everything read-only, Manage = everything and can act on it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        item {
            EzChips(listOf("role" to "By role", "user" to "By person", "type" to "By user type"), scope) { onScope(it) }
        }

        if (ref.isBlank()) {
            if (targets.isEmpty()) item { EzEmpty("Nothing to configure") }
            items(targets) { (id, label) ->
                val has = config.any { it.str("scope") == scope && it.str("ref") == id }
                EzRow(title = label, subtitle = if (has) "Custom app access set" else "Using defaults", onClick = { onRef(id) })
            }
        } else {
            val label = targets.firstOrNull { it.first == ref }?.second ?: ref
            item {
                EzCard {
                    Text(label, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (configured) "Custom app access is set."
                        else if (isUser) "No personal overrides. Everything is inherited from their role and user type."
                        else "Not configured yet: this currently follows the panel's own permissions. Saving here replaces that.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { onRef("") }, modifier = Modifier.padding(top = 8.dp)) { Text("Choose someone else") }
                }
            }
            items(modules) { m ->
                val key = m.str("key")
                EzCard {
                    Text(m.str("label"), style = MaterialTheme.typography.titleSmall)
                    Text(m.str("group"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    EzChips(opts, levels[key] ?: fallback) { levels[key] = it }
                }
            }
            item {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val payload = modules.mapNotNull { m ->
                            val k = m.str("key")
                            val l = levels[k] ?: fallback
                            if (l.isBlank() || (!isUser && l == "none")) null else k to l
                        }.toMap()
                        run.run("Saved", onDone = onChanged) {
                            EzApi.call("access_set", mapOf("scope" to scope, "ref" to ref, "modules" to payload))
                        }
                    }) { Text("Save access") }
                    OutlinedButton(onClick = {
                        run.run("Back to defaults", onDone = onChanged) {
                            EzApi.call("access_reset", mapOf("scope" to scope, "ref" to ref))
                        }
                    }) { Text("Reset to defaults") }
                }
            }

            if (isUser) {
                item { EzHeader("Roles and technician status") }
                items(roles) { r ->
                    val rid = r.optInt("id")
                    EzCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.str("name"), Modifier.weight(1f))
                            Switch(checked = roleSel[rid] == true, onCheckedChange = { roleSel[rid] = it })
                        }
                    }
                }
                item {
                    EzCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Is a technician (can be assigned jobs)", Modifier.weight(1f))
                            Switch(checked = isTech, onCheckedChange = { isTech = it })
                        }
                    }
                }
                item {
                    Button(onClick = {
                        run.run("Roles saved", onDone = onChanged) {
                            EzApi.call(
                                "staff_roles_set",
                                mapOf(
                                    "user_id" to ref.toIntOrNull(),
                                    "role_ids" to roleSel.filter { it.value }.keys.toList(),
                                    "is_technician" to isTech
                                )
                            )
                        }
                    }, modifier = Modifier.padding(12.dp)) { Text("Save roles") }
                }
            }
        }
    }
}
