package com.job.scheduler.service;

import com.job.scheduler.dto.CreateWorkflowRequestDTO;
import com.job.scheduler.dto.FunctionDefinitionRequestDTO;
import com.job.scheduler.dto.FunctionDefinitionResponseDTO;
import com.job.scheduler.dto.FunctionLanguageDTO;
import com.job.scheduler.dto.FunctionVersionRequestDTO;
import com.job.scheduler.dto.FunctionVersionResponseDTO;
import com.job.scheduler.dto.WorkflowAiProposedFunctionDTO;
import com.job.scheduler.dto.WorkflowAiResourcePlanDTO;
import com.job.scheduler.dto.CreateWorkflowRevisionRequestDTO;
import com.job.scheduler.dto.UpdateWorkflowCanvasLayoutRequestDTO;
import com.job.scheduler.dto.UpdateWorkflowMetadataRequestDTO;
import com.job.scheduler.dto.WorkflowAiConversationDetailDTO;
import com.job.scheduler.dto.WorkflowAiConversationSummaryDTO;
import com.job.scheduler.dto.WorkflowAiMcpRequirementDTO;
import com.job.scheduler.dto.WorkflowAiMessageDTO;
import com.job.scheduler.dto.WorkflowAiResponseDTO;
import com.job.scheduler.dto.WorkflowAiSaveWorkflowRequestDTO;
import com.job.scheduler.dto.WorkflowAiTrustReviewDTO;
import com.job.scheduler.exception.WorkflowAiTrustConfirmationRequiredException;
import com.job.scheduler.dto.WorkflowAiSaveWorkflowResponseDTO;
import com.job.scheduler.dto.WorkflowDefinitionResponseDTO;
import com.job.scheduler.dto.WorkflowResponseDTO;
import com.job.scheduler.dto.WorkflowAiWorkspaceRequestDTO;
import com.job.scheduler.dto.WorkflowAiWorkspaceSettingsDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.entity.WorkflowAiConversation;
import com.job.scheduler.entity.WorkflowAiMessage;
import com.job.scheduler.enums.WorkflowAiConversationStage;
import com.job.scheduler.enums.WorkflowAiMessageRole;
import com.job.scheduler.enums.WorkflowAiWorkspaceKind;
import com.job.scheduler.enums.FunctionStatus;
import com.job.scheduler.enums.FunctionVersionStatus;
import com.job.scheduler.enums.WorkflowStatus;
import com.job.scheduler.enums.AiStructuredOutputMode;
import com.job.scheduler.repository.WorkflowAiConversationRepository;
import com.job.scheduler.repository.WorkflowAiMessageRepository;
import com.job.scheduler.workflow.asl.runtime.AslRuntimeCapabilityValidator;
import com.job.scheduler.workflow.asl.validation.AslDefinitionValidator;
import com.job.scheduler.workflow.asl.validation.AslFunctionResourceValidator;
import com.job.scheduler.workflow.asl.validation.AslMcpResourceValidator;
import com.job.scheduler.workflow.asl.validation.AslValidationIssue;
import com.job.scheduler.workflow.asl.validation.AslValidationResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowAiConversationService {
    private static final Pattern THINKING_PATTERN = Pattern.compile(
            "(?is)<think(?:ing)?>(.*?)</think(?:ing)?>"
    );
    private static final Pattern REPEATED_CHARACTER_RUN = Pattern.compile("(.)\\1{11,}");
    private static final Pattern DISTINCTIVE_IDENTIFIER = Pattern.compile(
            "(?i)\\b(?=[a-z0-9._:/-]{4,}\\b)(?=[a-z0-9._:/-]*[a-z])(?=[a-z0-9._:/-]*[0-9])[a-z0-9._:/-]+\\b"
    );
    private static final String INVALID_AI_RESPONSE =
            "[AI_RESPONSE] The model response was not valid Voyager workflow JSON.";
    private static final String FAILED_VALIDATION_MESSAGE =
            "I couldn't apply the generated change because it still failed validation after "
                    + "automatic repair attempts. The last valid workflow was preserved.";
    private static final int MAX_ASSISTANT_GENERATION_ATTEMPTS = 3;
    private static final int THINKING_FLUSH_CHARACTERS = 24;
    private static final int ANSWER_PROGRESS_CHARACTERS = 160;
    /**
     * How long a stream may go silent before the turn abandons it and runs blocking. Long enough for
     * a slow local model's first token, short enough that a stream which never starts — or dies
     * part-way — costs one noticeable pause rather than the whole request budget.
     */
    private static final long STREAM_IDLE_POLL_SECONDS = 5;
    private static final int GENERAL_CHAT_MAX_OUTPUT_TOKENS = 512;
    private static final Pattern FUNCTION_NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    private static final Pattern EXPLICIT_SINGLE_TERMINAL_STATE = Pattern.compile(
            "(?is)\\bexactly\\s+one\\s+(Succeed|Fail)\\s+state\\s+named\\s+"
                    + "[\"']?([A-Za-z][A-Za-z0-9_-]{0,79})[\"']?(?=\\s|[.,;]|$)"
    );

    // Cheap heuristic to tell a "just chatting" turn (a greeting or a general question) from a real
    // build request, so a general turn can skip shipping the whole function/MCP catalog to the model
    // and answer far faster. It errs toward treating a turn as a build (catalog included): a general
    // opener only counts when it is not also a build imperative.
    private static final Pattern GENERAL_CHAT_OPENER = Pattern.compile(
            "(?is)^\\s*(hi|hii|hey|hello|yo|sup|greetings|thanks|thank you|ok|okay|cool|nice|great|"
                    + "good|fine|sure|yeah|yep|yes|no|nope|nah|lol|haha|hmm|oh|nothing|nvm|"
                    + "i'm|im|i am|i feel|i'm doing|i was|i just|just|who|what|whats|what's|how|why|"
                    + "which|when|where|is|are|was|were|do|does|did|can|could|would|should|explain|"
                    + "tell me|help me understand|difference)\\b.*");
    private static final Pattern BUILD_IMPERATIVE_OPENER = Pattern.compile(
            "(?is)^\\s*(add|create|make|build|generate|design|write|set ?up|fetch|send|schedule|"
                    + "update|change|modify|edit|remove|delete|connect|call|use|give me|i want|i need|"
                    + "please)\\b.*");
    private static final Pattern MISSING_RESOURCE_DEFLECTION = Pattern.compile(
            "(?is).*(?:\\b(?:need|needs|missing|attach|provide|connect|available|additional)\\b"
                    + ".{0,160}\\b(?:mcp|server|api|service|tool|resource|credential)\\b"
                    + "|\\b(?:mcp|server|api|service|tool|resource|credential)\\b.{0,160}"
                    + "\\b(?:need|needs|missing|attach|provide|connect|available|required)\\b).*"
    );
    private static final Pattern MISSING_FUNCTION_DEFLECTION = Pattern.compile(
            "(?is).*(?:\\b(?:need|needs|require|required|write|create|local)\\b.{0,120}"
                    + "\\b(?:function|helper|code)\\b|\\b(?:function|helper|code)\\b.{0,120}"
                    + "\\b(?:need|needs|require|required|write|create|local)\\b).*"
    );
    private static final Pattern EXPLICIT_ARTIFACT_REQUEST = Pattern.compile(
            "(?is).*(?:\\bexactly\\b.{0,120}\\b(?:state|states|workflow)\\b"
                    + "|\\bpropose\\b.{0,160}\\b(?:function|mcp|capability|resource)\\b"
                    + "|\\buse\\b.{0,80}\\b(?:an?\\s+)?mcp requirement\\b).*"
    );
    private static final Pattern CLARIFICATION_QUESTION = Pattern.compile(
            "(?is)^\\s*(?:which|what|where|when|how|why|do you|would you|could you|"
                    + "please (?:specify|provide|choose)|tell me)\\b.*"
    );
    private static final Pattern PREMATURE_WORKFLOW_NAME_QUESTION = Pattern.compile(
            "(?is).*(?:\\bworkflow name\\b|\\bname (?:for|of) (?:the|your) workflow\\b"
                    + "|\\bprovide (?:a|the) name\\b|\\bwhat should (?:it|the workflow) be called\\b).*"
    );
    private static final Pattern SCHEDULE_OPT_OUT = Pattern.compile(
            "(?is)\\b(?:not scheduled|no schedule|without (?:a )?schedule|unscheduled|"
                    + "run manually|manual(?:ly)? only|on[ -]demand|standalone)\\b"
    );
    private static final Pattern SCHEDULE_REQUEST = Pattern.compile(
            "(?is)\\b(?:schedule|scheduled|cron|daily|weekly|hourly|monthly|"
                    + "every\\s+(?:minute|hour|day|week|month|weekday|morning|evening)|"
                    + "each\\s+(?:minute|hour|day|week|month|weekday|morning|evening))s?\\b"
    );
    private static final Pattern EXACT_SINGLE_SUCCEED_REQUEST = Pattern.compile(
            "(?is).*\\bexactly\\s+one\\s+succeed\\s+state\\b.*"
    );
    private static final Pattern EXPLICIT_LOCAL_FUNCTION_REQUEST = Pattern.compile(
            "(?is).*\\b(?:deterministic\\s+local\\s+function|local\\s+deterministic\\s+function)\\b.*"
    );

    // Small local models routinely emit near-JSON: // or # comments, single quotes, trailing commas,
    // and literal newlines/tabs inside strings. Parsing model replies leniently lets a usable
    // resource plan or ASL survive those, so the user reaches the review card instead of a hard wall.
    private static final ObjectMapper LENIENT_MODEL_MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();

    private static final String SYSTEM_PROMPT = """
            You are Voyager's workflow builder for this scheduler.
            Return strict JSON only with these fields:
            {
              "stage": "COLLECTING_WORKFLOW_DETAILS|RESOURCES_PROPOSED|ASL_READY|ASL_UNDER_REVIEW|COLLECTING_SCHEDULE_DETAILS|PLAN_READY",
              "message": "short assistant message for the user",
              "aslDefinition": optional JSONata-only ASL object,
              "finalPlan": optional object,
              "draftWorkflowPayload": optional object with name, cronExpression, timezone, maxAttempts, idempotencyKey, definition,
              "resourcePlan": optional object {functions:[{name,description,languageId,sourceCode,testCases,rationale}], mcpRequirements:[{capability,suggestedToolName,reason,trustLevelHint}]}
            }
            Always include all six top-level fields. Use null for optional artifacts that do not apply.
            Stage/artifact invariants are mandatory:
            - ASL_READY requires a valid aslDefinition.
            - RESOURCES_PROPOSED requires a non-empty resourcePlan with at least one concrete function
              or MCP requirement. Never tell the user to attach, choose, or provide a service without
              emitting the matching concrete mcpRequirement.
            - COLLECTING_SCHEDULE_DETAILS requires valid ASL and is allowed only when the user explicitly
              requested scheduling.
            - PLAN_READY requires a complete draftWorkflowPayload containing a valid definition.
            You can also just talk. When the user's message is a general question, a greeting, or
            conversation rather than a request to build or change a workflow (for example "what is a
            cron schedule?", "how does retry work?", or "thanks"), answer it directly and concisely in
            "message". In that case keep the conversation's current stage (use COLLECTING_WORKFLOW_DETAILS
            if no workflow exists yet), and do NOT emit aslDefinition, resourcePlan, draftWorkflowPayload,
            or finalPlan, and do NOT ask for a workflow name, cron expression, timezone, or other build
            details. Only drive the build and detail-collection steps when the user actually asks to
            build or change a workflow, or is continuing one already in progress. Never answer a plain
            question by demanding schedule or workflow details.
            ASL rules: omit QueryLanguage and Version, use JSONata expressions with {% %}, reject JSONPath fields and States.* intrinsics.
            Never use JSONPath-only InputPath, OutputPath, Parameters, Result, ResultPath, ResultSelector,
            ItemsPath, or a field ending in '.$'. ItemSelector is allowed for a JSONata Map state.
            A Task passes input with Arguments and reads its result from $states.result in Output, for example:
            {"Type":"Task","Resource":"<exact catalog URI>","Arguments":{"payload":"{% $states.input %}"},"Output":{"result":"{% $states.result %}"},"Next":"NextState"}
            Arguments may reference only $states.input and $states.context; $states.result is forbidden there because
            it exists only in that same Task's Output. The only $states fields are input, result, context, and
            errorOutput. Carry accumulated values to the next state by returning an Output object that merges
            $states.input with the current $states.result; later states read those values from $states.input.
            State output becomes the next state's $states.input. For two sequential Tasks, use this exact pattern:
            {"StartAt":"First","States":{"First":{"Type":"Task","Resource":"<exact first URI>","Arguments":{"payload":"{% $states.input %}"},"Output":{"original":"{% $states.input %}","firstResult":"{% $states.result %}"},"Next":"Second"},"Second":{"Type":"Task","Resource":"<exact second URI>","Arguments":{"payload":"{% $states.input.firstResult %}"},"Output":{"original":"{% $states.input.original %}","firstResult":"{% $states.input.firstResult %}","secondResult":"{% $states.result %}"},"End":true}}}
            Never reference a previous state's $states.result: after a transition it is available only through a
            property that the previous state placed in Output, which the next state reads from $states.input.
            Spell the reserved variable exactly as $states. Never write $.states, $state, or states without the $.
            For an MCP Task, its catalog line's args object defines the Task Arguments keys. Use those exact
            top-level keys with JSONata values; never wrap them in payload unless payload is an advertised key.
            An MCP Task result is the full MCP CallToolResult. Read returned business fields from
            $states.result.structuredContent.<field>, not directly from $states.result.<field>.
            If the catalog has no output schema, preserve the entire $states.result.structuredContent under your
            own Output property; never guess that structuredContent contains a same-named wrapper object.
            End the final requested Task with End:true. Do not add a terminal Pass solely to end the workflow.
            If a Pass state is genuinely needed, use Output for transformed data; Result is JSONPath-only and invalid.
            Pass, Task, Wait, Parallel, and Map states must contain exactly one of Next or End.
            Choice uses Choices plus optional Default and never End. Succeed and Fail are terminal by
            their Type and must contain neither Next nor End.
            Succeed, Fail, Pass, Wait, Choice, Parallel, Map, and Task are built-in ASL states. Never
            propose a function merely to implement one of these state types. For example, a workflow
            with exactly one Succeed state needs only StartAt and that state with Type:"Succeed".
            Never return an Adaptive Card, Markdown wrapper, JSON Schema, tool-call envelope, or UI component as aslDefinition.
            aslDefinition must be the ASL machine itself with StartAt and a non-empty States object.
            Keep cron, timezone, approval, and schedule metadata outside ASL.
            When asked to recall an exact identifier, copy it verbatim from the supplied Exact source identifiers.
            Ask clarifying questions until the workflow is clear. When ASL is ready, include aslDefinition.
            If the user already has ASL open in their editor it is given to you as "Current ASL in the user's editor".
            Persisted workflow context may provide the same document as "Latest ASL definition (authoritative)".
            Treat that ASL as the source of truth, amend it in place for what the user asks, and return the whole
            amended definition as aslDefinition. Keep the states, names, and structure the user already has unless
            they ask otherwise, and never restart from scratch when an editor ASL is present.
            The live function and MCP catalog is supplied as "Available Voyager Task resources". For every requested
            action, compare the action to the catalog descriptions. When a catalog entry matches, you MUST create a
            Task using that exact Resource URI; do not replace it with Pass and do not shorten or rename any URI.
            Never invent a registered resource. Before returning, verify every requested action against the catalog.
            When a requested action has NO matching catalog resource, do NOT invent a URI and do NOT substitute a
            Pass. Instead propose the missing capability in resourcePlan, set stage RESOURCES_PROPOSED, and OMIT
            aslDefinition until the resources exist. Choose functions vs MCP by this rule:
            Use resourcePlan.functions only for self-contained deterministic local logic such as parsing, math,
            string/date reshaping, formatting, validation, filtering, or scoring. Use mcpRequirements for anything
            that talks to an external service, fetches from the network, or needs credentials. Do not invent
            credentials or resource URIs, and do not propose a function for an existing catalog capability. Voyager
            will provide the function-authoring contract only after you actually choose to propose a function.
            The entire response must be strict, valid JSON: no comments of any kind (no //, /* */, or #) and no
            trailing commas. Once proposed functions have been created (they will appear in the catalog on the next
            turn) and every required MCP tool is present in the catalog, generate the
            ASL referencing voyager://function/<name>@v<version> and the exact MCP URIs. If MCP tools are still
            missing, keep stage RESOURCES_PROPOSED, restate only the unmet mcpRequirements, and ask the user to attach
            and sync those servers and then continue.
            Scheduling is opt-in. Never ask for cron or timezone and never enter
            COLLECTING_SCHEDULE_DETAILS unless the user explicitly requested a schedule. For an
            unscheduled workflow, keep cronExpression null and continue without schedule questions.
            After ASL is approved, collect the workflow name and max attempts, plus cron expression
            and timezone only when scheduling was explicitly requested.
            When everything is ready, return PLAN_READY with finalPlan and draftWorkflowPayload.
            If the selected local model supports visible reasoning, put that reasoning before the JSON as <think>...</think>.
            The content after </think> must still be strict JSON only. Keep any <think> reasoning brief and keep
            the JSON output concise so it always completes and is never cut off mid-object.
            """;

    /**
     * Slim prompt used when the turn is just conversation (a greeting or general question), not a
     * request to build a workflow. It drops the whole builder ruleset — which is what made a small
     * model deflect chit-chat into "what's the workflow name?" — and only keeps the JSON envelope the
     * backend parses. Far fewer tokens too, so these turns are fast.
     */
    private static final String GENERAL_CHAT_SYSTEM_PROMPT = """
            You are Voyager's friendly assistant. Voyager is a workflow scheduler, but right now the
            user is just chatting, not asking you to build a workflow.
            Reply naturally and helpfully to their message in a sentence or two.
            Do NOT ask for a workflow name, a schedule, a cron expression, or any other build details,
            and do NOT try to design or describe a workflow. If they later ask to build or change a
            workflow, you will help then.
            Respond with strict JSON only — no markdown, no comments, no text outside the JSON:
            {"stage":"COLLECTING_WORKFLOW_DETAILS","message":"<your natural reply>"}
            """;

    private static final String FUNCTION_CREATION_PROMPT = """
            FUNCTION CREATION CONTRACT
            This contract applies because the proposed response creates one or more Voyager functions.
            Re-review and correct the complete response using every rule below:
            - A function is only for self-contained, deterministic local logic. It must not call the network,
              external APIs, SaaS products, databases, LLMs, or require secrets or placeholder credentials.
              Anything external must be an mcpRequirement instead.
            - Function names must be unique lowercase kebab-case matching ^[a-z0-9][a-z0-9-]*$, for example
              "shorten-and-title-case". Never use camelCase, PascalCase, snake_case, spaces, or underscores.
            - Always use the single AI default languageId and language supplied below. Write sourceCode in that
              language even if the user names another language, unless they explicitly ask to create it manually.
            - sourceCode must be complete, valid single-file code for that language. It must read exactly one valid
              JSON value from stdin, parse that JSON before transforming it, and write exactly one valid JSON value
              to stdout. Do not print logs or other text to stdout. Never transform or truncate the serialized JSON
              text itself; transform the parsed value, then JSON-serialize the result.
            - sourceCode is mandatory and must never be null, blank, pseudocode, or moved into finalPlan.
            - Copy the numeric AI default languageId exactly. Never guess an ID or use a different runtime. If the
              default is unavailable, do not fabricate a function proposal.
            - Keep sourceCode short, but handle the declared input shape and normal boundary cases. Do not provide
              pseudocode, an incomplete fragment, or code that only prints a hard-coded value.
            - Set testCases to null. Tests are generated only after the user approves the function and Voyager creates
              a draft; Judge0 must validate that draft before the generated tests are saved or the version is published.
            - Include a clear description and a one-line rationale explaining why local deterministic code is the
              correct resource type.
            - Return the complete workflow response contract as one strict JSON object, with no Markdown, comments,
              or text outside JSON. sourceCode is a JSON string, so escape its internal quotes and newlines.
            """;

    private static final String SUMMARY_SYSTEM_PROMPT = """
            You compact older turns from a workflow-design conversation into durable memory.
            Return only a concise factual summary, never an answer to the user.
            Preserve requirements, decisions, state names, resources, JSONata expressions,
            schedule details, corrections, unresolved questions, and explicit user preferences.
            Distinguish confirmed decisions from proposals. Do not invent missing details.
            The original request, current stage, and latest ASL are supplied separately to the
            workflow model, so focus on conversational facts that are not already represented there.
            """;

    private static final int APPROXIMATE_CHARACTERS_PER_TOKEN = 4;

    private final AiModelConfigService aiModelConfigService;
    private final WorkflowAiModelResolver modelResolver;
    private final WorkflowAiStreamBroker streamBroker;
    private final WorkflowAiTurnRegistry turnRegistry;
    private final WorkflowAiConversationRepository conversationRepository;
    private final WorkflowAiMessageRepository messageRepository;
    private final AslDefinitionValidator aslDefinitionValidator;
    private final AslRuntimeCapabilityValidator runtimeCapabilityValidator;
    private final AslMcpResourceValidator mcpResourceValidator;
    private final AslFunctionResourceValidator functionResourceValidator;
    private final WorkflowService workflowService;
    private final WorkflowAiResourceCatalogService resourceCatalogService;
    private final FunctionRegistryService functionRegistryService;
    private final FunctionRuntimePolicy functionRuntimePolicy;
    private final WorkflowAiProposedFunctionSafetyValidator proposedFunctionSafetyValidator;
    private final WorkflowAiFunctionQualificationService functionQualificationService;
    private final WorkflowAiTrustReviewService trustReviewService;
    private final ObjectMapper objectMapper;

    public String promptFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((
                    SYSTEM_PROMPT + "\n" + GENERAL_CHAT_SYSTEM_PROMPT + "\n"
                            + FUNCTION_CREATION_PROMPT
            ).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Value("${scheduler.workflow-ai.context.max-estimated-tokens:12000}")
    private int maximumContextTokens = 12000;

    @Value("${scheduler.workflow-ai.context.recent-estimated-tokens:4000}")
    private int recentContextTokens = 4000;

    /**
     * How long to wait for the model's FIRST streamed token before giving up on the stream. Generous,
     * because a large prompt makes prompt-eval (time to first token) legitimately long on a local
     * model; the old single 25 s budget tripped here and forced a wasteful blocking re-run.
     */
    @Value("${scheduler.workflow-ai.stream.first-token-seconds:60}")
    private long streamFirstTokenSeconds = 60;

    /** How long a stream may go silent AFTER it has started before it is treated as dead. */
    @Value("${scheduler.workflow-ai.stream.idle-seconds:25}")
    private long streamIdleSeconds = 25;

    @Value("${scheduler.workflow-ai.context.summary-max-characters:6000}")
    private int maximumSummaryCharacters = 6000;

    public List<WorkflowAiConversationSummaryDTO> listConversations() {
        return listWorkspaces(WorkflowAiWorkspaceKind.AI_CHAT);
    }

    public List<WorkflowAiConversationSummaryDTO> listDrafts() {
        return listWorkspaces(WorkflowAiWorkspaceKind.MANUAL_DRAFT);
    }

    private List<WorkflowAiConversationSummaryDTO> listWorkspaces(
            WorkflowAiWorkspaceKind workspaceKind
    ) {
        return conversationRepository.findTop50ByWorkspaceKindOrderByUpdatedAtDesc(workspaceKind)
                .stream()
                .map(this::summary)
                .toList();
    }

    public WorkflowAiConversationDetailDTO getConversation(UUID conversationId) {
        WorkflowAiConversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workflow AI conversation does not exist"
                ));

        requireWorkspaceKind(conversation, WorkflowAiWorkspaceKind.AI_CHAT);
        return detail(conversation);
    }

    public WorkflowAiConversationDetailDTO getDraft(UUID draftId) {
        WorkflowAiConversation draft = conversationRepository.findById(draftId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workflow draft does not exist"
                ));
        requireWorkspaceKind(draft, WorkflowAiWorkspaceKind.MANUAL_DRAFT);
        return detail(draft);
    }

    @Transactional
    public WorkflowAiConversationSummaryDTO renameConversation(
            UUID conversationId,
            String name
    ) {
        return renameWorkspace(conversationId, WorkflowAiWorkspaceKind.AI_CHAT, name);
    }

    @Transactional
    public WorkflowAiConversationSummaryDTO renameDraft(UUID draftId, String name) {
        return renameWorkspace(draftId, WorkflowAiWorkspaceKind.MANUAL_DRAFT, name);
    }

    private WorkflowAiConversationSummaryDTO renameWorkspace(
            UUID workspaceId,
            WorkflowAiWorkspaceKind workspaceKind,
            String name
    ) {
        WorkflowAiConversation workspace = findForUpdate(workspaceId);
        requireWorkspaceKind(workspace, workspaceKind);
        String normalizedName = requireText(name, "Workspace name");
        if (normalizedName.length() > 120) {
            throw new IllegalArgumentException("Workspace name cannot exceed 120 characters");
        }
        workspace.setCustomName(normalizedName);
        return summary(conversationRepository.saveAndFlush(workspace));
    }

    @Transactional
    public WorkflowAiConversationDetailDTO createDraft(
            WorkflowAiWorkspaceRequestDTO request
    ) {
        WorkflowAiConversation draft = new WorkflowAiConversation();
        draft.setWorkspaceKind(WorkflowAiWorkspaceKind.MANUAL_DRAFT);
        draft.setName(manualDraftName(request.settings()));
        draft.setInitialInstruction("");
        draft.setStage(WorkflowAiConversationStage.COLLECTING_WORKFLOW_DETAILS);
        applyWorkspace(draft, request);
        conversationRepository.saveAndFlush(draft);
        return detail(draft);
    }

    @Transactional
    public void deleteConversation(UUID conversationId) {
        WorkflowAiConversation conversation = findForUpdate(conversationId);
        requireWorkspaceKind(conversation, WorkflowAiWorkspaceKind.AI_CHAT);
        // Messages must go first: their conversation_id FK has no ON DELETE CASCADE. The draft ASL,
        // draft workflow payload, and canvas layout all live on the conversation row itself, so
        // deleting it removes every workflow JSON and canvas built inside this chat. Workflows that
        // were finalized through "Accept plan" are independent entities and are intentionally kept.
        messageRepository.deleteByConversationId(conversationId);
        conversationRepository.delete(conversation);
    }

    @Transactional
    public void deleteAllConversations() {
        deleteAllWorkspaces(WorkflowAiWorkspaceKind.AI_CHAT);
    }

    @Transactional
    public void deleteDraft(UUID draftId) {
        WorkflowAiConversation draft = findForUpdate(draftId);
        requireWorkspaceKind(draft, WorkflowAiWorkspaceKind.MANUAL_DRAFT);
        messageRepository.deleteByConversationId(draftId);
        conversationRepository.delete(draft);
    }

    @Transactional
    public void deleteAllDrafts() {
        deleteAllWorkspaces(WorkflowAiWorkspaceKind.MANUAL_DRAFT);
    }

    private void deleteAllWorkspaces(WorkflowAiWorkspaceKind workspaceKind) {
        List<WorkflowAiConversation> workspaces =
                conversationRepository.findAllByWorkspaceKind(workspaceKind);
        workspaces.forEach(workspace -> messageRepository.deleteByConversationId(workspace.getId()));
        conversationRepository.deleteAll(workspaces);
    }

    @Transactional
    public void saveWorkspace(UUID conversationId, WorkflowAiWorkspaceRequestDTO request) {
        WorkflowAiConversation conversation = findForUpdate(conversationId);
        if (applyWorkspace(conversation, request)) {
            conversationRepository.saveAndFlush(conversation);
        }
    }

    /**
     * Saves the workflow owned by a persistent workspace. The first call creates (or recovers via
     * the idempotency key) the workflow and records the link. Every later call creates a revision.
     */
    @Transactional
    public WorkflowAiSaveWorkflowResponseDTO saveConversationWorkflow(
            UUID conversationId,
            WorkflowAiSaveWorkflowRequestDTO request
    ) {
        return saveWorkspaceWorkflow(
                conversationId,
                WorkflowAiWorkspaceKind.AI_CHAT,
                request
        );
    }

    @Transactional
    public WorkflowAiSaveWorkflowResponseDTO saveDraftWorkflow(
            UUID draftId,
            WorkflowAiSaveWorkflowRequestDTO request
    ) {
        return saveWorkspaceWorkflow(
                draftId,
                WorkflowAiWorkspaceKind.MANUAL_DRAFT,
                request
        );
    }

    private WorkflowAiSaveWorkflowResponseDTO saveWorkspaceWorkflow(
            UUID workspaceId,
            WorkflowAiWorkspaceKind workspaceKind,
            WorkflowAiSaveWorkflowRequestDTO request
    ) {
        WorkflowAiConversation conversation = findForUpdate(workspaceId);
        requireWorkspaceKind(conversation, workspaceKind);

        CreateWorkflowRequestDTO requestedWorkflow = request.workflow();

        // Before creating anything, require explicit confirmation when the definition wires in
        // WRITE/DESTRUCTIVE MCP tools. Checked here (not just in the UI) so the gate holds for
        // every caller of this endpoint.
        if (!Boolean.TRUE.equals(request.confirmElevatedTrust())) {
            WorkflowAiTrustReviewDTO trustReview = trustReviewService.review(requestedWorkflow.definition());
            if (trustReview.requiresConfirmation()) {
                throw new WorkflowAiTrustConfirmationRequiredException(trustReview.tools());
            }
        }

        WorkflowResponseDTO workflow = conversation.getWorkflowId() == null
                ? workflowService.createWorkflow(requestedWorkflow)
                : workflowService.getWorkflow(conversation.getWorkflowId());

        if (workflowMetadataChanged(workflow, requestedWorkflow)) {
            String cronExpression = optionalText(requestedWorkflow.cronExpression());
            String timezone = optionalText(requestedWorkflow.timezone());
            workflow = workflowService.updateMetadata(
                    workflow.id(),
                    new UpdateWorkflowMetadataRequestDTO(
                            workflow.version(),
                            requestedWorkflow.name(),
                            objectMapper.valueToTree(cronExpression),
                            timezone == null ? "UTC" : timezone,
                            requestedWorkflow.maxAttempts()
                    )
            );
        }

        // A live recurring workflow should receive the newly saved definition immediately.
        // Draft and paused schedules remain non-running; manual workflows activate on save by
        // WorkflowService policy regardless of this flag.
        boolean activateRevision = workflow.status() == WorkflowStatus.ACTIVE;
        WorkflowDefinitionResponseDTO revision = workflowService.createRevision(
                workflow.id(),
                new CreateWorkflowRevisionRequestDTO(
                        requestedWorkflow.definition(),
                        activateRevision
                )
        );
        revision = workflowService.updateCanvasLayout(
                workflow.id(),
                revision.revision(),
                new UpdateWorkflowCanvasLayoutRequestDTO(request.canvasLayout())
        );
        workflow = workflowService.getWorkflow(workflow.id());

        conversation.setWorkflowId(workflow.id());
        conversation.setStage(WorkflowAiConversationStage.ACCEPTED);
        conversationRepository.saveAndFlush(conversation);
        return new WorkflowAiSaveWorkflowResponseDTO(workflow, revision);
    }

    private boolean workflowMetadataChanged(
            WorkflowResponseDTO workflow,
            CreateWorkflowRequestDTO request
    ) {
        String requestedCron = optionalText(request.cronExpression());
        String requestedTimezone = optionalText(request.timezone());
        int requestedMaxAttempts = request.maxAttempts() == null
                ? workflow.maxAttempts()
                : request.maxAttempts();
        return !Objects.equals(workflow.name(), requireText(request.name(), "Workflow name"))
                || !Objects.equals(optionalText(workflow.cronExpression()), requestedCron)
                || !Objects.equals(
                        optionalText(workflow.timezone()),
                        requestedTimezone == null ? "UTC" : requestedTimezone
                )
                || workflow.maxAttempts() != requestedMaxAttempts;
    }

    private boolean applyWorkspace(
            WorkflowAiConversation conversation,
            WorkflowAiWorkspaceRequestDTO request
    ) {
        if (!request.canvasLayout().isObject()) {
            throw new IllegalArgumentException("Canvas layout must be a JSON object");
        }

        String workspaceDefinitionText = request.definitionText();
        if (workspaceDefinitionText == null) {
            if (request.definition() == null || !request.definition().isObject()) {
                throw new IllegalArgumentException("ASL definition must be a JSON object");
            }
            workspaceDefinitionText = serialize(request.definition());
        }

        // The raw editor buffer is always durable, including incomplete JSON. Only a complete,
        // executable JSONata ASL object may replace draftAsl, which remains the authoritative
        // document supplied to the model and workflow creation path.
        JsonNode authoritativeDefinition = null;
        try {
            JsonNode parsed = objectMapper.readTree(workspaceDefinitionText);
            if (parsed != null && parsed.isObject()
                    && validateExecutableDefinition(parsed).isEmpty()) {
                authoritativeDefinition = parsed;
            }
        } catch (Exception ignored) {
            // Incomplete JSON is a normal intermediate editor state and is restored verbatim.
        }

        JsonNode workspaceSettings = objectMapper.valueToTree(request.settings());
        String nextName = conversation.getWorkspaceKind() == WorkflowAiWorkspaceKind.MANUAL_DRAFT
                ? manualDraftName(request.settings())
                : conversation.getName();
        boolean authoritativeChanged = authoritativeDefinition != null
                && !authoritativeDefinition.equals(readJson(conversation.getDraftAsl()));
        if (!authoritativeChanged
                && Objects.equals(nextName, conversation.getName())
                && Objects.equals(
                        workspaceDefinitionText,
                        conversation.getWorkspaceDefinitionText()
                )
                && request.canvasLayout().equals(readJson(conversation.getCanvasLayout()))
                && workspaceSettings.equals(readJson(conversation.getWorkspaceSettings()))) {
            return false;
        }

        conversation.setName(nextName);
        conversation.setWorkspaceDefinitionText(workspaceDefinitionText);
        if (authoritativeDefinition != null) {
            conversation.setDraftAsl(serialize(authoritativeDefinition));
        }
        conversation.setCanvasLayout(serialize(request.canvasLayout()));
        conversation.setWorkspaceSettings(serialize(workspaceSettings));
        return true;
    }

    @Transactional
    public WorkflowAiResponseDTO startConversation(
            String instruction,
            UUID modelConfigId,
            String userDateTime,
            JsonNode editorDefinition
    ) {
        return startConversation(
                instruction,
                modelConfigId,
                userDateTime,
                editorDefinition,
                null
        );
    }

    @Transactional
    public WorkflowAiResponseDTO startEvaluationConversation(
            String instruction,
            UUID modelConfigId,
            String userDateTime,
            Duration timeout
    ) {
        return modelResolver.withRequestTimeout(
                timeout,
                () -> startConversation(instruction, modelConfigId, userDateTime, null, null)
        );
    }

    @Transactional
    public WorkflowAiResponseDTO startConversation(
            String instruction,
            UUID modelConfigId,
            String userDateTime,
            JsonNode editorDefinition,
            String editorDefinitionText
    ) {
        String normalizedInstruction = requireText(instruction, "Instruction");
        AiModelConfig modelConfig = aiModelConfigService.resolveModel(modelConfigId);

        WorkflowAiConversation conversation = new WorkflowAiConversation();
        conversation.setWorkspaceKind(WorkflowAiWorkspaceKind.AI_CHAT);
        conversation.setName(generateConversationName(normalizedInstruction));
        conversation.setInitialInstruction(normalizedInstruction);
        conversation.setModelConfig(modelConfig);
        conversation.setStage(
                WorkflowAiConversationStage.COLLECTING_WORKFLOW_DETAILS
        );
        conversationRepository.save(conversation);

        String editorContext = editorAslContext(editorDefinition);
        List<String> editorValidationIssues = editorContext == null
                ? List.of()
                : validateExecutableDefinition(editorDefinition);
        if (editorContext != null && editorValidationIssues.isEmpty()) {
            conversation.setDraftAsl(serialize(editorDefinition));
        }
        appendMessage(
                conversation,
                WorkflowAiMessageRole.USER,
                normalizedInstruction,
                editorContext == null ? null : serialize(editorDefinition),
                modelConfig
        );

        String startTask = editorContext == null
                ? "Start from the user's first instruction."
                : "Amend the ASL already open in the user's editor.";
        if (!editorValidationIssues.isEmpty()) {
            startTask = rejectedEditorCandidateContext(editorDefinition, editorValidationIssues)
                    + "\nHelp the user correct this candidate without treating it as authoritative.";
        }
        if (editorContext == null) {
            String bufferContext = inProgressBufferContext(editorDefinitionText);
            if (bufferContext != null) {
                startTask += "\n" + bufferContext;
            }
        }
        if (userDateTime != null && !userDateTime.isBlank()) {
            startTask += "\nUser date/time context: " + userDateTime.trim();
        }
        return callAssistant(
                conversation,
                startTask
        );
    }

    public WorkflowAiResponseDTO continueConversation(
            UUID conversationId,
            String message,
            UUID modelConfigId,
            JsonNode editorDefinition
    ) {
        return continueConversation(
                conversationId,
                message,
                modelConfigId,
                editorDefinition,
                null
        );
    }

    @Transactional
    public WorkflowAiResponseDTO continueConversation(
            UUID conversationId,
            String message,
            UUID modelConfigId,
            JsonNode editorDefinition,
            String editorDefinitionText
    ) {
        WorkflowAiConversation conversation = findForUpdate(conversationId);
        String normalizedMessage = requireText(message, "Message");
        AiModelConfig selectedModel = aiModelConfigService.resolveModel(
                selectedModelId(conversation, modelConfigId)
        );
        conversation.setModelConfig(selectedModel);
        if (conversation.getInitialInstruction() == null
                || conversation.getInitialInstruction().isBlank()) {
            conversation.setInitialInstruction(normalizedMessage);
        }

        // Only resend the editor ASL when it drifted from what the conversation already knows,
        // so an unchanged definition is not repeated into every prompt.
        String editorContext = null;
        List<String> editorValidationIssues = List.of();
        if (editorAslContext(editorDefinition) != null
                && !serialize(editorDefinition).equals(conversation.getDraftAsl())) {
            editorContext = editorAslContext(editorDefinition);
            editorValidationIssues = validateExecutableDefinition(editorDefinition);
            if (editorValidationIssues.isEmpty()) {
                conversation.setDraftAsl(serialize(editorDefinition));
            }
        }

        appendMessage(
                conversation,
                WorkflowAiMessageRole.USER,
                normalizedMessage,
                editorContext == null ? null : serialize(editorDefinition),
                selectedModel
        );
        String task = "Continue the workflow design conversation.";
        if (!editorValidationIssues.isEmpty()) {
            task += "\n" + rejectedEditorCandidateContext(
                    editorDefinition,
                    editorValidationIssues
            );
        }
        if (editorContext == null) {
            String bufferContext = inProgressBufferContext(editorDefinitionText);
            if (bufferContext != null) {
                task += "\n" + bufferContext;
            }
        }
        return callAssistant(conversation, task);
    }

    @Transactional
    public WorkflowAiResponseDTO regenerateMessage(
            UUID messageId,
            UUID modelConfigId
    ) {
        WorkflowAiMessage targetMessage = messageRepository.findById(messageId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workflow AI message does not exist"
                ));
        if (targetMessage.getRole() != WorkflowAiMessageRole.ASSISTANT) {
            throw new IllegalArgumentException("Only assistant messages can be regenerated");
        }
        WorkflowAiConversation conversation = findForUpdate(
                targetMessage.getConversation().getId()
        );
        WorkflowAiMessage latestMessage = messageRepository
                .findFirstByConversationOrderByCreatedAtDesc(conversation)
                .orElseThrow(() -> new IllegalStateException(
                        "The conversation has no messages"
                ));
        if (!latestMessage.getId().equals(targetMessage.getId())) {
            throw new IllegalArgumentException(
                    "Only the latest assistant message can be regenerated"
            );
        }
        AiModelConfig selectedModel = aiModelConfigService.resolveModel(
                selectedModelId(conversation, modelConfigId)
        );
        conversation.setModelConfig(selectedModel);

        // Anchor on the first attempt, so retrying repeatedly does not feed each discarded reply
        // back into the prompt and drift the answer further every time.
        List<WorkflowAiMessage> history = historyBeforeMessage(
                conversation,
                regenerationRoot(targetMessage)
        );
        return callAssistant(
                conversation,
                "Regenerate the previous assistant response. Keep the workflow state consistent.",
                selectedModel,
                targetMessage,
                history
        );
    }

    @Transactional
    public WorkflowAiResponseDTO regenerateEvaluationMessage(
            UUID messageId,
            UUID modelConfigId,
            Duration timeout
    ) {
        return modelResolver.withRequestTimeout(
                timeout,
                () -> regenerateMessage(messageId, modelConfigId)
        );
    }

    @Transactional
    public WorkflowAiResponseDTO reviewAsl(UUID conversationId, JsonNode definition) {
        if (definition == null || definition.isNull()) {
            throw new IllegalArgumentException("ASL definition cannot be null");
        }
        WorkflowAiConversation conversation = findForUpdate(conversationId);
        List<String> validationIssues = validateExecutableDefinition(definition);
        String reviewInstruction = """
                The user edited the ASL in the editor. Compare it against the original request and the conversation.
                If the ASL is valid and matches the request, move to COLLECTING_SCHEDULE_DETAILS.
                If it needs changes, move to ASL_UNDER_REVIEW and explain the smallest correction.
                Current edited ASL:
                """ + serialize(definition);
        if (!validationIssues.isEmpty()) {
            reviewInstruction += "\nValidator issues:\n" + String.join("\n", validationIssues);
        }
        appendMessage(
                conversation,
                WorkflowAiMessageRole.USER,
                reviewInstruction,
                serialize(definition),
                conversation.getModelConfig()
        );

        WorkflowAiResponseDTO assistantResponse =
                callAssistant(conversation, "Review the edited ASL.");

        if (!validationIssues.isEmpty()) {
            conversation.setStage(WorkflowAiConversationStage.ASL_UNDER_REVIEW);
            return response(
                    conversation,
                    assistantResponse.message(),
                    definition,
                    validationIssues,
                    readJson(conversation.getFinalPlan()),
                    readDraftPayload(conversation),
                    null,
                    assistantResponse.assistantMessage()
            );
        }

        conversation.setDraftAsl(serialize(definition));
        if (conversation.getStage() == WorkflowAiConversationStage.ASL_UNDER_REVIEW
                || conversation.getStage() == WorkflowAiConversationStage.ASL_READY) {
            conversation.setStage(
                    WorkflowAiConversationStage.COLLECTING_SCHEDULE_DETAILS
            );
        }
        return response(
                conversation,
                assistantResponse.message(),
                definition,
                List.of(),
                readJson(conversation.getFinalPlan()),
                readDraftPayload(conversation),
                null,
                assistantResponse.assistantMessage()
        );
    }

    @Transactional
    public WorkflowAiResponseDTO acceptPlan(UUID conversationId) {
        WorkflowAiConversation conversation = findForUpdate(conversationId);
        CreateWorkflowRequestDTO draftPayload = readDraftPayload(conversation);
        if (draftPayload == null) {
            draftPayload = fallbackDraftPayload(conversation);
        }
        WorkflowResponseDTO workflow = workflowService.createWorkflow(draftPayload);
        conversation.setWorkflowId(workflow.id());
        conversation.setStage(WorkflowAiConversationStage.ACCEPTED);
        appendMessage(
                conversation,
                WorkflowAiMessageRole.SYSTEM,
                "Accepted final plan and created draft workflow " + workflow.id(),
                null,
                conversation.getModelConfig()
        );
        return response(
                conversation,
                "Draft workflow created.",
                readJson(conversation.getDraftAsl()),
                List.of(),
                readJson(conversation.getFinalPlan()),
                draftPayload,
                workflow,
                null
        );
    }

    /**
     * Creates the functions the user approved from a RESOURCES_PROPOSED plan, then re-invokes the
     * model so it can generate the ASL against the now-updated catalog (or restate any MCP servers
     * that still need attaching). Voyager never provisions MCP servers — those stay recommendations.
     */
    @Transactional
    public WorkflowAiResponseDTO provisionResources(
            UUID conversationId,
            List<WorkflowAiProposedFunctionDTO> approvedFunctions,
            UUID modelConfigId
    ) {
        WorkflowAiConversation conversation = findForUpdate(conversationId);
        ensureResourcePlanMessageId(conversation);
        WorkflowAiResourcePlanDTO previousPlan = readResourcePlan(conversation);
        List<WorkflowAiResourceCatalogService.McpRequirementMatch> matchedMcpResources =
                resourceCatalogService.findMcpRequirementMatches(
                        previousPlan == null ? null : previousPlan.mcpRequirements()
                );
        AiModelConfig selectedModel = aiModelConfigService.resolveModel(
                selectedModelId(conversation, modelConfigId)
        );
        conversation.setModelConfig(selectedModel);

        List<String> createdResources = new ArrayList<>();
        List<String> qualificationFailures = new ArrayList<>();
        if (approvedFunctions != null) {
            for (WorkflowAiProposedFunctionDTO function : approvedFunctions) {
                WorkflowAiFunctionQualificationService.QualificationResult result =
                        provisionFunction(function, selectedModel);
                if (result.qualified()) {
                    createdResources.add(result.resourceUri());
                } else {
                    qualificationFailures.add(
                            normalizeFunctionName(function.name()) + ": " + result.reason()
                    );
                }
            }
        }

        if (!qualificationFailures.isEmpty()) {
            String failureMessage = "Function validation failed. The function remains a draft and "
                    + "no generated tests were saved: " + String.join(" ", qualificationFailures);
            appendMessage(
                    conversation,
                    WorkflowAiMessageRole.SYSTEM,
                    failureMessage,
                    null,
                    selectedModel
            );
            conversation.setStage(WorkflowAiConversationStage.RESOURCES_PROPOSED);
            return response(
                    conversation,
                    failureMessage,
                    readJson(conversation.getDraftAsl()),
                    qualificationFailures,
                    readJson(conversation.getFinalPlan()),
                    readDraftPayload(conversation),
                    null,
                    null
            );
        }

        String created = createdResources.isEmpty()
                ? "none"
                : String.join(", ", createdResources);
        String matched = matchedMcpResources.isEmpty()
                ? "none"
                : matchedMcpResources.stream()
                        .map(match -> match.capability() + " -> " + match.resourceUri())
                        .collect(java.util.stream.Collectors.joining("\n- ", "\n- ", ""));
        appendMessage(
                conversation,
                WorkflowAiMessageRole.SYSTEM,
                "Provisioned functions: " + created + ". Matched MCP resources: " + matched,
                null,
                selectedModel
        );

        String task = "The user approved and Voyager created these functions, now in the catalog: "
                + created
                + ". These previously requested MCP capabilities are already satisfied by exact "
                + "catalog resources: " + matched
                + ". Do not propose those MCP capabilities again"
                + ". Re-read the Available Voyager Task resources. If every requested action now maps to a "
                + "catalog resource, generate the workflow ASL as aslDefinition (ASL_READY). If some action "
                + "still needs an MCP tool that is not yet in the catalog, keep stage RESOURCES_PROPOSED, "
                + "return only the unmet mcpRequirements, and ask the user to attach and sync those servers "
                + "before continuing.";
        return callAssistant(conversation, task);
    }

    private WorkflowAiFunctionQualificationService.QualificationResult provisionFunction(
            WorkflowAiProposedFunctionDTO function,
            AiModelConfig modelConfig
    ) {
        proposedFunctionSafetyValidator.assertSafe(function);
        String requestedName = requireText(function.name(), "Function name");
        String name = normalizeFunctionName(requestedName);
        if (name.isBlank() || !FUNCTION_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Function name must use lowercase letters, numbers, and hyphens: "
                            + requestedName
            );
        }
        if (function.languageId() == null) {
            throw new IllegalArgumentException("Function '" + name + "' is missing a languageId");
        }
        functionRuntimePolicy.assertLanguageSupported(function.languageId());
        String sourceCode = requireText(
                function.sourceCode(),
                "Function '" + name + "' source code"
        );

        FunctionDefinitionResponseDTO created = functionRegistryService.createFunction(
                new FunctionDefinitionRequestDTO(
                        name,
                        optionalText(function.description()),
                        FunctionStatus.ENABLED
                )
        );
        // Qualification owns publication. A broken or incomplete generated function must never
        // become active merely because the user approved its proposal card.
        FunctionVersionResponseDTO version = functionRegistryService.createVersion(
                created.id(),
                new FunctionVersionRequestDTO(
                        null,
                        function.languageId(),
                        sourceCode,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "Generated by the Voyager AI workflow builder.",
                        List.of(),
                        FunctionVersionStatus.DRAFT
                )
        );
        return functionQualificationService.qualify(
                function,
                version,
                modelConfig,
                name
        );
    }

    private WorkflowAiResponseDTO callAssistant(
            WorkflowAiConversation conversation,
            String task
    ) {
        return callAssistant(
                conversation,
                task,
                conversation.getModelConfig(),
                null,
                null
        );
    }

    private WorkflowAiResponseDTO callAssistant(
            WorkflowAiConversation conversation,
            String task,
            AiModelConfig modelConfig,
            WorkflowAiMessage regeneratedFromMessage,
            List<WorkflowAiMessage> historyOverride
    ) {
        // Retry/regeneration can enter here without the provision endpoint. Resolve the owner before
        // generating so every continuation keeps one durable resource-plan card.
        ensureResourcePlanMessageId(conversation);
        ChatModel model = modelResolver.resolve(modelConfig);
        BuiltPrompt prompt = buildPrompt(
                conversation,
                task,
                historyOverride,
                model
        );
        List<ChatMessage> messages = prompt.messages();
        // One turn is several model calls. The pass counter labels each of them for the live UI so a
        // repair or review pass visibly replaces the previous pass's reasoning instead of appending.
        TurnStream turnStream = openTurnStream(
                conversation,
                modelConfig,
                prompt.generalTurn() ? GENERAL_CHAT_MAX_OUTPUT_TOKENS : null
        );
        Instant startedAt = Instant.now();
        AssistantAttempt attempt = generateAssistantAttempt(
                model,
                modelConfig,
                turnStream,
                prompt.generalTurn() ? "Thinking" : "Designing the workflow",
                messages,
                prompt.generalTurn() ? GENERAL_CHAT_MAX_OUTPUT_TOKENS : null
        );
        attempt = normalizeExplicitSingleTerminalAttempt(conversation, attempt);
        // A plain-chat turn must stay chat-only. Small models, still constrained by the full response
        // schema, often emit a stub aslDefinition ({} or a blank StartAt) even for a greeting; promoting
        // or validating it wrongly drags the turn into ASL_UNDER_REVIEW. Drop any stray artifact here so
        // the reply is graded and shown as the conversation it actually is.
        attempt = normalizeGeneralChatAttempt(attempt, prompt.generalTurn());
        if (!prompt.generalTurn() && attempt.hasFunctionProposalSignal()) {
            attempt = generateAssistantAttempt(
                    model,
                    modelConfig,
                    turnStream,
                    "Reviewing the proposed function",
                    functionCreationReviewPrompt(messages, attempt.cleaned()),
                    null
            );
            attempt = normalizeExplicitSingleTerminalAttempt(conversation, attempt);
        }
        List<String> validationIssues = validateAssistantAttempt(
                conversation,
                attempt.parsed(),
                prompt.schedulingRequested(),
                prompt.generalTurn(),
                prompt.artifactRequired()
        );
        for (int generationAttempt = 1;
             !validationIssues.isEmpty()
                     && generationAttempt < MAX_ASSISTANT_GENERATION_ATTEMPTS;
             generationAttempt++) {
            boolean repairIncludesFunctionContract = !prompt.generalTurn()
                    && (attempt.hasFunctionProposalSignal()
                            || validationIssues.stream().anyMatch(
                                    issue -> issue.contains("resourcePlan.functions")
                                            && issue.contains("explicitly requested")
                            ));
            attempt = generateAssistantAttempt(
                    model,
                    modelConfig,
                    turnStream,
                    prompt.generalTurn()
                            ? "Preparing the reply"
                            : "Repairing the response (attempt " + (generationAttempt + 1)
                                    + " of " + MAX_ASSISTANT_GENERATION_ATTEMPTS + ")",
                    prompt.generalTurn()
                            ? generalChatRepairPrompt(messages)
                            : repairPrompt(
                                    messages,
                                    attempt.cleaned(),
                                    validationIssues,
                                    repairIncludesFunctionContract
                            ),
                    prompt.generalTurn() ? GENERAL_CHAT_MAX_OUTPUT_TOKENS : null
            );
            attempt = normalizeExplicitSingleTerminalAttempt(conversation, attempt);
            attempt = normalizeGeneralChatAttempt(attempt, prompt.generalTurn());
            if (!prompt.generalTurn()
                    && !repairIncludesFunctionContract
                    && attempt.hasFunctionProposalSignal()) {
                attempt = generateAssistantAttempt(
                        model,
                        modelConfig,
                        turnStream,
                        "Reviewing the proposed function",
                        functionCreationReviewPrompt(messages, attempt.cleaned()),
                        null
                );
                attempt = normalizeExplicitSingleTerminalAttempt(conversation, attempt);
            }
            validationIssues = validateAssistantAttempt(
                    conversation,
                    attempt.parsed(),
                    prompt.schedulingRequested(),
                    prompt.generalTurn(),
                    prompt.artifactRequired()
            );
        }
        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        ParsedAssistantResponse parsed = attempt.parsed();
        UUID resourcePlanOwnerId = conversation.getResourcePlanMessageId();

        JsonNode aslDefinition = parsed.aslDefinition();
        if (validationIssues.isEmpty() && aslDefinition != null) {
            conversation.setDraftAsl(serialize(aslDefinition));
            conversation.setResourcePlan(null);
            conversation.setResourcePlanMessageId(null);
            conversation.setStage(WorkflowAiConversationStage.ASL_READY);
        } else if (validationIssues.isEmpty() && parsed.hasResourcePlan()) {
            conversation.setResourcePlan(serialize(parsed.resourcePlan()));
            conversation.setStage(WorkflowAiConversationStage.RESOURCES_PROPOSED);
        } else if (validationIssues.isEmpty() && parsed.draftWorkflowPayload() != null) {
            conversation.setResourcePlan(null);
            conversation.setResourcePlanMessageId(null);
            conversation.setDraftWorkflowPayload(
                    serialize(parsed.draftWorkflowPayloadNode())
            );
            conversation.setFinalPlan(serialize(parsed.finalPlan()));
            if (parsed.draftWorkflowPayload().definition() != null) {
                conversation.setDraftAsl(serialize(parsed.draftWorkflowPayload().definition()));
            }
            conversation.setStage(WorkflowAiConversationStage.PLAN_READY);
        } else if (validationIssues.isEmpty() && parsed.stage() != null) {
            conversation.setStage(parsed.stage());
        } else if (parsed.hasDefinitionCandidate()) {
            conversation.setStage(WorkflowAiConversationStage.ASL_UNDER_REVIEW);
        }
        String assistantContent = assistantContent(
                parsed,
                attempt.cleaned(),
                validationIssues
        );

        WorkflowAiMessage assistantMessage = appendMessage(
                conversation,
                WorkflowAiMessageRole.ASSISTANT,
                assistantContent,
                jsonPayloadOrNull(attempt.cleaned()),
                modelConfig,
                attempt.thinkingExtraction().thinkingContent(),
                durationMs,
                attempt.modelResponse().tokenUsage(),
                attempt.modelResponse().finishReason() == null
                        ? null
                        : attempt.modelResponse().finishReason().name(),
                regeneratedFromMessage
        );
        if (parsed.hasResourcePlan()) {
            if (validationIssues.isEmpty()) {
                if (resourcePlanOwnerId == null) {
                    conversation.setResourcePlanMessageId(assistantMessage.getId());
                    assistantMessage.setMetadataJson(resourcePlanMessageMetadata(
                            parsed.resourcePlan(),
                            false
                    ));
                } else {
                    WorkflowAiMessage owner = messageRepository.findById(resourcePlanOwnerId)
                            .orElse(null);
                    if (owner != null) {
                        // The owner's attachment is an immutable record of what the assistant
                        // originally proposed. The conversation-level resourcePlan already holds
                        // the current unresolved subset, so do not erase completed requirements
                        // from the historical message when that subset changes.
                        assistantMessage.setMetadataJson(resourcePlanMessageMetadata(null, true));
                    } else {
                        conversation.setResourcePlanMessageId(assistantMessage.getId());
                        assistantMessage.setMetadataJson(resourcePlanMessageMetadata(
                                parsed.resourcePlan(),
                                false
                        ));
                    }
                }
            } else {
                // The structured payload is retained for diagnostics, but a rejected proposal must
                // never surface as a new actionable card through the legacy payload fallback.
                assistantMessage.setMetadataJson(resourcePlanMessageMetadata(null, true));
            }
            assistantMessage = messageRepository.saveAndFlush(assistantMessage);
        }

        return withRawReply(
                response(
                        conversation,
                        assistantContent,
                        readJson(conversation.getDraftAsl()),
                        validationIssues,
                        parsed.finalPlan() == null
                                ? readJson(conversation.getFinalPlan())
                                : parsed.finalPlan(),
                        parsed.draftWorkflowPayload() == null
                                ? readDraftPayload(conversation)
                                : parsed.draftWorkflowPayload(),
                        null,
                        message(assistantMessage)
                ),
                boundedExcerpt(attempt.cleaned(), 4000)
        );
    }

    private String assistantContent(
            ParsedAssistantResponse parsed,
            String cleanedResponse,
            List<String> validationIssues
    ) {
        if (validationIssues.isEmpty()) {
            return parsed.message() == null ? cleanedResponse : parsed.message();
        }
        // The raw reply is a debugging artifact, not a chat message. Pasting a kilobyte of broken
        // JSON into the conversation reads as a crash and buries the one thing the user can act on;
        // the full reply is logged instead.
        StringBuilder message = new StringBuilder(FAILED_VALIDATION_MESSAGE);
        if (!parsed.structured()) {
            message.append("\n\nThe model's reply wasn't valid JSON in the expected shape. "
                    + "Selecting **Retry** often fixes it; a consistently failing model usually "
                    + "means this one is too small for structured output.");
        }
        return message.toString();
    }

    private AssistantAttempt generateAssistantAttempt(
            ChatModel model,
            AiModelConfig modelConfig,
            TurnStream turnStream,
            String stageLabel,
            List<ChatMessage> messages,
            Integer maxOutputTokens
    ) {
        // Abort before spending another model call if the client cancelled between passes.
        turnRegistry.throwIfCancelled(streamBroker.currentSession());
        ChatResponse modelResponse;
        try {
            modelResponse = turnStream == null
                    ? blockingChat(model, modelConfig, messages, maxOutputTokens)
                    : turnStream.generate(model, stageLabel, messages);
        } catch (WorkflowAiCancelledException exception) {
            // A cancelled turn must stay cancelled, not be reframed as a model-call failure.
            throw exception;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException(modelCallFailureMessage(exception), exception);
        }
        String raw = requireModelReply(modelResponse);
        ThinkingExtraction thinkingExtraction = extractThinking(raw);
        String cleaned = stripMarkdown(thinkingExtraction.answer());
        return new AssistantAttempt(
                modelResponse,
                cleaned,
                thinkingExtraction,
                parseAssistantResponse(cleaned)
        );
    }

    /**
     * Runs one blocking model call in JSON mode where the endpoint supports it.
     *
     * <p>Asking for JSON in the prompt and repairing the reply afterwards costs up to three extra
     * model calls, and small models still fail — every observed failure was a syntax error the model
     * could not avoid by trying harder (an unescaped quote inside a string, a missing colon). JSON
     * mode moves the constraint into decoding, where malformed output is impossible.
     *
     * <p>A server that does not implement it rejects the request outright, so the first rejection
     * per endpoint falls back to a plain call and every later turn skips the attempt.
     */
    private ChatResponse blockingChat(
            ChatModel model,
            AiModelConfig modelConfig,
            List<ChatMessage> messages,
            Integer maxOutputTokens
    ) {
        if (modelConfig == null) {
            return promptOnlyChat(model, messages, maxOutputTokens);
        }

        AiStructuredOutputMode mode =
                modelResolver.preferredStructuredOutputMode(modelConfig);
        // Production resolvers never return null. Prompt-only is a defensive fallback for custom
        // resolver implementations and keeps focused unit-test mocks backward compatible.
        if (mode == null || mode == AiStructuredOutputMode.UNKNOWN) {
            mode = AiStructuredOutputMode.PROMPT_ONLY;
        }

        while (true) {
            try {
                ChatModel selectedModel = mode == AiStructuredOutputMode.JSON_SCHEMA
                        ? nonStrictModel(model, modelConfig)
                        : model;
                ChatResponse response = switch (mode) {
                    case STRICT_JSON_SCHEMA, JSON_SCHEMA -> selectedModel.chat(
                            structuredRequest(messages, maxOutputTokens)
                    );
                    case JSON_OBJECT -> selectedModel.chat(
                            chatRequest(messages, ResponseFormat.JSON, maxOutputTokens)
                    );
                    case PROMPT_ONLY, UNKNOWN ->
                            promptOnlyChat(selectedModel, messages, maxOutputTokens);
                };
                modelResolver.recordStructuredOutputMode(modelConfig, mode);
                return response;
            } catch (RuntimeException exception) {
                boolean recoverableStructuredOutput = isStructuredOutputRejection(exception)
                        || isStructuredContentDeserializationFailure(exception);
                if (isUnrecoverableProviderError(exception)
                        || isConnectionFailure(exception)
                        || !recoverableStructuredOutput) {
                    throw exception;
                }
                AiStructuredOutputMode fallback = weakerStructuredOutputMode(mode);
                if (fallback == null) {
                    throw exception;
                }
                log.info(
                        "AI endpoint {} could not use structured output mode {} ({}); retrying with {}",
                        modelConfig.getBaseUrl(),
                        mode,
                        exception.getMessage(),
                        fallback
                );
                modelResolver.recordStructuredOutputMode(modelConfig, fallback);
                mode = fallback;
            }
        }
    }

    private ChatModel nonStrictModel(ChatModel fallback, AiModelConfig modelConfig) {
        ChatModel nonStrict = modelResolver.resolveNonStrict(modelConfig);
        return nonStrict == null ? fallback : nonStrict;
    }

    private ChatRequest structuredRequest(
            List<ChatMessage> messages,
            Integer maxOutputTokens
    ) {
        return chatRequest(
                messages,
                WorkflowAiResponseSchema.responseFormat(),
                maxOutputTokens
        );
    }

    private ChatResponse promptOnlyChat(
            ChatModel model,
            List<ChatMessage> messages,
            Integer maxOutputTokens
    ) {
        return maxOutputTokens == null
                ? model.chat(messages)
                : model.chat(chatRequest(messages, null, maxOutputTokens));
    }

    private ChatRequest chatRequest(
            List<ChatMessage> messages,
            ResponseFormat responseFormat,
            Integer maxOutputTokens
    ) {
        ChatRequest.Builder request = ChatRequest.builder().messages(messages);
        if (responseFormat != null) {
            request.responseFormat(responseFormat);
        }
        if (maxOutputTokens != null) {
            request.maxOutputTokens(maxOutputTokens);
        }
        return request.build();
    }

    private AiStructuredOutputMode weakerStructuredOutputMode(AiStructuredOutputMode mode) {
        return switch (mode) {
            case STRICT_JSON_SCHEMA -> AiStructuredOutputMode.JSON_SCHEMA;
            case JSON_SCHEMA -> AiStructuredOutputMode.JSON_OBJECT;
            case JSON_OBJECT -> AiStructuredOutputMode.PROMPT_ONLY;
            case PROMPT_ONLY, UNKNOWN -> null;
        };
    }

    private boolean isSchemaMode(AiStructuredOutputMode mode) {
        return mode == AiStructuredOutputMode.STRICT_JSON_SCHEMA
                || mode == AiStructuredOutputMode.JSON_SCHEMA;
    }

    /**
     * True only when the provider identifies the structured-output request itself as invalid.
     */
    private boolean isStructuredOutputRejection(Throwable failure) {
        for (Throwable current = failure; current != null;
             current = current.getCause() == current ? null : current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String lowered = message.toLowerCase(Locale.ROOT);
            boolean namesConstraint = lowered.contains("response_format")
                    || lowered.contains("response format")
                    || lowered.contains("json_schema")
                    || lowered.contains("json schema")
                    || lowered.contains("json mode")
                    || lowered.contains("structured output")
                    || lowered.contains("strict schema")
                    || lowered.contains("strictjsonschema");
            if (namesConstraint && (lowered.contains("unsupported")
                    || lowered.contains("not supported")
                    || lowered.contains("unknown")
                    || lowered.contains("invalid")
                    || lowered.contains("not allowed")
                    || lowered.contains("unrecognized")
                    || lowered.contains("couldn't be met")
                    || lowered.contains("could not be met"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the provider accepted the request but returned {@code message.content} as a JSON object
     * instead of a string, so the OpenAI client cannot deserialize the response.
     *
     * <p>Some OpenAI-compatible endpoints (observed on Cloudflare Workers AI for certain models) inline
     * the parsed structured output as an object under {@code content} in schema/JSON modes. The client
     * types that field as a {@code String} and throws before any content reaches Voyager. This is an
     * HTTP-200 body-shape mismatch, not a request rejection, so it must trigger the same downgrade
     * ladder — falling back to a mode where the provider returns {@code content} as plain text.
     */
    private boolean isStructuredContentDeserializationFailure(Throwable failure) {
        for (Throwable current = failure; current != null;
             current = current.getCause() == current ? null : current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String lowered = message.toLowerCase(Locale.ROOT);
            if (lowered.contains("cannot deserialize value of type")
                    && lowered.contains("java.lang.string")
                    && lowered.contains("from object value")
                    && lowered.contains("content")) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when retrying without JSON mode cannot help — an exhausted quota or a rejected key fails
     * identically either way, and retrying would double the user's wait before the same error.
     */
    private boolean isUnrecoverableProviderError(Throwable failure) {
        String detail = null;
        for (Throwable current = failure; current != null && detail == null;
             current = current.getCause() == current ? null : current.getCause()) {
            detail = providerErrorDetail(current.getMessage());
        }
        if (detail == null) {
            return false;
        }
        String lowered = detail.toLowerCase(Locale.ROOT);
        return lowered.contains("quota")
                || lowered.contains("allocation")
                || lowered.contains("api key")
                || lowered.contains("unauthorized")
                || lowered.contains("rate limit");
    }

    /**
     * True when the endpoint could not be reached at all — a transient/environmental failure (a local
     * model still starting, a network blip), not evidence the endpoint lacks a capability. Such a
     * failure must not permanently disable streaming or JSON mode for the endpoint.
     */
    private boolean isConnectionFailure(Throwable failure) {
        for (Throwable current = failure; current != null;
             current = current.getCause() == current ? null : current.getCause()) {
            if (current instanceof java.net.ConnectException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof java.net.NoRouteToHostException
                    || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lowered = message.toLowerCase(Locale.ROOT);
                if (lowered.contains("connection refused")
                        || lowered.contains("connection reset")
                        || lowered.contains("failed to connect")
                        || lowered.contains("connect timed out")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Turns a provider failure into something the user can act on.
     *
     * <p>Model endpoints report real, fixable problems — an exhausted quota, a rejected key, a model
     * name that no longer exists — but the client library surfaces them as a raw JSON error body
     * inside a generic runtime exception, which reached the UI as "An unexpected error occurred".
     * That told the user nothing and made every provider issue look like a bug in Voyager.
     */
    private String modelCallFailureMessage(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String detail = providerErrorDetail(current.getMessage());
            if (detail != null) {
                return "The AI provider rejected the request: " + detail;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? "The AI request failed before the model replied. Retry or choose another model."
                : "The AI request failed: " + message;
    }

    /** Extracts the human-readable message from an OpenAI- or Cloudflare-shaped error body. */
    private String providerErrorDetail(String rawMessage) {
        if (rawMessage == null) {
            return null;
        }
        int start = rawMessage.indexOf('{');
        if (start < 0) {
            return null;
        }
        try {
            JsonNode body = LENIENT_MODEL_MAPPER.readTree(rawMessage.substring(start));
            JsonNode errors = body.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                String detail = errors.get(0).path("message").asString(null);
                if (detail != null && !detail.isBlank()) {
                    return boundedExcerpt(detail, 400);
                }
            }
            String openAiDetail = body.path("error").path("message").asString(null);
            if (openAiDetail != null && !openAiDetail.isBlank()) {
                return boundedExcerpt(openAiDetail, 400);
            }
        } catch (Exception exception) {
            log.debug("Provider error body was not JSON", exception);
        }
        return null;
    }

    /**
     * Returns a stream driver for this turn, or {@code null} when nobody is subscribed (the REST entry
     * points) so the turn falls back to a single blocking call.
     *
     * <p>Opened whenever a browser is subscribed, even for an endpoint currently marked non-streaming:
     * {@link TurnStream#generate} re-checks streaming support per pass and routes to a blocking call
     * when needed, but still emits a stage frame first. That keeps the frontend informed (and its idle
     * timer fed) during an otherwise frame-less blocking turn instead of leaving it spinning blind.
     */
    private TurnStream openTurnStream(
            WorkflowAiConversation conversation,
            AiModelConfig modelConfig,
            Integer maxOutputTokens
    ) {
        String sessionId = streamBroker.currentSession();
        if (sessionId == null) {
            return null;
        }
        try {
            return new TurnStream(
                    modelResolver.resolveStreaming(modelConfig),
                    modelConfig,
                    conversation.getId(),
                    sessionId,
                    maxOutputTokens
            );
        } catch (Exception exception) {
            // Streaming is a presentation nicety. If the endpoint cannot be built for it, still run
            // the turn the blocking way rather than failing the user's request.
            log.debug("Falling back to a non-streaming workflow AI turn", exception);
            return null;
        }
    }

    /**
     * Runs each model call of one turn token-by-token, forwarding reasoning to the browser as it
     * arrives and collapsing the stream back into the same {@link Response} the blocking API returns.
     */
    private final class TurnStream {
        private final StreamingChatModel model;
        private final UUID conversationId;
        /**
         * Captured on the turn's own thread. Token callbacks arrive on the HTTP client's thread,
         * where the broker's thread-bound session is not visible, so it must be passed explicitly.
         */
        private final String sessionId;
        private final AiModelConfig modelConfig;
        private final Integer maxOutputTokens;
        private int pass;

        private TurnStream(
                StreamingChatModel model,
                AiModelConfig modelConfig,
                UUID conversationId,
                String sessionId,
                Integer maxOutputTokens
        ) {
            this.model = model;
            this.modelConfig = modelConfig;
            this.conversationId = conversationId;
            this.sessionId = sessionId;
            this.maxOutputTokens = maxOutputTokens;
        }

        private ChatResponse generate(
                ChatModel blockingModel,
                String stageLabel,
                List<ChatMessage> messages
        ) {
            pass++;
            int currentPass = pass;
            streamBroker.emitStage(sessionId, conversationId, stageLabel, currentPass);

            AiStructuredOutputMode outputMode =
                    modelResolver.preferredStructuredOutputMode(modelConfig);
            // Schema-constrained decoding is not consistently streamable across compatible
            // providers. Preserve the stage event, then use the negotiated blocking path.
            if (isSchemaMode(outputMode)) {
                return blockingChat(
                        blockingModel,
                        modelConfig,
                        messages,
                        maxOutputTokens
                );
            }

            // Re-check per pass, not just when the turn opened. A turn runs up to five model calls,
            // and once one of them proves the endpoint cannot stream, every later pass must skip
            // straight to blocking instead of stalling out the idle budget again.
            if (!modelResolver.supportsStreaming(modelConfig)) {
                return blockingChat(
                        blockingModel,
                        modelConfig,
                        messages,
                        maxOutputTokens
                );
            }

            CompletableFuture<ChatResponse> completion = new CompletableFuture<>();
            // A streamed JSON-mode rejection surfaces through onError, which routes to the same
            // blocking fallback, so the request is built the same way as the blocking path.
            WorkflowAiThinkingStream splitter = new WorkflowAiThinkingStream();
            StringBuilder thinkingBuffer = new StringBuilder();
            AtomicLong lastActivityAt = new AtomicLong(System.nanoTime());
            AtomicBoolean firstTokenSeen = new AtomicBoolean(false);
            int[] answerCharacters = {0};
            int[] reportedAnswerCharacters = {0};

            model.chat(streamingRequest(messages), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    firstTokenSeen.set(true);
                    lastActivityAt.set(System.nanoTime());
                    try {
                        consume(splitter.accept(token), false);
                    } catch (Exception exception) {
                        log.debug("Could not forward a workflow AI token", exception);
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    firstTokenSeen.set(true);
                    try {
                        consume(splitter.flush(), true);
                    } catch (Exception exception) {
                        log.debug("Could not flush workflow AI stream", exception);
                    }
                    completion.complete(response);
                }

                @Override
                public void onError(Throwable error) {
                    completion.completeExceptionally(error);
                }

                private void consume(
                        List<WorkflowAiThinkingStream.Segment> segments,
                        boolean finalFlush
                ) {
                    for (WorkflowAiThinkingStream.Segment segment : segments) {
                        if (segment.phase() == WorkflowAiThinkingStream.Phase.THINKING) {
                            thinkingBuffer.append(segment.text());
                        } else {
                            answerCharacters[0] += segment.text().length();
                        }
                    }
                    // One STOMP frame per token would be thousands of frames per turn. Coalescing
                    // keeps the reveal smooth while staying an order of magnitude cheaper.
                    if (thinkingBuffer.length() >= THINKING_FLUSH_CHARACTERS || finalFlush) {
                        if (!thinkingBuffer.isEmpty()) {
                            streamBroker.emitThinking(
                                    sessionId,
                                    conversationId,
                                    thinkingBuffer.toString(),
                                    currentPass
                            );
                            thinkingBuffer.setLength(0);
                        }
                    }
                    if (answerCharacters[0] - reportedAnswerCharacters[0]
                            >= ANSWER_PROGRESS_CHARACTERS
                            || (finalFlush && answerCharacters[0] > reportedAnswerCharacters[0])) {
                        reportedAnswerCharacters[0] = answerCharacters[0];
                        streamBroker.emitAnswerProgress(
                                sessionId,
                                conversationId,
                                answerCharacters[0],
                                currentPass
                        );
                    }
                }
            });

            return awaitCompletion(completion, lastActivityAt, firstTokenSeen, blockingModel, messages);
        }

        private ChatRequest streamingRequest(List<ChatMessage> messages) {
            ChatRequest.Builder request = ChatRequest.builder().messages(messages);
            if (maxOutputTokens != null) {
                request.maxOutputTokens(maxOutputTokens);
            }
            if (modelResolver.preferredStructuredOutputMode(modelConfig)
                    == AiStructuredOutputMode.JSON_OBJECT) {
                request.responseFormat(ResponseFormat.JSON);
            }
            return request.build();
        }

        /**
         * Waits on an idle budget rather than a total one.
         *
         * <p>The handler's callbacks cannot be trusted to fire. langchain4j 0.31 throws inside its
         * own SSE failure path and kills the HTTP thread without ever calling {@code onError}, which
         * happens both when an endpoint cannot stream at all and when a stream dies part-way through.
         * Either way the future never completes, so silence — not elapsed time — is what identifies a
         * dead stream. A model that is merely slow keeps producing tokens and keeps its full budget.
         */
        private ChatResponse awaitCompletion(
                CompletableFuture<ChatResponse> completion,
                AtomicLong lastActivityAt,
                AtomicBoolean firstTokenSeen,
                ChatModel blockingModel,
                List<ChatMessage> messages
        ) {
            long overallDeadline = System.nanoTime()
                    + WorkflowAiModelResolver.REQUEST_TIMEOUT.toNanos();
            while (true) {
                try {
                    // Poll well below the idle budget: waiting a full budget per slice would let a
                    // dead stream burn up to twice the idle budget before being noticed.
                    return completion.get(STREAM_IDLE_POLL_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    // A cancel interrupts this wait; abandon the turn so its transaction rolls back.
                    if (turnRegistry.isCancelled(sessionId)) {
                        Thread.interrupted();
                        throw new WorkflowAiCancelledException();
                    }
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("The AI request was interrupted.", exception);
                } catch (ExecutionException exception) {
                    Throwable cause =
                            exception.getCause() == null ? exception : exception.getCause();
                    // A connection failure says the endpoint was unreachable this instant (a not-yet-
                    // ready local model, a blip) — not that it cannot stream. Fall back for this turn
                    // only, leaving streaming enabled so a later turn tries again once reachable.
                    if (isConnectionFailure(cause)) {
                        log.info(
                                "Streaming attempt could not reach {} ({}); using a blocking request "
                                        + "for this turn but keeping streaming enabled",
                                modelConfig.getBaseUrl(),
                                cause
                        );
                        return blockingChat(
                                blockingModel,
                                modelConfig,
                                messages,
                                maxOutputTokens
                        );
                    }
                    return fallback(blockingModel, messages, cause.toString());
                } catch (TimeoutException exception) {
                    turnRegistry.throwIfCancelled(sessionId);
                    long idleSeconds = TimeUnit.NANOSECONDS.toSeconds(
                            System.nanoTime() - lastActivityAt.get()
                    );
                    // Two budgets: be patient while the model is still reading the prompt (no token
                    // yet), then strict once tokens are flowing and a gap really does mean a dead
                    // stream. The old single budget punished a slow first token as if the stream died.
                    boolean streaming = firstTokenSeen.get();
                    long budgetSeconds = streaming ? streamIdleSeconds : streamFirstTokenSeconds;
                    if (idleSeconds >= budgetSeconds) {
                        return fallback(
                                blockingModel,
                                messages,
                                streaming
                                        ? "the stream went silent for " + idleSeconds + "s"
                                        : "no first token within " + idleSeconds + "s"
                        );
                    }
                    if (System.nanoTime() >= overallDeadline) {
                        throw new IllegalStateException(
                                "The AI model did not finish within "
                                        + WorkflowAiModelResolver.REQUEST_TIMEOUT.toSeconds()
                                        + " seconds. Retry or choose a faster model.",
                                exception
                        );
                    }
                }
            }
        }

        /**
         * Completes this pass without streaming and stops trying to stream this endpoint.
         *
         * <p>Live reasoning is a presentation nicety; the answer is the product. An endpoint that
         * cannot serve SSE must still produce workflows.
         */
        private ChatResponse fallback(
                ChatModel blockingModel,
                List<ChatMessage> messages,
                String reason
        ) {
            log.info(
                    "Streaming disabled for AI endpoint {} ({}); using a blocking request",
                    modelConfig.getBaseUrl(),
                    reason
            );
            modelResolver.markStreamingUnsupported(modelConfig);
            return blockingChat(
                    blockingModel,
                    modelConfig,
                    messages,
                    maxOutputTokens
            );
        }
    }

    private List<ChatMessage> repairPrompt(
            List<ChatMessage> originalPrompt,
            String rejectedResponse,
            List<String> validationIssues,
            boolean includeFunctionCreationContract
    ) {
        List<ChatMessage> repairMessages = new ArrayList<>(originalPrompt);
        if (includeFunctionCreationContract) {
            repairMessages.add(
                    Math.min(1, repairMessages.size()),
                    SystemMessage.systemMessage(functionCreationContext())
            );
        }
        repairMessages.add(AiMessage.aiMessage(rejectedResponse));
        String repairInstruction = """
                Your previous response was rejected, often because the JSON was truncated or incomplete.
                Return exactly one COMPLETE strict JSON object matching the workflow response contract; keep it small
                enough to finish in full, with brief reasoning.
                Do not return Markdown, an Adaptive Card, a tool-call envelope, or commentary outside JSON.
                If a requested action needs a function or MCP tool that is not in the catalog, do NOT force an
                aslDefinition: return "stage":"RESOURCES_PROPOSED" with a complete resourcePlan (functions and/or
                mcpRequirements) and omit aslDefinition. Otherwise, preserve the user's requested workflow and return
                JSONata-only ASL when aslDefinition is present.
                Never return ASL_READY without aslDefinition, RESOURCES_PROPOSED without at least one concrete
                resource, COLLECTING_SCHEDULE_DETAILS before valid ASL, or PLAN_READY without a complete
                draftWorkflowPayload containing valid ASL. If the rejected reply told the user to attach, choose, or
                provide an external service, API, MCP server, tool, or credential, replace that deflection with a
                concrete mcpRequirement. Use a complete function proposal instead for deterministic local parsing,
                math, hashing, validation, or formatting. Scheduling is opt-in: do not ask about frequency, cron, or
                timezone unless the user explicitly requested a schedule.
                Re-read the available Voyager Task catalog in the system context and keep every matching exact URI.
                If a rejection says an MCP capability already exists as voyager://mcp/..., do not propose that
                capability again. Remove it from resourcePlan and generate an aslDefinition whose Task Resource is
                that exact URI. The Task must use JSONata Arguments and Output; never use InputPath, ResultPath,
                ResultSelector, OutputPath, or a draft payload with $StartAt.
                In Task Arguments use $states.input or $states.context only, never $states.result. Use $states.result
                only inside that same Task's Output. State output becomes the next state's $states.input, so preserve
                each result under a named Output property and read it later from $states.input.<property>.
                Never reference a previous state's $states.result.
                Spell $states exactly; $.states is invalid. For MCP Tasks, use exactly the top-level Arguments keys
                printed in that resource's catalog args object and never a generic payload wrapper unless listed.
                Read MCP business result fields from $states.result.structuredContent.<field>.
                When no MCP output schema is listed, preserve all of $states.result.structuredContent instead of
                guessing a nested wrapper property.
                End the last requested Task with End:true instead of adding a terminal Pass. Pass uses Output,
                never the JSONPath-only Result field.
                When repairing a function proposal, return only the small RESOURCES_PROPOSED response needed now:
                stage, message, and resourcePlan. Omit aslDefinition, finalPlan, and draftWorkflowPayload. Every
                proposed function must contain name, description, the AI default languageId, non-null complete
                sourceCode, testCases:null, and rationale.
                Rejection reasons:
                """ + String.join("\n", validationIssues);
        if (includeFunctionCreationContract) {
            // Repeat the short dynamic list at the very end. Small models often replace an earlier
            // catalog value with a memorized Judge0 ID (for example 13 for JavaScript).
            repairInstruction += "\n\nCopy the AI default languageId exactly:\n"
                    + resourceCatalogService.buildFunctionCreationContext();
        }
        repairMessages.add(UserMessage.userMessage(repairInstruction));
        return repairMessages;
    }

    private List<ChatMessage> functionCreationReviewPrompt(
            List<ChatMessage> originalPrompt,
            String proposedResponse
    ) {
        List<ChatMessage> reviewMessages = new ArrayList<>(originalPrompt);
        reviewMessages.add(
                Math.min(1, reviewMessages.size()),
                SystemMessage.systemMessage(functionCreationContext())
        );
        reviewMessages.add(AiMessage.aiMessage(proposedResponse));
        reviewMessages.add(UserMessage.userMessage("""
                You chose to propose one or more functions. Re-review the complete proposal against the
                function creation contract. Correct the function names, language IDs, source code, JSON I/O,
                descriptions, and rationales. If a proposed function actually needs a network,
                external service, or credential, replace it with an mcpRequirement. Return the complete corrected
                response as strict JSON only, using this minimal shape:
                {"stage":"RESOURCES_PROPOSED","message":"<brief>","resourcePlan":{"functions":[{
                "name":"<kebab-case>","description":"<brief>","languageId":<exact AI default numeric ID>,
                "sourceCode":"<complete escaped single-file program>","testCases":null,
                "rationale":"<brief>"}],"mcpRequirements":[]}}
                Omit aslDefinition, finalPlan, and draftWorkflowPayload. sourceCode must not be null.
                
                Copy the AI default languageId below immediately before answering:
                """ + resourceCatalogService.buildFunctionCreationContext()));
        return reviewMessages;
    }

    private List<ChatMessage> generalChatRepairPrompt(List<ChatMessage> originalPrompt) {
        List<ChatMessage> repairMessages = new ArrayList<>(originalPrompt);
        repairMessages.add(UserMessage.userMessage("""
                Your previous reply was not valid JSON. Answer the user's conversational message again.
                Return only this small strict JSON object, with one natural sentence and nothing else:
                {"stage":"COLLECTING_WORKFLOW_DETAILS","message":"<friendly reply>"}
                Do not return workflow fields, ASL, functions, MCP requirements, plans, or Markdown.
                """));
        return repairMessages;
    }

    private String functionCreationContext() {
        return FUNCTION_CREATION_PROMPT
                + "\n\n"
                + resourceCatalogService.buildFunctionCreationContext();
    }

    /**
     * Strips every workflow artifact from a parsed reply, keeping only the stage and message, so a
     * general-chat turn is treated as pure conversation. The stage is pinned to
     * COLLECTING_WORKFLOW_DETAILS because a chat turn never advances the build.
     */
    private AssistantAttempt normalizeExplicitSingleTerminalAttempt(
            WorkflowAiConversation conversation,
            AssistantAttempt attempt
    ) {
        ParsedAssistantResponse parsed = attempt.parsed();
        if (!parsed.structured() || parsed.hasResourcePlan()) {
            return attempt;
        }
        Matcher requestedState = EXPLICIT_SINGLE_TERMINAL_STATE.matcher(
                Objects.toString(conversation.getInitialInstruction(), "")
        );
        if (!requestedState.find()) {
            return attempt;
        }

        JsonNode candidate = parsed.aslDefinition();
        if (candidate == null && parsed.draftWorkflowPayload() != null) {
            candidate = parsed.draftWorkflowPayload().definition();
        }
        if (candidate == null || !candidate.isArray() || candidate.size() != 1) {
            return attempt;
        }
        JsonNode stateCandidate = candidate.get(0);
        String requestedType = requestedState.group(1);
        if (stateCandidate == null
                || !stateCandidate.isObject()
                || !requestedType.equalsIgnoreCase(
                        stateCandidate.path("Type").asText("")
                )) {
            return attempt;
        }

        String stateName = requestedState.group(2);
        String canonicalType = "Fail".equalsIgnoreCase(requestedType)
                ? "Fail"
                : "Succeed";
        ObjectNode terminalState = objectMapper.createObjectNode();
        terminalState.put("Type", canonicalType);
        ObjectNode states = objectMapper.createObjectNode();
        states.set(stateName, terminalState);
        ObjectNode machine = objectMapper.createObjectNode();
        machine.put("StartAt", stateName);
        machine.set("States", states);

        ParsedAssistantResponse normalized = new ParsedAssistantResponse(
                WorkflowAiConversationStage.ASL_READY,
                parsed.message(),
                machine,
                null,
                null,
                null,
                null,
                true,
                null
        );
        return new AssistantAttempt(
                attempt.modelResponse(),
                attempt.cleaned(),
                attempt.thinkingExtraction(),
                normalized
        );
    }

    private AssistantAttempt normalizeGeneralChatAttempt(
            AssistantAttempt attempt,
            boolean generalTurn
    ) {
        if (!generalTurn || !attempt.parsed().structured()) {
            return attempt;
        }
        return new AssistantAttempt(
                attempt.modelResponse(),
                attempt.cleaned(),
                attempt.thinkingExtraction(),
                chatOnly(attempt.parsed())
        );
    }

    private ParsedAssistantResponse chatOnly(ParsedAssistantResponse parsed) {
        return new ParsedAssistantResponse(
                WorkflowAiConversationStage.COLLECTING_WORKFLOW_DETAILS,
                parsed.message(),
                null,
                null,
                null,
                null,
                null,
                true,
                null
        );
    }

    private List<String> validateAssistantAttempt(
            WorkflowAiConversation conversation,
            ParsedAssistantResponse parsed,
            boolean schedulingRequested,
            boolean generalTurn,
            boolean artifactRequired
    ) {
        List<String> issues = new ArrayList<>();
        if (!parsed.structured()) {
            issues.add(INVALID_AI_RESPONSE + " " + parsed.failureReason());
            return List.copyOf(issues);
        }
        issues.addAll(validateStageContract(
                conversation,
                parsed,
                schedulingRequested,
                generalTurn,
                artifactRequired
        ));
        if (parsed.aslDefinition() != null) {
            issues.addAll(validateExecutableDefinition(parsed.aslDefinition()));
        }
        JsonNode payloadDefinition = parsed.draftWorkflowPayload() == null
                ? null
                : parsed.draftWorkflowPayload().definition();
        if (payloadDefinition != null) {
            issues.addAll(validateExecutableDefinition(payloadDefinition));
        }
        if (parsed.aslDefinition() != null
                && payloadDefinition != null
                && !parsed.aslDefinition().equals(payloadDefinition)) {
            issues.add(
                    "[AI_RESPONSE] aslDefinition and draftWorkflowPayload.definition must match."
            );
        }
        boolean explicitLocalFunctionRequested = EXPLICIT_LOCAL_FUNCTION_REQUEST.matcher(
                Objects.toString(conversation.getInitialInstruction(), "")
        ).matches();
        boolean hasFunctionProposal = parsed.resourcePlan() != null
                && parsed.resourcePlan().functions() != null
                && parsed.resourcePlan().functions().stream().anyMatch(Objects::nonNull);
        boolean hasFunctionTask = containsTaskResource(
                parsed.aslDefinition(),
                "voyager://function/"
        ) || containsTaskResource(payloadDefinition, "voyager://function/");
        if (explicitLocalFunctionRequested && !hasFunctionProposal && !hasFunctionTask) {
            issues.add(
                    "[AI_RESOURCE_PLAN] The user explicitly requested a deterministic local "
                            + "function. Put the implementation in resourcePlan.functions with "
                            + "the AI default languageId and complete JSON stdin/stdout sourceCode; "
                            + "do not invent another Task resource or classify it as MCP."
            );
        }
        if (parsed.hasResourcePlan()) {
            if (parsed.resourcePlan().functions() != null) {
                Set<String> proposedFunctionNames = new HashSet<>();
                FunctionLanguageDTO defaultLanguage = functionRuntimePolicy.aiDefaultLanguage();
                if (!parsed.resourcePlan().functions().isEmpty()
                        && EXACT_SINGLE_SUCCEED_REQUEST.matcher(
                                Objects.toString(conversation.getInitialInstruction(), "")
                        ).matches()) {
                    issues.add(
                            "[AI_RESOURCE_PLAN] Succeed is a built-in ASL state and does not need "
                                    + "a function. Return the requested one-state Succeed machine."
                    );
                }
                for (WorkflowAiProposedFunctionDTO function :
                        parsed.resourcePlan().functions()) {
                    if (function == null) {
                        issues.add("[AI_RESOURCE_PLAN] Proposed function cannot be null.");
                        continue;
                    }
                    String functionName = normalizeFunctionName(function.name());
                    if (functionName.isBlank()) {
                        issues.add(
                                "[AI_RESOURCE_PLAN] Proposed function is missing a valid name."
                        );
                    } else if (!proposedFunctionNames.add(functionName)) {
                        issues.add(
                                "[AI_RESOURCE_PLAN] Proposed function name is duplicated: "
                                        + functionName
                        );
                    }
                    if (defaultLanguage == null) {
                        issues.add(
                                "[AI_RESOURCE_PLAN] No AI default function language is available."
                        );
                    } else if (function.languageId() == null) {
                        issues.add(
                                "[AI_RESOURCE_PLAN] Function '" + functionName
                                        + "' is missing languageId. Always use "
                                        + defaultLanguage.id() + " (" + defaultLanguage.name() + ")."
                        );
                    } else if (function.languageId() != defaultLanguage.id()) {
                        issues.add(
                                "[AI_RESOURCE_PLAN] Function '" + functionName
                                        + "' must use the AI default languageId "
                                        + defaultLanguage.id() + " (" + defaultLanguage.name()
                                        + "), not " + function.languageId() + "."
                        );
                    }
                    if (optionalText(function.sourceCode()) == null) {
                        issues.add(
                                "[AI_RESOURCE_PLAN] Function '" + functionName
                                        + "' is missing sourceCode."
                        );
                    }
                    proposedFunctionSafetyValidator.validate(function)
                            .forEach(issue -> issues.add(
                                    "[AI_RESOURCE_PLAN] Function '" + functionName + "': " + issue
                            ));
                }
            }
            if (parsed.resourcePlan().mcpRequirements() != null) {
                Set<String> proposedMcpCapabilities = new HashSet<>();
                for (WorkflowAiMcpRequirementDTO requirement :
                        parsed.resourcePlan().mcpRequirements()) {
                    if (requirement == null) {
                        issues.add("[AI_RESOURCE_PLAN] MCP requirement cannot be null.");
                        continue;
                    }
                    String capability = optionalText(requirement.capability());
                    if (capability == null) {
                        issues.add(
                                "[AI_RESOURCE_PLAN] MCP requirement is missing a capability. "
                                        + "Describe the external action the server must provide."
                        );
                    } else if (!proposedMcpCapabilities.add(
                            capability.toLowerCase(Locale.ROOT)
                    )) {
                        issues.add(
                                "[AI_RESOURCE_PLAN] MCP capability is duplicated: " + capability
                        );
                    }
                    String suggestedTool = optionalText(requirement.suggestedToolName());
                    if (suggestedTool != null
                            && suggestedTool.toLowerCase(Locale.ROOT)
                            .startsWith("voyager://function/")) {
                        issues.add(
                                "[AI_RESOURCE_PLAN] Capability '" + capability
                                        + "' was incorrectly proposed as MCP tool '"
                                        + suggestedTool + "'. Put deterministic local logic in "
                                        + "resourcePlan.functions with complete sourceCode instead."
                        );
                    }
                }
            }
            for (WorkflowAiResourceCatalogService.McpRequirementMatch match :
                    resourceCatalogService.findMcpRequirementMatches(
                            parsed.resourcePlan().mcpRequirements()
                    )) {
                issues.add(
                        "[AI_RESOURCE_PLAN] MCP capability '" + match.capability()
                                + "' already exists in the live catalog as " + match.resourceUri()
                                + ". Use that exact Task Resource and do not propose it again."
                );
            }
        }
        return List.copyOf(issues);
    }

    private boolean containsTaskResource(JsonNode node, String resourcePrefix) {
        if (node == null || resourcePrefix == null) {
            return false;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (containsTaskResource(item, resourcePrefix)) {
                    return true;
                }
            }
            return false;
        }
        if (!node.isObject()) {
            return false;
        }
        for (java.util.Map.Entry<String, JsonNode> entry : node.properties()) {
            if ("Resource".equals(entry.getKey())
                    && entry.getValue().isTextual()
                    && entry.getValue().textValue().startsWith(resourcePrefix)) {
                return true;
            }
            if (containsTaskResource(entry.getValue(), resourcePrefix)) {
                return true;
            }
        }
        return false;
    }

    private List<String> validateStageContract(
            WorkflowAiConversation conversation,
            ParsedAssistantResponse parsed,
            boolean schedulingRequested,
            boolean generalTurn,
            boolean artifactRequired
    ) {
        List<String> issues = new ArrayList<>();
        WorkflowAiConversationStage stage = parsed.stage();
        boolean hasPersistedDefinition = optionalText(conversation.getDraftAsl()) != null;
        boolean hasDefinition = parsed.hasDefinitionCandidate() || hasPersistedDefinition;

        if (stage == WorkflowAiConversationStage.ASL_READY
                && parsed.aslDefinition() == null) {
            issues.add(
                    "[AI_RESPONSE] ASL_READY requires a valid aslDefinition."
            );
        }
        if (stage == WorkflowAiConversationStage.RESOURCES_PROPOSED
                && !parsed.hasResourcePlan()) {
            issues.add(
                    "[AI_RESOURCE_PLAN] RESOURCES_PROPOSED requires a non-empty resourcePlan "
                            + "with at least one concrete function or MCP requirement."
            );
        }
        if (stage == WorkflowAiConversationStage.COLLECTING_SCHEDULE_DETAILS) {
            if (!hasDefinition) {
                issues.add(
                        "[AI_RESPONSE] COLLECTING_SCHEDULE_DETAILS requires valid ASL first. "
                                + "Return ASL_READY or a concrete RESOURCES_PROPOSED plan."
                );
            }
            if (!schedulingRequested) {
                issues.add(
                        "[AI_RESPONSE] Scheduling is opt-in. Do not ask for schedule details "
                                + "unless the user explicitly requested a schedule."
                );
            }
        }
        if (stage == WorkflowAiConversationStage.COLLECTING_WORKFLOW_DETAILS
                && !hasDefinition
                && PREMATURE_WORKFLOW_NAME_QUESTION.matcher(parsed.message()).matches()) {
            issues.add(
                    "[AI_RESPONSE] Do not collect the workflow name before valid ASL exists. "
                            + "First produce ASL or ask only for missing behavioral details."
            );
        }
        if (stage == WorkflowAiConversationStage.PLAN_READY) {
            if (!hasDefinition) {
                issues.add(
                        "[AI_RESPONSE] PLAN_READY requires a valid ASL definition."
                );
            }
            if (parsed.draftWorkflowPayload() == null
                    || parsed.draftWorkflowPayload().definition() == null) {
                issues.add(
                        "[AI_RESPONSE] PLAN_READY requires a complete draftWorkflowPayload "
                                + "containing the workflow definition."
                );
            }
        }
        if (!parsed.hasResourcePlan()
                && !parsed.hasDefinitionCandidate()
                && parsed.draftWorkflowPayload() == null
                && MISSING_RESOURCE_DEFLECTION.matcher(parsed.message()).matches()) {
            issues.add(
                    "[AI_RESOURCE_PLAN] The reply says a service or tool is missing but does not "
                            + "provide a concrete resourcePlan. External capabilities must be "
                            + "represented as mcpRequirements."
            );
        }
        if (!parsed.hasResourcePlan()
                && !parsed.hasDefinitionCandidate()
                && parsed.draftWorkflowPayload() == null
                && MISSING_FUNCTION_DEFLECTION.matcher(parsed.message()).matches()) {
            issues.add(
                    "[AI_RESOURCE_PLAN] The reply says a local function is needed but does not "
                            + "provide a complete resourcePlan.functions proposal."
            );
        }
        if (!generalTurn
                && artifactRequired
                && stage == WorkflowAiConversationStage.COLLECTING_WORKFLOW_DETAILS
                && !parsed.hasResourcePlan()
                && !parsed.hasDefinitionCandidate()
                && parsed.draftWorkflowPayload() == null
                && !asksClarifyingQuestion(parsed.message())) {
            issues.add(
                    "[AI_RESPONSE] The user explicitly requested a workflow artifact, but the "
                            + "reply neither produced one nor asked a concrete clarification."
            );
        }
        return List.copyOf(issues);
    }

    private boolean asksClarifyingQuestion(String message) {
        String text = optionalText(message);
        if (text == null) {
            return false;
        }
        return text.contains("?") || CLARIFICATION_QUESTION.matcher(text).matches();
    }

    private String requireModelReply(ChatResponse modelResponse) {
        String reply = modelResponse == null || modelResponse.aiMessage() == null
                ? null
                : modelResponse.aiMessage().text();
        if (reply == null || reply.isBlank()) {
            TokenUsage tokenUsage = modelResponse == null ? null : modelResponse.tokenUsage();
            log.warn(
                    "AI model returned an empty reply (output tokens: {}, finish reason: {})",
                    tokenUsage == null ? null : tokenUsage.outputTokenCount(),
                    modelResponse == null ? null : modelResponse.finishReason()
            );
            throw new IllegalStateException(
                    "The AI model returned an empty reply. Retry or choose a different model."
            );
        }
        return reply.trim();
    }

    /**
     * The assembled prompt plus turn intent used by response-contract validation.
     */
    private record BuiltPrompt(
            List<ChatMessage> messages,
            boolean generalTurn,
            boolean schedulingRequested,
            boolean artifactRequired
    ) {
    }

    private BuiltPrompt buildPrompt(
            WorkflowAiConversation conversation,
            String task,
            List<WorkflowAiMessage> historyOverride,
            ChatModel model
    ) {
        List<WorkflowAiMessage> rawHistory = historyOverride == null
                ? messageRepository.findByConversationOrderByCreatedAtAsc(conversation)
                : historyOverride;
        List<WorkflowAiMessage> effectiveHistory = effectiveHistory(rawHistory);
        // A general/chat turn doesn't need the resource catalog; skipping it shrinks the prompt a lot
        // and speeds up the reply. Building turns always get the full catalog.
        String lastUserMessage = effectiveHistory.stream()
                .filter(message -> message.getRole() == WorkflowAiMessageRole.USER)
                .reduce((first, second) -> second)
                .map(WorkflowAiMessage::getContent)
                .orElse(null);
        String intentMessage = lastUserMessage == null
                ? conversation.getInitialInstruction()
                : lastUserMessage;
        boolean generalTurn = isGeneralChatTurn(
                conversation,
                intentMessage
        );
        // A plain-chat turn uses the slim prompt (so a small model stops deflecting into "what's the
        // workflow name?") and skips the catalog entirely. Building turns get the full builder prompt.
        String systemPrompt = generalTurn ? GENERAL_CHAT_SYSTEM_PROMPT : SYSTEM_PROMPT;

        // Cache-friendly ordering: the stable blocks (system prompt, then the catalog) go first and
        // stay byte-identical across turns, so a server that caches the KV of an unchanged prompt
        // prefix (Ollama, vLLM, SGLang) only re-evaluates the new tail. Everything that changes per
        // turn — stage, task, latest ASL, settings — is emitted last, after the chat history, where it
        // also lands closest to where the model starts generating.
        String catalogContext = generalTurn ? null : resourceCatalogContext();
        String turnContext = turnContext(conversation, task);
        String exactIdentifiers = exactSourceIdentifiers(effectiveHistory);
        if (exactIdentifiers != null) {
            turnContext += "\nExact source identifiers (verbatim):\n" + exactIdentifiers;
        }
        int fixedContextTokens = estimatedTokens(systemPrompt)
                + (catalogContext == null ? 0 : estimatedTokens(catalogContext))
                + estimatedTokens(turnContext);
        ContextWindow contextWindow = compactContextIfNeeded(
                conversation,
                effectiveHistory,
                model,
                fixedContextTokens,
                historyOverride == null
        );

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.systemMessage(systemPrompt));
        if (catalogContext != null) {
            messages.add(SystemMessage.systemMessage(catalogContext));
        }
        if (contextWindow.summary() != null) {
            messages.add(SystemMessage.systemMessage(
                    "Summary of earlier conversation turns:\n" + contextWindow.summary()
            ));
        }
        for (WorkflowAiMessage message : contextWindow.recentMessages()) {
            if (message.getRole() == WorkflowAiMessageRole.ASSISTANT) {
                messages.add(AiMessage.aiMessage(message.getContent()));
            } else if (message.getRole() == WorkflowAiMessageRole.USER) {
                messages.add(UserMessage.userMessage(message.getContent()));
            }
        }
        // The volatile turn context is emitted last so it never invalidates the cached prefix above.
        messages.add(SystemMessage.systemMessage(turnContext));
        return new BuiltPrompt(
                messages,
                generalTurn,
                schedulingRequested(conversation, intentMessage),
                artifactRequired(conversation, intentMessage)
        );
    }

    /**
     * True when this turn is a greeting or a general question rather than a request to build or change
     * a workflow — the case where the model can answer directly and does not need the resource catalog.
     * Conservative: any workflow already in progress, or any build-imperative phrasing, disqualifies it.
     */
    private boolean isGeneralChatTurn(WorkflowAiConversation conversation, String lastUserMessage) {
        if (conversation.getStage() != WorkflowAiConversationStage.COLLECTING_WORKFLOW_DETAILS) {
            return false;
        }
        if (optionalText(conversation.getDraftAsl()) != null) {
            return false;
        }
        if (lastUserMessage == null || lastUserMessage.isBlank()) {
            return false;
        }
        String text = lastUserMessage.trim();
        return GENERAL_CHAT_OPENER.matcher(text).matches()
                && !BUILD_IMPERATIVE_OPENER.matcher(text).matches();
    }

    private boolean schedulingRequested(
            WorkflowAiConversation conversation,
            String lastUserMessage
    ) {
        Boolean currentIntent = explicitScheduleIntent(lastUserMessage);
        if (currentIntent != null) {
            return currentIntent;
        }
        return Boolean.TRUE.equals(explicitScheduleIntent(
                conversation.getInitialInstruction()
        ));
    }

    private Boolean explicitScheduleIntent(String value) {
        String text = optionalText(value);
        if (text == null) {
            return null;
        }
        if (SCHEDULE_OPT_OUT.matcher(text).find()) {
            return false;
        }
        return SCHEDULE_REQUEST.matcher(text).find() ? true : null;
    }

    private boolean artifactRequired(
            WorkflowAiConversation conversation,
            String lastUserMessage
    ) {
        return matchesExplicitArtifactRequest(lastUserMessage)
                || matchesExplicitArtifactRequest(conversation.getInitialInstruction());
    }

    private boolean matchesExplicitArtifactRequest(String value) {
        String text = optionalText(value);
        return text != null && EXPLICIT_ARTIFACT_REQUEST.matcher(text).matches();
    }

    /**
     * The resource catalog block. Stable across turns unless the registry itself changes, so it is
     * emitted as its own leading message to stay inside the cacheable prompt prefix.
     */
    private String resourceCatalogContext() {
        return "Available Voyager Task resources (current registry):\n"
                + resourceCatalogService.buildCatalog();
    }

    /**
     * The per-turn context that changes as the conversation progresses (stage, task, latest ASL,
     * settings). Emitted last so it never invalidates the cached prefix above it.
     */
    private String turnContext(
            WorkflowAiConversation conversation,
            String task
    ) {
        StringBuilder context = new StringBuilder()
                .append("Current stage: ")
                .append(conversation.getStage())
                .append("\nInitial request: ")
                .append(conversation.getInitialInstruction())
                .append("\nTask: ")
                .append(task);
        if (optionalText(conversation.getDraftAsl()) != null) {
            context.append("\nLatest ASL definition (authoritative):\n")
                    .append(conversation.getDraftAsl());
        }
        if (optionalText(conversation.getFinalPlan()) != null) {
            context.append("\nLatest final plan:\n")
                    .append(conversation.getFinalPlan());
        }
        if (optionalText(conversation.getWorkspaceSettings()) != null) {
            context.append("\nLatest workflow settings (outside ASL):\n")
                    .append(conversation.getWorkspaceSettings());
        }
        return context.toString();
    }

    private List<WorkflowAiMessage> effectiveHistory(List<WorkflowAiMessage> history) {
        Set<UUID> supersededMessageIds = new HashSet<>();
        for (WorkflowAiMessage message : history) {
            if (message.getRegeneratedFromMessage() != null
                    && message.getRegeneratedFromMessage().getId() != null) {
                supersededMessageIds.add(message.getRegeneratedFromMessage().getId());
            }
        }
        return history.stream()
                .filter(message -> message.getRole() == WorkflowAiMessageRole.USER
                        || message.getRole() == WorkflowAiMessageRole.ASSISTANT)
                .filter(message -> message.getId() == null
                        || !supersededMessageIds.contains(message.getId()))
                .toList();
    }

    private String exactSourceIdentifiers(List<WorkflowAiMessage> history) {
        Set<String> identifiers = new LinkedHashSet<>();
        int totalCharacters = 0;
        for (WorkflowAiMessage message : history) {
            Matcher matcher = DISTINCTIVE_IDENTIFIER.matcher(message.getContent());
            while (matcher.find() && identifiers.size() < 64) {
                String identifier = matcher.group();
                if (identifier.length() <= 120
                        && !identifiers.contains(identifier)
                        && totalCharacters + identifier.length() + 2 <= 2000) {
                    identifiers.add(identifier);
                    totalCharacters += identifier.length() + 2;
                }
            }
        }
        return identifiers.isEmpty() ? null : String.join(", ", identifiers);
    }

    private ContextWindow compactContextIfNeeded(
            WorkflowAiConversation conversation,
            List<WorkflowAiMessage> history,
            ChatModel model,
            int fixedContextTokens,
            boolean persistSummary
    ) {
        String existingSummary = optionalText(conversation.getConversationSummary());
        int unsummarizedStart = summarizedHistoryEnd(
                history,
                existingSummary,
                conversation.getSummarizedThroughMessageId()
        );
        if (unsummarizedStart < 0) {
            existingSummary = null;
            unsummarizedStart = 0;
        }
        if (existingSummary != null
                && unsummarizedStart > 0
                && !summaryIsGrounded(
                        existingSummary,
                        history.subList(0, unsummarizedStart)
                )) {
            // Legacy/free-form summaries can be fluent but unrelated to the source. Because all
            // messages remain stored, discard the bad summary and rebuild from source anchors.
            log.warn("Discarding ungrounded workflow conversation summary and rebuilding it");
            existingSummary = null;
            unsummarizedStart = 0;
        }

        int estimatedTotal = fixedContextTokens + estimatedTokens(existingSummary);
        for (int index = unsummarizedStart; index < history.size(); index++) {
            estimatedTotal += estimatedTokens(history.get(index));
        }

        int contextLimit = Math.max(256, maximumContextTokens);
        if (estimatedTotal <= contextLimit) {
            return new ContextWindow(
                    existingSummary,
                    List.copyOf(history.subList(unsummarizedStart, history.size()))
            );
        }

        int recentLimit = Math.max(
                64,
                Math.min(recentContextTokens, Math.max(64, contextLimit / 2))
        );
        int retainFrom = history.size();
        int retainedTokens = 0;
        while (retainFrom > unsummarizedStart) {
            int nextTokens = estimatedTokens(history.get(retainFrom - 1));
            if (retainFrom < history.size()
                    && retainedTokens + nextTokens > recentLimit) {
                break;
            }
            retainFrom--;
            retainedTokens += nextTokens;
        }

        if (retainFrom <= unsummarizedStart) {
            // There is no older prefix to replace; the durable ASL or latest turn itself is large.
            return new ContextWindow(
                    existingSummary,
                    List.copyOf(history.subList(unsummarizedStart, history.size()))
            );
        }

        // Rebuild the durable summary from every older source turn. This prevents repeated model
        // compaction from gradually dropping identifiers and decisions that are still in the DB.
        List<WorkflowAiMessage> messagesToSummarize = List.copyOf(
                history.subList(0, retainFrom)
        );
        String nextSummary = summarizeHistory(
                model,
                null,
                messagesToSummarize,
                contextLimit
        );

        WorkflowAiMessage summarizedThrough = messagesToSummarize.get(
                messagesToSummarize.size() - 1
        );
        if (persistSummary && summarizedThrough.getId() != null) {
            conversation.setConversationSummary(nextSummary);
            conversation.setSummarizedThroughMessageId(summarizedThrough.getId());
            conversationRepository.save(conversation);
        }

        return new ContextWindow(
                nextSummary,
                List.copyOf(history.subList(retainFrom, history.size()))
        );
    }

    private int summarizedHistoryEnd(
            List<WorkflowAiMessage> history,
            String summary,
            UUID summarizedThroughMessageId
    ) {
        if (summary == null || summarizedThroughMessageId == null) {
            return summary == null ? 0 : -1;
        }
        for (int index = 0; index < history.size(); index++) {
            if (summarizedThroughMessageId.equals(history.get(index).getId())) {
                return index + 1;
            }
        }
        return -1;
    }

    private String summarizeHistory(
            ChatModel model,
            String existingSummary,
            List<WorkflowAiMessage> messages,
            int contextLimit
    ) {
        int targetTokens = Math.max(128, Math.min(1500, contextLimit / 4));
        StringBuilder source = new StringBuilder()
                .append("Keep the summary under approximately ")
                .append(targetTokens)
                .append(" tokens.\n");
        if (existingSummary != null) {
            source.append("\nExisting durable summary:\n")
                    .append(existingSummary)
                    .append('\n');
        }
        int sourceBudget = Math.max(
                2048,
                Math.min(maximumSummaryCharacters * 2, contextLimit * 3)
        );
        source.append("\nSource-anchored older turns to summarize:\n")
                .append(summarySourceAnchors(messages, sourceBudget));

        try {
            ChatResponse summaryResponse = model.chat(List.of(
                    SystemMessage.systemMessage(SUMMARY_SYSTEM_PROMPT),
                    UserMessage.userMessage(source.toString())
            ));
            String generatedSummary = summaryResponse == null
                    || summaryResponse.aiMessage() == null
                    ? null
                    : summaryResponse.aiMessage().text();
            if (generatedSummary == null || generatedSummary.isBlank()) {
                throw new IllegalStateException("Summary model returned an empty reply");
            }
            String cleanedSummary = extractThinking(generatedSummary.trim()).answer();
            if (!summaryIsGrounded(cleanedSummary, messages)) {
                log.warn("Summary model omitted all distinctive source identifiers; using fallback");
                return fallbackSummary(existingSummary, messages);
            }
            return anchoredSummary(existingSummary, cleanedSummary, messages);
        } catch (RuntimeException exception) {
            // A summary refresh should not make the user's actual turn unavailable. The bounded
            // deterministic fallback keeps the context usable and the next refresh can improve it.
            log.warn("Could not generate workflow conversation summary; using bounded fallback", exception);
            return fallbackSummary(existingSummary, messages);
        }
    }

    private String fallbackSummary(
            String existingSummary,
            List<WorkflowAiMessage> messages
    ) {
        StringBuilder summary = new StringBuilder();
        if (existingSummary != null) {
            summary.append(existingSummary).append('\n');
        }
        summary.append("Source anchors (authoritative excerpts):\n")
                .append(summarySourceAnchors(
                        messages,
                        Math.max(256, maximumSummaryCharacters - summary.length() - 64)
                ));
        return limitSummary(summary.toString().trim());
    }

    private String anchoredSummary(
            String existingSummary,
            String generatedSummary,
            List<WorkflowAiMessage> messages
    ) {
        int limit = Math.max(512, maximumSummaryCharacters);
        StringBuilder summary = new StringBuilder();
        if (existingSummary != null) {
            summary.append(boundedExcerpt(existingSummary, limit / 4)).append('\n');
        }
        summary.append("Factual summary:\n")
                .append(boundedExcerpt(generatedSummary, limit / 3))
                .append("\nSource anchors (authoritative excerpts):\n");
        int remaining = Math.max(256, limit - summary.length());
        summary.append(summarySourceAnchors(messages, remaining));
        return limitSummary(summary.toString().trim());
    }

    private String summarySourceAnchors(
            List<WorkflowAiMessage> messages,
            int characterBudget
    ) {
        if (messages.isEmpty()) {
            return "";
        }
        int budget = Math.max(128, characterBudget);
        int perMessage = Math.max(32, (budget / messages.size()) - 16);
        StringBuilder anchors = new StringBuilder();
        for (WorkflowAiMessage message : messages) {
            anchors.append(message.getRole())
                    .append(": ")
                    .append(boundedExcerpt(compactSummaryText(message.getContent()), perMessage))
                    .append('\n');
        }
        return anchors.length() <= budget
                ? anchors.toString().trim()
                : boundedExcerpt(anchors.toString().trim(), budget);
    }

    private String compactSummaryText(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return REPEATED_CHARACTER_RUN.matcher(compact).replaceAll("$1…");
    }

    private String boundedExcerpt(String value, int limit) {
        String compact = value == null ? "" : value.trim();
        int boundedLimit = Math.max(24, limit);
        if (compact.length() <= boundedLimit) {
            return compact;
        }
        int headLength = (boundedLimit * 2) / 3;
        int tailLength = Math.max(1, boundedLimit - headLength - 5);
        return compact.substring(0, headLength)
                + " ... "
                + compact.substring(compact.length() - tailLength);
    }

    private boolean summaryIsGrounded(
            String summary,
            List<WorkflowAiMessage> sourceMessages
    ) {
        Set<String> identifiers = new HashSet<>();
        for (WorkflowAiMessage message : sourceMessages) {
            Matcher matcher = DISTINCTIVE_IDENTIFIER.matcher(message.getContent());
            while (matcher.find() && identifiers.size() < 64) {
                identifiers.add(matcher.group().toLowerCase(Locale.ROOT));
            }
        }
        if (identifiers.isEmpty()) {
            return true;
        }
        String normalizedSummary = summary == null
                ? ""
                : summary.toLowerCase(Locale.ROOT);
        return identifiers.stream().anyMatch(normalizedSummary::contains);
    }

    private String limitSummary(String value) {
        String summary = value == null ? "" : value.trim();
        int limit = Math.max(512, maximumSummaryCharacters);
        if (summary.length() <= limit) {
            return summary;
        }
        int headLength = (limit * 2) / 3;
        int tailLength = limit - headLength;
        return summary.substring(0, headLength)
                + "\n...[older summary compacted]...\n"
                + summary.substring(summary.length() - tailLength);
    }

    private int estimatedTokens(WorkflowAiMessage message) {
        return 6 + estimatedTokens(message.getContent());
    }

    private int estimatedTokens(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return 4 + (value.length() + APPROXIMATE_CHARACTERS_PER_TOKEN - 1)
                / APPROXIMATE_CHARACTERS_PER_TOKEN;
    }

    private ParsedAssistantResponse parseAssistantResponse(String value) {
        try {
            JsonNode root = normalizeAslDefinition(LENIENT_MODEL_MAPPER.readTree(value));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Response root must be a JSON object");
            }
            if (looksLikeAsl(root)) {
                return new ParsedAssistantResponse(
                        WorkflowAiConversationStage.ASL_READY,
                        "ASL definition is ready.",
                        root,
                        null,
                        null,
                        null,
                        null,
                        true,
                        null
                );
            }
            WorkflowAiConversationStage stage = parseStage(root.path("stage"));
            JsonNode messageNode = root.get("message");
            String message = messageNode != null && messageNode.isTextual()
                    ? messageNode.textValue()
                    : null;
            JsonNode resourcePlanNode = root.get("resourcePlan");
            WorkflowAiResourcePlanDTO resourcePlan = resourcePlanNode == null
                    || resourcePlanNode.isNull()
                    ? null
                    : normalizeResourcePlan(objectMapper.treeToValue(
                            resourcePlanNode,
                            WorkflowAiResourcePlanDTO.class
                    ));
            boolean hasResourcePlan = resourcePlan != null && !resourcePlan.isEmpty();
            if (stage == null && hasResourcePlan) {
                // A model that proposes resources but omits the stage still means RESOURCES_PROPOSED.
                stage = WorkflowAiConversationStage.RESOURCES_PROPOSED;
            }
            if (stage == null) {
                throw new IllegalArgumentException(
                        "Response must include a recognized stage"
                );
            }
            JsonNode asl = normalizeAslDefinition(root.get("aslDefinition"));
            if (asl != null && asl.isObject() && asl.isEmpty()) {
                // A model that emits an empty aslDefinition ({}) alongside a resource proposal or plan
                // means "no ASL yet", not a malformed one. Treat it as absent so the stray placeholder
                // does not fail ASL validation and discard an otherwise-valid response (e.g. a genuine
                // mcpRequirement). A partially-filled ASL is still kept and reported as invalid.
                asl = null;
            }
            JsonNode finalPlan = root.get("finalPlan");
            if (asl == null && finalPlan != null && finalPlan.isObject()) {
                JsonNode finalPlanDefinition = normalizeAslDefinition(
                        finalPlan.get("definition")
                );
                if (looksLikeAsl(finalPlanDefinition)) {
                    asl = finalPlanDefinition;
                }
            }
            JsonNode draftPayloadNode = root.get("draftWorkflowPayload");
            if (draftPayloadNode != null
                    && draftPayloadNode.isObject()
                    && draftPayloadNode.has("definition")) {
                ((ObjectNode) draftPayloadNode).set(
                        "definition",
                        normalizeAslDefinition(draftPayloadNode.get("definition"))
                );
                JsonNode draftDefinition = draftPayloadNode.get("definition");
                if (asl == null && looksLikeAsl(draftDefinition)) {
                    asl = draftDefinition;
                    if (stage != WorkflowAiConversationStage.PLAN_READY) {
                        // A complete ASL machine placed in a premature draft is still useful, but
                        // workflow metadata is not ready yet. Promote only the definition.
                        finalPlan = null;
                        draftPayloadNode = null;
                    }
                }
            }
            if (stage == WorkflowAiConversationStage.RESOURCES_PROPOSED
                    && hasResourcePlan
                    && (!looksLikeAsl(asl) || resourcePlanNeedsProvisioning(resourcePlan))) {
                // Resource discovery precedes workflow generation. Some weaker models append a
                // pseudo-workflow or an illustrative, non-ASL object to an otherwise valid resource
                // proposal. It is not an executable candidate and must not invalidate the proposal.
                asl = null;
                finalPlan = null;
                draftPayloadNode = null;
            }
            CreateWorkflowRequestDTO draftPayload = draftPayloadNode == null
                    || draftPayloadNode.isNull()
                    ? null
                    : objectMapper.treeToValue(
                            draftPayloadNode,
                            CreateWorkflowRequestDTO.class
                    );
            if (stage == WorkflowAiConversationStage.PLAN_READY
                    && asl != null
                    && (draftPayload == null || draftPayload.definition() == null)) {
                // A complete definition hidden in finalPlan is useful, but an empty draft is not
                // ready to save. Preserve the workflow at the honest earlier stage.
                stage = WorkflowAiConversationStage.ASL_READY;
                finalPlan = null;
                draftPayloadNode = null;
                draftPayload = null;
            }
            if (message == null || message.isBlank()) {
                if (asl != null && !asl.isNull()) {
                    message = "Generated workflow definition.";
                } else if (draftPayload != null || (finalPlan != null && !finalPlan.isNull())) {
                    message = "Generated workflow plan.";
                } else if (hasResourcePlan) {
                    message = "I need a few resources created before I can build this workflow.";
                } else {
                    throw new IllegalArgumentException(
                            "Response must include a non-empty message when no workflow artifact is present"
                    );
                }
            }
            return new ParsedAssistantResponse(
                    stage,
                    message,
                    asl,
                    finalPlan,
                    draftPayload,
                    draftPayloadNode,
                    resourcePlan,
                    true,
                    null
            );
        } catch (Exception exception) {
            // Log the reply itself, not just the parser error: diagnosing a model that emits
            // near-JSON is impossible without seeing what it actually sent.
            log.warn(
                    "Could not parse workflow AI response as structured JSON. Reply was: {}",
                    boundedExcerpt(value, 2000),
                    exception
            );
            return new ParsedAssistantResponse(
                    null,
                    value,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    exception.getMessage()
            );
        }
    }

    private boolean resourcePlanNeedsProvisioning(WorkflowAiResourcePlanDTO resourcePlan) {
        if (resourcePlan == null) {
            return false;
        }
        if (resourcePlan.functions() != null
                && resourcePlan.functions().stream().anyMatch(Objects::nonNull)) {
            return true;
        }
        List<WorkflowAiMcpRequirementDTO> requirements = resourcePlan.mcpRequirements();
        if (requirements == null || requirements.isEmpty()) {
            return false;
        }
        List<WorkflowAiResourceCatalogService.McpRequirementMatch> matches =
                resourceCatalogService.findMcpRequirementMatches(requirements);
        Set<String> matchedCapabilities = matches.stream()
                .map(WorkflowAiResourceCatalogService.McpRequirementMatch::capability)
                .filter(Objects::nonNull)
                .map(capability -> capability.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        return requirements.stream()
                .filter(Objects::nonNull)
                .map(WorkflowAiMcpRequirementDTO::capability)
                .anyMatch(capability -> capability == null
                        || !matchedCapabilities.contains(capability.trim().toLowerCase(Locale.ROOT)));
    }

    private List<String> validateExecutableDefinition(JsonNode definition) {
        AslValidationResult validation = aslDefinitionValidator.validate(definition);
        List<AslValidationIssue> issues = new ArrayList<>(validation.issues());
        issues.addAll(runtimeCapabilityValidator.validate(definition));
        issues.addAll(mcpResourceValidator.validate(definition));
        issues.addAll(functionResourceValidator.validate(definition));
        return issues.stream()
                .map(issue -> String.format(
                        "[%s] %s at %s: %s",
                        issue.category(),
                        issue.code(),
                        issue.location(),
                        issue.message()
                ))
                .toList();
    }

    private WorkflowAiConversation findForUpdate(UUID conversationId) {
        if (conversationId == null) {
            throw new IllegalArgumentException("Conversation id cannot be null");
        }
        return conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workflow AI conversation does not exist"
                ));
    }

    private void requireWorkspaceKind(
            WorkflowAiConversation workspace,
            WorkflowAiWorkspaceKind expectedKind
    ) {
        if (workspace.getWorkspaceKind() != expectedKind) {
            throw new EntityNotFoundException(
                    expectedKind == WorkflowAiWorkspaceKind.MANUAL_DRAFT
                            ? "Workflow draft does not exist"
                            : "Workflow AI conversation does not exist"
            );
        }
    }

    private UUID selectedModelId(
            WorkflowAiConversation conversation,
            UUID requestedModelId
    ) {
        if (requestedModelId != null) {
            return requestedModelId;
        }
        return conversation.getModelConfig() == null
                ? null
                : conversation.getModelConfig().getId();
    }

    private void appendMessage(
            WorkflowAiConversation conversation,
            WorkflowAiMessageRole role,
            String content,
            String structuredPayload,
            AiModelConfig modelConfig
    ) {
        appendMessage(
                conversation,
                role,
                content,
                structuredPayload,
                modelConfig,
                null,
                null,
                null,
                null,
                null
        );
    }

    private WorkflowAiMessage appendMessage(
            WorkflowAiConversation conversation,
            WorkflowAiMessageRole role,
            String content,
            String structuredPayload,
            AiModelConfig modelConfig,
            String thinkingContent,
            Long durationMs,
            TokenUsage tokenUsage,
            String finishReason,
            WorkflowAiMessage regeneratedFromMessage
    ) {
        WorkflowAiMessage message = new WorkflowAiMessage();
        message.setConversation(conversation);
        message.setModelConfig(modelConfig);
        message.setRegeneratedFromMessage(regeneratedFromMessage);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setStructuredPayload(structuredPayload);
        message.setThinkingContent(optionalText(thinkingContent));
        message.setDurationMs(durationMs);
        if (tokenUsage != null) {
            message.setInputTokens(tokenUsage.inputTokenCount());
            message.setOutputTokens(tokenUsage.outputTokenCount());
            message.setTotalTokens(tokenUsage.totalTokenCount());
        }
        message.setFinishReason(finishReason);
        return messageRepository.saveAndFlush(message);
    }

    private WorkflowAiResponseDTO response(
            WorkflowAiConversation conversation,
            String message,
            JsonNode aslDefinition,
            List<String> validationIssues,
            JsonNode finalPlan,
            CreateWorkflowRequestDTO draftWorkflowPayload,
            WorkflowResponseDTO workflow,
            WorkflowAiMessageDTO assistantMessage
    ) {
        return new WorkflowAiResponseDTO(
                conversation.getId(),
                workspaceDisplayName(conversation),
                conversation.getStage(),
                message,
                aslDefinition,
                validationIssues == null ? List.of() : validationIssues,
                finalPlan,
                draftWorkflowPayload,
                readResourcePlan(conversation),
                inferredResourcePlanMessageId(conversation),
                workflow == null ? null : workflow.id(),
                workflow,
                assistantMessage,
                null
        );
    }

    /** Returns a copy of the response carrying the raw model reply for diagnostics. */
    private WorkflowAiResponseDTO withRawReply(
            WorkflowAiResponseDTO response,
            String rawAssistantReply
    ) {
        return new WorkflowAiResponseDTO(
                response.conversationId(),
                response.conversationName(),
                response.stage(),
                response.message(),
                response.aslDefinition(),
                response.validationIssues(),
                response.finalPlan(),
                response.draftWorkflowPayload(),
                response.resourcePlan(),
                response.resourcePlanMessageId(),
                response.workflowId(),
                response.workflow(),
                response.assistantMessage(),
                rawAssistantReply
        );
    }

    private WorkflowAiResourcePlanDTO readResourcePlan(WorkflowAiConversation conversation) {
        if (conversation.getResourcePlan() == null) {
            return null;
        }
        try {
            return normalizeResourcePlan(objectMapper.treeToValue(
                    objectMapper.readTree(conversation.getResourcePlan()),
                    WorkflowAiResourcePlanDTO.class
            ));
        } catch (Exception exception) {
            log.warn("Could not read stored workflow AI resource plan", exception);
            return null;
        }
    }

    private WorkflowAiConversationSummaryDTO summary(
            WorkflowAiConversation conversation
    ) {
        return new WorkflowAiConversationSummaryDTO(
                conversation.getId(),
                workspaceDisplayName(conversation),
                conversation.getStage(),
                conversation.getModelConfig() == null ? null : conversation.getModelConfig().getId(),
                conversation.getModelConfig() == null ? null : conversation.getModelConfig().getDisplayName(),
                conversation.getInitialInstruction(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }

    private WorkflowAiConversationDetailDTO detail(
            WorkflowAiConversation conversation
    ) {
        return new WorkflowAiConversationDetailDTO(
                conversation.getId(),
                workspaceDisplayName(conversation),
                conversation.getStage(),
                conversation.getModelConfig() == null ? null : conversation.getModelConfig().getId(),
                conversation.getModelConfig() == null ? null : conversation.getModelConfig().getDisplayName(),
                conversation.getInitialInstruction(),
                readJson(conversation.getDraftAsl()),
                conversation.getWorkspaceDefinitionText(),
                readJson(conversation.getFinalPlan()),
                readDraftPayload(conversation),
                readResourcePlan(conversation),
                inferredResourcePlanMessageId(conversation),
                readJson(conversation.getCanvasLayout()),
                readWorkspaceSettings(conversation),
                conversation.getWorkflowId(),
                messageRepository.findByConversationOrderByCreatedAtAsc(conversation)
                        .stream()
                        .map(this::message)
                        .toList(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }

    private WorkflowAiMessageDTO message(WorkflowAiMessage message) {
        return new WorkflowAiMessageDTO(
                message.getId(),
                message.getRole(),
                userFacingMessageContent(message),
                message.getModelConfig() == null ? null : message.getModelConfig().getId(),
                message.getModelConfig() == null ? null : message.getModelConfig().getDisplayName(),
                message.getDurationMs(),
                message.getInputTokens(),
                message.getOutputTokens(),
                message.getTotalTokens(),
                message.getThinkingContent(),
                message.getFinishReason(),
                message.getRegeneratedFromMessage() == null
                        ? null
                        : message.getRegeneratedFromMessage().getId(),
                readMessageResourcePlan(message),
                message.getCreatedAt()
        );
    }

    private WorkflowAiResourcePlanDTO readMessageResourcePlan(WorkflowAiMessage message) {
        // Older rows predate explicit suppression metadata. Their failure text is authoritative:
        // a rejected payload is diagnostic evidence, not an accepted resource proposal.
        if (message.getContent() != null
                && message.getContent().startsWith(FAILED_VALIDATION_MESSAGE)) {
            return null;
        }
        if (message.getMetadataJson() != null) {
            try {
                if (objectMapper.readTree(message.getMetadataJson())
                        .path("resourcePlanSuppressed")
                        .asBoolean(false)) {
                    return null;
                }
            } catch (Exception exception) {
                log.debug("Could not read workflow resource-plan message metadata", exception);
            }
        }
        // structured_payload is the immutable model response. Prefer it over metadata so rows from
        // older builds, where metadata was overwritten with the unresolved subset, recover their
        // original completed requirements after refresh.
        WorkflowAiResourcePlanDTO originalPlan = readResourcePlanFromPayload(
                message.getStructuredPayload(),
                false
        );
        if (originalPlan != null) {
            return originalPlan;
        }
        // Backward compatibility for proposals whose structured response was unavailable (for
        // example, accepted lenient near-JSON) and therefore exist only in message metadata.
        return readResourcePlanFromPayload(message.getMetadataJson(), true);
    }

    private WorkflowAiResourcePlanDTO readResourcePlanFromPayload(
            String payload,
            boolean metadataEnvelope
    ) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode planNode = metadataEnvelope
                    ? root.path("resourcePlan")
                    : root.get("resourcePlan");
            if (planNode == null || planNode.isMissingNode() || planNode.isNull()) {
                return null;
            }
            WorkflowAiResourcePlanDTO plan = normalizeResourcePlan(objectMapper.treeToValue(
                    planNode,
                    WorkflowAiResourcePlanDTO.class
            ));
            return plan == null || plan.isEmpty() ? null : plan;
        } catch (Exception exception) {
            log.debug("Could not read workflow resource plan from message payload", exception);
            return null;
        }
    }

    private String resourcePlanMessageMetadata(
            WorkflowAiResourcePlanDTO resourcePlan,
            boolean suppressed
    ) {
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        if (resourcePlan != null) {
            metadata.put("resourcePlan", resourcePlan);
        }
        if (suppressed) {
            metadata.put("resourcePlanSuppressed", true);
        }
        return serialize(metadata);
    }

    private WorkflowAiResourcePlanDTO normalizeResourcePlan(
            WorkflowAiResourcePlanDTO resourcePlan
    ) {
        if (resourcePlan == null || resourcePlan.functions() == null) {
            return resourcePlan;
        }
        List<WorkflowAiProposedFunctionDTO> functions = resourcePlan.functions()
                .stream()
                .map(function -> function == null
                        ? null
                        : new WorkflowAiProposedFunctionDTO(
                                normalizeFunctionName(function.name()),
                                function.description(),
                                function.languageId(),
                                function.sourceCode(),
                                // Tests are never carried on an AI proposal — they are generated later,
                                // independently, in the Functions section after the user approves the
                                // draft. Drop whatever the model emitted so a stray or hallucinated test
                                // case (models routinely ignore the "set testCases to null" instruction)
                                // never rides along with the proposal.
                                null,
                                function.rationale()
                        ))
                .toList();
        return new WorkflowAiResourcePlanDTO(functions, resourcePlan.mcpRequirements());
    }

    private String normalizeFunctionName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim()
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-+|-+$", "")
                .replaceAll("-{2,}", "-")
                .toLowerCase(Locale.ROOT);
    }

    private void ensureResourcePlanMessageId(WorkflowAiConversation conversation) {
        if (conversation.getResourcePlanMessageId() == null
                && conversation.getStage() == WorkflowAiConversationStage.RESOURCES_PROPOSED) {
            conversation.setResourcePlanMessageId(inferredResourcePlanMessageId(conversation));
        }
    }

    private UUID inferredResourcePlanMessageId(WorkflowAiConversation conversation) {
        if (conversation.getResourcePlanMessageId() != null) {
            return conversation.getResourcePlanMessageId();
        }
        if (conversation.getStage() != WorkflowAiConversationStage.RESOURCES_PROPOSED) {
            return null;
        }
        List<WorkflowAiMessage> messages =
                messageRepository.findByConversationOrderByCreatedAtAsc(conversation);
        Set<UUID> supersededIds = messages.stream()
                .map(WorkflowAiMessage::getRegeneratedFromMessage)
                .filter(Objects::nonNull)
                .map(WorkflowAiMessage::getId)
                .collect(java.util.stream.Collectors.toSet());
        return messages.stream()
                .filter(message -> message.getRole() == WorkflowAiMessageRole.ASSISTANT)
                .filter(message -> message.getId() == null || !supersededIds.contains(message.getId()))
                .filter(message -> readMessageResourcePlan(message) != null)
                .map(WorkflowAiMessage::getId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** Walks back to the original assistant reply that later retries all descend from. */
    private WorkflowAiMessage regenerationRoot(WorkflowAiMessage message) {
        WorkflowAiMessage root = message;
        Set<UUID> visited = new HashSet<>();
        while (visited.add(root.getId()) && root.getRegeneratedFromMessage() != null) {
            root = root.getRegeneratedFromMessage();
        }
        return root;
    }

    private List<WorkflowAiMessage> historyBeforeMessage(
            WorkflowAiConversation conversation,
            WorkflowAiMessage targetMessage
    ) {
        List<WorkflowAiMessage> history = new ArrayList<>();
        for (WorkflowAiMessage message :
                messageRepository.findByConversationOrderByCreatedAtAsc(conversation)) {
            if (message.getId().equals(targetMessage.getId())) {
                break;
            }
            history.add(message);
        }
        return history;
    }

    private CreateWorkflowRequestDTO fallbackDraftPayload(
            WorkflowAiConversation conversation
    ) {
        JsonNode definition = readJson(conversation.getDraftAsl());
        if (definition == null) {
            throw new IllegalStateException("No reviewed ASL definition is available");
        }
        return new CreateWorkflowRequestDTO(
                conversation.getName(),
                null,
                3,
                "workflow-ai-" + conversation.getId(),
                "UTC",
                definition
        );
    }

    private CreateWorkflowRequestDTO readDraftPayload(
            WorkflowAiConversation conversation
    ) {
        if (conversation.getDraftWorkflowPayload() == null) {
            return null;
        }
        try {
            return objectMapper.treeToValue(
                    objectMapper.readTree(conversation.getDraftWorkflowPayload()),
                    CreateWorkflowRequestDTO.class
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not read stored draft workflow payload",
                    exception
            );
        }
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read stored JSON", exception);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize value", exception);
        }
    }

    private WorkflowAiConversationStage parseStage(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return WorkflowAiConversationStage.valueOf(
                    node.textValue().trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean looksLikeAsl(JsonNode root) {
        return root != null && root.isObject() && root.has("StartAt") && root.has("States");
    }

    /**
     * Repairs only unambiguous structural/casing mistakes made by weaker models. Semantic
     * conversions (for example JSONPath fields to JSONata) remain the model repair loop's job.
     */
    private JsonNode normalizeAslDefinition(JsonNode candidate) {
        if (candidate != null && candidate.isTextual()) {
            String embeddedJson = candidate.textValue();
            if (embeddedJson != null && embeddedJson.trim().startsWith("{")) {
                try {
                    return normalizeAslDefinition(LENIENT_MODEL_MAPPER.readTree(embeddedJson));
                } catch (Exception ignored) {
                    // Keep the original text node so ordinary ASL validation reports the bad shape.
                }
            }
        }
        if (candidate == null || !candidate.isObject()) {
            return candidate;
        }
        ObjectNode normalized = (ObjectNode) candidate.deepCopy();
        normalized.remove("Version");
        normalized.remove("version");
        normalized.remove("QueryLanguage");
        // End belongs to individual non-terminal states. It is never a machine-level field; weak
        // models commonly duplicate it at the root after correctly terminating the last state.
        normalized.remove("End");
        JsonNode lowerStartAt = normalized.get("startAt");
        if (!normalized.has("StartAt")
                && lowerStartAt != null
                && lowerStartAt.isTextual()) {
            normalized.set("StartAt", lowerStartAt);
            normalized.remove("startAt");
        }
        if (!normalized.has("States") && normalized.path("states").isObject()) {
            normalized.set("States", normalized.get("states"));
            normalized.remove("states");
        }
        JsonNode statesNode = normalized.get("States");
        if (statesNode != null && statesNode.isObject()) {
            statesNode.properties().forEach(entry -> {
                JsonNode state = entry.getValue();
                JsonNode type = state == null ? null : state.get("Type");
                if (state instanceof ObjectNode stateObject
                        && type != null
                        && type.isTextual()
                        && ("Succeed".equals(type.textValue()) || "Fail".equals(type.textValue()))) {
                    stateObject.remove("Next");
                    stateObject.remove("End");
                }
            });
        }
        JsonNode startAtNode = normalized.get("StartAt");
        if (normalized.has("States")
                || startAtNode == null
                || !startAtNode.isTextual()) {
            return normalized;
        }

        String startAt = startAtNode.textValue();
        JsonNode startState = normalized.get(startAt);
        JsonNode startType = startState == null ? null : startState.get("Type");
        if (startState == null
                || !startState.isObject()
                || startType == null
                || !startType.isTextual()) {
            return normalized;
        }

        ObjectNode states = objectMapper.createObjectNode();
        normalized.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (!Set.of("StartAt", "Comment", "TimeoutSeconds", "QueryLanguage", "Version")
                    .contains(entry.getKey())
                    && value.isObject()
                    && value.get("Type") != null
                    && value.get("Type").isTextual()) {
                states.set(entry.getKey(), value);
            }
        });
        if (!states.has(startAt)) {
            return normalized;
        }

        ObjectNode machine = objectMapper.createObjectNode();
        for (String field : List.of(
                "Comment", "StartAt", "TimeoutSeconds", "QueryLanguage", "Version"
        )) {
            if (normalized.has(field)) {
                machine.set(field, normalized.get(field));
            }
        }
        machine.set("States", states);
        return machine;
    }

    private String stripMarkdown(String rawOutput) {
        String output = rawOutput == null ? "" : rawOutput.trim();
        if (output.startsWith("```json")) {
            output = output.substring(7);
        }
        if (output.startsWith("```")) {
            output = output.substring(3);
        }
        if (output.endsWith("```")) {
            output = output.substring(0, output.length() - 3);
        }
        return output.trim();
    }

    private ThinkingExtraction extractThinking(String value) {
        String output = value == null ? "" : value;
        Matcher matcher = THINKING_PATTERN.matcher(output);
        StringBuilder thinking = new StringBuilder();
        while (matcher.find()) {
            String block = matcher.group(1);
            if (block != null && !block.isBlank()) {
                if (!thinking.isEmpty()) {
                    thinking.append("\n\n");
                }
                thinking.append(block.trim());
            }
        }
        String answer = matcher.replaceAll("").trim();
        return new ThinkingExtraction(
                answer.isBlank() ? output.trim() : answer,
                thinking.isEmpty() ? null : thinking.toString()
        );
    }

    private String generateConversationName(String instruction) {
        String compact = instruction.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 48) {
            return compact;
        }
        return compact.substring(0, 48).trim();
    }

    private String manualDraftName(WorkflowAiWorkspaceSettingsDTO settings) {
        String configuredName = settings == null ? null : optionalText(settings.name());
        return configuredName == null ? "Untitled workflow" : configuredName;
    }

    private String workspaceDisplayName(WorkflowAiConversation conversation) {
        String customName = optionalText(conversation.getCustomName());
        return customName == null ? conversation.getName() : customName;
    }

    private String jsonPayloadOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            objectMapper.readTree(value);
            return value;
        } catch (Exception exception) {
            // structured_payload is a json column, so malformed model output would fail the whole
            // insert and lose the turn. The prose is kept on the message content regardless.
            log.debug("Assistant response was not valid JSON; storing no structured payload", exception);
            return null;
        }
    }

    private String editorAslContext(JsonNode definition) {
        if (!looksLikeAsl(definition) || definition.path("States").isEmpty()) {
            return null;
        }
        return "Current ASL in the user's editor:\n" + serialize(definition);
    }

    private String rejectedEditorCandidateContext(
            JsonNode definition,
            List<String> validationIssues
    ) {
        return "Candidate ASL in the user's editor (not authoritative):\n"
                + serialize(definition)
                + "\nValidator issues:\n"
                + String.join("\n", validationIssues);
    }

    /**
     * Surfaces the raw editor buffer for edits that could not be sent as a parsed definition
     * (incomplete or unparseable JSON, or an empty machine). It is explicitly not authoritative:
     * the last valid draftAsl remains the source of truth the model amends.
     */
    private String inProgressBufferContext(String editorDefinitionText) {
        String text = optionalText(editorDefinitionText);
        if (text == null) {
            return null;
        }
        StringBuilder context = new StringBuilder(
                "Current editor buffer (work in progress, may be incomplete or invalid; "
                        + "not authoritative, do not treat as the final ASL):\n"
        ).append(boundedExcerpt(text, 8000));
        try {
            JsonNode parsed = objectMapper.readTree(text);
            if (parsed != null && parsed.isObject()) {
                List<String> issues = validateExecutableDefinition(parsed);
                if (!issues.isEmpty()) {
                    context.append("\nValidator issues for this buffer:\n")
                            .append(String.join("\n", issues));
                }
            }
        } catch (Exception parseFailure) {
            context.append("\nThis buffer is not valid JSON yet.");
        }
        return context.toString();
    }

    private WorkflowAiWorkspaceSettingsDTO readWorkspaceSettings(
            WorkflowAiConversation conversation
    ) {
        if (conversation.getWorkspaceSettings() == null) {
            return null;
        }
        try {
            return objectMapper.treeToValue(
                    objectMapper.readTree(conversation.getWorkspaceSettings()),
                    WorkflowAiWorkspaceSettingsDTO.class
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not read stored workflow AI workspace settings",
                    exception
            );
        }
    }

    private String userFacingMessageContent(WorkflowAiMessage message) {
        String content = message.getContent();
        if (message.getRole() != WorkflowAiMessageRole.USER || content == null) {
            return content;
        }

        // Older conversations stored model-only context in the user message. Keep that context in
        // model history, but do not expose it as text the user typed when the chat is rehydrated.
        int editorContextStart = content.indexOf("\n\nCurrent ASL in the user's editor:\n");
        if (editorContextStart >= 0) {
            content = content.substring(0, editorContextStart);
        }
        return content.replaceFirst(
                "(?s)\\n\\nUser date/time context:\\s*[^\\r\\n]+\\s*$",
                ""
        );
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " cannot be blank");
        }
        return value.trim();
    }

    private String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ParsedAssistantResponse(
            WorkflowAiConversationStage stage,
            String message,
            JsonNode aslDefinition,
            JsonNode finalPlan,
            CreateWorkflowRequestDTO draftWorkflowPayload,
            JsonNode draftWorkflowPayloadNode,
            WorkflowAiResourcePlanDTO resourcePlan,
            boolean structured,
            String failureReason
    ) {
        private boolean hasResourcePlan() {
            return resourcePlan != null && !resourcePlan.isEmpty();
        }

        private boolean hasFunctionProposals() {
            return resourcePlan != null
                    && resourcePlan.functions() != null
                    && resourcePlan.functions().stream().anyMatch(Objects::nonNull);
        }

        private boolean hasDefinitionCandidate() {
            return aslDefinition != null
                    || draftWorkflowPayload != null
                    && draftWorkflowPayload.definition() != null;
        }
    }

    private record AssistantAttempt(
            ChatResponse modelResponse,
            String cleaned,
            ThinkingExtraction thinkingExtraction,
            ParsedAssistantResponse parsed
    ) {
        private boolean hasFunctionProposalSignal() {
            if (parsed.hasFunctionProposals()) {
                return true;
            }
            return cleaned != null
                    && cleaned.contains("\"functions\"")
                    && (cleaned.contains("\"sourceCode\"")
                    || cleaned.contains("\"languageId\""));
        }
    }

    private record ThinkingExtraction(
            String answer,
            String thinkingContent
    ) {
    }

    private record ContextWindow(
            String summary,
            List<WorkflowAiMessage> recentMessages
    ) {
    }
}
