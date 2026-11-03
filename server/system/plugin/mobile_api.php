<?php
/**
 * Eazyradius CRM - mobile API plugin (v2)
 *
 * Install: copy to  system/plugin/mobile_api.php  on the PHPNuxBill panel.
 * Test:    https://YOUR-PANEL/?_route=plugin/mobile_api&a=ping
 *
 * Works against the real panel tables: tbl_users, tbl_customers, tbl_leads,
 * tbl_support_tickets, tbl_installations, tbl_transactions, tbl_plans,
 * tbl_staff_roles / tbl_staff_user_roles / tbl_ops_techs and the tbl_hr_* tables.
 *
 * What each person sees in the app is decided by an admin (app_access module).
 * Access is stored in its own table (tbl_mobile_access) so the web panel's role
 * editor can never wipe it.
 */

if (!class_exists('EzMobileApi')) {

class EzMobileApi
{
    const VERSION = '2.0';

    /** module key => [label, group] */
    public static $MODULES = [
        'dashboard'     => ['Dashboard', 'Overview'],
        'customers'     => ['Customers', 'Billing'],
        'plans'         => ['Plans', 'Billing'],
        'payments'      => ['Payments', 'Billing'],
        'leads'         => ['Leads', 'Work'],
        'tickets'       => ['Support tickets', 'Work'],
        'installations' => ['Installations', 'Work'],
        'store'         => ['Store (stock, requests, usage)', 'Work'],
        'staff'         => ['Staff list', 'Admin'],
        'hr_self'       => ['My HR (attendance, leave, payslips)', 'HRM'],
        'hr_manage'     => ['HR management', 'HRM'],
        'hr_payroll'    => ['Payroll', 'HRM'],
        'app_access'    => ['App access control', 'Admin'],
    ];

    /** none < mine (own/assigned only) < view (everything, read) < manage (everything, edit) */
    public static $LEVELS = ['none' => 0, 'mine' => 1, 'view' => 2, 'manage' => 3];

    private static $pdo = null;
    private static $in = [];

    /* ------------------------------------------------------------------ */
    /* plumbing                                                            */
    /* ------------------------------------------------------------------ */

    public static function out($data, $code = 200)
    {
        http_response_code($code);
        echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PARTIAL_OUTPUT_ON_ERROR);
        exit;
    }

    public static function ok($data = [])
    {
        self::out(['ok' => true] + $data);
    }

    public static function fail($msg, $code = 400)
    {
        self::out(['ok' => false, 'error' => $msg], $code);
    }

    private static function db()
    {
        if (self::$pdo === null) {
            self::$pdo = ORM::get_db();
            self::$pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
        }
        return self::$pdo;
    }

    private static function rows($sql, $p = [])
    {
        $s = self::db()->prepare($sql);
        $s->execute($p);
        return $s->fetchAll(PDO::FETCH_ASSOC);
    }

    private static function row($sql, $p = [])
    {
        $r = self::rows($sql, $p);
        return $r ? $r[0] : null;
    }

    private static function run($sql, $p = [])
    {
        $s = self::db()->prepare($sql);
        $s->execute($p);
        return $s->rowCount();
    }

    private static function lastId()
    {
        return (int) self::db()->lastInsertId();
    }

    private static function in($k, $default = null)
    {
        return isset(self::$in[$k]) ? self::$in[$k] : $default;
    }

    private static function str($k, $max = 255)
    {
        $v = self::in($k, '');
        return is_scalar($v) ? mb_substr(trim((string) $v), 0, $max) : '';
    }

    private static function intv($k)
    {
        return (int) self::in($k, 0);
    }

    private static function now()
    {
        return date('Y-m-d H:i:s');
    }

    private static function page()
    {
        $lim = min(100, max(1, (int) self::in('limit', 50)));
        $off = max(0, (int) self::in('offset', 0));
        return " LIMIT $lim OFFSET $off";
    }

    private static function setting($name, $default = null)
    {
        $r = self::row("SELECT `value` FROM tbl_appconfig WHERE `setting` = ? LIMIT 1", [$name]);
        return $r ? $r['value'] : $default;
    }

    private static function ensureTables()
    {
        self::run("CREATE TABLE IF NOT EXISTS tbl_mobile_sessions (
            id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
            token_hash CHAR(64) NOT NULL,
            kind VARCHAR(10) NOT NULL,
            user_id INT NOT NULL,
            device VARCHAR(80) DEFAULT NULL,
            created_at DATETIME NOT NULL,
            last_used DATETIME NOT NULL,
            UNIQUE KEY uq_token (token_hash)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        self::run("CREATE TABLE IF NOT EXISTS tbl_mobile_access (
            scope VARCHAR(10) NOT NULL,
            ref VARCHAR(60) NOT NULL,
            modules TEXT NOT NULL,
            updated_by INT DEFAULT NULL,
            updated_at DATETIME NOT NULL,
            PRIMARY KEY (scope, ref)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /* ------------------------------------------------------------------ */
    /* entry point                                                         */
    /* ------------------------------------------------------------------ */

    public static function handle()
    {
        header('Content-Type: application/json; charset=utf-8');
        header('Cache-Control: no-store');

        $raw = file_get_contents('php://input');
        $json = $raw ? json_decode($raw, true) : null;
        self::$in = array_merge($_GET, $_POST, is_array($json) ? $json : []);

        $a = (string) self::in('a', '');
        if ($a === 'ping') {
            self::ok(['version' => self::VERSION]);
        }

        self::ensureTables();

        if ($a === 'login') {
            self::login();
        }

        $sess = self::session();
        if ($sess['kind'] === 'customer') {
            self::customerRoutes($a, $sess);
        } else {
            self::staffRoutes($a, $sess);
        }
    }

    /* ------------------------------------------------------------------ */
    /* auth                                                                */
    /* ------------------------------------------------------------------ */

    private static function bearer()
    {
        $h = '';
        if (!empty($_SERVER['HTTP_AUTHORIZATION'])) {
            $h = $_SERVER['HTTP_AUTHORIZATION'];
        } elseif (!empty($_SERVER['REDIRECT_HTTP_AUTHORIZATION'])) {
            $h = $_SERVER['REDIRECT_HTTP_AUTHORIZATION'];
        } elseif (function_exists('getallheaders')) {
            foreach (getallheaders() as $k => $v) {
                if (strtolower($k) === 'authorization') {
                    $h = $v;
                }
            }
        }
        if (stripos($h, 'bearer ') === 0) {
            return trim(substr($h, 7));
        }
        $t = self::in('token', '');
        return is_string($t) ? $t : '';
    }

    private static function session()
    {
        $tok = self::bearer();
        if ($tok === '') {
            self::fail('Not signed in', 401);
        }
        $s = self::row(
            "SELECT * FROM tbl_mobile_sessions WHERE token_hash = ? AND last_used > (NOW() - INTERVAL 30 DAY) LIMIT 1",
            [hash('sha256', $tok)]
        );
        if (!$s) {
            self::fail('Session expired, sign in again', 401);
        }
        self::run("UPDATE tbl_mobile_sessions SET last_used = NOW() WHERE id = ?", [$s['id']]);
        $s['token'] = $tok;
        return $s;
    }

    private static function newSession($kind, $uid)
    {
        $tok = bin2hex(random_bytes(24));
        self::run(
            "INSERT INTO tbl_mobile_sessions (token_hash, kind, user_id, device, created_at, last_used) VALUES (?,?,?,?,NOW(),NOW())",
            [hash('sha256', $tok), $kind, $uid, self::str('device', 80)]
        );
        return $tok;
    }

    private static function staffPasswordOk($plain, $hash)
    {
        $hash = (string) $hash;
        if ($hash === '') {
            return false;
        }
        // PHPNuxBill admins: unsalted sha1
        if (hash_equals(strtolower($hash), sha1($plain))) {
            return true;
        }
        if (preg_match('/^\$2[aby]\$/', $hash)) {
            return password_verify($plain, $hash);
        }
        return false;
    }

    private static function login()
    {
        $user = self::str('username', 60);
        $pass = (string) self::in('password', '');
        if ($user === '' || $pass === '') {
            self::fail('Username and password are required');
        }

        $u = self::row("SELECT * FROM tbl_users WHERE username = ? LIMIT 1", [$user]);
        if ($u && $u['status'] === 'Active' && self::staffPasswordOk($pass, $u['password'])) {
            self::run("UPDATE tbl_users SET last_login = ? WHERE id = ?", [self::now(), $u['id']]);
            $tok = self::newSession('staff', (int) $u['id']);
            self::ok(['token' => $tok, 'kind' => 'staff'] + self::staffProfile($u));
        }

        $c = self::row("SELECT * FROM tbl_customers WHERE username = ? LIMIT 1", [$user]);
        if ($c && in_array($c['status'], ['Active', 'Limited', 'Inactive'], true)
            && hash_equals((string) $c['password'], $pass)) {
            self::run("UPDATE tbl_customers SET last_login = ? WHERE id = ?", [self::now(), $c['id']]);
            $tok = self::newSession('customer', (int) $c['id']);
            self::ok(['token' => $tok, 'kind' => 'customer'] + self::customerProfile($c));
        }

        usleep(400000); // slow down password guessing a little
        self::fail('Wrong username or password', 401);
    }

    /* ------------------------------------------------------------------ */
    /* access control                                                      */
    /* ------------------------------------------------------------------ */

    private static function accessRow($scope, $ref)
    {
        $r = self::row("SELECT modules FROM tbl_mobile_access WHERE scope = ? AND ref = ?", [$scope, (string) $ref]);
        if (!$r) {
            return null;
        }
        $m = json_decode($r['modules'], true);
        return is_array($m) ? $m : [];
    }

    private static function mergeMax(&$into, $add)
    {
        foreach ($add as $k => $lvl) {
            if (!isset(self::$MODULES[$k]) || !isset(self::$LEVELS[$lvl])) {
                continue;
            }
            $cur = isset($into[$k]) ? self::$LEVELS[$into[$k]] : 0;
            if (self::$LEVELS[$lvl] > $cur) {
                $into[$k] = $lvl;
            }
        }
    }

    private static function typeDefaults($type)
    {
        $m = [];
        if ($type === 'Admin') {
            foreach (self::$MODULES as $k => $_) {
                $m[$k] = 'manage';
            }
            unset($m['app_access']);
            $m['hr_payroll'] = 'view';
        } elseif ($type === 'Report') {
            $m = ['dashboard' => 'view', 'payments' => 'view', 'customers' => 'view', 'plans' => 'view'];
        }
        return $m;
    }

    /** Map the web panel's existing staff-role permissions to app modules (used until an admin configures the role). */
    private static function legacy($perms)
    {
        $has = function ($k) use ($perms) {
            return in_array($k, $perms, true);
        };
        $m = [];
        if ($has('leads.action_all')) {
            $m['leads'] = 'manage';
        } elseif ($has('leads.view_all')) {
            $m['leads'] = 'view';
        } elseif ($has('jobs.request_install') || $has('customers.register')) {
            $m['leads'] = 'mine';
        }
        if ($has('installations.work') || $has('jobs.update') || $has('jobs.record')) {
            $m['installations'] = 'mine';
            $m['tickets'] = 'mine';
        }
        if ($has('customers.view_assigned') || $has('customers.view_registered')
            || $has('customers.view_connection') || $has('customers.register')) {
            $m['customers'] = 'mine';
        }
        if ($has('plans.view')) {
            $m['plans'] = 'view';
        }
        return $m;
    }

    private static function employeeOf($uid)
    {
        return self::row("SELECT * FROM tbl_hr_employees WHERE admin_id = ? LIMIT 1", [$uid]);
    }

    private static function roleRows($uid)
    {
        return self::rows(
            "SELECT r.id, r.name, r.permissions FROM tbl_staff_roles r
             JOIN tbl_staff_user_roles ur ON ur.role_id = r.id WHERE ur.user_id = ?",
            [$uid]
        );
    }

    private static function isTech($uid)
    {
        return (bool) self::row("SELECT 1 AS x FROM tbl_ops_techs WHERE user_id = ?", [$uid]);
    }

    private static function effective($u)
    {
        $uid = (int) $u['id'];
        $final = [];

        if ($u['user_type'] === 'SuperAdmin') {
            foreach (self::$MODULES as $k => $_) {
                $final[$k] = 'manage';
            }
            return $final;
        }

        $typeRow = self::accessRow('type', $u['user_type']);
        self::mergeMax($final, $typeRow !== null ? $typeRow : self::typeDefaults($u['user_type']));

        foreach (self::roleRows($uid) as $r) {
            $cfg = self::accessRow('role', $r['id']);
            if ($cfg === null) {
                $perms = json_decode((string) $r['permissions'], true);
                $cfg = self::legacy(is_array($perms) ? $perms : []);
            }
            self::mergeMax($final, $cfg);
        }

        if (self::isTech($uid)) {
            self::mergeMax($final, ['installations' => 'mine', 'tickets' => 'mine', 'store' => 'mine']);
        }

        // baseline everyone gets (an admin can still switch these off per user)
        self::mergeMax($final, ['dashboard' => 'mine']);
        if (self::employeeOf($uid)) {
            self::mergeMax($final, ['hr_self' => 'mine']);
        }
        $map = json_decode((string) self::setting('hrm_role_map', '{}'), true);
        if (is_array($map) && !empty($map[(string) $uid])) {
            self::mergeMax($final, ['hr_manage' => 'manage']);
        }

        $ov = self::accessRow('user', $uid);
        if ($ov) {
            foreach ($ov as $k => $lvl) {
                if (!isset(self::$MODULES[$k]) || !isset(self::$LEVELS[$lvl])) {
                    continue;
                }
                if ($lvl === 'none') {
                    unset($final[$k]);
                } else {
                    $final[$k] = $lvl;
                }
            }
        }

        // only a SuperAdmin may hold app_access
        unset($final['app_access']);
        if (!empty($ov['app_access']) && $ov['app_access'] !== 'none') {
            $final['app_access'] = $ov['app_access'];
        }
        return $final;
    }

    private static function lvl($mods, $m)
    {
        return isset($mods[$m]) ? self::$LEVELS[$mods[$m]] : 0;
    }

    private static function need($mods, $m, $min = 'view')
    {
        if (self::lvl($mods, $m) < self::$LEVELS[$min]) {
            self::fail('Your account does not have access to this', 403);
        }
    }

    /* ------------------------------------------------------------------ */
    /* profiles                                                            */
    /* ------------------------------------------------------------------ */

    /** Simple role string for the app: admin | technician | sales | report | client */
    private static function simpleRole($u)
    {
        if (in_array($u['user_type'], ['SuperAdmin', 'Admin'], true)) {
            return 'admin';
        }
        if (self::isTech((int) $u['id'])) {
            return 'technician';
        }
        if (in_array($u['user_type'], ['Sales', 'Agent'], true)) {
            return 'sales';
        }
        return strtolower($u['user_type']);
    }

    private static function staffProfile($u)
    {
        $mods = self::effective($u);
        $catalog = [];
        foreach ($mods as $k => $lvl) {
            $catalog[] = ['key' => $k, 'label' => self::$MODULES[$k][0], 'group' => self::$MODULES[$k][1], 'level' => $lvl];
        }
        $emp = self::employeeOf((int) $u['id']);
        return [
            'role' => self::simpleRole($u),
            'user_type' => $u['user_type'],
            'user' => [
                'id' => (int) $u['id'],
                'username' => $u['username'],
                'fullname' => $u['fullname'],
                'user_type' => $u['user_type'],
                'role' => self::simpleRole($u),
                'phone' => $u['phone'],
                'email' => $u['email'],
            ],
            'roles' => array_map(function ($r) {
                return ['id' => (int) $r['id'], 'name' => $r['name']];
            }, self::roleRows((int) $u['id'])),
            'is_technician' => self::isTech((int) $u['id']),
            'employee' => $emp ? [
                'id' => (int) $emp['id'],
                'employee_no' => $emp['employee_no'],
                'job_title' => $emp['job_title'],
            ] : null,
            'modules' => $mods,
            'menu' => $catalog,
            'currency' => self::setting('currency_code', 'KES'),
            'company' => self::setting('CompanyName', ''),
        ];
    }

    private static function activePlan($customerId)
    {
        return self::row(
            "SELECT namebp AS plan, expiration, `time` AS expires_time, type, routers
             FROM tbl_user_recharges WHERE customer_id = ? AND status = 'on' ORDER BY id DESC LIMIT 1",
            [$customerId]
        );
    }

    private static function customerProfile($c)
    {
        return [
            'role' => 'client',
            'user_type' => 'Client',
            'user' => [
                'role' => 'client',
                'id' => (int) $c['id'],
                'username' => $c['username'],
                'fullname' => $c['fullname'],
                'phone' => $c['phonenumber'],
                'email' => $c['email'],
                'service_type' => $c['service_type'],
                'status' => $c['status'],
            ],
            'balance' => (float) $c['balance'],
            'active_plan' => self::activePlan((int) $c['id']),
            'modules' => ['wallet' => 'view', 'plans' => 'view', 'history' => 'view'],
            'menu' => [
                ['key' => 'wallet', 'label' => 'Wallet & plan', 'group' => 'Account', 'level' => 'view'],
                ['key' => 'plans', 'label' => 'Plans', 'group' => 'Account', 'level' => 'view'],
                ['key' => 'history', 'label' => 'Payment history', 'group' => 'Account', 'level' => 'view'],
            ],
            'currency' => self::setting('currency_code', 'KES'),
            'company' => self::setting('CompanyName', ''),
        ];
    }

    /* ------------------------------------------------------------------ */
    /* customer (client) side                                              */
    /* ------------------------------------------------------------------ */

    private static function customerRoutes($a, $sess)
    {
        $c = self::row("SELECT * FROM tbl_customers WHERE id = ?", [$sess['user_id']]);
        if (!$c) {
            self::fail('Account not found', 401);
        }
        switch ($a) {
            case 'me':
                self::ok(['kind' => 'customer'] + self::customerProfile($c));
            case 'logout':
                self::run("DELETE FROM tbl_mobile_sessions WHERE id = ?", [$sess['id']]);
                self::ok();
            case 'plans':
                $type = $c['service_type'] === 'Hotspot' ? 'Hotspot' : 'PPPOE';
                $rows = self::rows(
                    "SELECT id, name_plan, price, type, validity, validity_unit, typebp
                     FROM tbl_plans WHERE enabled = 1 AND allow_purchase = 'yes' AND type = ? ORDER BY CAST(price AS DECIMAL(15,2))",
                    [$type]
                );
                self::ok(['plans' => $rows]);
            case 'history':
                $rows = self::rows(
                    "SELECT invoice, plan_name, price, recharged_on, recharged_time, expiration, method, type
                     FROM tbl_transactions WHERE user_id = ? ORDER BY id DESC" . self::page(),
                    [$c['id']]
                );
                self::ok(['history' => $rows]);
        }
        self::fail('Unknown action', 404);
    }

    /* ------------------------------------------------------------------ */
    /* staff routes                                                        */
    /* ------------------------------------------------------------------ */

    private static function staffRoutes($a, $sess)
    {
        $u = self::row("SELECT * FROM tbl_users WHERE id = ?", [$sess['user_id']]);
        if (!$u || $u['status'] !== 'Active') {
            self::fail('Account disabled', 401);
        }
        $mods = self::effective($u);
        $uid = (int) $u['id'];

        switch ($a) {
            case 'me':
                self::ok(['kind' => 'staff'] + self::staffProfile($u));
            case 'logout':
                self::run("DELETE FROM tbl_mobile_sessions WHERE id = ?", [$sess['id']]);
                self::ok();

            case 'dashboard':
                self::need($mods, 'dashboard', 'mine');
                self::ok(self::dashboard($u, $mods));

            case 'customers':
                self::need($mods, 'customers', 'mine');
                self::ok(['customers' => self::customers($mods, $uid)]);
            case 'customer':
                self::need($mods, 'customers', 'mine');
                self::ok(['customer' => self::customerDetail($mods, $uid)]);

            case 'plans':
                self::need($mods, 'plans', 'view');
                self::ok(['plans' => self::rows(
                    "SELECT id, name_plan, price, type, validity, validity_unit, typebp, device, routers, enabled, allow_purchase
                     FROM tbl_plans ORDER BY type, CAST(price AS DECIMAL(15,2))"
                )]);

            case 'payments':
                self::need($mods, 'payments', 'view');
                self::ok(self::payments());

            case 'lead_meta':
                self::need($mods, 'leads', 'mine');
                self::ok(self::leadMeta());
            case 'leads':
                self::need($mods, 'leads', 'mine');
                self::ok(['leads' => self::leads($mods, $uid)]);
            case 'lead_save':
                self::need($mods, 'leads', 'mine');
                self::ok(self::leadSave($u));
            case 'lead_qualify':
                self::need($mods, 'leads', 'manage');
                self::ok(self::leadQualify($u));
            case 'lead_disqualify':
                self::need($mods, 'leads', 'manage');
                self::ok(self::leadDisqualify($u));

            case 'techs':
                if (self::lvl($mods, 'tickets') < 3 && self::lvl($mods, 'installations') < 3 && self::lvl($mods, 'app_access') < 3) {
                    self::fail('Your account does not have access to this', 403);
                }
                self::ok(['technicians' => self::techs()]);

            case 'tickets':
                self::need($mods, 'tickets', 'mine');
                self::ok(['tickets' => self::tickets($mods, $uid), 'categories' => self::rows("SELECT id, name FROM tbl_support_categories ORDER BY name")]);
            case 'ticket':
                self::need($mods, 'tickets', 'mine');
                self::ok(['ticket' => self::ticketDetail($mods, $uid)]);
            case 'ticket_save':
                self::need($mods, 'tickets', 'mine');
                self::ok(self::ticketSave($u, $mods));
            case 'ticket_assign':
                self::need($mods, 'tickets', 'manage');
                self::ok(self::ticketAssign($u));
            case 'ticket_action':
                self::need($mods, 'tickets', 'mine');
                self::ok(self::ticketAction($u, $mods));

            case 'installs':
                self::need($mods, 'installations', 'mine');
                self::ok(['installations' => self::installs($mods, $uid)]);
            case 'install':
                self::need($mods, 'installations', 'mine');
                self::ok(['installation' => self::installDetail($mods, $uid)]);
            case 'install_assign':
                self::need($mods, 'installations', 'manage');
                self::ok(self::installAssign($u));
            case 'install_start':
                self::need($mods, 'installations', 'mine');
                self::ok(self::installStart($u, $mods));

            case 'store_stock':
                self::need($mods, 'store', 'mine');
                self::ok(self::storeStock($mods, $uid));
            case 'store_requests':
                self::need($mods, 'store', 'mine');
                self::ok(['requests' => self::storeRequests($mods, $uid)]);
            case 'store_request':
                self::need($mods, 'store', 'mine');
                self::ok(self::storeRequestCreate($u, $mods));
            case 'store_request_decide':
                self::need($mods, 'store', 'manage');
                self::ok(self::storeRequestDecide($u));
            case 'store_receive':
                self::need($mods, 'store', 'manage');
                self::ok(self::storeReceive($u));
            case 'store_use':
                self::need($mods, 'store', 'mine');
                self::ok(self::storeUse($u, $mods));

            case 'staff':
                self::need($mods, 'staff', 'view');
                self::ok(['staff' => self::staffList()]);

            case 'access_get':
                self::need($mods, 'app_access', 'manage');
                self::ok(self::accessGet());
            case 'access_set':
                self::need($mods, 'app_access', 'manage');
                self::ok(self::accessSet($u));
            case 'access_reset':
                self::need($mods, 'app_access', 'manage');
                self::ok(self::accessReset());
            case 'staff_roles_set':
                self::need($mods, 'app_access', 'manage');
                self::ok(self::staffRolesSet($u));

            case 'hr_me':
                self::need($mods, 'hr_self', 'mine');
                self::ok(self::hrMe($uid));
            case 'hr_clock':
                self::need($mods, 'hr_self', 'mine');
                self::ok(self::hrClock($u));
            case 'hr_leave_request':
                self::need($mods, 'hr_self', 'mine');
                self::ok(self::hrLeaveRequest($u));

            case 'hr_employees':
                self::need($mods, 'hr_manage', 'view');
                self::ok(self::hrEmployees());
            case 'hr_attendance':
                self::need($mods, 'hr_manage', 'view');
                self::ok(self::hrAttendance());
            case 'hr_leave_list':
                self::need($mods, 'hr_manage', 'view');
                self::ok(self::hrLeaveList());
            case 'hr_leave_decide':
                self::need($mods, 'hr_manage', 'manage');
                self::ok(self::hrLeaveDecide($u));
            case 'hr_overtime_list':
                self::need($mods, 'hr_manage', 'view');
                self::ok(self::hrOvertimeList());
            case 'hr_overtime_decide':
                self::need($mods, 'hr_manage', 'manage');
                self::ok(self::hrOvertimeDecide($u));
            case 'hr_payroll':
                self::need($mods, 'hr_payroll', 'view');
                self::ok(self::hrPayroll());
        }
        self::fail('Unknown action', 404);
    }

    /* ------------------------------------------------------------------ */
    /* dashboard                                                           */
    /* ------------------------------------------------------------------ */

    private static function scopeLeads($mods, $uid)
    {
        return self::lvl($mods, 'leads') >= 2 ? ['1=1', []] : ['created_by = ?', [$uid]];
    }

    private static function scopeTickets($mods, $uid)
    {
        return self::lvl($mods, 'tickets') >= 2 ? ['1=1', []] : ['(assigned_to = ? OR created_by = ?)', [$uid, $uid]];
    }

    private static function scopeInstalls($mods, $uid)
    {
        return self::lvl($mods, 'installations') >= 2 ? ['1=1', []] : ['technician_id = ?', [$uid]];
    }

    private static function countBy($table, $col, $where, $p)
    {
        $out = [];
        foreach (self::rows("SELECT $col AS k, COUNT(*) AS c FROM $table WHERE $where GROUP BY $col", $p) as $r) {
            $out[(string) $r['k']] = (int) $r['c'];
        }
        return $out;
    }

    private static function dashboard($u, $mods)
    {
        $uid = (int) $u['id'];
        $d = [];

        if (self::lvl($mods, 'payments') >= 2) {
            $sum = "SELECT COALESCE(SUM(CAST(price AS DECIMAL(15,2))),0) AS s FROM tbl_transactions WHERE ";
            $d['money'] = [
                'today' => (float) self::row($sum . "recharged_on = ?", [date('Y-m-d')])['s'],
                'month' => (float) self::row($sum . "DATE_FORMAT(recharged_on,'%Y-%m') = ?", [date('Y-m')])['s'],
            ];
        }
        if (self::lvl($mods, 'customers') >= 2) {
            $d['customers'] = [
                'total' => (int) self::row("SELECT COUNT(*) AS c FROM tbl_customers")['c'],
                'active_plans' => (int) self::row("SELECT COUNT(DISTINCT customer_id) AS c FROM tbl_user_recharges WHERE status = 'on'")['c'],
            ];
        }
        if (self::lvl($mods, 'leads') >= 1) {
            list($w, $p) = self::scopeLeads($mods, $uid);
            $d['leads'] = self::countBy('tbl_leads', 'status', $w, $p);
        }
        if (self::lvl($mods, 'tickets') >= 1) {
            list($w, $p) = self::scopeTickets($mods, $uid);
            $d['tickets'] = self::countBy('tbl_support_tickets', 'status', $w, $p);
            if (self::lvl($mods, 'tickets') >= 3) {
                $d['unassigned_tickets'] = (int) self::row(
                    "SELECT COUNT(*) AS c FROM tbl_support_tickets WHERE (assigned_to IS NULL OR assigned_to = 0) AND status <> 'closed'"
                )['c'];
            }
        }
        if (self::lvl($mods, 'installations') >= 1) {
            list($w, $p) = self::scopeInstalls($mods, $uid);
            $d['installations'] = self::countBy('tbl_installations', 'status', $w, $p);
            if (self::lvl($mods, 'installations') >= 3) {
                $d['unassigned_installs'] = (int) self::row(
                    "SELECT COUNT(*) AS c FROM tbl_installations WHERE technician_id IS NULL AND status NOT IN ('completed','cancelled')"
                )['c'];
            }
        }
        if (self::lvl($mods, 'store') >= 2) {
            $d['store'] = [
                'pending_requests' => (int) self::row("SELECT COUNT(*) AS c FROM tbl_store_requests WHERE status = 'pending'")['c'],
                'low_stock' => (int) self::row(
                    "SELECT COUNT(*) AS c FROM tbl_store_items i WHERE i.active = 1 AND i.min_stock > 0 AND
                     (SELECT COALESCE(SUM(m.qty),0) FROM tbl_store_moves m WHERE m.item_id = i.id AND m.holder_id = 0) < i.min_stock"
                )['c'],
            ];
        }
        if (self::lvl($mods, 'hr_self') >= 1) {
            $emp = self::employeeOf($uid);
            if ($emp) {
                $d['attendance_today'] = self::row(
                    "SELECT clock_in, clock_out, status FROM tbl_hr_attendance WHERE employee_id = ? AND work_date = ?",
                    [$emp['id'], date('Y-m-d')]
                );
            }
        }
        if (self::lvl($mods, 'hr_manage') >= 2) {
            $d['hr_pending'] = [
                'leave' => (int) self::row("SELECT COUNT(*) AS c FROM tbl_hr_leave_requests WHERE status = 'Pending'")['c'],
                'overtime' => (int) self::row("SELECT COUNT(*) AS c FROM tbl_hr_overtime WHERE status = 'Pending'")['c'],
            ];
        }
        return ['dashboard' => $d];
    }

    /* ------------------------------------------------------------------ */
    /* customers / plans / payments                                        */
    /* ------------------------------------------------------------------ */

    private static function customers($mods, $uid)
    {
        $where = ['1=1'];
        $p = [];
        if (self::lvl($mods, 'customers') < 2) {
            $where[] = "(c.created_by = ? OR c.id IN (SELECT customer_id FROM tbl_installations WHERE technician_id = ? AND customer_id IS NOT NULL))";
            array_push($p, $uid, $uid);
        }
        $q = self::str('q', 60);
        if ($q !== '') {
            $where[] = "(c.username LIKE ? OR c.fullname LIKE ? OR c.phonenumber LIKE ? OR c.pppoe_username LIKE ?)";
            $like = '%' . $q . '%';
            array_push($p, $like, $like, $like, $like);
        }
        $st = self::str('status', 20);
        if ($st !== '') {
            $where[] = "c.status = ?";
            $p[] = $st;
        }
        return self::rows(
            "SELECT c.id, c.username, c.fullname, c.phonenumber, c.email, c.service_type, c.status, c.balance, c.address, c.pppoe_username,
                    (SELECT r.namebp FROM tbl_user_recharges r WHERE r.customer_id = c.id AND r.status = 'on' ORDER BY r.id DESC LIMIT 1) AS plan,
                    (SELECT r.expiration FROM tbl_user_recharges r WHERE r.customer_id = c.id AND r.status = 'on' ORDER BY r.id DESC LIMIT 1) AS expires
             FROM tbl_customers c WHERE " . implode(' AND ', $where) . " ORDER BY c.id DESC" . self::page(),
            $p
        );
    }

    private static function customerDetail($mods, $uid)
    {
        $id = self::intv('id');
        $visible = self::customers($mods, $uid);
        $ok = false;
        foreach (self::rows("SELECT id FROM tbl_customers WHERE id = ?", [$id]) as $_) {
            if (self::lvl($mods, 'customers') >= 2) {
                $ok = true;
            } else {
                $ok = (bool) self::row(
                    "SELECT 1 AS x FROM tbl_customers c WHERE c.id = ? AND (c.created_by = ? OR c.id IN
                     (SELECT customer_id FROM tbl_installations WHERE technician_id = ? AND customer_id IS NOT NULL))",
                    [$id, $uid, $uid]
                );
            }
        }
        if (!$ok) {
            self::fail('Customer not found', 404);
        }
        $c = self::row(
            "SELECT id, username, fullname, phonenumber, email, address, city, coordinates, service_type, account_type, status, balance,
                    pppoe_username, pppoe_password, created_at, last_login
             FROM tbl_customers WHERE id = ?",
            [$id]
        );
        if (self::lvl($mods, 'customers') < 3) {
            unset($c['pppoe_password']);
        }
        $c['recharges'] = self::rows(
            "SELECT namebp, recharged_on, expiration, status, method, type FROM tbl_user_recharges WHERE customer_id = ? ORDER BY id DESC LIMIT 10",
            [$id]
        );
        if (self::lvl($mods, 'payments') >= 2) {
            $c['transactions'] = self::rows(
                "SELECT invoice, plan_name, price, recharged_on, method, type FROM tbl_transactions WHERE user_id = ? ORDER BY id DESC LIMIT 10",
                [$id]
            );
        }
        return $c;
    }

    private static function payments()
    {
        $from = self::str('from', 10) ?: date('Y-m-01');
        $to = self::str('to', 10) ?: date('Y-m-d');
        $tx = self::rows(
            "SELECT id, invoice, username, plan_name, price, recharged_on, recharged_time, method, type
             FROM tbl_transactions WHERE recharged_on BETWEEN ? AND ? ORDER BY id DESC" . self::page(),
            [$from, $to]
        );
        $tot = self::row(
            "SELECT COALESCE(SUM(CAST(price AS DECIMAL(15,2))),0) AS total, COUNT(*) AS n FROM tbl_transactions WHERE recharged_on BETWEEN ? AND ?",
            [$from, $to]
        );
        $mp = self::rows(
            "SELECT TransID, TransactionType, TransTime, TransAmount, BillRefNumber, FirstName
             FROM tbl_mpesa_transactions ORDER BY id DESC LIMIT 30"
        );
        return ['from' => $from, 'to' => $to, 'total' => (float) $tot['total'], 'count' => (int) $tot['n'], 'transactions' => $tx, 'mpesa' => $mp];
    }

    /* ------------------------------------------------------------------ */
    /* leads                                                               */
    /* ------------------------------------------------------------------ */

    private static function leadMeta()
    {
        return [
            'reasons' => self::rows("SELECT id, reason FROM tbl_lead_reasons ORDER BY reason"),
            'plans' => self::rows("SELECT id, name_plan, price, type FROM tbl_plans WHERE enabled = 1 AND type = 'PPPOE' ORDER BY name_plan"),
            'locations' => self::rows("SELECT id, name FROM tbl_lead_locations ORDER BY name"),
            'sublocations' => self::rows("SELECT id, location_id, name FROM tbl_lead_sublocations ORDER BY name"),
            'technicians' => self::techs(),
        ];
    }

    private static function leads($mods, $uid)
    {
        list($w, $p) = self::scopeLeads($mods, $uid);
        $where = [$w];
        $st = self::str('status', 30);
        if ($st !== '') {
            $where[] = "status = ?";
            $p[] = $st;
        }
        $q = self::str('q', 60);
        if ($q !== '') {
            $where[] = "(client_name LIKE ? OR phone LIKE ? OR lead_no LIKE ?)";
            $like = '%' . $q . '%';
            array_push($p, $like, $like, $like);
        }
        return self::rows(
            "SELECT id, lead_no, client_name, email, phone, location, sub_location, plan_id, plan_name, status, reason_text, comment,
                    created_by_name, actioned_by_name, created_at, actioned_at, job_id, job_no
             FROM tbl_leads WHERE " . implode(' AND ', $where) . " ORDER BY id DESC" . self::page(),
            $p
        );
    }

    private static function leadSave($u)
    {
        $name = self::str('client_name', 150);
        $phone = self::str('phone', 50);
        $loc = self::str('location', 150);
        if ($name === '' || $phone === '' || $loc === '') {
            self::fail('Name, phone and location are required');
        }
        $planId = self::intv('plan_id');
        $planName = null;
        if ($planId) {
            $pl = self::row("SELECT name_plan FROM tbl_plans WHERE id = ?", [$planId]);
            $planName = $pl ? $pl['name_plan'] : null;
            if (!$pl) {
                $planId = null;
            }
        }
        $now = self::now();
        self::run(
            "INSERT INTO tbl_leads (client_name, email, phone, location, sub_location, plan_id, plan_name, sla_days, status, comment,
                                    created_by, created_by_name, created_at, updated_at, location_id, sublocation_id)
             VALUES (?,?,?,?,?,?,?,3,'new',?,?,?,?,?,?,?)",
            [
                $name, self::str('email', 150) ?: null, $phone, $loc, self::str('sub_location', 150) ?: null,
                $planId ?: null, $planName, self::str('comment', 1000), $u['id'], $u['fullname'], $now, $now,
                self::intv('location_id') ?: null, self::intv('sublocation_id') ?: null,
            ]
        );
        $id = self::lastId();
        $no = sprintf('LDS%05d', $id);
        self::run("UPDATE tbl_leads SET lead_no = ? WHERE id = ?", [$no, $id]);
        return ['id' => $id, 'lead_no' => $no];
    }

    private static function leadQualify($u)
    {
        $id = self::intv('id');
        $lead = self::row("SELECT * FROM tbl_leads WHERE id = ?", [$id]);
        if (!$lead) {
            self::fail('Lead not found', 404);
        }
        if ($lead['status'] !== 'new') {
            self::fail('Only new leads can be qualified');
        }
        $tech = self::intv('technician_id') ?: null;
        if ($tech && !self::isTech($tech)) {
            self::fail('That user is not a technician');
        }
        $sched = self::str('scheduled_at', 19) ?: null;
        $now = self::now();
        $due = date('Y-m-d H:i:s', time() + 48 * 3600);

        $db = self::db();
        $db->beginTransaction();
        try {
            self::run(
                "INSERT INTO tbl_installations (job_no, lead_id, lead_no, customer_name, phone, email, location, sub_location, plan_id, plan_name,
                    technician_id, status, scheduled_at, sla_hours, due_at, created_by, created_at, updated_at, amount_paid, pay_status, activate_plan)
                 VALUES (?,?,?,?,?,?,?,?,?,?,?,'pending',?,48,?,?,?,?,0,'none',1)",
                [
                    'TMP' . bin2hex(random_bytes(6)), $lead['id'], $lead['lead_no'], $lead['client_name'], $lead['phone'], $lead['email'],
                    $lead['location'], $lead['sub_location'], $lead['plan_id'], $lead['plan_name'], $tech, $sched, $due,
                    $u['id'], $now, $now,
                ]
            );
            $jid = self::lastId();
            $jno = sprintf('INS%05d', $jid);
            self::run("UPDATE tbl_installations SET job_no = ? WHERE id = ?", [$jno, $jid]);
            self::run(
                "UPDATE tbl_leads SET status = 'pending_installation', job_id = ?, job_no = ?, actioned_by_name = ?, actioned_at = ?, updated_at = ? WHERE id = ?",
                [$jid, $jno, $u['fullname'], $now, $now, $lead['id']]
            );
            $db->commit();
        } catch (Throwable $e) {
            $db->rollBack();
            throw $e;
        }
        return ['job_id' => $jid, 'job_no' => $jno];
    }

    private static function leadDisqualify($u)
    {
        $id = self::intv('id');
        $lead = self::row("SELECT id, status FROM tbl_leads WHERE id = ?", [$id]);
        if (!$lead) {
            self::fail('Lead not found', 404);
        }
        if ($lead['status'] !== 'new') {
            self::fail('Only new leads can be disqualified');
        }
        $r = self::row("SELECT id, reason FROM tbl_lead_reasons WHERE id = ?", [self::intv('reason_id')]);
        if (!$r) {
            self::fail('Pick a reason');
        }
        $now = self::now();
        self::run(
            "UPDATE tbl_leads SET status = 'disqualified', reason_id = ?, reason_text = ?, comment = ?, actioned_by_name = ?, actioned_at = ?, updated_at = ? WHERE id = ?",
            [$r['id'], $r['reason'], self::str('comment', 1000), $u['fullname'], $now, $now, $id]
        );
        return [];
    }

    /* ------------------------------------------------------------------ */
    /* technicians, tickets, installations                                 */
    /* ------------------------------------------------------------------ */

    private static function techs()
    {
        return self::rows(
            "SELECT u.id, u.fullname, u.username, u.phone FROM tbl_users u
             JOIN tbl_ops_techs t ON t.user_id = u.id WHERE u.status = 'Active' ORDER BY u.fullname"
        );
    }

    private static function tickets($mods, $uid)
    {
        list($w, $p) = self::scopeTickets($mods, $uid);
        $where = [$w];
        $st = self::str('status', 25);
        if ($st !== '') {
            $where[] = "t.status = ?";
            $p[] = $st;
        }
        $as = self::intv('assigned_to');
        if ($as && self::lvl($mods, 'tickets') >= 2) {
            $where[] = "t.assigned_to = ?";
            $p[] = $as;
        }
        $where[0] = str_replace(['assigned_to', 'created_by'], ['t.assigned_to', 't.created_by'], $where[0]);
        return self::rows(
            "SELECT t.id, t.title, t.priority, t.status, t.assigned_to, t.created_at, t.updated_at, t.job_no,
                    c.name AS category, a.fullname AS assigned_name
             FROM tbl_support_tickets t
             LEFT JOIN tbl_support_categories c ON c.id = t.category_id
             LEFT JOIN tbl_users a ON a.id = t.assigned_to
             WHERE " . implode(' AND ', $where) . " ORDER BY t.id DESC" . self::page(),
            $p
        );
    }

    private static function ticketRow($mods, $uid, $id)
    {
        $t = self::row("SELECT * FROM tbl_support_tickets WHERE id = ?", [$id]);
        if (!$t) {
            self::fail('Ticket not found', 404);
        }
        if (self::lvl($mods, 'tickets') < 2 && (int) $t['assigned_to'] !== $uid && (int) $t['created_by'] !== $uid) {
            self::fail('Ticket not found', 404);
        }
        return $t;
    }

    private static function ticketDetail($mods, $uid)
    {
        $t = self::ticketRow($mods, $uid, self::intv('id'));
        $cat = self::row("SELECT name FROM tbl_support_categories WHERE id = ?", [$t['category_id']]);
        $t['category'] = $cat ? $cat['name'] : null;
        return $t;
    }

    private static function logLine($name, $text)
    {
        return date('Y-m-d H:i') . ' - ' . $name . ' ' . $text;
    }

    private static function appendLog($id, $line)
    {
        self::run(
            "UPDATE tbl_support_tickets SET log = CONCAT(COALESCE(log,''), CASE WHEN COALESCE(log,'') = '' THEN '' ELSE CHAR(10) END, ?), updated_at = ? WHERE id = ?",
            [$line, self::now(), $id]
        );
    }

    private static function ticketSave($u, $mods)
    {
        $title = self::str('title', 200);
        if ($title === '') {
            self::fail('Title is required');
        }
        $prio = in_array(self::str('priority', 10), ['Low', 'Medium', 'High'], true) ? self::str('priority', 10) : 'Medium';
        $assign = 0;
        if (self::lvl($mods, 'tickets') >= 3 && self::intv('assigned_to')) {
            $assign = self::intv('assigned_to');
        }
        $now = self::now();
        self::run(
            "INSERT INTO tbl_support_tickets (title, description, category_id, priority, status, assigned_to, created_by, created_at, updated_at, log)
             VALUES (?,?,?,?,'open',?,?,?,?,?)",
            [$title, self::str('description', 2000), self::intv('category_id'), $prio, $assign, $u['id'], $now, $now,
             self::logLine($u['fullname'], 'created ticket')]
        );
        $id = self::lastId();
        if ($assign) {
            $a = self::row("SELECT fullname FROM tbl_users WHERE id = ?", [$assign]);
            self::appendLog($id, self::logLine($u['fullname'], 'assigned to ' . ($a ? $a['fullname'] : '#' . $assign)));
        }
        return ['id' => $id];
    }

    private static function ticketAssign($u)
    {
        $id = self::intv('id');
        $to = self::intv('assigned_to');
        $t = self::row("SELECT id FROM tbl_support_tickets WHERE id = ?", [$id]);
        $a = self::row("SELECT id, fullname FROM tbl_users WHERE id = ? AND status = 'Active'", [$to]);
        if (!$t || !$a) {
            self::fail('Ticket or user not found', 404);
        }
        self::run("UPDATE tbl_support_tickets SET assigned_to = ? WHERE id = ?", [$to, $id]);
        self::appendLog($id, self::logLine($u['fullname'], 'assigned to ' . $a['fullname']));
        return [];
    }

    private static function ticketAction($u, $mods)
    {
        $uid = (int) $u['id'];
        $t = self::ticketRow($mods, $uid, self::intv('id'));
        $act = self::str('action', 12);
        $manage = self::lvl($mods, 'tickets') >= 3;
        $mine = (int) $t['assigned_to'] === $uid;
        if (!$manage && !$mine) {
            self::fail('Only the assigned person can do this', 403);
        }
        $now = self::now();
        switch ($act) {
            case 'start':
                self::run("UPDATE tbl_support_tickets SET status = 'in_progress' WHERE id = ?", [$t['id']]);
                self::appendLog($t['id'], self::logLine($u['fullname'], 'started work'));
                break;
            case 'resolve':
                self::run(
                    "UPDATE tbl_support_tickets SET status = 'resolved', solution = ?, resolved_at = ?, resolved_by = ? WHERE id = ?",
                    [self::str('solution', 1000), $now, $uid, $t['id']]
                );
                self::appendLog($t['id'], self::logLine($u['fullname'], 'marked resolved (awaiting verification)'));
                break;
            case 'close':
                if (!$manage) {
                    self::fail('Only a manager can close tickets', 403);
                }
                self::run("UPDATE tbl_support_tickets SET status = 'closed' WHERE id = ?", [$t['id']]);
                self::appendLog($t['id'], self::logLine($u['fullname'], 'verified and closed'));
                break;
            case 'reopen':
                if (!$manage) {
                    self::fail('Only a manager can reopen tickets', 403);
                }
                self::run("UPDATE tbl_support_tickets SET status = 'open' WHERE id = ?", [$t['id']]);
                self::appendLog($t['id'], self::logLine($u['fullname'], 'reopened ticket'));
                break;
            default:
                self::fail('Unknown ticket action');
        }
        return [];
    }

    private static function installs($mods, $uid)
    {
        list($w, $p) = self::scopeInstalls($mods, $uid);
        $where = [$w];
        $st = self::str('status', 20);
        if ($st !== '') {
            $where[] = "i.status = ?";
            $p[] = $st;
        }
        $tech = self::intv('technician_id');
        if ($tech && self::lvl($mods, 'installations') >= 2) {
            $where[] = "i.technician_id = ?";
            $p[] = $tech;
        }
        $where[0] = str_replace('technician_id', 'i.technician_id', $where[0]);
        return self::rows(
            "SELECT i.id, i.job_no, i.customer_name, i.phone, i.location, i.sub_location, i.plan_name, i.status, i.technician_id,
                    u.fullname AS technician_name, i.scheduled_at, i.due_at, i.started_at, i.completed_at, i.pay_status, i.amount_due, i.amount_paid
             FROM tbl_installations i LEFT JOIN tbl_users u ON u.id = i.technician_id
             WHERE " . implode(' AND ', $where) . " ORDER BY i.id DESC" . self::page(),
            $p
        );
    }

    private static function installRow($mods, $uid, $id)
    {
        $i = self::row("SELECT * FROM tbl_installations WHERE id = ?", [$id]);
        if (!$i || (self::lvl($mods, 'installations') < 2 && (int) $i['technician_id'] !== $uid)) {
            self::fail('Installation not found', 404);
        }
        return $i;
    }

    private static function installDetail($mods, $uid)
    {
        $i = self::installRow($mods, $uid, self::intv('id'));
        if (self::lvl($mods, 'installations') < 2 && (int) $i['technician_id'] !== $uid) {
            unset($i['pppoe_password']);
        }
        return $i;
    }

    private static function installAssign($u)
    {
        $id = self::intv('id');
        $to = self::intv('technician_id');
        $i = self::row("SELECT id, status FROM tbl_installations WHERE id = ?", [$id]);
        if (!$i) {
            self::fail('Installation not found', 404);
        }
        if (in_array($i['status'], ['completed', 'cancelled'], true)) {
            self::fail('This job is already ' . $i['status']);
        }
        if ($to && !self::isTech($to)) {
            self::fail('That user is not a technician');
        }
        self::run("UPDATE tbl_installations SET technician_id = ?, updated_at = ? WHERE id = ?", [$to ?: null, self::now(), $id]);
        return [];
    }

    /** Technicians can start a job. Completing is deliberately NOT possible here (payment gate stays in the panel). */
    private static function installStart($u, $mods)
    {
        $uid = (int) $u['id'];
        $i = self::installRow($mods, $uid, self::intv('id'));
        if (self::lvl($mods, 'installations') < 3 && (int) $i['technician_id'] !== $uid) {
            self::fail('This job is not assigned to you', 403);
        }
        if (!in_array($i['status'], ['pending', 'in_progress'], true)) {
            self::fail('This job cannot be started (status: ' . $i['status'] . ')');
        }
        $now = self::now();
        self::run(
            "UPDATE tbl_installations SET status = 'in_progress', started_at = COALESCE(started_at, ?), updated_at = ? WHERE id = ?",
            [$now, $now, $i['id']]
        );
        return [];
    }

    /* ------------------------------------------------------------------ */
    /* staff + app access administration                                   */
    /* ------------------------------------------------------------------ */

    private static function staffList()
    {
        $users = self::rows("SELECT id, username, fullname, user_type, status, phone, email, last_login FROM tbl_users ORDER BY id");
        foreach ($users as &$u) {
            $u['roles'] = array_map(function ($r) {
                return ['id' => (int) $r['id'], 'name' => $r['name']];
            }, self::roleRows((int) $u['id']));
            $u['is_technician'] = self::isTech((int) $u['id']);
        }
        return $users;
    }

    private static function accessGet()
    {
        $cfg = [];
        foreach (self::rows("SELECT scope, ref, modules FROM tbl_mobile_access") as $r) {
            $cfg[] = ['scope' => $r['scope'], 'ref' => $r['ref'], 'modules' => json_decode($r['modules'], true) ?: new stdClass()];
        }
        $cat = [];
        foreach (self::$MODULES as $k => $v) {
            $cat[] = ['key' => $k, 'label' => $v[0], 'group' => $v[1]];
        }
        return [
            'modules' => $cat,
            'levels' => array_keys(self::$LEVELS),
            'user_types' => ['Admin', 'Report', 'Agent', 'Sales'],
            'roles' => self::rows("SELECT id, name, description FROM tbl_staff_roles ORDER BY name"),
            'staff' => self::staffList(),
            'config' => $cfg,
        ];
    }

    private static function accessSet($u)
    {
        $scope = self::str('scope', 10);
        $ref = self::str('ref', 60);
        $mods = self::in('modules', []);
        if (!in_array($scope, ['type', 'role', 'user'], true) || $ref === '' || !is_array($mods)) {
            self::fail('scope, ref and modules are required');
        }
        if ($scope === 'role' && !self::row("SELECT id FROM tbl_staff_roles WHERE id = ?", [(int) $ref])) {
            self::fail('Role not found', 404);
        }
        if ($scope === 'user' && !self::row("SELECT id FROM tbl_users WHERE id = ?", [(int) $ref])) {
            self::fail('User not found', 404);
        }
        if ($scope === 'type' && !in_array($ref, ['Admin', 'Report', 'Agent', 'Sales'], true)) {
            self::fail('Unknown user type');
        }
        $clean = [];
        foreach ($mods as $k => $lvl) {
            if (!isset(self::$MODULES[$k]) || !is_string($lvl) || !isset(self::$LEVELS[$lvl])) {
                continue;
            }
            if ($k === 'app_access' && $u['user_type'] !== 'SuperAdmin') {
                continue; // only SuperAdmin can hand out access control
            }
            if ($lvl === 'none' && $scope !== 'user') {
                continue;
            }
            $clean[$k] = $lvl;
        }
        self::run(
            "INSERT INTO tbl_mobile_access (scope, ref, modules, updated_by, updated_at) VALUES (?,?,?,?,?)
             ON DUPLICATE KEY UPDATE modules = VALUES(modules), updated_by = VALUES(updated_by), updated_at = VALUES(updated_at)",
            [$scope, $ref, json_encode($clean ?: new stdClass()), $u['id'], self::now()]
        );
        return ['saved' => $clean];
    }

    private static function accessReset()
    {
        $scope = self::str('scope', 10);
        $ref = self::str('ref', 60);
        self::run("DELETE FROM tbl_mobile_access WHERE scope = ? AND ref = ?", [$scope, $ref]);
        return [];
    }

    /** Assign staff roles (Network Technician, Sales Agent, ...) and the technician flag. Shared with the web panel. */
    private static function staffRolesSet($u)
    {
        $target = self::intv('user_id');
        $t = self::row("SELECT id, user_type FROM tbl_users WHERE id = ?", [$target]);
        if (!$t) {
            self::fail('User not found', 404);
        }
        if ($t['user_type'] === 'SuperAdmin' && $u['user_type'] !== 'SuperAdmin') {
            self::fail('Only a SuperAdmin can change a SuperAdmin', 403);
        }
        $ids = self::in('role_ids', null);
        if (is_array($ids)) {
            $db = self::db();
            $db->beginTransaction();
            try {
                self::run("DELETE FROM tbl_staff_user_roles WHERE user_id = ?", [$target]);
                foreach (array_unique(array_map('intval', $ids)) as $rid) {
                    if (self::row("SELECT id FROM tbl_staff_roles WHERE id = ?", [$rid])) {
                        self::run("INSERT INTO tbl_staff_user_roles (user_id, role_id) VALUES (?,?)", [$target, $rid]);
                    }
                }
                $db->commit();
            } catch (Throwable $e) {
                $db->rollBack();
                throw $e;
            }
        }
        if (self::in('is_technician', null) !== null) {
            self::run("DELETE FROM tbl_ops_techs WHERE user_id = ?", [$target]);
            if (filter_var(self::in('is_technician'), FILTER_VALIDATE_BOOLEAN)) {
                self::run("INSERT INTO tbl_ops_techs (user_id) VALUES (?)", [$target]);
            }
        }
        return [];
    }


    /* ------------------------------------------------------------------ */
    /* store (ledger in tbl_store_moves: holder_id 0 = main store)         */
    /* ------------------------------------------------------------------ */

    private static function stockOf($item, $holder)
    {
        $r = self::row("SELECT COALESCE(SUM(qty),0) AS s FROM tbl_store_moves WHERE item_id = ? AND holder_id = ?", [$item, $holder]);
        return (float) $r['s'];
    }

    private static function parseItems()
    {
        $items = self::in('items', []);
        if (!is_array($items) || !$items) {
            self::fail('Add at least one item');
        }
        $out = [];
        foreach ($items as $it) {
            $id = (int) (isset($it['item_id']) ? $it['item_id'] : 0);
            $q = (float) (isset($it['qty']) ? $it['qty'] : 0);
            if ($id <= 0 || $q <= 0) {
                self::fail('Each item needs an item_id and a quantity above 0');
            }
            if (!self::row("SELECT id FROM tbl_store_items WHERE id = ? AND active = 1", [$id])) {
                self::fail("Item $id not found");
            }
            $out[] = ['item_id' => $id, 'qty' => $q, 'serial' => isset($it['serial']) ? mb_substr((string) $it['serial'], 0, 80) : null];
        }
        return $out;
    }

    private static function storeStock($mods, $uid)
    {
        $full = self::lvl($mods, 'store') >= 2;
        $items = self::rows("SELECT id, sku, name, category, unit, min_stock, unit_cost FROM tbl_store_items WHERE active = 1 ORDER BY name");
        $hold = [];
        foreach (self::rows("SELECT item_id, holder_id, SUM(qty) AS q FROM tbl_store_moves GROUP BY item_id, holder_id HAVING SUM(qty) <> 0") as $r) {
            $hold[(int) $r['item_id']][(int) $r['holder_id']] = (float) $r['q'];
        }
        $names = [];
        foreach (self::rows("SELECT id, fullname FROM tbl_users") as $r) {
            $names[(int) $r['id']] = $r['fullname'];
        }
        foreach ($items as &$i) {
            $iid = (int) $i['id'];
            $i['mine'] = isset($hold[$iid][$uid]) ? $hold[$iid][$uid] : 0;
            if ($full) {
                $i['in_store'] = isset($hold[$iid][0]) ? $hold[$iid][0] : 0;
                $i['low'] = (float) $i['min_stock'] > 0 && $i['in_store'] < (float) $i['min_stock'];
                $i['holders'] = [];
                $byHolder = isset($hold[$iid]) ? $hold[$iid] : [];
                foreach ($byHolder as $h => $q) {
                    if ($h > 0) {
                        $i['holders'][] = ['user_id' => $h, 'name' => isset($names[$h]) ? $names[$h] : '#' . $h, 'qty' => $q];
                    }
                }
            } else {
                unset($i['unit_cost']);
            }
        }
        unset($i);

        $kits = self::rows("SELECT id, name FROM tbl_store_kits ORDER BY name");
        foreach ($kits as &$k) {
            $k['items'] = self::rows("SELECT item_id, qty FROM tbl_store_kit_items WHERE kit_id = ?", [$k['id']]);
        }
        return ['items' => $items, 'kits' => $kits];
    }

    private static function storeRequests($mods, $uid)
    {
        $where = ['1=1'];
        $p = [];
        if (self::lvl($mods, 'store') < 2) {
            $where[] = "(r.tech_id = ? OR r.created_by = ?)";
            array_push($p, $uid, $uid);
        }
        $st = self::str('status', 12);
        if ($st !== '') {
            $where[] = "r.status = ?";
            $p[] = $st;
        }
        $reqs = self::rows(
            "SELECT r.id, r.req_no, r.tech_id, u.fullname AS tech_name, r.status, r.note, r.admin_note, r.created_at, r.decided_at
             FROM tbl_store_requests r LEFT JOIN tbl_users u ON u.id = r.tech_id
             WHERE " . implode(' AND ', $where) . " ORDER BY r.id DESC" . self::page(),
            $p
        );
        foreach ($reqs as &$r) {
            $r['items'] = self::rows(
                "SELECT ri.item_id, i.name, i.unit, ri.qty_req, ri.qty_ok FROM tbl_store_request_items ri
                 JOIN tbl_store_items i ON i.id = ri.item_id WHERE ri.request_id = ?",
                [$r['id']]
            );
        }
        return $reqs;
    }

    private static function storeRequestCreate($u, $mods)
    {
        $items = self::parseItems();
        $tech = (int) $u['id'];
        if (self::lvl($mods, 'store') >= 3 && self::intv('tech_id')) {
            $tech = self::intv('tech_id');
        }
        $db = self::db();
        $db->beginTransaction();
        try {
            self::run(
                "INSERT INTO tbl_store_requests (req_no, tech_id, status, note, created_by, created_at) VALUES (?,?,'pending',?,?,?)",
                ['TMP' . bin2hex(random_bytes(5)), $tech, self::str('note', 255), $u['id'], self::now()]
            );
            $id = self::lastId();
            $no = sprintf('REQ%05d', $id);
            self::run("UPDATE tbl_store_requests SET req_no = ? WHERE id = ?", [$no, $id]);
            foreach ($items as $it) {
                self::run("INSERT INTO tbl_store_request_items (request_id, item_id, qty_req) VALUES (?,?,?)", [$id, $it['item_id'], $it['qty']]);
            }
            $db->commit();
        } catch (Throwable $e) {
            $db->rollBack();
            throw $e;
        }
        return ['id' => $id, 'req_no' => $no];
    }

    /** Approve = move stock from main store (holder 0) to the technician, exactly like the web panel's issue rows. */
    private static function storeRequestDecide($u)
    {
        $id = self::intv('id');
        $dec = self::str('decision', 10);
        if (!in_array($dec, ['approve', 'reject'], true)) {
            self::fail("decision must be 'approve' or 'reject'");
        }
        $req = self::row("SELECT * FROM tbl_store_requests WHERE id = ?", [$id]);
        if (!$req) {
            self::fail('Request not found', 404);
        }
        if ($req['status'] !== 'pending') {
            self::fail('This request was already ' . $req['status']);
        }
        $now = self::now();
        if ($dec === 'reject') {
            self::run(
                "UPDATE tbl_store_requests SET status = 'rejected', admin_note = ?, decided_by = ?, decided_at = ? WHERE id = ?",
                [self::str('note', 255), $u['id'], $now, $id]
            );
            return ['status' => 'rejected'];
        }

        // optional per-item overrides: items = [{item_id, qty}]
        $override = [];
        $given = self::in('items', []);
        if (is_array($given)) {
            foreach ($given as $g) {
                if (isset($g['item_id'], $g['qty'])) {
                    $override[(int) $g['item_id']] = max(0, (float) $g['qty']);
                }
            }
        }

        $db = self::db();
        $db->beginTransaction();
        try {
            foreach (self::rows("SELECT * FROM tbl_store_request_items WHERE request_id = ?", [$id]) as $ri) {
                $iid = (int) $ri['item_id'];
                $qty = isset($override[$iid]) ? $override[$iid] : (float) $ri['qty_req'];
                if ($qty > 0) {
                    self::row("SELECT id FROM tbl_store_items WHERE id = ? FOR UPDATE", [$iid]);
                    $have = self::stockOf($iid, 0);
                    if ($have < $qty) {
                        $nm = self::row("SELECT name FROM tbl_store_items WHERE id = ?", [$iid]);
                        throw new RuntimeException('Not enough ' . $nm['name'] . ' in store (have ' . $have . ', need ' . $qty . ')');
                    }
                    self::run(
                        "INSERT INTO tbl_store_moves (item_id, holder_id, qty, kind, ref_type, ref_id, note, by_id, created_at) VALUES (?,0,?,'issue','request',?,?,?,?)",
                        [$iid, -$qty, $id, $req['req_no'], $u['id'], $now]
                    );
                    self::run(
                        "INSERT INTO tbl_store_moves (item_id, holder_id, qty, kind, ref_type, ref_id, note, by_id, created_at) VALUES (?,?,?,'issue','request',?,?,?,?)",
                        [$iid, $req['tech_id'], $qty, $id, $req['req_no'], $u['id'], $now]
                    );
                }
                self::run("UPDATE tbl_store_request_items SET qty_ok = ? WHERE id = ?", [$qty, $ri['id']]);
            }
            self::run(
                "UPDATE tbl_store_requests SET status = 'approved', admin_note = ?, decided_by = ?, decided_at = ? WHERE id = ?",
                [self::str('note', 255), $u['id'], $now, $id]
            );
            $db->commit();
        } catch (RuntimeException $e) {
            $db->rollBack();
            self::fail($e->getMessage());
        } catch (Throwable $e) {
            $db->rollBack();
            throw $e;
        }
        return ['status' => 'approved'];
    }

    private static function storeReceive($u)
    {
        $items = self::parseItems();
        $now = self::now();
        foreach ($items as $it) {
            self::run(
                "INSERT INTO tbl_store_moves (item_id, holder_id, qty, kind, serial, note, by_id, created_at) VALUES (?,0,?,'receive',?,?,?,?)",
                [$it['item_id'], $it['qty'], $it['serial'], self::str('note', 255) ?: null, $u['id'], $now]
            );
        }
        return [];
    }

    /** A technician records material used on an installation job, taken from their own holdings. */
    private static function storeUse($u, $mods)
    {
        $uid = (int) $u['id'];
        $job = self::row("SELECT id, technician_id FROM tbl_installations WHERE id = ?", [self::intv('job_id')]);
        if (!$job) {
            self::fail('Installation job not found', 404);
        }
        if (self::lvl($mods, 'store') < 3 && (int) $job['technician_id'] !== $uid) {
            self::fail('This job is not assigned to you', 403);
        }
        $items = self::parseItems();
        $now = self::now();
        $db = self::db();
        $db->beginTransaction();
        try {
            foreach ($items as $it) {
                self::row("SELECT id FROM tbl_store_items WHERE id = ? FOR UPDATE", [$it['item_id']]);
                $have = self::stockOf($it['item_id'], $uid);
                if ($have < $it['qty']) {
                    $nm = self::row("SELECT name FROM tbl_store_items WHERE id = ?", [$it['item_id']]);
                    throw new RuntimeException('You only hold ' . $have . ' of ' . $nm['name']);
                }
                self::run(
                    "INSERT INTO tbl_store_moves (item_id, holder_id, qty, kind, ref_type, ref_id, serial, note, by_id, created_at) VALUES (?,?,?,'use','job',?,?,?,?,?)",
                    [$it['item_id'], $uid, -$it['qty'], $job['id'], $it['serial'], self::str('note', 255) ?: null, $uid, $now]
                );
            }
            $db->commit();
        } catch (RuntimeException $e) {
            $db->rollBack();
            self::fail($e->getMessage());
        } catch (Throwable $e) {
            $db->rollBack();
            throw $e;
        }
        return [];
    }

    /* ------------------------------------------------------------------ */
    /* HRM                                                                 */
    /* ------------------------------------------------------------------ */

    private static function hrAudit($u, $action, $entity, $entityId, $details)
    {
        self::run(
            "INSERT INTO tbl_hr_audit_logs (actor_id, actor_name, action, entity_type, entity_id, details, ip_address, created_at) VALUES (?,?,?,?,?,?,?,?)",
            [$u['id'], $u['fullname'], $action, $entity, $entityId, $details, isset($_SERVER['REMOTE_ADDR']) ? $_SERVER['REMOTE_ADDR'] : null, self::now()]
        );
    }

    private static function requireEmployee($uid)
    {
        $e = self::employeeOf($uid);
        if (!$e) {
            self::fail('Your login is not linked to an HR employee record. Ask HR to link it.', 409);
        }
        return $e;
    }

    private static function hrMe($uid)
    {
        $e = self::requireEmployee($uid);
        $eid = (int) $e['id'];
        unset($e['bank_account'], $e['kra_pin'], $e['nssf_no'], $e['shif_no'], $e['national_id'], $e['basic_salary']);
        return [
            'employee' => $e,
            'work_start' => self::setting('hrm_work_start', '08:30'),
            'today' => self::row("SELECT work_date, clock_in, clock_out, status FROM tbl_hr_attendance WHERE employee_id = ? AND work_date = ?", [$eid, date('Y-m-d')]),
            'attendance' => self::rows("SELECT work_date, clock_in, clock_out, status FROM tbl_hr_attendance WHERE employee_id = ? ORDER BY work_date DESC LIMIT 20", [$eid]),
            'leave' => self::rows("SELECT id, leave_type, start_date, end_date, days, reason, status, decision_note, created_at FROM tbl_hr_leave_requests WHERE employee_id = ? ORDER BY id DESC LIMIT 20", [$eid]),
            'payslips' => self::rows(
                "SELECT period_month, basic_salary, allowances, deductions, overtime_pay, paye, nssf, shif, housing_levy, gross_pay, net_pay, status
                 FROM tbl_hr_payroll WHERE employee_id = ? AND status IN ('Approved','Paid') ORDER BY period_month DESC LIMIT 12",
                [$eid]
            ),
        ];
    }

    private static function hrClock($u)
    {
        $e = self::requireEmployee((int) $u['id']);
        $dir = self::str('direction', 3);
        $today = date('Y-m-d');
        $time = date('H:i:s');
        $now = self::now();
        $cur = self::row("SELECT * FROM tbl_hr_attendance WHERE employee_id = ? AND work_date = ?", [$e['id'], $today]);

        if ($dir === 'in') {
            if ($cur && $cur['clock_in']) {
                self::fail('You already clocked in today at ' . $cur['clock_in']);
            }
            $start = self::setting('hrm_work_start', '08:30') . ':00';
            $status = $time > substr($start, 0, 8) ? 'Late' : 'Present';
            self::run(
                "INSERT INTO tbl_hr_attendance (employee_id, work_date, clock_in, status, recorded_by, created_at) VALUES (?,?,?,?,?,?)",
                [$e['id'], $today, $time, $status, $u['id'], $now]
            );
            return ['status' => $status, 'clock_in' => $time];
        }
        if ($dir === 'out') {
            if (!$cur || !$cur['clock_in']) {
                self::fail('Clock in first');
            }
            if ($cur['clock_out']) {
                self::fail('You already clocked out today at ' . $cur['clock_out']);
            }
            self::run("UPDATE tbl_hr_attendance SET clock_out = ?, updated_at = ? WHERE id = ?", [$time, $now, $cur['id']]);
            return ['clock_out' => $time];
        }
        self::fail("direction must be 'in' or 'out'");
    }

    /** Working days between two dates: Mon-Fri, excluding holidays (recurring ones match on month-day). */
    private static function workingDays($from, $to)
    {
        $hol = [];
        foreach (self::rows("SELECT holiday_date, is_recurring FROM tbl_hr_holidays") as $h) {
            $hol[$h['is_recurring'] ? substr($h['holiday_date'], 5) : $h['holiday_date']] = true;
        }
        $n = 0;
        for ($t = strtotime($from); $t <= strtotime($to); $t += 86400) {
            if ((int) date('N', $t) >= 6) {
                continue;
            }
            if (isset($hol[date('Y-m-d', $t)]) || isset($hol[date('m-d', $t)])) {
                continue;
            }
            $n++;
        }
        return $n;
    }

    private static function validDate($d)
    {
        $x = DateTime::createFromFormat('Y-m-d', $d);
        return $x && $x->format('Y-m-d') === $d;
    }

    private static function hrLeaveRequest($u)
    {
        $e = self::requireEmployee((int) $u['id']);
        $from = self::str('start_date', 10);
        $to = self::str('end_date', 10);
        if (!self::validDate($from) || !self::validDate($to) || $to < $from) {
            self::fail('Enter valid start and end dates (YYYY-MM-DD)');
        }
        $type = self::str('leave_type', 60) ?: 'Annual Leave';
        $days = self::workingDays($from, $to);
        if ($days <= 0) {
            self::fail('That range has no working days');
        }
        self::run(
            "INSERT INTO tbl_hr_leave_requests (employee_id, leave_type, start_date, end_date, days, reason, status, requested_by, created_at)
             VALUES (?,?,?,?,?,?,'Pending',?,?)",
            [$e['id'], $type, $from, $to, $days, self::str('reason', 500), $u['id'], self::now()]
        );
        $id = self::lastId();
        self::hrAudit($u, 'leave.request', 'leave_request', $id, "$type $from to $to ($days days) via mobile app");
        return ['id' => $id, 'days' => $days];
    }

    private static function hrEmployees()
    {
        return [
            'employees' => self::rows(
                "SELECT e.id, e.employee_no, e.first_name, e.last_name, e.phone, e.email, e.job_title, e.employment_type, e.status, e.hire_date,
                        d.name AS department, e.admin_id
                 FROM tbl_hr_employees e LEFT JOIN tbl_hr_departments d ON d.id = e.department_id ORDER BY e.employee_no"
            ),
            'departments' => self::rows("SELECT id, name FROM tbl_hr_departments WHERE is_active = 1 ORDER BY name"),
            'holidays' => self::rows("SELECT name, holiday_date, is_recurring FROM tbl_hr_holidays ORDER BY holiday_date"),
        ];
    }

    private static function hrAttendance()
    {
        $d = self::str('date', 10);
        if (!self::validDate($d)) {
            $d = date('Y-m-d');
        }
        return [
            'date' => $d,
            'attendance' => self::rows(
                "SELECT e.id AS employee_id, e.employee_no, CONCAT(e.first_name,' ',e.last_name) AS name, a.clock_in, a.clock_out, a.status
                 FROM tbl_hr_employees e LEFT JOIN tbl_hr_attendance a ON a.employee_id = e.id AND a.work_date = ?
                 WHERE e.status = 'Active' ORDER BY e.employee_no",
                [$d]
            ),
        ];
    }

    private static function hrLeaveList()
    {
        $p = [];
        $w = '1=1';
        $st = self::str('status', 20);
        if ($st !== '') {
            $w = 'l.status = ?';
            $p[] = $st;
        }
        return ['leave' => self::rows(
            "SELECT l.id, l.employee_id, CONCAT(e.first_name,' ',e.last_name) AS name, l.leave_type, l.start_date, l.end_date, l.days,
                    l.reason, l.status, l.decided_by_name, l.decision_note, l.created_at
             FROM tbl_hr_leave_requests l JOIN tbl_hr_employees e ON e.id = l.employee_id
             WHERE $w ORDER BY l.id DESC" . self::page(),
            $p
        )];
    }

    private static function hrLeaveDecide($u)
    {
        $id = self::intv('id');
        $dec = self::str('decision', 10);
        if (!in_array($dec, ['approve', 'reject'], true)) {
            self::fail("decision must be 'approve' or 'reject'");
        }
        $l = self::row("SELECT id, status FROM tbl_hr_leave_requests WHERE id = ?", [$id]);
        if (!$l) {
            self::fail('Request not found', 404);
        }
        if ($l['status'] !== 'Pending') {
            self::fail('This request was already ' . strtolower($l['status']));
        }
        $status = $dec === 'approve' ? 'Approved' : 'Rejected';
        self::run(
            "UPDATE tbl_hr_leave_requests SET status = ?, decided_by = ?, decided_by_name = ?, decided_at = ?, decision_note = ? WHERE id = ?",
            [$status, $u['id'], $u['fullname'], self::now(), self::str('note', 255), $id]
        );
        self::hrAudit($u, 'leave.' . $dec, 'leave_request', $id, 'via mobile app');
        return ['status' => $status];
    }

    private static function hrOvertimeList()
    {
        $p = [];
        $w = '1=1';
        $st = self::str('status', 20);
        if ($st !== '') {
            $w = 'o.status = ?';
            $p[] = $st;
        }
        return ['overtime' => self::rows(
            "SELECT o.id, o.employee_id, CONCAT(e.first_name,' ',e.last_name) AS name, o.work_date, o.hours_worked, o.overtime_hours,
                    o.status, o.reason, o.decided_by_name, o.decision_note
             FROM tbl_hr_overtime o JOIN tbl_hr_employees e ON e.id = o.employee_id
             WHERE $w ORDER BY o.id DESC" . self::page(),
            $p
        )];
    }

    private static function hrOvertimeDecide($u)
    {
        $id = self::intv('id');
        $dec = self::str('decision', 10);
        if (!in_array($dec, ['approve', 'reject'], true)) {
            self::fail("decision must be 'approve' or 'reject'");
        }
        $o = self::row("SELECT id, status FROM tbl_hr_overtime WHERE id = ?", [$id]);
        if (!$o) {
            self::fail('Overtime record not found', 404);
        }
        if ($o['status'] !== 'Pending') {
            self::fail('This record was already ' . strtolower($o['status']));
        }
        $status = $dec === 'approve' ? 'Approved' : 'Rejected';
        self::run(
            "UPDATE tbl_hr_overtime SET status = ?, decided_by = ?, decided_by_name = ?, decided_at = ?, decision_note = ?, updated_at = ? WHERE id = ?",
            [$status, $u['id'], $u['fullname'], self::now(), self::str('note', 255), self::now(), $id]
        );
        self::hrAudit($u, 'overtime.' . $dec, 'overtime', $id, 'via mobile app');
        return ['status' => $status];
    }

    private static function hrPayroll()
    {
        $period = self::str('period', 7);
        if (!preg_match('/^\d{4}-\d{2}$/', $period)) {
            $period = date('Y-m');
        }
        return [
            'period' => $period,
            'payroll' => self::rows(
                "SELECT p.id, p.employee_id, CONCAT(e.first_name,' ',e.last_name) AS name, e.employee_no, p.basic_salary, p.allowances, p.deductions,
                        p.overtime_pay, p.paye, p.nssf, p.shif, p.housing_levy, p.gross_pay, p.net_pay, p.status
                 FROM tbl_hr_payroll p JOIN tbl_hr_employees e ON e.id = p.employee_id
                 WHERE p.period_month = ? ORDER BY e.employee_no",
                [$period]
            ),
        ];
    }
}

} // class_exists

/** PHPNuxBill calls this for  ?_route=plugin/mobile_api */
function mobile_api()
{
    try {
        EzMobileApi::handle();
    } catch (Throwable $e) {
        EzMobileApi::fail('Server error: ' . $e->getMessage(), 500);
    }
}
