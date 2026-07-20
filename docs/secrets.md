# Secrets

Voyager stores provider and MCP secrets **encrypted in the database**. You enter the
actual value in the UI; the backend encrypts it with AES-256-GCM and stores only the
ciphertext. The plaintext is decrypted in memory just before it is used (an outbound API
call, an HTTP auth header, or a STDIO child-process environment) and is never returned by
the API.

Encrypted secrets are used in four places:

- **AI models** — the provider API key (`credential`); see [AI Workflow Generator](ai-workflows.md#configure-a-model).
- **MCP servers** — the auth token/secret (`authToken`).
- **MCP custom authentication headers** — each `secretHeaders` value, while only
  `secretHeaderNames` are returned.
- **MCP STDIO servers** — secret child-process environment variables (`secretEnv`),
  alongside the plaintext, non-secret `env`.

## Master key

Encryption uses a single deployment-owned **master key**, supplied out of band via one
environment variable:

```
SCHEDULER_SECRETS_MASTER_KEY=<base64-encoded 32-byte key>
```

Generate one with:

```bash
openssl rand -base64 32
```

The production profile and repository Compose deployment **fail fast at startup** if the key is
missing or does not decode to 32 bytes. Keep the key out of source control and out of the database;
it is the only secret the deployment must manage directly. The standalone `local` Spring profile
retains a documented development-only fallback, but Compose deliberately overrides it with the
required deployment value.

### Kubernetes example

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: voyager-master-key
type: Opaque
stringData:
  master-key: replace-through-your-secret-delivery-system   # openssl rand -base64 32
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
            - name: SCHEDULER_SECRETS_MASTER_KEY
              valueFrom:
                secretKeyRef:
                  name: voyager-master-key
                  key: master-key
```

### Docker Compose

The repository's Compose file requires the master key and loads it from the git-ignored `.env` file.
There is no shared fallback key. Create `.env` before the first `docker compose up`:

```dotenv
SCHEDULER_SECRETS_MASTER_KEY=<output of openssl rand -base64 32>
```

The equivalent Compose environment mapping is:

```yaml
services:
  app:
    environment:
      SCHEDULER_SECRETS_MASTER_KEY: ${SCHEDULER_SECRETS_MASTER_KEY:?Set SCHEDULER_SECRETS_MASTER_KEY in .env or the deployment secret store}
```

Back up the key in a secure password manager or secret store. Losing it makes existing encrypted AI
and MCP credentials unrecoverable. Do not commit `.env` or copy its value into documentation.

## Upgrading from secret references

The production profile automatically runs `db/workflow-ai-conversations.sql` and
`db/inline-secrets.sql` before Hibernate validates the schema. The latter adds the encrypted columns,
enables the `CUSTOM_HEADERS` auth enum value, and removes the obsolete reference columns. It is
idempotent and may also be applied manually before a rolling deployment.

A legacy reference name cannot be converted into ciphertext without its plaintext value, so re-enter
existing AI and MCP credentials through their write-only forms after upgrading from that schema.

## Storage format

Each encrypted value is stored as `v1:<base64(iv || ciphertext||tag)>` with a random 12-byte
IV per value. The `v1:` prefix reserves room for future key rotation. GCM's authentication
tag means a tampered value or a wrong master key surfaces as a decrypt failure rather than
garbage plaintext.

## Notes

- **Values are write-only.** API responses expose only presence flags (`hasCredential`,
  `hasAuthToken`) and secret names (`secretEnvKeys`, `secretHeaderNames`) — never the secret
  itself. Editing an item without re-entering a single credential leaves it unchanged. For secret
  maps, a blank value preserves that named secret and removing its key deletes it.
- **Rotating the master key** (re-encrypting existing rows) is not yet automated; the `v1:`
  prefix leaves room for it.
