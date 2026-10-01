# SkillPulse ML Service

The Python (FastAPI) service that holds **all AI/ML logic** for SkillPulse.
The Java Spring Boot backend does no ML of its own: it handles login, the database, practice
flow and pages, and calls this service over HTTP.

> Last updated: 1 Oct 2026. Sections marked **Verified** were confirmed by running them.
> Sections marked **Written, not verified** exist as code but have not been compiled or run.
> Sections marked **To do** do not exist yet.

### Project status (1 Oct 2026)

| Step | Status |
|---|---|
| A. Java cleanup | Done. `_removed_java_ai/` is deleted and no old AI classes remain in `src`. |
| B. Study chatbot | Built. 30 chat tests pass and `/chat/stream` answered 200 in a real run. Behaviour in the browser still needs a manual check. |
| C. Timetable | Done. Target done verified by running. The AI explanation was failing with HTTP 503 (empty LLM reply); the token limit was raised. |
| D. LLM features | Done. Skill gap verified by running. |
| E. More ML models | Built: dropout risk, Rasch (IRT) question quality, adaptive question order and k-means segments, all on the admin AI page. New and **written, not verified**: per-topic BKT fitting (admin AI page, Model training tab). |
| F. Data and evaluation | **Written, not verified:** anonymous attempts CSV export and `python train.py --attempts attempts.csv` (split by learner, refuses to train on too little data, `--dry-run`). Until real attempts exist, every reported metric comes from simulated data. |

Tests: 77 passed in the last real run; 5 more were added for the real-data loader (expected total 82).

---

## 1. Vision

SkillPulse is a skill-tracking and MCQ practice platform (Subject > Topic > Question). The faculty
asked us to integrate AI/ML features. The design rule we chose:

1. **Backend 100% Java.** Auth, sessions, PostgreSQL storage, practice, dashboard, notifications, static pages.
2. **AI/ML 100% Python.** Every model, formula and LLM call lives here, so the two halves can be
   developed, tested and explained separately.
3. **The app must never break because of ML.** If this service is down, Java uses a plain,
   non-ML safety net (simple accuracy, fixed review intervals, rule-based plan). Users see slightly
   less smart behaviour, not errors.
4. **Real ML, honestly reported.** Where we lack real data we use simulated data, say so, and
   report metrics against a baseline. LLMs are used for language, never for hard constraints such as scheduling.

### Architecture

```
Browser  ->  Java Spring Boot (port 8080)  ->  Python FastAPI ML service (port 8001)  ->  Groq / Gemini / Ollama
              auth, DB, pages, storage           models, formulas, LLM calls
              MlServiceClient (timeouts,         stateless: Java sends the state
              30 s cool-down, fallbacks)         in every request
```

- The service is **stateless**. Java stores mastery, review schedules and chat history and passes
  the needed state in each call.
- Optional shared secret: if `ML_SERVICE_KEY` is set, every endpoint except `/health` requires the
  header `X-Internal-Key`. Java sends it from `skillpulse.ml.key`.
- Difficulty uses a 1-3 scale: EASY=1, MEDIUM=2, HARD=3.

---

## 2. Run it (Windows PowerShell)

```powershell
cd C:\Drishti\SkillPulse-Groq-final\ml-service
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env        # then fill in GROQ_API_KEY etc.
python -m pytest              # 82 tests should pass (77 verified, 5 newly added)
uvicorn app.main:app --port 8001 --reload
```

- Interactive docs: http://localhost:8001/docs
- Health check: http://localhost:8001/health should show `"ready": true`, the recall engine
  (`xgboost-recall-v1`) and the configured LLM providers (for example `["groq"]`).
- `.env` is git-ignored. **Never paste API keys into chat, issues or commits.** A Groq key was pasted
  in chat earlier, so delete it in console.groq.com and create a new one before any demo or submission.
- Start order for the full app: Python service first (port 8001), then `mvn spring-boot:run` (port 8080).

### Configuration (`.env`)

| Variable | Purpose |
|---|---|
| `ML_SERVICE_KEY` | Shared secret with Java (`X-Internal-Key`). Empty means no check (local dev only). |
| `GROQ_API_KEY`, `GROQ_MODEL` | First LLM provider (currently `openai/gpt-oss-120b`). |
| `GEMINI_API_KEY`, `GEMINI_MODEL` | Second provider (`gemini-2.5-flash`). |
| `OLLAMA_ENABLED`, `OLLAMA_URL`, `OLLAMA_MODEL` | Local last-resort provider. Off by default. |
| `LLM_TIMEOUT_SECONDS` | Per-provider timeout (default 40). |
| `LLM_TOTAL_TIMEOUT_SECONDS` | One deadline for the whole Groq, Gemini, Ollama chain (default 45). Keep it below Java's `skillpulse.ml.plan-timeout-seconds` (60). |
| `AUTO_TRAIN` | Train the recall model on synthetic data at startup if no model file exists. |

Every provider is optional. They are tried in order: Groq, then Gemini, then Ollama.

---

## 3. What is DONE

### 3.1 Python service (Verified: 77/77 tests passed in the last run, service runs on port 8001)

| Endpoint | Replaces (old Java) | What it does |
|---|---|---|
| `GET /health` | `MlController.health` | Readiness, active recall engine, configured LLM providers. |
| `GET /model/info` | new | Recall model metrics, useful for the viva. |
| `POST /analyze` | `SkillPulseMlService` | Skill decay analysis: predicted recall probability, status (DECAYING / STABLE / IMPROVING), confidence, priority, suggested actions, explanation. |
| `POST /bkt/update` | `AdaptiveLearningService.updateBkt` | One Bayesian Knowledge Tracing step. Guess and slip adjust to question difficulty; slip rises when an answer takes over 180 s. |
| `POST /bkt/decay` | `effectiveMastery`, `daysUntilThreshold` | Mastery after forgetting (0.5% per idle day) and days until it falls below a threshold. Batched. |
| `POST /bkt/replay` | `initializeFromHistory` | Seeds mastery from a learner's old attempts, many topics in one call. |
| `POST /bkt/fit` | new | Fits BKT parameters (init, learn, guess, slip) per topic from answer sequences. |
| `POST /review/schedule` | `AdaptiveLearningService.updateSm2` | SM-2 spaced repetition: quality score, repetitions, interval, ease factor. |
| `POST /review/replay` | new | Final SM-2 state for many questions from their stored attempts, so old answers get a real schedule. Called by Java's `initializeFromHistory`. |
| `POST /plan/generate` | `GroqApiClient` | 7-day personalised plan via the LLM chain. Returns 503 if every provider fails, so Java uses its rule-based plan. |

Code layout:

```
ml-service/
  app/main.py        FastAPI app, auth dependency, all routes
  app/config.py      environment settings, provider list
  app/schemas.py     request/response models (pydantic)
  app/recall.py      recall model loading, analysis, formula fallback
  app/features.py    feature engineering for the recall model
  app/training.py    training + evaluation code
  app/synthetic.py   simulated learners (latent ability + forgetting curve)
  app/bkt.py         BKT update, decay, replay, parameter fitting
  app/sm2.py         SM-2 scheduling
  app/llm.py         provider chain (Groq -> Gemini -> Ollama)
  app/plan.py        7-day plan prompt, JSON validation
  train.py           retrain the model (synthetic, or --csv real data)
  models/            saved model + recall_metrics.json
  tests/             pytest suite
  Dockerfile, pytest.ini, requirements.txt, .env.example
```

### 3.2 The recall model (Verified: trains and loads)

- XGBoost classifier that predicts whether a learner answers correctly on their next attempt after a gap.
- Features: days since last practice, recent and overall accuracy, accuracy trend, difficulty,
  attempt count, hint usage, completion rate.
- `models/recall_metrics.json` compares AUC, log loss and Brier score against the original
  hand-tuned half-life formula on a held-out split.
- **Honesty note for the report/viva:** the model is trained on *simulated* learners, so the
  metrics show it learned our simulator, not real human forgetting. Say this openly. Retrain with
  `python train.py --csv attempts.csv` once real attempts are exported; the API stays the same.
- If the model file is missing and training fails, the service uses the original formula and reports
  `engine: formula-fallback`.

### 3.3 Groq plan generation (reported working end to end)

- After `GROQ_API_KEY` was placed in `ml-service/.env` and the service restarted, `/health` showed
  `llmProviders: ["groq"]`, and the Personalized Plan page should show a Groq model name in
  "Generated by" instead of `rule-based-fallback`.
- If the plan page ever shows `rule-based-fallback`, check the Python console around the
  `POST /plan/generate` line.

### 3.4 Java side (Written; app compiles and starts, integration not fully verified)

- `integration/MlServiceClient.java`: HTTP client with a 3 s timeout for fast calls, 60 s for plans,
  and a 30 s cool-down after an outage so a dead Python service does not slow every request.
  Methods: `bktUpdate`, `bktReplay`, `daysToThreshold`, `reviewSchedule`, `generatePlan`, `analyze`, `isUp`.
  Every method returns `null` when the service is unavailable.
- `integration/MlProxyController.java`: keeps `/api/ml/health` and `/api/ml/analyze` working by
  forwarding to Python (and strips the legacy `token` field).
- After these were added, `mvn spring-boot:run` started cleanly on Spring Boot 2.7.18, port 8080,
  with no duplicate `/api/ml/analyze` mapping. That suggests the old `ml/MlController` is gone,
  but the logs alone do not prove it.

---

## 4. Java cleanup status (updated after the code audit)

**Done (verified by reading the source):** `ml/` and `GroqApiClient` were moved to `_removed_java_ai/`.
`AdaptiveLearningService` now only stores mastery and review state and calls Python through
`MlServiceClient`, with plain fallbacks. `PersonalizationService` gets its plan from Python and keeps
the rule-based fallback plan. `application.properties` has the `skillpulse.ml.*` settings and no LLM keys.
Dead adaptive-difficulty code was removed from `PracticeService`. No Java `llm/` or `chat/` package exists.

**Left on purpose (rules, not ML):** the dashboard health-score formula, achievements, the notification
scheduler and the plan-adherence ratio.

**Done:** `_removed_java_ai/` has been deleted.

The list below is the older checklist, kept for reference.

### Older checklist (superseded)

Run from the project root:

```powershell
Select-String -Path src -Pattern "AdaptiveLearningService|GroqApiClient|SkillPulseMlService" -Recurse | Select Path, Line
```

- No matches outside `_removed_java_ai/` means the Java cleanup is finished.
- Matches mean those files (likely `PracticeService`, `DashboardService`, `PersonalizationService`)
  still use old Java AI classes and must be rewired to `MlServiceClient` with a plain fallback.

Also unconfirmed:
- Whether `adaptive/`, `ml/` and `personalization/GroqApiClient` were moved to `_removed_java_ai/`.
  The `user_topic_mastery` queries in the last run suggest the old mastery code was still active at 16:25.
- Whether the `skillpulse.ml.*` settings were added to `application.properties`
  (`enabled`, `base-url`, `key`, `timeout-ms`, `plan-timeout-seconds`).
- Whether an earlier session's Java-based chatbot draft (`llm/`, `chat/`, `chat.html`,
  `V3__study_chat.sql`) exists in the project. Check for those folders. Per the current decision the
  chatbot's AI logic must live in Python, so any Java `llm/` package should not be kept.

---

## 5. What is TO DO (in order)

### Step A. Finish the Java cleanup (do this first)
1. Commit current code to git so every change is reviewable.
2. Run the `Select-String` check above.
3. Rewire `PracticeService`, `DashboardService`, `PersonalizationService` to `MlServiceClient`.
   Fallbacks when Python is down: plain accuracy for mastery, fixed review intervals
   (for example 1, 3, 7, 14 days) for reviews, and the existing rule-based plan.
4. Move `ml/`, `adaptive/`, `GroqApiClient` into `_removed_java_ai/`, run `mvn compile`, fix errors,
   then delete the folder once reviewed.
5. Add `skillpulse.ml.*` settings to `application.properties`.

### Step B. Study chatbot (AI in Python, thin relay in Java)
**Status (1 Oct 2026): built.** Python router, streaming and refusals with 30 passing tests; Java conversations, rate limit, SSE relay and admin view; `chat.html`. The original design notes below are kept for reference. Still to do: a manual browser check (streaming, off-topic decline, a follow-up such as "why?", the admin view of refused messages).

Original design notes:
Goal: a chat page that answers study questions naturally like ChatGPT/Claude, and politely declines
non-study questions in natural, varied wording.

Python (`ml-service`):
- `POST /chat/stream` (Server-Sent Events) and a non-streaming variant.
- Three-layer restriction:
  1. **Router**: a small fast model (for example `llama-3.1-8b-instant`) classifies each message using
     the last ~6 messages as STUDY, GREETING, MIXED or OFF_TOPIC, so follow-ups such as "why?" are not blocked.
  2. **Scoped system prompt** for the main model. Study is defined broadly: school and college
     subjects, programming and debugging, maths, science, languages, exam prep, study techniques,
     career-skill learning. User text is never inserted into the system prompt; injection guards included.
  3. **Natural refusals**: for OFF_TOPIC the model writes a brief, warm decline and offers a study angle.
     MIXED messages get the study part answered and a note that the rest is out of scope.
- Policy decisions already agreed: allow anything with educational value ("how does the stock market
  work?"), decline pure task requests ("write my cover letter", jokes, sports predictions);
  homework is explained step by step with the final answer at the end.
- Provider fallback: Groq, then Gemini, then Ollama. Fall back only if nothing has been streamed yet.
- Personalisation: language and explanation style from preferences, plus weakest topics.
- Router failure defaults to STUDY (the scoped prompt still applies).

Java:
- Auth (`AuthService.requireUser`), `conversations` and `messages` tables (migration `V3__chat.sql`),
  a per-user rate limit (about 12 messages per minute), and an SSE relay to `chat.html`.
- `SecurityConfig`: add `/api/chat/**` to the authenticated list, otherwise unauthenticated calls fall
  under `permitAll` and fail confusingly.
- Java 8 note: `RestTemplate` cannot stream, so use `HttpURLConnection` or OkHttp.
- The logged-in user is not available on the async thread, so fetch it in the controller first and
  pass it into the streaming task.
- Admin view of refused messages (log the router category on each user message).

Frontend: `chat.html` in the brown / DM Sans style: streaming text, stop button, markdown with
sanitised code blocks and copy buttons, conversation sidebar, starter prompts. Add a "Study Chat" link
in `dashboard.html` (its sidebar is built in JavaScript, in `buildSidebar()`) and `personalized-plan.html`.

### Step C. Weekly timetable page (Written, not verified: compile and test first)
**What exists now (30 Sep 2026):** `timetable/TimetableRebalancer.java` (the deterministic rule), `TimetableService`, `TimetableController`
(`GET /api/timetable`, `POST /api/timetable/days/{id}/complete`, `POST /api/timetable/days/{id}/adjust`), `static/timetable.html`
(week grid, today card with progress bar, Adjust dialog, canvas confetti once per day) and Python `POST /timetable/explain` (LLM wording only;
Java uses a built-in sentence if it is down). Two nullable columns were added to `personalized_plan_tasks` (`planned_minutes`,
`reschedule_count`); Hibernate `ddl-auto=update` creates them, so there is no Flyway file. Priority = low BKT mastery first, overdue tasks get a
bonus. SM-2 due reviews are not part of the priority yet. A day holds up to 125% of the busiest planned day before it counts as full.
Missed days are carried forward when the learner presses Adjust, not automatically on page open.

Original design notes:
Reuses `PersonalizedPlan > PersonalizedPlanDay > PersonalizedPlanTask` and adherence.
- Migration: add `planned_minutes`, `status` (PENDING / DONE / MOVED) and a reschedule count to tasks.
- Endpoints: `GET /timetable`, `POST /timetable/days/{id}/complete` ("Target done"),
  `POST /timetable/days/{id}/adjust` (minutes available today, or "can't study today").
- **Rebalancing is a deterministic algorithm, not an LLM call**: keep the highest-priority tasks in
  today's minutes, push the rest into later days with spare capacity, warn if the week cannot fit.
  Priority comes from low BKT mastery and overdue SM-2 reviews.
- The LLM only writes the friendly explanation ("I moved Arrays to Thursday because you had 20 minutes today").
- Carry-over of missed days happens the next time the user opens the page.
- `timetable.html`: weekly grid, today card with progress bar, Adjust dialog, and a canvas confetti
  burst (no library, once per day).

### Step D. More LLM features (endpoints in this service)
**Status (30 Sep 2026):** all four are now in code. Wrong-answer explanations (`/tutor/explain`, button on the assessment page), question drafting with
admin review (`/tutor/generate-questions`) and nudge emails (`/coach/nudge`) already existed. New and Written, not verified: the AI coach note in the
weekly progress email (`/coach/weekly-note`, used by `NotificationScheduler`; set `skillpulse.notifications.ai-weekly-note=false` to switch it off) and the
job-description skill gap (`/skills/gap`, `POST /api/ai/skill-gap`, `static/skill-gap.html`). In the skill gap the LLM only extracts the job's skills; each
status is recomputed from real mastery (70% or more is strong) and practice topics must exist on the platform. Tests: `tests/test_coach_tools.py`.

Original list:
- Wrong-answer explanations after a practice question.
- Weekly progress email text for `NotificationScheduler`.
- Question generation with an admin approval step (never auto-published).
- Job-description skill-gap analysis.

### Step E. More real ML models
**Status (1 Oct 2026):** dropout risk (`/risk/predict`, XGBoost with a rule-based fallback), Rasch question calibration (`/questions/quality`), adaptive question order (`/adaptive/order`) and k-means learner segments (`/segments/cluster`) are all implemented in Python and wired through `AiInsightsService` to the admin AI page. **New, written, not verified:** per-topic BKT fitting. Admin AI page, Model training tab, calls `POST /api/admin/ai/fit-bkt`, which sends each topic's answer sequences to `/bkt/fit` and stores learn, guess and slip on each learner's mastery row. A topic needs at least 5 learners with 2+ answers. Stored mastery is not rewritten; the new values apply from the next answer. Two limits: `pInit` is stored but the update still starts new topics at 0.20, and the dropout-risk model is still trained on simulated learners (`python train.py --risk` retrains it on simulated data only).

| Model | Data | Output / use |
|---|---|---|
| Per-topic BKT fitting (wired, written not verified) | `user_practice_attempts` | Per-topic parameters replace the fixed 0.20 / 0.12 / 0.20 / 0.10 |
| IRT question difficulty | correctness + `timeTakenSeconds` | Data-driven difficulty; admin flags for bad or misleading questions |
| Adaptive question selection | mastery + difficulty scores | Choose the next question instead of heuristics |
| Dropout-risk prediction | activity gaps, accuracy trend | Time nudges to at-risk learners via `NotificationScheduler` |
| Learner clustering | behaviour features | Admin insights on learner groups |

### Step F. Data and evaluation
**Status (1 Oct 2026): written, not verified.**
1. Log in as admin, open the Model training tab on `admin-ai.html` and press Download attempts CSV (`GET /api/admin/ai/export-attempts.csv`). It holds only numeric ids, difficulty, correct, seconds and time.
2. Save it as `ml-service/attempts.csv` (git-ignored).
3. `python train.py --attempts attempts.csv --dry-run` trains on real answers and prints XGBoost against the old formula (AUC, log loss, Brier) without replacing the live model. Each answer from the third onwards in a learner's topic history becomes one example, and the train/test split is by learner, so the test score is on people the model has not seen.
4. If it is better, run it again without `--dry-run`, then restart uvicorn. It refuses to train with fewer than 200 examples, 5 learners or 20 of each outcome.
5. Quote the `source` field of `models/recall_metrics.json` in the report: `synthetic` or `attempts:...`.

Original list:
- Export real attempts to CSV and retrain (`python train.py --csv attempts.csv`).
- Keep the synthetic generator for cold start, and always report which data a metric came from.
- Report AUC, log loss and Brier score against the baseline formula in the project report.

---

## 6. Known issues and housekeeping

- **N+1 queries in Java.** For every topic Hibernate repeats the same three queries (questions,
  mastery, attempts) on practice and dashboard loads. Fix with one query per user after the cleanup.
- **Secrets in the repo (partly fixed).** `application.properties` reads the database, mail and admin settings from environment variables, and the commented-out old password was removed, but it still has local defaults (`DB_PASSWORD` defaults to `root`, plus a personal admin and mail address). `.gitignore` is now filled in (it was empty). If `.env`, `target/` or `.venv/` were already committed, untrack them with `git rm -r --cached`. Also rotate the Groq key that was pasted in chat.
  Move them to environment variables before pushing or submitting.
- **Java version mismatch.** Local runs use Java 25, while the Dockerfile and README target Java 8.
  Pick one before the demo. Java 17 is the safest for Spring Boot 2.7.
- **README mismatch (fixed).** The root README no longer names an LLM model;
  the model is set in `ml-service/app/config.py` (`GROQ_MODEL`, default `openai/gpt-oss-120b`).
- **Cookie on plain HTTP.** `SESSION_COOKIE_SECURE=false` is needed for local `http://localhost` development.
- **Hikari "thread starvation" warning** with a huge delta is the laptop sleeping; ignore it.
- **Reasoning models need a big token limit.** `openai/gpt-oss-120b` spends part of `max_tokens` on hidden reasoning, so a small limit (300) produced an empty reply and HTTP 503 from `/timetable/explain`. The timetable and weekly-note calls now use 1200.

---

## 7. Viva talking points

- Why split Java and Python: separation of concerns, independent testing, fallbacks keep the app alive.
- Why the timetable uses an algorithm and the LLM only explains: LLMs are unreliable with hard
  scheduling constraints; the algorithm guarantees a valid timetable.
- Why a three-layer chatbot restriction: a single system prompt is easy to bypass.
- Why we admit synthetic data: it is honest, and the pipeline switches to real data with one command.
- Which parts are real ML (XGBoost recall, BKT fitting, IRT, clustering, dropout) versus LLM-based
  language features.
