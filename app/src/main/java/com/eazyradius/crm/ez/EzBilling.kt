@file:OptIn(ExperimentalMaterial3Api::class)

package com.eazyradius.crm.ez

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/* ======================= CUSTOMERS ======================= */

@Composable
fun EzCustomers(mods: Map<String, String>) {
    var q by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        EzSearch("Search name, phone, username") { q = it }
        EzChips(
            listOf("" to "All", "Active" to "Active", "Limited" to "Limited", "Suspended" to "Suspended", "Disabled" to "Disabled"),
            status
        ) { status = it }
        EzLoad(key = q to status, load = { EzApi.call("customers", mapOf("q" to q, "status" to status)) }) { res, _ ->
            val list = res.arr("customers")
            LazyColumn(Modifier.fillMaxSize()) {
                if (list.isEmpty()) item { EzEmpty("No customers found") }
                items(list) { c ->
                    EzRow(
                        title = c.str("fullname").ifBlank { c.str("username") },
                        subtitle = listOf(c.str("username"), c.str("phonenumber"), c.str("plan")).filter { it.isNotBlank() }.joinToString(" - "),
                        trailing = c.str("status") + (if (c.str("expires").isNotBlank()) "\nto " + c.str("expires") else ""),
                        onClick = { openId = c.optInt("id") }
                    )
                }
            }
        }
    }

    if (openId != 0) {
        EzDialogLoad(
            "Customer", key = openId,
            load = { EzApi.call("customer", mapOf("id" to openId)).getJSONObject("customer") },
            onDismiss = { openId = 0 }
        ) { c, _ ->
            EzKV("Name", c.str("fullname"))
            EzKV("Username", c.str("username"))
            EzKV("Phone", c.str("phonenumber"))
            EzKV("Email", c.str("email"))
            EzKV("Address", c.str("address"))
            EzKV("Service", c.str("service_type"))
            EzKV("Status", c.str("status"))
            EzKV("Balance", c.str("balance"))
            EzKV("PPPoE user", c.str("pppoe_username"))
            EzKV("PPPoE password", c.str("pppoe_password"))
            val rech = c.arr("recharges")
            if (rech.isNotEmpty()) {
                EzHeader("Recent plans")
                rech.forEach { r ->
                    Text(r.str("namebp") + "  " + r.str("recharged_on") + " to " + r.str("expiration") + "  (" + r.str("status") + ")")
                }
            }
            val tx = c.arr("transactions")
            if (tx.isNotEmpty()) {
                EzHeader("Recent payments")
                tx.forEach { t -> Text(t.str("recharged_on") + "  " + t.str("plan_name") + "  " + t.str("price") + "  " + t.str("method")) }
            }
        }
    }
}

/* ======================= PLANS ======================= */

@Composable
fun EzPlans() {
    EzLoad(load = { EzApi.call("plans") }) { res, _ ->
        val list = res.arr("plans")
        LazyColumn(Modifier.fillMaxSize()) {
            if (list.isEmpty()) item { EzEmpty("No plans") }
            items(list) { p ->
                EzRow(
                    title = p.str("name_plan"),
                    subtitle = p.str("type") + " - " + p.str("validity") + " " + p.str("validity_unit") +
                        (if (p.str("enabled") == "0") " - disabled" else ""),
                    trailing = p.str("price")
                )
            }
        }
    }
}

/* ======================= PAYMENTS ======================= */

@Composable
fun EzPayments() {
    EzLoad(load = { EzApi.call("payments") }) { res, _ ->
        val tx = res.arr("transactions")
        val mp = res.arr("mpesa")
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                EzCard {
                    Text(res.str("from") + "  to  " + res.str("to"), style = MaterialTheme.typography.labelLarge)
                    Text(res.str("total"), style = MaterialTheme.typography.headlineMedium)
                    Text(res.str("count") + " transactions", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { EzHeader("Transactions") }
            if (tx.isEmpty()) item { EzEmpty("No transactions in this period") }
            items(tx) { t ->
                EzRow(
                    title = t.str("username") + "  " + t.str("plan_name"),
                    subtitle = t.str("recharged_on") + " " + t.str("recharged_time") + " - " + t.str("method"),
                    trailing = t.str("price")
                )
            }
            if (mp.isNotEmpty()) item { EzHeader("Latest M-Pesa payments") }
            items(mp) { m ->
                EzRow(
                    title = m.str("TransID") + "  " + m.str("FirstName"),
                    subtitle = "Ref " + m.str("BillRefNumber") + " - " + m.str("TransTime"),
                    trailing = m.str("TransAmount")
                )
            }
        }
    }
}

/* ======================= STAFF LIST ======================= */

@Composable
fun EzStaff() {
    EzLoad(load = { EzApi.call("staff") }) { res, _ ->
        val list = res.arr("staff")
        LazyColumn(Modifier.fillMaxSize()) {
            items(list) { u ->
                val roles = u.arr("roles").joinToString(", ") { it.str("name") }
                EzRow(
                    title = u.str("fullname").ifBlank { u.str("username") },
                    subtitle = listOf(u.str("username"), u.str("user_type"), roles, if (u.optBoolean("is_technician")) "Technician" else "")
                        .filter { it.isNotBlank() }.joinToString(" - "),
                    trailing = u.str("status")
                )
            }
        }
    }
}

/* ======================= CLIENT SIDE ======================= */

@Composable
fun EzClientWallet() {
    EzLoad(load = { EzApi.call("me") }) { me, reload ->
        val plan = me.optJSONObject("active_plan")
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Top) {
            EzCard {
                Text("Wallet balance", style = MaterialTheme.typography.labelLarge)
                Text(me.str("currency").uppercase() + " " + me.str("balance"), style = MaterialTheme.typography.headlineMedium)
            }
            EzCard {
                Text("Current plan", style = MaterialTheme.typography.labelLarge)
                if (plan == null) {
                    Text("No active plan")
                } else {
                    Text(plan.str("plan"), style = MaterialTheme.typography.titleLarge)
                    Text("Expires " + plan.str("expiration") + " " + plan.str("expires_time"))
                }
            }
            ActionBtn("Refresh", reload)
        }
    }
}

@Composable
fun EzClientPlans() {
    EzLoad(load = { EzApi.call("plans") }) { res, _ ->
        val list = res.arr("plans")
        LazyColumn(Modifier.fillMaxSize()) {
            if (list.isEmpty()) item { EzEmpty("No plans available") }
            items(list) { p ->
                EzRow(
                    title = p.str("name_plan"),
                    subtitle = p.str("validity") + " " + p.str("validity_unit"),
                    trailing = p.str("price")
                )
            }
            item { EzEmpty("To buy or renew a plan, use the Pay Bill number shown by your provider or ask support.") }
        }
    }
}

@Composable
fun EzClientHistory() {
    EzLoad(load = { EzApi.call("history") }) { res, _ ->
        val list = res.arr("history")
        LazyColumn(Modifier.fillMaxSize()) {
            if (list.isEmpty()) item { EzEmpty("No payments yet") }
            items(list) { t ->
                EzRow(
                    title = t.str("plan_name"),
                    subtitle = t.str("recharged_on") + " - " + t.str("method"),
                    trailing = t.str("price")
                )
            }
        }
    }
}
