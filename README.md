
# SkillPulse(Spring Boot Backend)

SkillPulse is a skill-tracking and MCQ practice platform. Users register, take assessments 
organized by Subject → Topic → Question, and the system tracks their health score over time with 
email notifications. 

Tech stack: Spring Boot 2.7 · Java 8 · PostgreSQL · BCrypt · Spring Mail · Python FastAPI ML service (XGBoost, BKT, SM-2, Groq/Gemini LLMs)

## AI/ML architecture

The Java backend contains no AI/ML logic. All of it lives in the Python service in `ml-service/`
(see `ml-service/README.md`): the XGBoost recall model, Bayesian Knowledge Tracing, SM-2 spaced
repetition and the LLM-written 7-day plan (Groq, then Gemini, then Ollama).

Java stores learner state and calls the service through `integration/MlServiceClient`. If the
service is down, plain non-ML fallbacks keep the app working (running accuracy for mastery, a fixed
review-interval ladder, and a rule-based plan).

LLM keys such as `GROQ_API_KEY` belong in `ml-service/.env`, never in the Java configuration.
Java only needs `ML_SERVICE_URL`, `ML_SERVICE_KEY`, `ML_TIMEOUT_MS`, `ML_PLAN_TIMEOUT_SECONDS`
and `SKILLPULSE_ML_ENABLED`. Start the Python service on port 8001 before the backend.

## Authentication

Authentication uses Spring Security and a PostgreSQL-backed Spring Session. The browser receives
an `HttpOnly`, `Secure`, `SameSite=Strict` cookie named `SKILLPULSE_SESSION`; the cookie, rather than
the legacy `token` query parameter, identifies the user. Existing JSON responses retain a
`cookie-session` marker temporarily so older frontend code can detect a signed-in response, but it
is not a credential.

For local HTTP-only development, set `SESSION_COOKIE_SECURE=false`. Production must use HTTPS and
leave it enabled. Spring Session creates its `SPRING_SESSION` tables automatically at startup.


## API endpoints

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/logout`
- `GET /api/auth/me?token=...`
- `GET /api/dashboard/summary?token=...`
- `GET /api/skills`
- `POST /api/ml/analyze`
- `GET /api/ml/health`
- `GET /api/personalization/preferences`
- `PUT /api/personalization/preferences`
- `POST /api/personalization/plans/generate`
- `GET /api/timetable`, `POST /api/timetable/days/{id}/complete`, `POST /api/timetable/days/{id}/adjust`
- `POST /api/ai/skill-gap`
- Admin AI (ADMIN role): `GET /api/admin/ai/risk`, `GET /api/admin/ai/segments`, `GET /api/admin/ai/question-quality`, `POST /api/admin/ai/fit-bkt`, `GET /api/admin/ai/export-attempts.csv`

## Training the models on real data

The recall and dropout models start out trained on simulated learners, and every metric says so.
Once learners have practised:

1. Sign in as admin, open `admin-ai.html`, go to the Model training tab and press **Fit learning parameters**.
   This fits Bayesian Knowledge Tracing per topic from real answers.
2. Press **Download attempts CSV** (anonymous ids only) and save it as `ml-service/attempts.csv`.
3. From `ml-service`, run `python train.py --attempts attempts.csv --dry-run` to compare a model trained on
   real answers against the current one. Run it without `--dry-run` to replace the model, then restart the ML service.

See `ml-service/README.md` for the full status of each step.

<img width="1895" height="901" alt="image" src="https://github.com/user-attachments/assets/4f03e780-d15d-4187-8df0-30d253a69804" />
<img width="1896" height="902" alt="image" src="https://github.com/user-attachments/assets/a44107f4-969d-475b-b430-150130c6e1d4" />
<img width="1895" height="901" alt="image" src="https://github.com/user-attachments/assets/872a796e-bfed-4989-b4d0-00d58552529d" />
<img width="1900" height="902" alt="image" src="https://github.com/user-attachments/assets/ebbfde6b-372b-48c4-af41-94157ba4138b" />
<img width="1912" height="906" alt="image" src="https://github.com/user-attachments/assets/08baf89d-f4fd-44ff-a66e-5b0ba5b1fba3" />
<img width="1898" height="906" alt="image" src="https://github.com/user-attachments/assets/deee92b9-28f8-4270-b473-3858d2a78c46" />



