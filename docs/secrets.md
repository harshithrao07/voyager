# Deployment secrets

Voyager stores secret references, never secret values. MCP servers use
`authTokenRef`; AI model configurations use `credentialRef`. Both are resolved
through the same `SecretResolver`.

References use `UPPER_SNAKE_CASE`, for example `OPENAI_API_KEY` or
`MCP_GITHUB_TOKEN`.

## Resolution order

For reference `OPENAI_API_KEY`, the default resolver checks:

1. `scheduler.secrets.files.OPENAI_API_KEY`, normally supplied as environment
   variable `SCHEDULER_SECRETS_FILES_OPENAI_API_KEY`. Its value is a path to a
   mounted secret file.
2. `scheduler.secrets.values.OPENAI_API_KEY`, normally supplied as environment
   variable `SCHEDULER_SECRETS_VALUES_OPENAI_API_KEY`. Its value is the secret.

File-backed secrets take precedence. Legacy `scheduler.mcp.tokens.*` and
`scheduler.mcp.token-files.*` properties remain supported during migration.

## Kubernetes example

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: voyager-provider-secrets
type: Opaque
stringData:
  openai-api-key: replace-through-your-secret-delivery-system
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: voyager
spec:
  template:
    spec:
      containers:
        - name: app
          env:
            - name: SCHEDULER_SECRETS_FILES_OPENAI_API_KEY
              value: /var/run/secrets/voyager/openai-api-key
          volumeMounts:
            - name: provider-secrets
              mountPath: /var/run/secrets/voyager
              readOnly: true
      volumes:
        - name: provider-secrets
          secret:
            secretName: voyager-provider-secrets
```

Set the model's `credentialRef` to `OPENAI_API_KEY`. PostgreSQL stores that
reference only.

## Docker Secret example

Mount the Docker Secret and provide its path:

```yaml
services:
  app:
    environment:
      SCHEDULER_SECRETS_FILES_OPENAI_API_KEY: /run/secrets/openai_api_key
    secrets:
      - openai_api_key

secrets:
  openai_api_key:
    file: ./local-secrets/openai_api_key
```

Do not commit the referenced file.

## STDIO MCP environment

Secret-bearing child-process environment variables must contain references:

```json
{
  "LOG_LEVEL": "info",
  "GITHUB_TOKEN": "${secret:MCP_GITHUB_TOKEN}",
  "SLACK_TOKEN": "ref:SLACK_TOKEN"
}
```

Voyager resolves the markers immediately before spawning the process. The
database retains the markers, not their values.
