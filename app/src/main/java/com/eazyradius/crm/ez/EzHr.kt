@file:OptIn(ExperimentalMaterial3Api::class)

package com.eazyradius.crm.ez

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
private fun thisMonth(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

/** key is one of hr_self, hr_manage, hr_payroll (each is its own entry in the server-built menu). */
@Composable
fun EzHr(mods: Map<String, String>, key: String) {
    when (key) {
        "hr_self" -> EzHrSelf()
        "hr_manage" -> EzHrTeam(mods)
        "hr_payroll" -> EzHrPayroll()
    }
}

/* ======================= MY HR ======================= */

@Composable
fun EzHrSelf() {
    var version by remember { mutableStateOf(0) }
    val run = rememberRunner()
    EzLoad(key = version, load = { EzApi.call("hr_me") }) { me, _ ->
        var leaveOpen by remember { mutableStateOf(false) }
        val todayRec = me.optJSONObject("today")
        val emp = me.optJSONObject("employee") ?: JSONObject()
        val attendance = me.arr("attendance")
        val leave = me.arr("leave")
        val slips = me.arr("payslips")

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                EzCard {
                    Text(emp.str("first_name") + " " + emp.str("last_name"), style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOf(emp.str("employee_no"), emp.str("job_title")).filter { it.isNotBlank() }.joinToString(" - "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                EzCard {
                    Text("Today", style = MaterialTheme.typography.labelLarge)
                    if (todayRec == null) {
                        Text("Not clocked in yet (work starts " + me.str("work_start") + ")")
                    } else {
                        Text(
                            "In " + todayRec.str("clock_in").ifBlank { "-" } + "   Out " + todayRec.str("clock_out").ifBlank { "-" } +
                                "   " + todayRec.str("status")
                        )
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            run.run("Clocked in", onDone = { version++ }) { EzApi.call("hr_clock", mapOf("direction" to "in")) }
                        }) { Text("Clock in") }
                        OutlinedButton(onClick = {
                            run.run("Clocked out", onDone = { version++ }) { EzApi.call("hr_clock", mapOf("direction" to "out")) }
                        }) { Text("Clock out") }
                    }
                }
            }
            item { EzHeader("Leave") }
            item { Button(onClick = { leaveOpen = true }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Request leave") } }
            if (leave.isEmpty()) item { EzEmpty("No leave requests") }
            items(leave) { l ->
                EzRow(
                    title = l.str("leave_type") + "  (" + l.str("days") + " days)",
                    subtitle = l.str("start_date") + " to " + l.str("end_date") +
                        (if (l.str("decision_note").isNotBlank()) "\n" + l.str("decision_note") else ""),
                    trailing = l.str("status")
                )
            }
            item { EzHeader("Recent attendance") }
            if (attendance.isEmpty()) item { EzEmpty("No attendance yet") }
            items(attendance) { a ->
                EzRow(
                    title = a.str("work_date"),
                    subtitle = "In " + a.str("clock_in").ifBlank { "-" } + "   Out " + a.str("clock_out").ifBlank { "-" },
                    trailing = a.str("status")
                )
            }
            item { EzHeader("Payslips") }
            if (slips.isEmpty()) item { EzEmpty("No approved payslips yet") }
            items(slips) { s ->
                EzRow(
                    title = s.str("period_month"),
                    subtitle = "Gross " + s.str("gross_pay") + "   Deductions " + s.str("deductions") +
                        "\nPAYE " + s.str("paye") + "  NSSF " + s.str("nssf") + "  SHIF " + s.str("shif") + "  Housing " + s.str("housing_levy"),
                    trailing = "Net " + s.str("net_pay") + "\n" + s.str("status")
                )
            }
        }

        if (leaveOpen) {
            EzFormDialog(
                "Request leave",
                listOf(
                    EzField("leave_type", "Type", initial = "Annual Leave"),
                    EzField("start_date", "From (YYYY-MM-DD)", initial = today()),
                    EzField("end_date", "To (YYYY-MM-DD)", initial = today()),
                    EzField("reason", "Reason", multiline = true)
                ),
                submitLabel = "Send",
                onSubmit = { f ->
                    leaveOpen = false
                    run.run("Leave requested", onDone = { version++ }) { EzApi.call("hr_leave_request", f) }
                },
                onDismiss = { leaveOpen = false }
            )
        }
    }
}

/* ======================= HR TEAM (managers) ======================= */

@Composable
fun EzHrTeam(mods: Map<String, String>) {
    var section by remember { mutableStateOf("leave") }
    Column(Modifier.fillMaxSize()) {
        EzChips(
            listOf("leave" to "Leave", "overtime" to "Overtime", "attendance" to "Attendance", "employees" to "Employees"),
            section
        ) { section = it }
        when (section) {
            "leave" -> EzHrDecisions(mods, "hr_leave_list", "leave", "hr_leave_decide", false)
            "overtime" -> EzHrDecisions(mods, "hr_overtime_list", "overtime", "hr_overtime_decide", true)
            "attendance" -> EzHrAttendance()
            "employees" -> EzHrEmployees()
        }
    }
}

/** Shared list + approve/reject for leave and overtime. */
@Composable
private fun EzHrDecisions(mods: Map<String, String>, listAction: String, listKey: String, decideAction: String, overtime: Boolean) {
    val manage = ezLvl(mods, "hr_manage") >= 3
    var status by remember(listAction) { mutableStateOf("Pending") }
    var version by remember(listAction) { mutableStateOf(0) }
    val run = rememberRunner()
    Column(Modifier.fillMaxSize()) {
        EzChips(listOf("Pending" to "Pending", "Approved" to "Approved", "Rejected" to "Rejected", "" to "All"), status) { status = it }
        EzLoad(key = listAction to status to version, load = { EzApi.call(listAction, mapOf("status" to status)) }) { res, _ ->
            var open by remember { mutableStateOf<JSONObject?>(null) }
            val list = res.arr(listKey)
            LazyColumn(Modifier.fillMaxSize()) {
                if (list.isEmpty()) item { EzEmpty("Nothing to show") }
                items(list) { r ->
                    EzRow(
                        title = r.str("name"),
                        subtitle = if (overtime) r.str("work_date") + " - " + r.str("overtime_hours") + " h overtime"
                        else r.str("leave_type") + " - " + r.str("start_date") + " to " + r.str("end_date") + " (" + r.str("days") + " days)",
                        trailing = r.str("status"),
                        onClick = { open = r }
                    )
                }
            }
            open?.let { r ->
                val id = r.optInt("id")
                val actions = mutableListOf<Pair<String, () -> Unit>>()
                if (manage && r.str("status") == "Pending") {
                    actions.add("Approve" to {
                        open = null
                        run.run("Approved", onDone = { version++ }) { EzApi.call(decideAction, mapOf("id" to id, "decision" to "approve")) }
                    })
                    actions.add("Reject" to {
                        open = null
                        run.run("Rejected", onDone = { version++ }) { EzApi.call(decideAction, mapOf("id" to id, "decision" to "reject")) }
                    })
                }
                EzDetail(
                    r.str("name"),
                    listOf(
                        "Status" to r.str("status"), "Type" to r.str("leave_type"),
                        "From" to r.str("start_date"), "To" to r.str("end_date"), "Days" to r.str("days"),
                        "Date" to r.str("work_date"), "Hours worked" to r.str("hours_worked"), "Overtime hours" to r.str("overtime_hours"),
                        "Reason" to r.str("reason"), "Decided by" to r.str("decided_by_name"), "Note" to r.str("decision_note")
                    ),
                    actions, { open = null }
                )
            }
        }
    }
}

@Composable
private fun EzHrAttendance() {
    var date by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        EzSearch("Date YYYY-MM-DD (blank = today)") { date = it }
        EzLoad(key = date, load = { EzApi.call("hr_attendance", mapOf("date" to date)) }) { res, _ ->
            val list = res.arr("attendance")
            LazyColumn(Modifier.fillMaxSize()) {
                item { EzHeader(res.str("date")) }
                items(list) { a ->
                    EzRow(
                        title = a.str("name"),
                        subtitle = "In " + a.str("clock_in").ifBlank { "-" } + "   Out " + a.str("clock_out").ifBlank { "-" },
                        trailing = a.str("status").ifBlank { "Absent" }
                    )
                }
            }
        }
    }
}

@Composable
private fun EzHrEmployees() {
    EzLoad(load = { EzApi.call("hr_employees") }) { res, _ ->
        val list = res.arr("employees")
        LazyColumn(Modifier.fillMaxSize()) {
            items(list) { e ->
                EzRow(
                    title = e.str("first_name") + " " + e.str("last_name"),
                    subtitle = listOf(e.str("employee_no"), e.str("job_title"), e.str("department"), e.str("phone")).filter { it.isNotBlank() }.joinToString(" - "),
                    trailing = e.str("status")
                )
            }
            item { EzHeader("Holidays") }
            items(res.arr("holidays")) { h ->
                EzRow(title = h.str("name"), subtitle = h.str("holiday_date") + (if (h.str("is_recurring") == "1") " (every year)" else ""))
            }
        }
    }
}

/* ======================= PAYROLL ======================= */

@Composable
fun EzHrPayroll() {
    var period by remember { mutableStateOf(thisMonth()) }
    Column(Modifier.fillMaxSize()) {
        EzSearch("Month YYYY-MM") { if (it.isNotBlank()) period = it }
        EzLoad(key = period, load = { EzApi.call("hr_payroll", mapOf("period" to period)) }) { res, _ ->
            val list = res.arr("payroll")
            LazyColumn(Modifier.fillMaxSize()) {
                item { EzHeader("Payroll " + res.str("period")) }
                if (list.isEmpty()) item { EzEmpty("No payroll for this month") }
                items(list) { p ->
                    EzRow(
                        title = p.str("name"),
                        subtitle = "Gross " + p.str("gross_pay") + "   Deductions " + p.str("deductions") +
                            "\nPAYE " + p.str("paye") + "  NSSF " + p.str("nssf") + "  SHIF " + p.str("shif") + "  Housing " + p.str("housing_levy"),
                        trailing = "Net " + p.str("net_pay") + "\n" + p.str("status")
                    )
                }
            }
        }
    }
}
