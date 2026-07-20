# Jenkins CI/CD

Voyager includes a local-first Jenkins pipeline that:

1. checks out the Git revision;
2. runs the 17/JVM backend tests and publishes Surefire plus JaCoCo results;
3. runs frontend lint, ASL tests, and the production build with Node 22;
4. optionally runs Playwright against the backend already listening on host port `8081`;
5. builds the backend and frontend container images;
6. pushes immutable images to GitHub Container Registry (GHCR); and
7. optionally replaces the local Compose `app` and `frontend`, with health-based rollback.

Kubernetes is not involved. The initial deployment target is the same Docker Desktop engine that
runs Jenkins.

## Images and tags

The pipeline publishes:

```text
ghcr.io/harshithrao07/voyager-app
ghcr.io/harshithrao07/voyager-frontend
```

Every publish receives an immutable `sha-<12-character-commit>` tag. Successful `main` builds also
move the `main` tag, while Git tags add a matching sanitized image tag. Deployments use the SHA tag;
they never rely on `latest`.

## 1. Prerequisites

- Docker Desktop running Linux containers.
- Voyager's ignored `.env` at the repository root, including the permanent
  `SCHEDULER_SECRETS_MASTER_KEY`.
- A GitHub personal access token (classic) with `write:packages`. A later remote deployment machine
  should receive a different token with only `read:packages`.

The local Jenkins controller mounts the Docker socket and can therefore control Docker Desktop.
Only build trusted repository branches. Do not enable builds for pull requests from untrusted forks
on this controller.

## 2. Start Jenkins

From the repository root:

```bash
docker compose -f compose.jenkins.yml up -d --build
```

Read the one-time unlock password:

```bash
docker compose -f compose.jenkins.yml exec jenkins \
  cat /var/jenkins_home/secrets/initialAdminPassword
```

Open `http://localhost:8080`, paste the password, and create the administrator account. The custom
image already installs the Pipeline, GitHub Branch Source, Docker Pipeline, credentials, JUnit,
HTML Publisher, and workspace-cleanup plugins.

The `jenkins-home` volume persists configuration and build history. A normal stop preserves it:

```bash
docker compose -f compose.jenkins.yml down
```

Do not add `-v` unless the Jenkins configuration and history should be permanently deleted.

## 3. Add Jenkins credentials

Open **Manage Jenkins → Credentials → System → Global credentials** and add:

| Kind | ID | Value |
|---|---|---|
| Username with password | `github-container-registry` | Username `harshithrao07`; password is the GitHub PAT with `write:packages` |
| Secret text (optional) | `sonar-token` | SonarQube token |

The pipeline sends the PAT to `docker login --password-stdin`, disables shell command echoing around
the login, and logs out in the post-build cleanup. No credential is committed to Git or baked into
an image.

## 4. Create the multibranch job

1. Select **New Item → Multibranch Pipeline**.
2. Name it `voyager`.
3. Under **Branch Sources**, choose Git and use
   `https://github.com/harshithrao07/voyager`.
4. Keep the script path as `Jenkinsfile`.
5. Discover trusted branches only; do not enable untrusted fork pull requests on this local
   Docker-enabled controller.
6. Save, then select **Scan Multibranch Pipeline Now** once.

Because a workstation is not publicly reachable by GitHub, each discovered branch polls SCM every
five minutes. A future public Jenkins installation can replace polling with the normal GitHub
webhook.

## 5. Pipeline parameters

| Parameter | Default | Effect |
|---|---:|---|
| `PUBLISH_IMAGES` | `true` | On `main` and Git tags, push SHA-tagged images to GHCR |
| `DEPLOY_LOCAL` | `false` | On `main`, pull the published SHA and update the local app/frontend |
| `RUN_E2E` | `false` | Run Playwright using the backend currently available at host port `8081` |
| `SONAR_HOST_URL` | empty | When set, run Sonar using the `sonar-token` credential |

Branch builds always run backend and frontend checks. Image building and publishing are restricted
to `main` and Git tags. Local deployment additionally requires both `PUBLISH_IMAGES=true` and
`DEPLOY_LOCAL=true`.

## 6. Local deployment and rollback

Before enabling local CD, start the normal Voyager dependencies at least once:

```bash
docker compose up -d
```

Run the `main` job with `DEPLOY_LOCAL=true`. Jenkins will:

1. authenticate to GHCR;
2. pull both immutable SHA-tagged images;
3. record the image currently used by each local service;
4. replace and health-check `app`;
5. replace and health-check `frontend`; and
6. restore both previous images if either health check fails.

The deployment reuses the Compose project named `voyager`. PostgreSQL, Redis, Kafka, Prometheus,
Judge0, and every persistent volume remain untouched. The controller reads the existing `.env`
through a read-only bind mount; it never prints or copies the file.

The deploy override can also be inspected manually:

```bash
VOYAGER_APP_IMAGE=ghcr.io/harshithrao07/voyager-app:sha-abc123 \
VOYAGER_FRONTEND_IMAGE=ghcr.io/harshithrao07/voyager-frontend:sha-abc123 \
docker compose -f docker-compose.yml -f compose.deploy.yml config
```

## Operations

View controller status and logs:

```bash
docker compose -f compose.jenkins.yml ps
docker compose -f compose.jenkins.yml logs -f jenkins
```

Rebuild Jenkins after changing `.jenkins/Dockerfile` or `plugins.txt`:

```bash
docker compose -f compose.jenkins.yml up -d --build
```

When a remote Linux deployment host is added later, the CI and GHCR stages remain unchanged. Only
the final deployment stage needs to switch from the local Docker socket to an SSH credential and
remote Compose command.
