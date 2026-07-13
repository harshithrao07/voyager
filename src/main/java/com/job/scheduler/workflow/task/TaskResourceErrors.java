package com.job.scheduler.workflow.task;

/**
 * The stable, public error vocabulary for Task resources. These names are a
 * contract: workflow authors match them in {@code Retry}/{@code Catch}, so they
 * must not change with internal refactors. Resources map their failures to one
 * of these instead of leaking Java exception class names.
 *
 * <p>ASL built-ins are reused where they apply:
 * <ul>
 *   <li>{@code States.Permissions} — authorization/trust failures (HTTP 401/403,
 *       MCP trust-level rejection). Not retryable; a workflow should catch or
 *       fail rather than retry.</li>
 *   <li>{@code States.TaskFailed} — generic/unclassified failure, and invalid
 *       Task arguments.</li>
 * </ul>
 */
public final class TaskResourceErrors {
    public static final String PERMISSIONS = "States.Permissions";
    public static final String TASK_FAILED = "States.TaskFailed";

    // voyager://webhook
    public static final String WEBHOOK_TIMEOUT = "Scheduler.Webhook.Timeout";
    public static final String WEBHOOK_CLIENT_ERROR =
            "Scheduler.Webhook.ClientError";
    public static final String WEBHOOK_SERVER_ERROR =
            "Scheduler.Webhook.ServerError";

    // voyager://send-email
    public static final String EMAIL_SEND_FAILED = "Scheduler.Email.SendFailed";

    // mcp://serverId/toolName
    public static final String MCP_TOOL_NOT_FOUND = "Mcp.ToolNotFound";
    public static final String MCP_TOOL_FAILED = "Mcp.ToolFailed";

    // voyager://namespace/name@version
    public static final String TIMEOUT = "States.Timeout";
    public static final String FUNCTION_NOT_FOUND = "Function.NotFound";
    public static final String FUNCTION_COMPILE_ERROR =
            "Function.CompileError";
    public static final String FUNCTION_RUNTIME_ERROR =
            "Function.RuntimeError";
    public static final String FUNCTION_MEMORY_EXCEEDED =
            "Function.MemoryExceeded";
    public static final String FUNCTION_INVALID_OUTPUT =
            "Function.InvalidOutput";
    public static final String FUNCTION_PLATFORM_ERROR =
            "Function.PlatformError";

    private TaskResourceErrors() {
    }
}
