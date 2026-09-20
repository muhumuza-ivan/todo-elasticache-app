# todo-app

Java 21 / Spring Boot To-Do application. Writes go to Amazon RDS for PostgreSQL
through RDS Proxy; reads are served cache-aside from Amazon ElastiCache for
Redis. Runs as a container on ECS Fargate.

Infrastructure lives in a separate repository: **`todo-infra`**.

## What it demonstrates

- `GET /api/tasks` checks Redis (`todo:tasks:all`) first. On a hit the response
  says `"source": "cache"`; on a miss it reads PostgreSQL through the proxy,
  repopulates the key with a 60 second TTL and says `"source": "database"`.
- Every write commits to PostgreSQL and then evicts the keys it touched, so the
  cache can never serve something the database rejected.
- Redis is best-effort. If the cache is unreachable the request still succeeds
  against the database and the ALB health check keeps passing - the Redis health
  indicator is deliberately disabled for that reason.
- The UI shows which backend answered and how long it took, so a live demo is
  just clicking *Refresh* twice.

## API

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/api/tasks` | `{ source, elapsedMs, count, items[] }` |
| `GET` | `/api/tasks/{id}` | `{ source, elapsedMs, task }` |
| `POST` | `/api/tasks` | `{ title, description?, completed? }` |
| `PUT` | `/api/tasks/{id}` | full update |
| `DELETE` | `/api/tasks/{id}` | `204` |
| `POST` | `/api/cache/flush` | drops this app's Redis keys (demo aid) |
| `GET` | `/api/info` | environment, Region, backend hosts, cache reachability |
| `GET` | `/actuator/health` | ALB and container health check |

## Configuration

There are two configuration files and a clear line between them:

| File | Applies | Ships in the image |
| --- | --- | --- |
| `src/main/resources/application.yaml` | AWS. It is the default — no profile is active on ECS | yes, and it is what runs |
| `src/main/resources/application-local.yaml` | local only, via `-Dspring-boot.run.profiles=local` | yes, but inert unless that profile is switched on |

`application.yaml` is the AWS configuration, not a neutral base: TLS to
PostgreSQL is pinned on, Redis TLS defaults to on, and the credential source is
the Secrets Manager ARN. `application-local.yaml` overrides exactly the three
things a laptop needs — plaintext PostgreSQL, plaintext Redis, and an empty
`secret-arn` so credentials come from the environment instead of AWS.

Nothing environment-specific is baked into the image. ECS resolves every value
from SSM Parameter Store at task start, as declared in `taskdef.json`:

| Env var | Source | Meaning |
| --- | --- | --- |
| `DB_HOST` | `/todo-app/dev/db/host` | RDS Proxy endpoint |
| `DB_PORT` | `/todo-app/dev/db/port` | 5432 |
| `DB_NAME` | `/todo-app/dev/db/name` | `todoapp` |
| `DB_SECRET_ARN` | `/todo-app/dev/db/secret-arn` | **ARN** of the RDS-managed Secrets Manager secret |
| `REDIS_HOST` | `/todo-app/dev/redis/host` | primary endpoint |
| `REDIS_PORT` | `/todo-app/dev/redis/port` | 6379 |
| `REDIS_SSL` | `/todo-app/dev/redis/ssl` | matches `TransitEncryptionEnabled` |

The database password is never an environment variable and never appears in the
task definition. The container receives only the *ARN* of the secret and calls
`GetSecretValue` at startup with the AWS SDK for Java v2, authenticating as the
ECS task role (`DataSourceConfig`).

Nobody ever chooses that password. RDS generates it, owns the secret, and
rotates it every seven days (`ManageMasterUserPassword` in `data.yaml`), so it
is not in the template, not in a change set, and not in anyone's hands. The
application re-reads the secret at startup and RDS Proxy re-reads it on each
authentication, so a rotation needs no redeploy.

## Deployment files

`taskdef.json` and `appspec.yaml` sit at the repository root and are consumed
verbatim by the CodeDeployToECS pipeline action. They are static on purpose -
the pipeline does not template, rewrite or regenerate them:

- `taskdef.json` keeps exactly one placeholder, `<IMAGE1_NAME>`, which
  CodePipeline replaces with the image the ECR source action reported. Every
  other value is either a literal or an SSM parameter reference.
- IAM roles are referenced **by name** (`todo-app-dev-task-execution`,
  `todo-app-dev-task`). `RegisterTaskDefinition` accepts a role name as well as
  an ARN, which keeps the account ID out of the file.
- `secrets[].valueFrom` uses SSM parameter **names**, which ECS resolves within
  the task's own Region - again, no account ID, no Region-qualified ARNs.
- `appspec.yaml` omits `NetworkConfiguration` so CodeDeploy reuses the subnets
  and security groups already on the ECS service.

A CI step fails the build if the container name or port drifts between the two
files, or if the `<IMAGE1_NAME>` placeholder is ever resolved by hand.

Only two literals in `taskdef.json` are environment-coupled and must match
`todo-infra`: `awslogs-region` (`eu-west-1`) and the `/todo-app/dev/*` parameter
prefix.

## Pipeline

```
push to main
  └─ GitHub Actions: mvn verify → docker buildx → push :<sha> → re-tag :latest
       └─ EventBridge (ECR Image Action, PUSH, SUCCESS, tag=latest)
            └─ CodePipeline: app repo source + ECR imageDetail.json
                 └─ CodeDeploy: blue/green onto ECS, test listener :8080,
                    cutover on :80, rollback on 5xx or unhealthy hosts
```

The workflow pushes the commit SHA tag first and only then moves `:latest`
(with `ecr put-image`, which re-tags the existing manifest instead of
re-uploading). The deployment therefore starts exactly once, after the image is
complete.

Authentication to AWS is OIDC only - `permissions: id-token: write` plus
`aws-actions/configure-aws-credentials`, assuming a role whose trust policy is
scoped to this repository. No access keys exist in the repository or in GitHub
secrets.

Required repository secrets:

| Secret | Example |
| --- | --- |
| `AWS_REGION` | `eu-west-1` |
| `AWS_ROLE_ARN` | `arn:aws:iam::<account>:role/todo-app-dev-gha-ecr-push` |
| `ECR_REPOSITORY` | `todo-app` |

## Running locally

Credentials are not committed and have no defaults. Create your `.env` first:

```bash
cp .env.example .env      # then set DB_USERNAME and DB_PASSWORD
```

Docker Compose reads `.env` on its own. Maven does not, so export it into the
shell that runs the application:

```bash
set -a && . ./.env && set +a          # bash / Git Bash
docker compose up -d                  # postgres + redis
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

```powershell
# PowerShell equivalent of the export line
Get-Content .env | Where-Object { $_ -match '^\s*[^#].*=' } |
  ForEach-Object { $n,$v = $_ -split '=',2; [Environment]::SetEnvironmentVariable($n.Trim(), $v.Trim()) }
```

Then open <http://localhost:8080>. `application-local.yaml` disables TLS to
PostgreSQL and Redis and pins `app.db.secret-arn` to empty, so `DB_USERNAME`
and `DB_PASSWORD` are used and Secrets Manager is never contacted. Miss either
variable and startup fails immediately naming the one that is missing, rather
than failing later against PostgreSQL with an opaque authentication error.

Forget `-Dspring-boot.run.profiles=local` and you get the AWS configuration,
which is the safe direction to fail: it demands TLS and a secret ARN rather
than quietly running with local settings.

### `password authentication failed for user "postgres"`

Almost always a port clash, not a wrong password. A natively installed
PostgreSQL service on Windows listens on 5432 and starts automatically at boot,
so it answers before the container does - and its `postgres` role has a
different password. Both can appear bound to `0.0.0.0:5432` at once:

```powershell
netstat -ano | Select-String ":5432.*LISTENING"    # two PIDs = clash
Get-Service *postgres*                             # the native service
```

The container is innocent - confirm it with a real TCP login that bypasses the
published port entirely:

```bash
docker exec todo-app-postgres-1 sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select version()"'
```

If that succeeds while the application fails, the application is talking to the
other server. Move the container to a free port - `DB_PORT` is read by both
`docker-compose.yml` and the application, so one line fixes both sides:

```bash
echo "DB_PORT=5433" >> .env
docker compose up -d --force-recreate     # --force-recreate: port changes need a new container
```

Or free 5432 by stopping the native service (needs an admin shell):

```powershell
Stop-Service postgresql-x64-17
Set-Service postgresql-x64-17 -StartupType Manual   # stop it returning at boot
```

The same clash can happen on 6379 if you have another Redis installed.

Build the image the same way CI does:

```bash
docker build -t todo-app:dev .
docker run --rm -p 8080:8080 \
  -e DB_HOST=host.docker.internal -e DB_SSL_MODE=disable \
  -e REDIS_HOST=host.docker.internal \
  todo-app:dev
```

## Layout

```
src/main/java/com/lab/todo
├── config/AppProperties.java     all backend coordinates, bound from env vars
├── config/DataSourceConfig.java  Hikari pool; credentials from Secrets Manager
├── domain/                       Task entity, TaskDto (cache payload), TaskRequest
├── repository/TaskRepository.java
├── service/TaskService.java      cache-aside read/write logic
└── web/                          REST controllers, error handling
src/main/resources/static/        single-page UI (no build step)
taskdef.json, appspec.yaml        CodeDeploy inputs, consumed verbatim
Dockerfile                        multi-stage, non-root, healthcheck
```
