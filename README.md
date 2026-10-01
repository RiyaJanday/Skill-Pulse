# SkillPulse

**An AI-assisted skill-tracking and MCQ practice platform.**

Learners practise multiple-choice questions organised as **Subject → Topic → Question**. SkillPulse tracks how well they know each topic, predicts what they are about to forget, schedules reviews, builds a personalised 7-day study plan, and reshapes a weekly timetable when real life gets in the way. A study chatbot, an AI tutor and a job skill-gap analyser sit on top.

The project is built as a college group project. The backend is **100% Java (Spring Boot)** and all AI/ML is **100% Python (FastAPI)**, so the two halves can be developed, tested and explained separately.

---

## Table of contents

1. [Team and work split](#1-team-and-work-split)
2. [Features](#2-features)
3. [Architecture](#3-architecture)
4. [How it works (end-to-end flow)](#4-how-it-works-end-to-end-flow)
5. [Tech stack](#5-tech-stack)
6. [Project structure](#6-project-structure)
7. [Authentication and authorization](#7-authentication-and-authorization)
8. [AI/ML in detail](#8-aiml-in-detail)
9. [API endpoints](#9-api-endpoints)
10. [Database](#10-database)
11. [Configuration](#11-configuration)
12. [Running the project locally](#12-running-the-project-locally)
13. [Docker](#13-docker)
14. [CI pipeline](#14-ci-pipeline)
15. [Deployment (Railway)](#15-deployment-railway)
16. [Testing](#16-testing)
17. [Known limitations](#17-known-limitations)
18. [Screenshots](#18-screenshots)

---

## 1. Team and work split

| Member |
|---|
| Aman Shankar |
| Riya Janday |
| Komal |
| Riya Adatiya |

| Semester | Scope |
|---|---|
| **6th semester** | The frontend (all HTML pages) and part of the backend, including **authentication and authorization**: register, login, logout, sessions and role-based access. |
| **7th semester** | The rest of the backend (practice flow, dashboard, personalisation, timetable, notifications, admin tools, chat relay) and all **ML / AI services** in the Python `ml-service`. |

The faculty asked the team to integrate AI/ML features in the 7th semester, which is why the ML service exists.

---

## 2. Features

### For learners
- **Register, log in, log out**, change or reset a forgotten password by email, edit profile.
- **Practice (assessment page):** answer MCQs by Subject → Topic, with an AI explanation for wrong answers.
- **Dashboard:** health score per skill over time, achievements, skill summaries.
- **Skill decay analysis:** predicted recall probability per topic with status DECAYING / STABLE / IMPROVING and suggested actions.
- **Spaced repetition:** each question gets a review date (SM-2).
- **Personalised 7-day plan:** written by an LLM from the learner's weak topics and preferences.
- **Weekly timetable:** week grid, today card with progress bar, "Target done" button, "Adjust today" (fewer minutes, or "I can't study today"), and a small confetti burst when the day is done.
- **Study chatbot (`chat.html`):** streaming answers to study questions, with polite refusals of off-topic requests.
- **Job skill gap (`skill-gap.html`):** paste a job description; see strong, partial and missing skills compared with your real mastery, plus topics to practise.
- **Email notifications:** weekly progress email with an optional AI coach note, and nudges to learners at risk of dropping out.

### For admins
- Manage users and questions (`admin.html`, `admin-users.html`).
- AI admin page (`admin-ai.html`): dropout risk list, learner segments (clustering), question quality (IRT), per-topic BKT fitting, anonymous attempts CSV export, AI-drafted questions that need admin approval before publishing, and a view of chatbot messages that were refused.

---

## 3. Architecture

```
Browser  ->  Java Spring Boot (port 8080)  ->  Python FastAPI ML service (port 8001)  ->  Groq / Gemini / Ollama
             auth, DB, pages, storage          models, formulas, LLM calls
             MlServiceClient (timeouts,        stateless: Java sends the state
             30 s cool-down, fallbacks)        in every request
                     |
                PostgreSQL
```

Design rules:

1. **Backend 100% Java.** Auth, sessions, PostgreSQL storage, practice, dashboard, notifications, static pages.
2. **AI/ML 100% Python.** Every model, formula and LLM call lives in `ml-service/`.
3. **The app must never break because of ML.** If the Python service is down, Java uses plain non-ML fallbacks: running accuracy for mastery, a fixed review-interval ladder, and a rule-based plan. Users see slightly less smart behaviour, not errors.
4. **Real ML, honestly reported.** Where there is no real data yet, models are trained on simulated learners and every metric says so. LLMs are used for language, never for hard constraints such as scheduling.
5. **The ML service is stateless.** Java stores mastery, review schedules and chat history, and sends the needed state in every request.
6. **Optional shared secret.** If `ML_SERVICE_KEY` is set, every ML endpoint except `/health` requires the header `X-Internal-Key`.

---

## 4. How it works (end-to-end flow)

1. **Sign up / log in.** `AuthController` checks the BCrypt-hashed password and creates a PostgreSQL-backed session. The browser gets an `HttpOnly` cookie `SKILLPULSE_SESSION`.
2. **Practice.** The learner picks a Subject and Topic. `PracticeService` serves questions (the adaptive order can come from the ML service). Each answer is stored as a `UserPracticeAttempt`, with correctness, difficulty and time taken.
3. **Mastery update (BKT).** Java calls `POST /bkt/update` on the ML service. It returns the new probability that the learner knows the topic. Guess and slip adjust to question difficulty; slip rises when an answer takes over 180 s.
4. **Review schedule (SM-2).** Java calls `POST /review/schedule`, which returns repetitions, interval and ease factor for that question.
5. **Forgetting.** Mastery decays by 0.5% per idle day (`/bkt/decay`). The XGBoost recall model (`/analyze`) predicts the chance of answering correctly after a gap and labels the topic DECAYING, STABLE or IMPROVING.
6. **Dashboard.** `DashboardService` combines mastery, accuracy and activity into a health score and achievements (plain rules, not ML).
7. **Plan.** `PersonalizationService` sends weak topics and preferences to `POST /plan/generate`. The LLM chain (Groq → Gemini → Ollama) writes a 7-day plan, validated as JSON. If every provider fails, Java saves a **rule-based plan** instead.
8. **Timetable.** The plan's days and tasks form the weekly timetable. When the learner presses **Adjust**, `TimetableRebalancer` (a deterministic rule, no AI) keeps the highest-priority tasks in today's minutes, moves the rest to later days with spare room, and reports what fits nowhere. Priority is low topic mastery first, with a bonus for overdue tasks. The LLM (`/timetable/explain`) only writes the friendly explanation; Java has a built-in sentence if it is down.
9. **Chat.** `chat.html` talks to `ChatController`, which applies a per-user rate limit, stores the conversation and relays the Python SSE stream. Python classifies each message (STUDY, GREETING, MIXED, OFF_TOPIC), uses a scoped system prompt and writes natural refusals.
10. **Notifications.** `NotificationScheduler` sends weekly stats emails (optionally with an AI note from `/coach/weekly-note`) and nudges at-risk learners (`/coach/nudge`).
11. **Admin.** Admins review risk, segments and question quality, fit BKT parameters from real answers, export attempts as CSV, and approve AI-drafted questions.
12. **Retraining.** The exported CSV can retrain the recall model on real data with `python train.py --attempts attempts.csv`.

---

## 5. Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 8 (Docker image), Spring Boot 2.7.18, Spring Security, Spring Session (JDBC), Spring Data JPA / Hibernate, Flyway, Spring Mail, BCrypt |
| Database | PostgreSQL |
| Frontend | Static HTML, CSS and JavaScript served by Spring Boot |
| ML service | Python 3.11, FastAPI, Uvicorn, pydantic, XGBoost, scikit-learn style tooling |
| LLMs | Groq (first), Gemini (second), Ollama (optional local fallback) |
| Tooling | Maven, pytest, Docker, GitHub Actions |

---

## 6. Project structure

```
SkillPulse-Groq-final/
  pom.xml
  Dockerfile                    backend image (Java 8, honours $PORT)
  .dockerignore
  .github/workflows/ci.yml      CI pipeline
  src/main/java/com/skillpulse/
    SkillPulseApplication.java
    auth/             register, login, logout, password reset, AppUser
    config/           SecurityConfig, PageController, error handling
    practice/         subjects, topics, questions, options, attempts, seeding
    adaptive/         stores mastery and review state, calls the ML service
    dashboard/        summary, health score
    personalization/  preferences, 7-day plans, plan progress
    timetable/        TimetableRebalancer, service, controller
    chat/             conversations, messages, rate limit, SSE relay, admin view
    ai/               AI insights for admins, skill-gap analysis
    notification/     NotificationScheduler (weekly email, nudges)
    admin/            user management, question management, admin bootstrap
    integration/      MlServiceClient, MlProxyController
  src/main/resources/
    application.properties
    db/migration/     V1 to V5 Flyway scripts
    static/           all HTML pages and assets
  ml-service/
    app/              main.py (routes), config, schemas, recall, features,
                      training, synthetic, bkt, sm2, llm, plan
    train.py          retrain models (simulated or real data)
    models/           saved models and metrics (recall_metrics.json)
    tests/            pytest suite
    Dockerfile, requirements.txt, .env.example, README.md
```

### Frontend pages (`src/main/resources/static`)

| Page | Purpose |
|---|---|
| `index.html`, `login.html` | Landing page and login / register |
| `forgot-password.html`, `reset-password.html`, `change-password.html`, `edit-profile.html` | Account management |
| `dashboard.html` | Learner dashboard |
| `assessment.html` | MCQ practice |
| `personalized-plan.html` | 7-day plan |
| `timetable.html` | Weekly timetable |
| `chat.html` | Study chatbot |
| `ai-coach.html` | AI coach |
| `skill-gap.html` | Job description skill gap |
| `admin.html`, `admin-users.html`, `admin-ai.html` | Admin tools |

---

## 7. Authentication and authorization

*(Built in the 6th semester.)*

- Spring Security with a **PostgreSQL-backed Spring Session** (`spring.session.store-type=jdbc`); the `SPRING_SESSION` tables are created automatically.
- The browser receives an `HttpOnly`, `Secure`, `SameSite=Strict` cookie named `SKILLPULSE_SESSION`. The cookie, not the legacy `token` query parameter, identifies the user. JSON responses keep a `cookie-session` marker so older frontend code can detect a signed-in response; it is not a credential.
- Passwords are hashed with **BCrypt**.
- **Roles:** learner and `ADMIN`. Admin endpoints (`/api/admin/**`) require the ADMIN role; `/api/timetable/**` and chat endpoints require login.
- Password reset by email with a token that expires after 15 minutes.
- Session timeout: 30 minutes.
- For local plain-HTTP development set `SESSION_COOKIE_SECURE=false`. Production must use HTTPS and keep it enabled.

---

## 8. AI/ML in detail

*(Built in the 7th semester, in `ml-service/`.)*

### 8.1 Models and algorithms

| Component | What it does |
|---|---|
| **XGBoost recall model** | Predicts whether a learner answers correctly on the next attempt after a gap. Features: days since last practice, recent and overall accuracy, accuracy trend, difficulty, attempt count, hint usage, completion rate. Compared with the original hand-tuned half-life formula on AUC, log loss and Brier score. Falls back to the formula if no model is available (`engine: formula-fallback`). |
| **Bayesian Knowledge Tracing (BKT)** | Per-topic mastery update, decay, replay from history, and parameter fitting from real answer sequences. |
| **SM-2 spaced repetition** | Quality score, repetitions, interval and ease factor per question; replay of old attempts. |
| **Dropout-risk model** | XGBoost with a rule-based fallback; drives nudge emails and the admin risk list. |
| **Rasch (IRT) question calibration** | Data-driven question difficulty; flags poor or misleading questions. |
| **Adaptive question order** | Picks the next question from mastery and difficulty. |
| **k-means segments** | Groups learners by behaviour for admin insights. |

### 8.2 LLM features

| Feature | Endpoint |
|---|---|
| 7-day plan | `POST /plan/generate` |
| Timetable explanation | `POST /timetable/explain` |
| Study chatbot (streaming) | `POST /chat/stream` |
| Wrong-answer explanations | `POST /tutor/explain` |
| Question drafting (admin approves) | `POST /tutor/generate-questions` |
| Nudge emails | `POST /coach/nudge` |
| Weekly email coach note | `POST /coach/weekly-note` |
| Job skill gap | `POST /skills/gap` |

Providers are tried in order: **Groq → Gemini → Ollama**. If all fail, the endpoint returns 503 and Java uses its fallback. In the skill gap, the LLM only extracts the job's skills; each status is recomputed from real mastery (70% or more is "strong") and recommended topics must exist on the platform.

### 8.3 The chatbot's three-layer restriction

1. A small fast **router** model classifies the message (using the last few messages so "why?" is not blocked).
2. A **scoped system prompt** for the main model. User text is never inserted into the system prompt.
3. **Natural refusals** for off-topic requests, with an offer of a study angle.

### 8.4 Honesty about data

The recall and dropout models start out trained on **simulated learners**, and every metric reports that. Once real attempts exist, the same pipeline retrains on them:

1. Admin page → **Model training** tab → **Fit learning parameters** (per-topic BKT).
2. **Download attempts CSV** (anonymous ids only) and save it as `ml-service/attempts.csv`.
3. `python train.py --attempts attempts.csv --dry-run` compares the real-data model with the current one; run without `--dry-run` to replace it, then restart the ML service.

See `ml-service/README.md` for the status of every step.

---

## 9. API endpoints

**Auth:** `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`

**Learner:**
`GET /api/dashboard/summary`, `GET /api/skills`, `POST /api/ml/analyze`, `GET /api/ml/health`,
`GET|PUT /api/personalization/preferences`, `POST /api/personalization/plans/generate`,
`GET /api/timetable`, `POST /api/timetable/days/{id}/complete`, `POST /api/timetable/days/{id}/adjust`,
`POST /api/ai/skill-gap`, chat endpoints under `/api/chat/**`

**Admin (ADMIN role):**
`GET /api/admin/ai/risk`, `GET /api/admin/ai/segments`, `GET /api/admin/ai/question-quality`,
`POST /api/admin/ai/fit-bkt`, `GET /api/admin/ai/export-attempts.csv`, user and question management under `/api/admin/**`

**ML service (port 8001, interactive docs at `/docs`):**
`GET /health`, `GET /model/info`, `POST /analyze`, `POST /bkt/update`, `/bkt/decay`, `/bkt/replay`, `/bkt/fit`,
`POST /review/schedule`, `/review/replay`, `POST /plan/generate`, `POST /timetable/explain`,
`POST /chat/stream`, `POST /tutor/explain`, `/tutor/generate-questions`, `POST /coach/nudge`, `/coach/weekly-note`,
`POST /skills/gap`, `POST /risk/predict`, `POST /questions/quality`, `POST /adaptive/order`, `POST /segments/cluster`

---

## 10. Database

PostgreSQL, database name `skillpulse_db` locally.

- **Hibernate** (`ddl-auto=update`) owns the main tables: users, subjects, topics, questions, options, attempts, mastery, plans, plan days and tasks, learning preferences, chat conversations and messages.
- **Flyway** migrations in `src/main/resources/db/migration`:

| File | Purpose |
|---|---|
| `V1__base_tables.sql` | Stub tables (`app_users`, `practice_topics`, `practice_questions`) so later migrations work on a brand-new database such as Docker or Railway. Hibernate adds all other columns afterwards. |
| `V2__adaptive_learning_model.sql` | Adaptive learning (mastery and review) tables |
| `V3__mastery_ml_sync.sql` | Mastery columns for ML sync |
| `V4__mastery_version.sql` | Mastery versioning |
| `V5__chat.sql` | Chat conversations and messages |

Existing databases are baselined at version 1 (`spring.flyway.baseline-version=1`), so V1 never runs there.

- **Spring Session** tables are created automatically at startup.
- The timetable adds two nullable columns to the plan tasks (`planned_minutes`, `reschedule_count`), created by Hibernate.

---

## 11. Configuration

### Java backend (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/skillpulse_db` | Database URL |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | `root` | Database password. Always set your own. |
| `SESSION_COOKIE_SECURE` | `true` | Set `false` for local HTTP only |
| `ML_SERVICE_URL` | `http://localhost:8001` | Python service address |
| `ML_SERVICE_KEY` | empty | Shared secret sent as `X-Internal-Key` |
| `ML_TIMEOUT_MS` | `3000` | Timeout for fast ML calls |
| `ML_PLAN_TIMEOUT_SECONDS` | `60` | Timeout for plan generation |
| `SKILLPULSE_ML_ENABLED` | `true` | Turn ML calls on or off |
| `SKILLPULSE_ADMIN_EMAIL` | see `application.properties` | Admin account email |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | Gmail SMTP | Email sending |
| `SKILLPULSE_BASE_URL` | `http://localhost:8080` | Base URL used in email links |
| `NOTIFICATION_TIME_ZONE` | `Asia/Kolkata` | Time zone for scheduled emails |
| `PORT` | `8080` | Port (Railway sets it) |

LLM keys never go in the Java configuration.

### ML service (`ml-service/.env`, copy from `.env.example`)

| Variable | Purpose |
|---|---|
| `ML_SERVICE_KEY` | Shared secret with Java. Empty means no check (local development only). |
| `GROQ_API_KEY`, `GROQ_MODEL` | First LLM provider |
| `GEMINI_API_KEY`, `GEMINI_MODEL` | Second provider |
| `OLLAMA_ENABLED`, `OLLAMA_URL`, `OLLAMA_MODEL` | Local last-resort provider, off by default |
| `LLM_TIMEOUT_SECONDS` | Per-provider timeout (default 40) |
| `LLM_TOTAL_TIMEOUT_SECONDS` | Deadline for the whole provider chain (default 45); keep below Java's plan timeout |
| `AUTO_TRAIN` | Train the recall model at startup if no model file exists |
| `PORT`, `HOST` | Port and host (Railway sets `PORT`) |

> **Never commit `.env` or paste API keys into chat, issues or commits.** Rotate any key that has been exposed.

---

## 12. Running the project locally

**Prerequisites:** Java (see Known limitations about versions), Maven, Python 3.11, PostgreSQL with an empty database called `skillpulse_db`.

Start the Python service first, then the Java backend. Use two PowerShell windows.

### Window 1: ML service

```powershell
cd C:\Drishti\SkillPulse-Groq-final\ml-service
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env          # then fill in GROQ_API_KEY etc.
python -m pytest
uvicorn app.main:app --port 8001 --reload
```

Check http://localhost:8001/health. It should show `"ready": true`, the recall engine and your LLM providers.

### Window 2: Java backend

```powershell
cd C:\Drishti\SkillPulse-Groq-final
$env:DB_PASSWORD = "your-postgres-password"
$env:SESSION_COOKIE_SECURE = "false"
mvn spring-boot:run
```

Open http://localhost:8080. Restart the backend after pulling changes so Hibernate can create new columns.

---

## 13. Docker

Each service has its own Dockerfile.

- **Backend (`Dockerfile`):** multi-stage Maven build, runs on `eclipse-temurin:8-jre`, listens on `$PORT` (default 8080).
- **ML service (`ml-service/Dockerfile`):** Python image; trains the recall model during the build so the container starts instantly; listens on `$PORT` (default 8001).
- `.dockerignore` files keep `target/`, `.git`, `.env` and virtual environments out of the build.

### Run everything with Docker Compose

`docker-compose.yml` starts PostgreSQL, the ML service and the backend together.

```powershell
copy .env.example .env                 # set DB_PASSWORD (optional: mail settings)
copy ml-service\.env.example ml-service\.env   # add GROQ_API_KEY etc. (skip if it already exists)
docker compose up --build
```

Open http://localhost:8080. The ML service is also at http://localhost:8001/health.

- `docker compose down` stops everything and keeps the database; `docker compose down -v` also deletes the database.
- The database port is not published, so it cannot clash with a PostgreSQL already installed on your PC.
- Do not run `mvn spring-boot:run` at the same time (both use port 8080).
- If you set `ML_SERVICE_KEY` in `ml-service/.env`, set the same value in the root `.env`.

Build the images separately if needed:

```powershell
docker build -t skillpulse-backend .
docker build -t skillpulse-ml ./ml-service
```

---

## 14. CI pipeline

`.github/workflows/ci.yml` runs on every push to `main` and every pull request:

1. **ml-service job:** installs Python 3.11 dependencies and runs pytest with empty API keys, so a test that secretly needs a real LLM fails.
2. **backend job:** `mvn verify` on Java 8, the same version as the Dockerfile.
3. **docker job:** builds both images, only if the first two pass.

A newer push cancels an older run of the same branch. Status is on the **Actions** tab of the GitHub repository.

---

## 15. Deployment (Railway)

One Railway project with three services: **Postgres**, **ml-service** (from `ml-service/Dockerfile`) and **backend** (from the root `Dockerfile`). Only the backend gets a public domain.

1. Push the repo to GitHub.
2. In Railway: **New Project → Deploy PostgreSQL**.
3. **New → GitHub Repo → this repo**. Name it `ml-service`, and in Settings set **Root Directory** to `/ml-service`. Variables:
   - `PORT=8001`
   - `GROQ_API_KEY=...` (and `GEMINI_API_KEY` if used)
   - `ML_SERVICE_KEY=<long random secret>`
   - Settings → Healthcheck path: `/health`
   - Only if the backend cannot reach it (old environments resolve private names over IPv6 only): `HOST=::`
4. **New → GitHub Repo → this repo** again. Name it `backend`, leave Root Directory as `/`. Variables:
   - `DB_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}`
   - `DB_USERNAME=${{Postgres.PGUSER}}`
   - `DB_PASSWORD=${{Postgres.PGPASSWORD}}`
   - `ML_SERVICE_URL=http://${{ml-service.RAILWAY_PRIVATE_DOMAIN}}:${{ml-service.PORT}}`
   - `ML_SERVICE_KEY=` the same secret as the ML service
   - `SESSION_COOKIE_SECURE=true`
   - `SKILLPULSE_BASE_URL=https://<your-backend-domain>` (used in email links)
   - `MAIL_USERNAME`, `MAIL_PASSWORD`, `SKILLPULSE_ADMIN_EMAIL`
   - Settings → Networking → **Generate Domain**
5. Deploy both. The first backend start runs Flyway V1 to V5 and lets Hibernate create the rest on the empty database.

If a service name differs from `ml-service` or `Postgres`, use that name inside `${{ }}`. Railway injects `PORT` itself for public services, which the Dockerfiles already honour. Check that `https://<backend-domain>` loads, you can register and log in, and generating a plan shows a Groq model name rather than `rule-based-fallback`.

---

## 16. Testing

- **ML service:** `cd ml-service; python -m pytest`. The last full real run passed 77 tests; 5 more were added for the real-data loader, and the coach and skill-gap tests are in `tests/test_coach_tools.py`.
- **Java:** there are currently no Java tests; `mvn verify` only checks that the code compiles.
- **Fallback check:** stop the ML service, generate a plan, and confirm it saves as a rule-based plan without an error page.
- **Manual checks:** timetable Adjust and Target done, chat streaming and refusals, skill-gap page.

---

## 17. Known limitations

- Models trained on simulated learners until real attempts are collected; metrics say so.
- Java version mismatch: local runs use Java 25, Docker and CI use Java 8.
- N+1 queries on practice and dashboard loads.
- Missed timetable days carry forward only when the learner presses Adjust.
- SM-2 due reviews are not part of timetable priority yet.
- Per-topic BKT `pInit` is stored but new topics still start at 0.20.
- `application.properties` still has local defaults (database password, admin and mail address); use environment variables for any shared or deployed setup.
- No Java tests yet. The Docker Compose and Railway setups are written but have not been run end to end.

---

## 18. Screenshots

<img width="1895" height="901" alt="image" src="https://github.com/user-attachments/assets/4f03e780-d15d-4187-8df0-30d253a69804" />
<img width="1896" height="902" alt="image" src="https://github.com/user-attachments/assets/a44107f4-969d-475b-b430-150130c6e1d4" />
<img width="1895" height="901" alt="image" src="https://github.com/user-attachments/assets/872a796e-bfed-4989-b4d0-00d58552529d" />
<img width="1900" height="902" alt="image" src="https://github.com/user-attachments/assets/ebbfde6b-372b-48c4-af41-94157ba4138b" />
<img width="1912" height="906" alt="image" src="https://github.com/user-attachments/assets/08baf89d-f4fd-44ff-a66e-5b0ba5b1fba3" />
<img width="1898" height="906" alt="image" src="https://github.com/user-attachments/assets/deee92b9-28f8-4270-b473-3858d2a78c46" />
