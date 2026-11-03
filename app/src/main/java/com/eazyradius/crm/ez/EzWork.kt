@file:OptIn(ExperimentalMaterial3Api::class)

package com.eazyradius.crm.ez

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/* ======================= DASHBOARD ======================= */

@Composable
fun EzDashboard() {
    EzLoad(load = { EzApi.call("dashboard").getJSONObject("dashboard") }) { d, reload ->
        val keys = d.keys().asSequence().toList()
        LazyColumn(Modifier.fillMaxSize()) {
            item { OutlinedButton(onClick = reload, modifier = Modifier.padding(12.dp)) { Text("Refresh") } }
            if (keys.isEmpty()) item { EzEmpty("No numbers to show yet") }
            items(keys) { k ->
                val v = d.opt(k)
                if (v is JSONObject) {
                    EzCard {
                        Text(pretty(k), style = MaterialTheme.typography.titleMedium)
                        val inner = v.keys().asSequence().toList()
                        if (inner.isEmpty()) Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner.forEach { kk ->
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                Text(pretty(kk), Modifier.weight(1f))
                                Text(if (v.isNull(kk)) "-" else v.opt(kk).toString(), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                } else if (v is Number) {
                    EzCard {
                        Row(Modifier.fillMaxWidth()) {
                            Text(pretty(k), Modifier.weight(1f))
                            Text(v.toString(), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

/* ======================= LEADS ======================= */

@Composable
fun EzLeads(mods: Map<String, String>) {
    val manage = ezLvl(mods, "leads") >= 3
    var status by remember { mutableStateOf("") }
    var version by remember { mutableStateOf(0) }
    val run = rememberRunner()

    Column(Modifier.fillMaxSize()) {
        EzChips(
            listOf("" to "All", "new" to "New", "pending_installation" to "Pending install", "installed" to "Installed", "disqualified" to "Disqualified"),
            status
        ) { status = it }
        EzLoad(key = status to version, load = {
            Pair(EzApi.call("leads", mapOf("status" to status)), EzApi.call("lead_meta"))
        }) { (res, meta), _ ->
            var adding by remember { mutableStateOf(false) }
            var draft by remember { mutableStateOf<Map<String, String>?>(null) }
            var open by remember { mutableStateOf<JSONObject?>(null) }
            var qualify by remember { mutableStateOf<JSONObject?>(null) }
            var disq by remember { mutableStateOf<JSONObject?>(null) }
            val leads = res.arr("leads")

            LazyColumn(Modifier.fillMaxSize()) {
                item { Button(onClick = { adding = true }, modifier = Modifier.padding(12.dp)) { Text("Add lead") } }
                if (leads.isEmpty()) item { EzEmpty("No leads") }
                items(leads) { l ->
                    EzRow(
                        title = l.str("client_name"),
                        subtitle = listOf(l.str("phone"), l.str("location"), l.str("plan_name")).filter { it.isNotBlank() }.joinToString(" - "),
                        trailing = pretty(l.str("status")),
                        onClick = { open = l }
                    )
                }
            }

            if (adding) {
                EzFormDialog(
                    "New lead",
                    listOf(
                        EzField("client_name", "Client name"), EzField("phone", "Phone"), EzField("email", "Email"),
                        EzField("location", "Location"), EzField("sub_location", "Sub-location"),
                        EzField("comment", "Comment", multiline = true)
                    ),
                    submitLabel = "Next",
                    onSubmit = { draft = it; adding = false },
                    onDismiss = { adding = false }
                )
            }
            draft?.let { d ->
                val plans = listOf("" to "No plan yet") + meta.arr("plans").map { it.str("id") to (it.str("name_plan") + " - " + it.str("price")) }
                EzPick("Plan", plans, onPick = { planId ->
                    draft = null
                    run.run("Lead saved", onDone = { version++ }) {
                        EzApi.call("lead_save", d + mapOf("plan_id" to planId))
                    }
                }, onDismiss = { draft = null })
            }
            open?.let { l ->
                val actions = mutableListOf<Pair<String, () -> Unit>>()
                if (manage && l.str("status") == "new") {
                    actions.add("Qualify and create installation job" to { open = null; qualify = l })
                    actions.add("Disqualify" to { open = null; disq = l })
                }
                EzDetail(
                    l.str("lead_no") + "  " + l.str("client_name"),
                    listOf(
                        "Status" to pretty(l.str("status")), "Phone" to l.str("phone"), "Email" to l.str("email"),
                        "Location" to (l.str("location") + " " + l.str("sub_location")), "Plan" to l.str("plan_name"),
                        "Reason" to l.str("reason_text"), "Comment" to l.str("comment"),
                        "Created by" to l.str("created_by_name"), "Created" to l.str("created_at"),
                        "Actioned by" to l.str("actioned_by_name"), "Job" to l.str("job_no")
                    ),
                    actions, { open = null }
                )
            }
            qualify?.let { l ->
                val techs = listOf("" to "Assign later") + meta.arr("technicians").map { it.str("id") to it.str("fullname") }
                EzPick("Assign technician", techs, onPick = { tid ->
                    qualify = null
                    run.run("Installation job created", onDone = { version++ }) {
                        EzApi.call("lead_qualify", mapOf("id" to l.optInt("id"), "technician_id" to tid.toIntOrNull()))
                    }
                }, onDismiss = { qualify = null })
            }
            disq?.let { l ->
                val reasons = meta.arr("reasons").map { it.str("id") to it.str("reason") }
                EzPick("Reason", reasons, onPick = { rid ->
                    disq = null
                    run.run("Lead disqualified", onDone = { version++ }) {
                        EzApi.call("lead_disqualify", mapOf("id" to l.optInt("id"), "reason_id" to rid.toIntOrNull()))
                    }
                }, onDismiss = { disq = null })
            }
        }
    }
}

/* ======================= TICKETS ======================= */

@Composable
fun EzTickets(mods: Map<String, String>) {
    val manage = ezLvl(mods, "tickets") >= 3
    var status by remember { mutableStateOf("") }
    var version by remember { mutableStateOf(0) }
    val run = rememberRunner()

    Column(Modifier.fillMaxSize()) {
        EzChips(
            listOf("" to "All", "open" to "Open", "in_progress" to "In progress", "resolved" to "Resolved", "closed" to "Closed"),
            status
        ) { status = it }
        EzLoad(key = status to version, load = {
            Pair(
                EzApi.call("tickets", mapOf("status" to status)),
                if (manage) EzApi.call("techs") else JSONObject()
            )
        }) { (res, techRes), _ ->
            var adding by remember { mutableStateOf(false) }
            var draft by remember { mutableStateOf<Map<String, String>?>(null) }
            var prio by remember { mutableStateOf<Pair<Map<String, String>, String>?>(null) }
            var cat by remember { mutableStateOf<Pair<Map<String, String>, String>?>(null) }
            var openId by remember { mutableStateOf(0) }
            var assignFor by remember { mutableStateOf(0) }
            var resolveFor by remember { mutableStateOf(0) }
            val tickets = res.arr("tickets")
            val categories = res.arr("categories")
            val techs = techRes.arr("technicians")

            LazyColumn(Modifier.fillMaxSize()) {
                item { Button(onClick = { adding = true }, modifier = Modifier.padding(12.dp)) { Text("New ticket") } }
                if (tickets.isEmpty()) item { EzEmpty("No tickets") }
                items(tickets) { t ->
                    EzRow(
                        title = "#" + t.str("id") + "  " + t.str("title"),
                        subtitle = listOf(t.str("category"), t.str("assigned_name").ifBlank { "Unassigned" }, t.str("priority")).filter { it.isNotBlank() }.joinToString(" - "),
                        trailing = pretty(t.str("status")),
                        onClick = { openId = t.optInt("id") }
                    )
                }
            }

            if (adding) {
                EzFormDialog(
                    "New ticket",
                    listOf(EzField("title", "Title"), EzField("description", "Description", multiline = true)),
                    submitLabel = "Next", onSubmit = { draft = it; adding = false }, onDismiss = { adding = false }
                )
            }
            draft?.let { d ->
                EzPick("Priority", listOf("Low" to "Low", "Medium" to "Medium", "High" to "High"), onPick = { p ->
                    prio = d to p; draft = null
                }, onDismiss = { draft = null })
            }
            prio?.let { (d, p) ->
                EzPick("Category", categories.map { it.str("id") to it.str("name") }, onPick = { c ->
                    prio = null
                    cat = d + mapOf("priority" to p) to c
                }, onDismiss = { prio = null })
            }
            cat?.let { (d, c) ->
                // managers can pick an assignee, everyone else submits straight away
                if (manage) {
                    EzPick("Assign to", listOf("" to "Leave unassigned") + techs.map { it.str("id") to it.str("fullname") }, onPick = { tid ->
                        cat = null
                        run.run("Ticket created", onDone = { version++ }) {
                            EzApi.call("ticket_save", d + mapOf("category_id" to c, "assigned_to" to tid))
                        }
                    }, onDismiss = { cat = null })
                } else {
                    LaunchedEffect(c, d) {
                        cat = null
                        run.run("Ticket created", onDone = { version++ }) {
                            EzApi.call("ticket_save", d + mapOf("category_id" to c))
                        }
                    }
                }
            }

            if (openId != 0) {
                EzDialogLoad(
                    "Ticket #$openId", key = openId,
                    load = { EzApi.call("ticket", mapOf("id" to openId)).getJSONObject("ticket") },
                    onDismiss = { openId = 0 }
                ) { t, _ ->
                    val st = t.str("status")
                    EzKV("Status", pretty(st))
                    EzKV("Title", t.str("title"))
                    EzKV("Category", t.str("category"))
                    EzKV("Priority", t.str("priority"))
                    EzKV("Description", t.str("description"))
                    EzKV("Solution", t.str("solution"))
                    EzKV("History", t.str("log"))
                    val id = t.optInt("id")
                    if (st == "open") ActionBtn("Start work") {
                        openId = 0
                        run.run("Started", onDone = { version++ }) { EzApi.call("ticket_action", mapOf("id" to id, "action" to "start")) }
                    }
                    if (st == "open" || st == "in_progress") ActionBtn("Mark resolved") { openId = 0; resolveFor = id }
                    if (manage && st != "closed") ActionBtn("Verify and close") {
                        openId = 0
                        run.run("Closed", onDone = { version++ }) { EzApi.call("ticket_action", mapOf("id" to id, "action" to "close")) }
                    }
                    if (manage && st == "closed") ActionBtn("Reopen") {
                        openId = 0
                        run.run("Reopened", onDone = { version++ }) { EzApi.call("ticket_action", mapOf("id" to id, "action" to "reopen")) }
                    }
                    if (manage && st != "closed") ActionBtn("Assign to technician") { openId = 0; assignFor = id }
                }
            }
            if (assignFor != 0) {
                EzPick("Assign to", techs.map { it.str("id") to it.str("fullname") }, onPick = { tid ->
                    val id = assignFor
                    assignFor = 0
                    run.run("Assigned", onDone = { version++ }) {
                        EzApi.call("ticket_assign", mapOf("id" to id, "assigned_to" to tid.toIntOrNull()))
                    }
                }, onDismiss = { assignFor = 0 })
            }
            if (resolveFor != 0) {
                EzFormDialog(
                    "How was it solved?", listOf(EzField("solution", "Solution", multiline = true)), submitLabel = "Resolve",
                    onSubmit = { f ->
                        val id = resolveFor
                        resolveFor = 0
                        run.run("Marked resolved", onDone = { version++ }) {
                            EzApi.call("ticket_action", mapOf("id" to id, "action" to "resolve", "solution" to f["solution"]))
                        }
                    },
                    onDismiss = { resolveFor = 0 }
                )
            }
        }
    }
}

@Composable
fun ActionBtn(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

/* ======================= INSTALLATIONS ======================= */

@Composable
fun EzInstalls(mods: Map<String, String>) {
    val manage = ezLvl(mods, "installations") >= 3
    val hasStore = ezLvl(mods, "store") >= 1
    var status by remember { mutableStateOf("") }
    var version by remember { mutableStateOf(0) }
    val run = rememberRunner()

    Column(Modifier.fillMaxSize()) {
        EzChips(
            listOf("" to "All", "pending" to "Pending", "in_progress" to "In progress", "awaiting_payment" to "Awaiting payment", "completed" to "Completed", "cancelled" to "Cancelled"),
            status
        ) { status = it }
        EzLoad(key = status to version, load = {
            Pair(
                EzApi.call("installs", mapOf("status" to status)),
                if (manage) EzApi.call("techs") else JSONObject()
            )
        }) { (res, techRes), _ ->
            var openId by remember { mutableStateOf(0) }
            var assignFor by remember { mutableStateOf(0) }
            var materialsFor by remember { mutableStateOf(0) }
            val list = res.arr("installations")
            val techs = techRes.arr("technicians")

            LazyColumn(Modifier.fillMaxSize()) {
                if (list.isEmpty()) item { EzEmpty("No installation jobs") }
                items(list) { i ->
                    EzRow(
                        title = i.str("job_no") + "  " + i.str("customer_name"),
                        subtitle = listOf(i.str("location"), i.str("plan_name"), i.str("technician_name").ifBlank { "Unassigned" }).filter { it.isNotBlank() }.joinToString(" - "),
                        trailing = pretty(i.str("status")),
                        onClick = { openId = i.optInt("id") }
                    )
                }
            }

            if (openId != 0) {
                EzDialogLoad(
                    "Installation", key = openId,
                    load = { EzApi.call("install", mapOf("id" to openId)).getJSONObject("installation") },
                    onDismiss = { openId = 0 }
                ) { i, _ ->
                    val st = i.str("status")
                    val id = i.optInt("id")
                    EzKV("Job", i.str("job_no"))
                    EzKV("Status", pretty(st))
                    EzKV("Customer", i.str("customer_name"))
                    EzKV("Phone", i.str("phone"))
                    EzKV("Location", i.str("location") + " " + i.str("sub_location"))
                    EzKV("Address", i.str("address"))
                    EzKV("Plan", i.str("plan_name"))
                    EzKV("Scheduled", i.str("scheduled_at"))
                    EzKV("Due", i.str("due_at"))
                    EzKV("Started", i.str("started_at"))
                    EzKV("PPPoE user", i.str("pppoe_username"))
                    EzKV("PPPoE password", i.str("pppoe_password"))
                    EzKV("Payment", pretty(i.str("pay_status")) + "  " + i.str("amount_paid") + " / " + i.str("amount_due"))
                    EzKV("Notes", i.str("notes"))
                    if (st == "pending" || st == "in_progress") ActionBtn(if (st == "pending") "Start job" else "Mark as started again") {
                        openId = 0
                        run.run("Job started", onDone = { version++ }) { EzApi.call("install_start", mapOf("id" to id)) }
                    }
                    if (manage && st != "completed" && st != "cancelled") ActionBtn("Assign technician") { openId = 0; assignFor = id }
                    if (hasStore && st != "cancelled") ActionBtn("Record materials used") { openId = 0; materialsFor = id }
                    Text(
                        "Completing a job is done in the panel so the payment check stays in place.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (assignFor != 0) {
                EzPick("Assign technician", listOf("0" to "Unassign") + techs.map { it.str("id") to it.str("fullname") }, onPick = { tid ->
                    val id = assignFor
                    assignFor = 0
                    run.run("Assigned", onDone = { version++ }) {
                        EzApi.call("install_assign", mapOf("id" to id, "technician_id" to tid.toIntOrNull()))
                    }
                }, onDismiss = { assignFor = 0 })
            }
            if (materialsFor != 0) {
                EzMaterialsUsed(materialsFor) { materialsFor = 0 }
            }
        }
    }
}

/** Technician records stock used on a job; only items they hold are listed. */
@Composable
fun EzMaterialsUsed(jobId: Int, onClose: () -> Unit) {
    val run = rememberRunner()
    var items by remember { mutableStateOf<List<JSONObject>?>(null) }
    LaunchedEffect(jobId) {
        try {
            items = EzApi.call("store_stock").arr("items").filter { it.optDouble("mine", 0.0) > 0 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            run.toast(e.message ?: "Could not load your stock")
            onClose()
        }
    }
    val list = items ?: return
    EzItemsDialog(
        "Materials used", list, hint = { "you hold " + it.str("mine") + " " + it.str("unit") }, submitLabel = "Record",
        onSubmit = { rows, note ->
            if (rows.isEmpty()) {
                run.toast("Enter a quantity for at least one item")
            } else {
                onClose()
                run.run("Recorded") { EzApi.call("store_use", mapOf("job_id" to jobId, "items" to rows, "note" to note)) }
            }
        },
        onDismiss = onClose
    )
}

/* ======================= STORE ======================= */

@Composable
fun EzStore(mods: Map<String, String>) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Stock") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Requests") })
        }
        if (tab == 0) EzStockTab(mods) else EzRequestsTab(mods)
    }
}

@Composable
private fun EzStockTab(mods: Map<String, String>) {
    val manage = ezLvl(mods, "store") >= 3
    val full = ezLvl(mods, "store") >= 2
    var version by remember { mutableStateOf(0) }
    val run = rememberRunner()
    EzLoad(key = version, load = { EzApi.call("store_stock") }) { res, _ ->
        var requesting by remember { mutableStateOf(false) }
        var receiving by remember { mutableStateOf(false) }
        val items = res.arr("items")
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { requesting = true }) { Text("Request materials") }
                    if (manage) OutlinedButton(onClick = { receiving = true }) { Text("Receive stock") }
                }
            }
            if (items.isEmpty()) item { EzEmpty("No store items") }
            items(items) { i ->
                val low = i.optBoolean("low", false)
                EzRow(
                    title = i.str("name"),
                    subtitle = listOf(i.str("category"), i.str("unit")).filter { it.isNotBlank() }.joinToString(" - ") +
                        if (full) {
                            val h = i.arr("holders").joinToString(", ") { it.str("name") + " " + it.str("qty") }
                            if (h.isNotBlank()) "\nWith technicians: $h" else ""
                        } else "",
                    trailing = if (full) "Store ${i.str("in_store")}" + (if (low) " LOW" else "") + "\nYou ${i.str("mine")}" else "You ${i.str("mine")}"
                )
            }
        }
        if (requesting) {
            EzItemsDialog(
                "Request materials", items, hint = { it.str("unit") }, submitLabel = "Send request",
                onSubmit = { rows, note ->
                    if (rows.isEmpty()) {
                        run.toast("Enter a quantity for at least one item")
                    } else {
                        requesting = false
                        run.run("Request sent", onDone = { version++ }) { EzApi.call("store_request", mapOf("items" to rows, "note" to note)) }
                    }
                },
                onDismiss = { requesting = false }
            )
        }
        if (receiving) {
            EzItemsDialog(
                "Receive stock into main store", items, hint = { it.str("unit") }, submitLabel = "Receive",
                onSubmit = { rows, note ->
                    if (rows.isEmpty()) {
                        run.toast("Enter a quantity for at least one item")
                    } else {
                        receiving = false
                        run.run("Stock received", onDone = { version++ }) { EzApi.call("store_receive", mapOf("items" to rows, "note" to note)) }
                    }
                },
                onDismiss = { receiving = false }
            )
        }
    }
}

@Composable
private fun EzRequestsTab(mods: Map<String, String>) {
    val manage = ezLvl(mods, "store") >= 3
    var status by remember { mutableStateOf("") }
    var version by remember { mutableStateOf(0) }
    val run = rememberRunner()
    Column(Modifier.fillMaxSize()) {
        EzChips(listOf("" to "All", "pending" to "Pending", "approved" to "Approved", "rejected" to "Rejected"), status) { status = it }
        EzLoad(key = status to version, load = { EzApi.call("store_requests", mapOf("status" to status)) }) { res, _ ->
            var open by remember { mutableStateOf<JSONObject?>(null) }
            val reqs = res.arr("requests")
            LazyColumn(Modifier.fillMaxSize()) {
                if (reqs.isEmpty()) item { EzEmpty("No requests") }
                items(reqs) { r ->
                    EzRow(
                        title = r.str("req_no") + "  " + r.str("tech_name"),
                        subtitle = r.arr("items").joinToString(", ") { it.str("name") + " x" + it.str("qty_req") },
                        trailing = pretty(r.str("status")),
                        onClick = { open = r }
                    )
                }
            }
            open?.let { r ->
                val id = r.optInt("id")
                val actions = mutableListOf<Pair<String, () -> Unit>>()
                if (manage && r.str("status") == "pending") {
                    actions.add("Approve and issue stock" to {
                        open = null
                        run.run("Approved - stock issued", onDone = { version++ }) {
                            EzApi.call("store_request_decide", mapOf("id" to id, "decision" to "approve"))
                        }
                    })
                    actions.add("Reject" to {
                        open = null
                        run.run("Rejected", onDone = { version++ }) {
                            EzApi.call("store_request_decide", mapOf("id" to id, "decision" to "reject"))
                        }
                    })
                }
                EzDetail(
                    r.str("req_no"),
                    listOf(
                        "Technician" to r.str("tech_name"), "Status" to pretty(r.str("status")),
                        "Items" to r.arr("items").joinToString("\n") {
                            it.str("name") + ": asked " + it.str("qty_req") + (if (it.str("qty_ok").isNotBlank()) ", given " + it.str("qty_ok") else "") + " " + it.str("unit")
                        },
                        "Note" to r.str("note"), "Admin note" to r.str("admin_note"), "Requested" to r.str("created_at")
                    ),
                    actions, { open = null }
                )
            }
        }
    }
}
