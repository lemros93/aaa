<?php
// Suppress HTML errors — return JSON error instead
ini_set('display_errors', 0);
error_reporting(E_ALL);
set_exception_handler(function($e) {
    header('Content-Type: application/json');
    echo json_encode(['error' => $e->getMessage()]);
    exit;
});
set_error_handler(function($errno, $errstr) {
    header('Content-Type: application/json');
    echo json_encode(['error' => $errstr]);
    exit;
});

header('Content-Type: application/json');
require_once '../config/db.php';

$db      = getDb();
$action  = $_GET['action']  ?? 'runs';
$from    = $_GET['from']    ?? date('Y-m-d', strtotime('-14 days'));
$to      = $_GET['to']      ?? date('Y-m-d');
$project = $_GET['project'] ?? 'all';
$toEnd   = $to . ' 23:59:59';

$pf = $project !== 'all' ? 'AND r.project = :project' : '';

switch ($action) {

    // --- Runs list ---
    case 'runs':
        $sql = "SELECT r.id, r.project, r.run_at, r.build_number, r.branch,
                       r.total_scenarios, r.passed_scenarios, r.failed_scenarios,
                       ROUND(r.passed_scenarios/NULLIF(r.total_scenarios,0)*100,1) AS success_rate
                FROM runs r
                WHERE r.run_at BETWEEN :from AND :to $pf
                ORDER BY r.run_at DESC";
        $stmt = $db->prepare($sql);
        $stmt->bindValue(':from', $from);
        $stmt->bindValue(':to',   $toEnd);
        if ($project !== 'all') $stmt->bindValue(':project', $project);
        $stmt->execute();
        echo json_encode($stmt->fetchAll());
        break;

    // --- Features for a run ---
    case 'features':
        $runId = (int)($_GET['run_id'] ?? 0);
        $stmt = $db->prepare("SELECT id, name, file_path, status, duration_ms,
                                      total_scenarios, passed_scenarios, failed_scenarios
                               FROM features WHERE run_id = ? ORDER BY name");
        $stmt->execute([$runId]);
        echo json_encode($stmt->fetchAll());
        break;

    // --- Scenarios for a feature ---
    case 'scenarios':
        $featureId = (int)($_GET['feature_id'] ?? 0);
        $stmt = $db->prepare("SELECT id, name, status, line, duration_ms,
                                      total_steps, passed_steps, failed_steps, skipped_steps,
                                      hook_error_type, hook_error_message
                               FROM scenarios WHERE feature_id = ? ORDER BY line");
        $stmt->execute([$featureId]);
        echo json_encode($stmt->fetchAll());
        break;

    // --- Steps for a scenario ---
    case 'steps':
        $scenarioId = (int)($_GET['scenario_id'] ?? 0);
        $stmt = $db->prepare("SELECT id, keyword, name, translated_text, line, status,
                                      duration_ms, error_message, stack_trace,
                                      screenshot_url, failed_screenshot_url
                               FROM steps WHERE scenario_id = ? ORDER BY line");
        $stmt->execute([$scenarioId]);
        echo json_encode($stmt->fetchAll());
        break;

    default:
        echo json_encode(['error' => 'Unknown action']);
}
