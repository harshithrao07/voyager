package com.job.scheduler.service;

import com.job.scheduler.dto.CreateWorkflowRequestDTO;
import com.job.scheduler.dto.FunctionDefinitionRequestDTO;
import com.job.scheduler.dto.FunctionDefinitionResponseDTO;
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
import com.job.scheduler.enums.WorkflowStatus;
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
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
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
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
    private static final Pattern FUNCTION_NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

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
            Every state must contain exactly one of Next or End.
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
            After ASL is approved, collect workflow name, cron expression if scheduled, timezone, and max attempts.
            When everything is ready, return PLAN_READY with finalPlan and draftWorkflowPayload.
            If the selected local model supports visible reasoning, put that reasoning before the JSON as <think>...</think>.
            The content after </think> must still be strict JSON only. Keep any <think> reasoning brief and keep
            the JSON output concise so it always completes and is never cut off mid-object.
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
            - languageId must be selected from the supported function-language list supplied below.
            - sourceCode must be complete, valid single-file code for that language. It must read exactly one valid
              JSON value from stdin, parse that JSON before transforming it, and write exactly one valid JSON value
              to stdout. Do not print logs or other text to stdout. Never transform or truncate the serialized JSON
              text itself; transform the parsed value, then JSON-serialize the result.
            - Keep sourceCode short, but handle the declared input shape and normal boundary cases. Do not provide
              pseudocode, an incomplete fragment, or code that only prints a hard-coded value.
            - Include meaningful testCases. Each test case has name, input, expectedOutput, and expectedError; input
              and expectedOutput are JSON strings. Use expectedError only for an intentional failure case. Cover at
              least normal and boundary behavior.
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
    private final ObjectMapper objectMapper;

    @Value("${scheduler.workflow-ai.context.max-estimated-tokens:12000}")
    private int maximumContextTokens = 12000;

    @Value("${scheduler.workflow-ai.context.recent-estimated-tokens:4000}")
    private int recentContextTokens = 4000;

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
        if (approvedFunctions != null) {
            for (WorkflowAiProposedFunctionDTO function : approvedFunctions) {
                createdResources.add(provisionFunction(function));
            }
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

    private String provisionFunction(WorkflowAiProposedFunctionDTO function) {
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
        // Default version status is AVAILABLE, which both publishes the version and activates it as
        // the function's first active version, so it immediately enters the catalog.
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
                        function.testCases(),
                        null
                )
        );
        return "voyager://function/" + name + "@v" + version.version();
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
        ChatLanguageModel model = modelResolver.resolve(modelConfig);
        List<ChatMessage> messages = buildPrompt(
                conversation,
                task,
                historyOverride,
                model
        );
        Instant startedAt = Instant.now();
        AssistantAttempt attempt = generateAssistantAttempt(model, messages);
        if (attempt.hasFunctionProposalSignal()) {
            attempt = generateAssistantAttempt(
                    model,
                    functionCreationReviewPrompt(messages, attempt.cleaned())
            );
        }
        List<String> validationIssues = validateAssistantAttempt(attempt.parsed());
        for (int generationAttempt = 1;
             !validationIssues.isEmpty()
                     && generationAttempt < MAX_ASSISTANT_GENERATION_ATTEMPTS;
             generationAttempt++) {
            boolean repairIncludesFunctionContract = attempt.hasFunctionProposalSignal();
            attempt = generateAssistantAttempt(
                    model,
                    repairPrompt(
                            messages,
                            attempt.cleaned(),
                            validationIssues,
                            repairIncludesFunctionContract
                    )
            );
            if (!repairIncludesFunctionContract && attempt.hasFunctionProposalSignal()) {
                attempt = generateAssistantAttempt(
                        model,
                        functionCreationReviewPrompt(messages, attempt.cleaned())
                );
            }
            validationIssues = validateAssistantAttempt(attempt.parsed());
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

        return response(
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
        StringBuilder message = new StringBuilder(FAILED_VALIDATION_MESSAGE);
        if (!parsed.structured()) {
            message.append("\nRaw model reply: ")
                    .append(boundedExcerpt(cleanedResponse, 1000));
        }
        return message.toString();
    }

    private AssistantAttempt generateAssistantAttempt(
            ChatLanguageModel model,
            List<ChatMessage> messages
    ) {
        Response<AiMessage> modelResponse = model.generate(messages);
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
        repairMessages.add(UserMessage.userMessage("""
                Your previous response was rejected, often because the JSON was truncated or incomplete.
                Return exactly one COMPLETE strict JSON object matching the workflow response contract; keep it small
                enough to finish in full, with brief reasoning.
                Do not return Markdown, an Adaptive Card, a tool-call envelope, or commentary outside JSON.
                If a requested action needs a function or MCP tool that is not in the catalog, do NOT force an
                aslDefinition: return "stage":"RESOURCES_PROPOSED" with a complete resourcePlan (functions and/or
                mcpRequirements) and omit aslDefinition. Otherwise, preserve the user's requested workflow and return
                JSONata-only ASL when aslDefinition is present.
                Re-read the available Voyager Task catalog in the system context and keep every matching exact URI.
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
                Rejection reasons:
                """ + String.join("\n", validationIssues)));
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
                test cases, descriptions, and rationales. If a proposed function actually needs a network,
                external service, or credential, replace it with an mcpRequirement. Return the complete corrected
                workflow response as strict JSON only.
                """));
        return reviewMessages;
    }

    private String functionCreationContext() {
        return FUNCTION_CREATION_PROMPT
                + "\n\n"
                + resourceCatalogService.buildFunctionCreationContext();
    }

    private List<String> validateAssistantAttempt(ParsedAssistantResponse parsed) {
        List<String> issues = new ArrayList<>();
        if (!parsed.structured()) {
            issues.add(INVALID_AI_RESPONSE + " " + parsed.failureReason());
            return List.copyOf(issues);
        }
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
        if (parsed.hasResourcePlan()) {
            if (parsed.resourcePlan().functions() != null) {
                Set<String> proposedFunctionNames = new HashSet<>();
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
                    if (function.languageId() == null) {
                        issues.add(
                                "[AI_RESOURCE_PLAN] Function '" + functionName
                                        + "' is missing a languageId."
                        );
                    } else {
                        try {
                            functionRuntimePolicy.assertLanguageSupported(function.languageId());
                        } catch (IllegalArgumentException exception) {
                            issues.add(
                                    "[AI_RESOURCE_PLAN] Function '" + functionName
                                            + "' uses an unsupported languageId: "
                                            + function.languageId()
                            );
                        }
                    }
                    if (optionalText(function.sourceCode()) == null) {
                        issues.add(
                                "[AI_RESOURCE_PLAN] Function '" + functionName
                                        + "' is missing sourceCode."
                        );
                    }
                }
            }
            if (parsed.resourcePlan().mcpRequirements() != null) {
                for (WorkflowAiMcpRequirementDTO requirement :
                        parsed.resourcePlan().mcpRequirements()) {
                    String suggestedTool = optionalText(requirement.suggestedToolName());
                    if (suggestedTool != null
                            && suggestedTool.toLowerCase(Locale.ROOT)
                            .startsWith("voyager://function/")) {
                        issues.add(
                                "[AI_RESOURCE_PLAN] Capability '" + requirement.capability()
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

    private String requireModelReply(Response<AiMessage> modelResponse) {
        String reply = modelResponse == null || modelResponse.content() == null
                ? null
                : modelResponse.content().text();
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

    private List<ChatMessage> buildPrompt(
            WorkflowAiConversation conversation,
            String task,
            List<WorkflowAiMessage> historyOverride,
            ChatLanguageModel model
    ) {
        List<WorkflowAiMessage> rawHistory = historyOverride == null
                ? messageRepository.findByConversationOrderByCreatedAtAsc(conversation)
                : historyOverride;
        List<WorkflowAiMessage> effectiveHistory = effectiveHistory(rawHistory);
        String durableContext = durableConversationContext(conversation, task);
        String exactIdentifiers = exactSourceIdentifiers(effectiveHistory);
        if (exactIdentifiers != null) {
            durableContext += "\nExact source identifiers (verbatim):\n" + exactIdentifiers;
        }
        int fixedContextTokens = estimatedTokens(SYSTEM_PROMPT)
                + estimatedTokens(durableContext);
        ContextWindow contextWindow = compactContextIfNeeded(
                conversation,
                effectiveHistory,
                model,
                fixedContextTokens,
                historyOverride == null
        );

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.systemMessage(SYSTEM_PROMPT));
        messages.add(SystemMessage.systemMessage(durableContext));
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
        return messages;
    }

    private String durableConversationContext(
            WorkflowAiConversation conversation,
            String task
    ) {
        StringBuilder context = new StringBuilder()
                .append("Current stage: ")
                .append(conversation.getStage())
                .append("\nInitial request: ")
                .append(conversation.getInitialInstruction())
                .append("\nTask: ")
                .append(task)
                .append("\nAvailable Voyager Task resources (current registry):\n")
                .append(resourceCatalogService.buildCatalog());
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
            ChatLanguageModel model,
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
            ChatLanguageModel model,
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
            Response<AiMessage> summaryResponse = model.generate(List.of(
                    SystemMessage.systemMessage(SUMMARY_SYSTEM_PROMPT),
                    UserMessage.userMessage(source.toString())
            ));
            String generatedSummary = summaryResponse == null
                    || summaryResponse.content() == null
                    ? null
                    : summaryResponse.content().text();
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
            JsonNode root = LENIENT_MODEL_MAPPER.readTree(value);
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
            String message = root.path("message").isString()
                    ? root.path("message").stringValue()
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
            JsonNode asl = root.get("aslDefinition");
            JsonNode finalPlan = root.get("finalPlan");
            JsonNode draftPayloadNode = root.get("draftWorkflowPayload");
            CreateWorkflowRequestDTO draftPayload = draftPayloadNode == null
                    || draftPayloadNode.isNull()
                    ? null
                    : objectMapper.treeToValue(
                            draftPayloadNode,
                            CreateWorkflowRequestDTO.class
                    );
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
            log.warn("Could not parse workflow AI response as structured JSON", exception);
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
                assistantMessage
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
                                function.testCases(),
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
        if (node == null || !node.isString()) {
            return null;
        }
        try {
            return WorkflowAiConversationStage.valueOf(
                    node.stringValue().trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean looksLikeAsl(JsonNode root) {
        return root != null && root.isObject() && root.has("StartAt") && root.has("States");
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
            Response<AiMessage> modelResponse,
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
