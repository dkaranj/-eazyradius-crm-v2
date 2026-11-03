# Eazyradius CRM v2

Native Android app (Kotlin + Jetpack Compose) for a PHPNuxBill panel.
What each person sees is decided by an admin on the server (App access screen).

## 1. Install the server plugin
Copy `server/system/plugin/mobile_api.php` to `system/plugin/mobile_api.php` on the panel.
Check: `https://YOUR-PANEL/?_route=plugin/mobile_api&a=ping` -> `{"ok":true,"version":"2.0"}`
It creates `tbl_mobile_sessions` and `tbl_mobile_access` on first use.

## 2. Build the APK
Push to `main`. Open the Actions tab, run "Build APK", download `EazyradiusCRM-apk`.

## 3. Sign in
Panel address, panel username, password. Customers sign in with their customer username/password.

## Modules the admin can switch per role, person or user type
dashboard, customers, plans, payments, leads, tickets, installations, store,
staff, hr_self, hr_manage, hr_payroll, app_access.
Levels: none, mine (own/assigned only), view, manage.
