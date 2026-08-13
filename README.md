String triggerKey = System.getenv("bamboo_triggerReason_key");

if (triggerKey == null || triggerKey.isBlank()) {
    System.setProperty("trigger.type", "unknown");
    System.setProperty("triggered.by",  "unknown");

} else if (triggerKey.contains("Manual")) {
    System.setProperty("trigger.type", "manual");
    // ManualBuildTriggerReason má userName
    String user = System.getenv("bamboo_ManualBuildTriggerReason_userName");
    System.setProperty("triggered.by", user != null ? user : "unknown");

} else if (triggerKey.contains("Scheduled")) {
    System.setProperty("trigger.type", "scheduled");
    System.setProperty("triggered.by",  "scheduler");

} else if (triggerKey.contains("Dependency")) {
    // Triggered by another plan
    String plan = System.getenv("bamboo_triggerReason_key");
    System.setProperty("trigger.type", "dependency");
    System.setProperty("triggered.by",  plan != null ? plan : "dependency");

} else if (triggerKey.contains("Remote")) {
    // Triggered via REST API
    System.setProperty("trigger.type", "remote");
    System.setProperty("triggered.by",  "api");

} else {
    // Fallback — ulož raw hodnotu nech vieme čo to bolo
    System.setProperty("trigger.type", triggerKey);
    System.setProperty("triggered.by",  "unknown");
}
