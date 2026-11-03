@file:OptIn(ExperimentalMaterial3Api::class)

package com.eazyradius.crm.ez

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Entry point. In MainActivity:   setContent { EzApp() }
 * Everything the user sees is driven by the "menu" the server returns for their account.
 */
@Composable
fun EzApp() {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Color(0xFF5BD6A8))
        else lightColorScheme(primary = Color(0xFF0B6E4F))
    ) {
        Surface(Modifier.fillMaxSize()) { EzRoot() }
    }
}

@Composable
private fun EzRoot() {
    val ctx = LocalContext.current
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var booting by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        EzApi.load(ctx)
        EzApi.onUnauthorized = {
            EzApi.clearToken(ctx)
            profile = null
        }
        if (EzApi.token.isNotEmpty()) {
            try {
                profile = EzApi.call("me")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // stay on the login screen
            }
        }
        booting = false
    }

    val p = profile
    when {
        booting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        p == null -> EzLogin { profile = it }
        else -> EzShell(p) {
            EzApi.clearToken(ctx)
            profile = null
        }
    }
}

/* ---------------- login ---------------- */

@Composable
fun EzLogin(onLoggedIn: (JSONObject) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf(EzApi.baseUrl.removePrefix("https://").removePrefix("http://")) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Eazyradius CRM", style = MaterialTheme.typography.headlineMedium)
        Text("Sign in with your panel account", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = panel, onValueChange = { panel = it }, label = { Text("Panel address (billing.example.com)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
        )
        OutlinedTextField(
            value = user, onValueChange = { user = it }, label = { Text("Username") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        OutlinedTextField(
            value = pass, onValueChange = { pass = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        Button(
            enabled = !busy && panel.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
            onClick = {
                busy = true
                error = ""
                scope.launch {
                    try {
                        EzApi.baseUrl = EzApi.normalize(panel)
                        val r = EzApi.call(
                            "login",
                            mapOf("username" to user.trim(), "password" to pass, "device" to Build.MODEL),
                            anonymous = true
                        )
                        EzApi.token = r.getString("token")
                        EzApi.save(ctx)
                        onLoggedIn(r)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Could not sign in"
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) { Text(if (busy) "Signing in..." else "Sign in") }
    }
}

/* ---------------- shell: menu comes from the server ---------------- */

private val ORDER = listOf(
    "dashboard", "wallet", "leads", "tickets", "installations", "store", "customers", "payments",
    "plans", "history", "hr_self", "hr_manage", "hr_payroll", "staff", "app_access"
)

private val SHORT = mapOf(
    "dashboard" to "Home", "wallet" to "Wallet", "leads" to "Leads", "tickets" to "Tickets",
    "installations" to "Installs", "store" to "Store", "customers" to "Customers", "payments" to "Payments",
    "plans" to "Plans", "history" to "History", "hr_self" to "My HR", "hr_manage" to "HR team",
    "hr_payroll" to "Payroll", "staff" to "Staff", "app_access" to "App access"
)

@Composable
fun EzShell(profile: JSONObject, onLogout: () -> Unit) {
    val kind = profile.str("kind")
    val menu = remember(profile) {
        profile.arr("menu").sortedBy { val i = ORDER.indexOf(it.str("key")); if (i < 0) 99 else i }
    }
    val mods = remember(menu) { menu.associate { it.str("key") to it.str("level") } }
    val primary = menu.take(4)
    val more = menu.drop(4)
    var current by remember(menu) { mutableStateOf(menu.firstOrNull()?.str("key") ?: "more") }
    val primaryKeys = primary.map { it.str("key") }

    BackHandler(enabled = current != "more" && current !in primaryKeys) { current = "more" }

    fun labelOf(key: String): String =
        SHORT[key] ?: menu.firstOrNull { it.str("key") == key }?.str("label") ?: "More"

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (current == "more") "More" else labelOf(current)) }) },
        bottomBar = {
            NavigationBar {
                primary.forEach { m ->
                    val k = m.str("key")
                    NavigationBarItem(
                        selected = current == k,
                        onClick = { current = k },
                        icon = { Text(labelOf(k).take(1), style = MaterialTheme.typography.titleMedium) },
                        label = { Text(labelOf(k), fontSize = 11.sp, maxLines = 1) }
                    )
                }
                NavigationBarItem(
                    selected = current == "more" || (current !in primaryKeys),
                    onClick = { current = "more" },
                    icon = { Text("...", style = MaterialTheme.typography.titleMedium) },
                    label = { Text("More", fontSize = 11.sp) }
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (menu.isEmpty()) {
                EzEmpty("Your account has no app access yet. Ask an admin to enable modules for you.")
            }
            when (current) {
                "more" -> EzMore(profile, more, { k -> labelOf(k) }, { k -> current = k }, onLogout)
                "dashboard" -> EzDashboard()
                "wallet" -> EzClientWallet()
                "history" -> EzClientHistory()
                "plans" -> if (kind == "customer") EzClientPlans() else EzPlans()
                "customers" -> EzCustomers(mods)
                "payments" -> EzPayments()
                "leads" -> EzLeads(mods)
                "tickets" -> EzTickets(mods)
                "installations" -> EzInstalls(mods)
                "store" -> EzStore(mods)
                "staff" -> EzStaff()
                "hr_self", "hr_manage", "hr_payroll" -> EzHr(mods, current)
                "app_access" -> EzAccess()
            }
        }
    }
}

@Composable
private fun EzMore(
    profile: JSONObject,
    more: List<JSONObject>,
    labelOf: (String) -> String,
    open: (String) -> Unit,
    onLogout: () -> Unit
) {
    val u = profile.optJSONObject("user") ?: JSONObject()
    val roles = profile.arr("roles").joinToString(", ") { it.str("name") }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            EzCard {
                Text(u.str("fullname").ifBlank { u.str("username") }, style = MaterialTheme.typography.titleLarge)
                Text(
                    listOf(profile.str("role"), roles).filter { it.isNotBlank() }.joinToString(" - "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(profile.str("company"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (more.isNotEmpty()) item { EzHeader("More") }
        items(more) { m ->
            val k = m.str("key")
            EzRow(title = labelOf(k), subtitle = m.str("label"), onClick = { open(k) })
        }
        item {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            EzApi.call("logout")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // ignore: we sign out locally anyway
                        }
                        onLogout()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Text("Sign out") }
        }
    }
}
