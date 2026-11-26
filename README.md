# CodeTriage

Lab submission intake and similarity triage for a Java programming course.

Students submit a `.zip`. The lecturer uploads the assignment sheet and gets two
separate things back: a **grading sheet** with pass / not pass per student, and a
**similarity review queue**.

Those two stay separate on purpose. See "Why grading and similarity never touch"
below.

---

## What this is, and what it deliberately is not

It is **not** an AI detector, because those do not work on code. Five detectors
tested over 5,069 problems at ICSE-SEET 2024 "perform poorly in distinguishing
between human-written code and AI-generated code"; a 2025 follow-up over six
more, including the code-specific GPTSniffer, found none deployable. OpenAI's own
classifier was explicitly unreliable on code and was withdrawn in July 2023.
Accuracy also collapses under trivial obfuscation — renaming variables is enough.

The failure that matters is the asymmetric one: a 2026 benchmark found every
model tested misclassified *some human-written code* as AI-generated. A tool like
that falsely accuses honest students, and you cannot defend the number to them or
to a committee, because there is nothing to show.

So this takes the approach from Frontiers in Computer Science (2026), which
reached over 96% in a user study: generate your own reference solutions for the
task — "pseudo-AI submissions" — and measure student work against them with
ordinary code-similarity analysis. It works because honest student code does not
closely resemble model-generated code.

What you get out is different in kind:

- a **similarity percentage against a specific file you can print** and put next
  to the student's code, together with the prompt that produced it
- a **deterministic, reproducible** number — same inputs, same result
- **student-to-student similarity** in the same pass, which is still the more
  common problem
- a **convergence floor**: how alike two independent model answers to your task
  are *anyway*. A student at or below it tells you nothing. Without this number,
  high similarity on a "reverse a string" exercise looks alarming and means nothing.

It remains triage. It tells you whose code to open and who to talk to. The
conversation is the evidence; this is only how you decide whom to have it with.
Every page in the admin UI says so, on purpose.

---

## Why grading and similarity never touch

It would be easy to wire the similarity score into the grade, and it would be
wrong. Similarity cannot establish that a student copied — that is the whole
finding above. Feeding an unprovable signal into a mark converts a hunch into a
penalty the student cannot contest, and hides the conversion inside a number.

So the data model keeps three things apart:

| | what it is | who sets it |
|---|---|---|
| `compileOk` | does it build | javac |
| `suggestedVerdict` | how it reads against your assignment | a model, advisory |
| `verdict` | **the grade** | you, by clicking |

`verdict` is written by exactly one method, called only from a lecturer's click.
The grading page shows the similarity flag beside the grade, clearly marked as
context, and nothing computes with it.

---

## Grading

Admin-only. Students see a submission receipt and nothing else — no grade, no
suggestion, no similarity figure.

**The compile check** runs javac in-process via `javax.tools`. It does not
execute student code, and grading deliberately stops there: running an unknown
student's program on the VPS is arbitrary code execution on a host that holds
every other student's work. Two details make compiling safe rather than
merely safer:

- `-proc:none` — annotation processors *are* code, and javac would run them. This
  flag is the boundary, not a tidiness setting.
- empty `-classpath` — a submission cannot compile against this application's own
  classes. There is a test that tries.

A compile failure is a fact you can show a student. Nothing more is claimed.

**The rubric assessment** sends your assignment text and the submission to Claude
and gets back per-requirement notes plus a suggested verdict. The prompt forbids
any comment on authorship — mixing "does this meet the requirements" with "did a
model write this" is how an unprovable hunch leaks into a mark. Temperature is 0,
because an assessment that changes between runs on identical input is not
something to put in front of a student.

It is an opinion, and it can be wrong about an ambiguous requirement. Open the
code when it matters.

**The decision** is two buttons and an optional note, on the grading sheet or
beside the code in the comparison view. Where you decide against the suggestion,
the sheet says so and your verdict stands.

---

## How it decides what to flag

A fixed threshold is indefensible, because convergence depends entirely on the
exercise. So the question asked is not "is this number high?" but **"does this
submission stand apart from its own cohort?"**

Median and MAD are used rather than mean and standard deviation: if a quarter of
the class copied, a mean would be dragged up and hide them. The median barely
moves. (There is a test for exactly this.) A submission is queued for review when
any of these hold:

| Reason | Meaning |
|---|---|
| byte-identical archive | two students uploaded the same file; not similarity at all |
| peer similarity ≥ 80% | another student's submission is nearly the same |
| reference similarity ≥ 80% | very close to a generated reference |
| z ≥ 3.5 **and** above the convergence floor | stands apart from this cohort |
| no comparable Java found | did not parse — check by hand rather than assume it is clean |

Thresholds live in `application.yml`.

---

## Running it

### Locally

```bash
cp .env.example .env     # fill in ADMIN_PASSWORD_HASH and ANTHROPIC_API_KEY
mvn spring-boot:run
```

Student portal on `http://localhost:8080/`, lecturer area on `/admin`.

If `ADMIN_PASSWORD_HASH` is empty, a temporary password is printed to the log on
every start, so you are never locked out and there is no guessable default.

### Generating the password hash

```bash
# with apache2-utils
htpasswd -bnBC 10 "" 'your-password' | tr -d ':\n'

# or with Docker, no local tooling
docker run --rm python:3.12-alpine sh -c \
  "pip -q install bcrypt && python -c \"import bcrypt;print(bcrypt.hashpw(b'your-password', bcrypt.gensalt()).decode())\""
```

### Deploying to a server

```bash
./deploy.sh              # sync, build and start
./deploy.sh --logs       # ... then follow the logs
./deploy.sh --no-build   # push changed files and restart, no rebuild
./deploy.sh --logs-only  # just tail the remote logs
./deploy.sh --down       # stop the stack (the database volume survives)
```

Defaults are in the first few lines of the script and overridable from the
environment:

```bash
DEPLOY_HOST=1.2.3.4 DEPLOY_USER=joan HOST_PORT=9000 ./deploy.sh
```

It checks the obvious failure modes before touching the server: `.env` present
and populated, the compose file and pom actually being what they claim, SSH
reachable, Docker and the compose plugin installed, and enough RAM for a Maven
build. `.env` is piped over SSH rather than rsynced, and written `600`.

`BIND_ADDR` decides exposure. `0.0.0.0` publishes the port directly — convenient,
and plain HTTP. `127.0.0.1` is the production setting, with nginx in front.

### On the VPS

```bash
git clone <your-repo> codetriage && cd codetriage
cp .env.example .env && nano .env
docker compose up -d --build
docker compose logs -f
```

The container binds to `127.0.0.1:8080`, so put nginx in front:

```nginx
server {
    listen 443 ssl http2;
    server_name labs.your-domain.al;

    ssl_certificate     /etc/letsencrypt/live/labs.your-domain.al/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/labs.your-domain.al/privkey.pem;

    # Students upload zips; the app's own cap is 20 MB.
    client_max_body_size 25m;

    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }
}
```

Serve it over TLS. Student names and coursework should not travel in plain text.

**Memory**: a triage run holds every submission's token stream at once. Give the
container at least 2 GB for a cohort of 60.

---

## Using it

1. **`/admin` → open a lab → upload the assignment sheet.** `.pdf`, `.docx`,
   `.txt` or `.md`. The text is extracted and shown back to you — **read it**. If
   it looks mangled or empty, the PDF was a scan, so the text is an image; paste
   the task into the box instead. Both reference generation and rubric grading
   read this, so a bad extraction quietly degrades both.
2. **Paste any starter code you handed out.** It is excluded from similarity.
   Skip this and shared boilerplate inflates every score and buries the signal.
3. **Tick "Open for submissions"**, set a deadline if you want one. Late
   submissions are accepted and marked, never rejected.
4. **Students submit** at `/` — name, group, lab 1–5, zip. They see a notice that
   solutions are compared against each other before they upload.
5. **Run checks** → compile plus rubric assessment for every submission.
6. **Open the grading sheet** and set pass / not pass. Export CSV when done.
7. **Generate references** and **run triage** for the similarity side. Eight
   prompting styles × 3 temperatures = 24 references, a few minutes and a few
   cents.

Resubmission is allowed; the most recent one per student counts and the earlier
is marked superseded.

### The prompting styles

The corpus is only as good as its spread: a student who pastes the bare task and
one who asks for heavy comments get materially different code. Built in: bare
paste, code-only, "write like a first-year student", heavily commented,
production-idiomatic, shortest-possible, full OOP, and **one prompted in
Albanian** — which yields Albanian identifiers and comments, as your students'
own prompts will.

Add more in `GenerationProfiles.java`; wider costs only tokens. Implementing
`LlmClient` for a second provider covers more of what students actually use.

---

## Configuration

Everything is environment-overridable. See `application.yml`.

| Variable | Purpose |
|---|---|
| `ADMIN_USERNAME` / `ADMIN_PASSWORD_HASH` | lecturer login; hash is bcrypt |
| `ANTHROPIC_API_KEY` | reference generation only; everything else works without it |
| `ANTHROPIC_MODEL` | default `claude-sonnet-5-5` |
| `CODETRIAGE_DATA_DIR` | uploads, extracted sources, H2 database |
| `PORT` | default 8080 |

Tuning knobs under `codetriage.similarity`: `minimum-token-match` (lower = more
sensitive, noisier), `outlier-z-threshold`, `absolute-review-threshold`.

---

## Upload safety

The submission endpoint is public, so the extractor refuses rather than repairs.
Covered by tests in `SafeZipExtractorTest`:

- **zip-slip** — every entry resolved against the target and rejected if it would
  escape; `../`, absolute paths and Windows drive prefixes all handled
- **zip bombs** — per-entry compression ratio, declared total size, *and* real
  bytes written, each capped independently
- **allow-list only** — `.java` and a few text types. No `.class`, `.jar`, `.sh`
  or nested archive is ever written, and nested archives are never recursed into
- caps on entry count, source-file count and path length
- `target/`, `build/`, `.git`, `__MACOSX` and friends ignored

Assignment uploads are lecturer-only and limited to `.pdf`, `.docx`, `.txt` and
`.md`. `.docx` text is extracted from the document XML directly rather than with
Apache POI, which would cost about 15 MB of dependencies to read one file. That
extractor scans `<w:t>` contents rather than stripping tags globally — the
obvious approach leaves every newline and indent of a pretty-printed
`document.xml` behind as spurious whitespace.

---

## Notes on the build

- **The runtime needs a JDK, not a JRE.** Two things require the `jdk.compiler`
  module, which Temurin's JRE image does not ship: the grading compile check, and
  JPlag's Java parser. On a JRE, `getSystemJavaCompiler()` returns null and JPlag
  fails to parse. The Dockerfile uses `eclipse-temurin:21-jdk` for this reason —
  do not "optimise" it to a JRE.
- **JPlag is pinned to 6.2.0, and bumping it breaks the build.** 6.3.0 ships Java
  25 bytecode, which a Java 21 JVM cannot load; the error surfaces as twenty
  "cannot find symbol" lines wrapped around one real one (`class file has wrong
  version 69.0, should be 65.0`). 6.2.0 is the last JDK 21 release. To move to
  6.3.0 you must also set `java.version` to 25 and change both Docker images to
  `-25`, and Spring Boot 3.5.x does not officially support Java 25 — that is a
  stack upgrade, not a version bump.
- **JPlag is GPL-3.0.** Running it internally is fine. If you ever distribute
  this application, that licence reaches your code too. Worth knowing before it
  grows into something you hand to other faculties.
- **Spring Boot 3.5.x**, not 4.x. Deliberate: 4.x is current, but migrating is a
  separate job from getting this working.
- **`JPlagRunner` is the only class touching the JPlag API**, because its library
  surface shifts between majors. If a JPlag upgrade breaks compilation, that one
  file is the place to fix it. The two optional calls — minimum token match and
  the HTML report — go through reflection and degrade to a logged warning rather
  than failing the run.
- `mvn test` runs six suites — source splitting, cohort statistics, zip safety,
  docx extraction, compile checking and JSON extraction. None need a network, a
  database or an API key.

---

## Known limits

- A student who meaningfully rewrites generated code will not be flagged. Nothing
  fixes that, and no tool should claim to.
- Small exercises converge. With 15 students on a 20-line task, expect high
  similarity everywhere and treat the convergence floor as your baseline.
- Cohorts under 8 disable the z-score entirely — there is no distribution to
  stand apart from. Absolute thresholds still apply.
- References come from one model family by default. Students use several.
- Name matching for resubmissions is on the name typed in. Someone who types it
  differently each time produces separate records.
- A scanned PDF assignment cannot be read — the text is an image. There is no OCR;
  paste the task instead.
- The rubric assessment judges against the assignment text as extracted. A vague
  assignment produces a vague assessment, and it has no way to know what you said
  out loud in the lab.
- Grading does not run student code, so it cannot tell you whether the program
  produces correct output — only whether it compiles and reads as complete.
