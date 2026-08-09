# PlayTak Protocol: Executable Specification Plan

**Status:** proposal, not yet approved
**Scope:** the PlayTak text protocol served by `server/` (telnet + WebSocket). Not the REST API in `api/`.
**Audience:** engineers and coding agents picking up individual phases.

Each phase below is written to be executed independently by someone (or something) with no
prior context on this effort. Read "Background" and "Ground rules" first, then your phase.

---

## 1. Background

### What the protocol is

A line-oriented, positional, plain-text, asymmetric, versioned protocol. Clients connect over
raw TCP (`port`, default 10000) or WebSocket (`portws`, default 9999) and exchange
`\n`-terminated lines. Messages are space-delimited positional fields, e.g.:

```
Seek 5 300 30 A 2 21 1 0 0 30 300 someopponent
Game Start 42 alice vs bob white 5 300 30 2 21 1 0 0 30 300 0
```

Four protocol versions exist (0, 1, 2, 3). Version is selected by the client sending
`Protocol N` before login. Version changes both field arity and field order on several
messages.

### Current state (verified 2026-08)

**Testing: none.**

- `server/pom.xml` has no JUnit dependency and no surefire plugin.
- No `server/src/test/java` directory exists. `server/src/test/` contains only `bruno/`.
- The only server-side tests are 2 Bruno HTTP requests against the PNT REST endpoint
  (`Create Seek.bru`, `Get Seek List.bru`). They assert HTTP 200 and field presence.
  They exercise none of the text protocol.
- `.github/workflows/server-cd-dev.yml` runs `mvn compile && mvn package` then deploys.
  There is **no test step**, and it triggers on push to `dev`, not on pull requests.
  The server therefore has no PR CI at all.

**Documentation: one hand-maintained prose table, already drifted.**

`server/README.md` is the only spec. It is good as a narrative but is not derived from code,
and has measurably diverged. Confirmed drift as of commit `ba5092e`:

| Message | README fields | Actual fields | Delta |
|---|---:|---:|---|
| `Seek new` / `Seek remove` (v<=1) | 12 | 14 | `triggerMove`, `timeAmount` inserted **before** `opponent` |
| `Seek new` / `Seek remove` (v>=2) | 13 | 15 | same two, before `opponent`/`botSeek` |
| `GameList Add` / `GameList Remove` | 11 | 13 | `triggerMove`, `timeAmount` (trailing) |
| `Observe` | 11 | 13 | same |
| `Game Start` (v<2) | 10 | 12 | same |
| `Game Start` (v>=2) | 16 | 16 | matches |

Sources: `Seek.java:236-284` (`buildSeekStringArray`), `Game.java:502-519` (`stringForm`),
`Client.java:971-987` (`createGameStartString`), `Game.java:1297-1315` (`playerRejoin`).

Other confirmed documentation defects:

- **Parameter order reversed.** README documents Seek V3 as
  `... unrated tournament extra_time_amount extra_time_trigger opponent`. The regex at
  `Client.java:98` binds group 10 -> `triggerMove`, group 11 -> `timeAmount`
  (see the `newSeek` call at `Client.java:602`), i.e. **trigger before amount**. The README's
  own `Rematch` row documents `triggerMove timeAmount`, so the two rows contradict each other.
  V3 is the wrong one.
- **Stale prose.** "Currently two protocol versions are supported, 0 and 1" — but the tables
  below it reference `>= 2`, and v2 branches exist in three files.
- **`Protocol 0` is unreachable.** The regex `^Protocol ([1-9][0-9]{0,8})` (`Client.java:65`)
  cannot match `0`, and has no upper bound.
- **Undocumented commands:** `ChangePassword`, `SendResetToken`, `ResetPassword`, plus the
  entire `sudo`/mod/admin surface (~15 commands, `Client.java:1033-1331`).
- **Undocumented server messages:** `OnlinePlayers`, `CmdReply`, `sudoReply`,
  `Rematch seek created with ID: N`, `Is Mod`, `Authentication failure`, `Wrong password`,
  `Wrong token`, `No such player`, `Registered`, `Reset token sent`.
- **Two rows invisible** due to broken markdown table cells: `Game#no Show Sq` is swallowed
  into the `Unobserve` row, `quit` into the `PING` row.

### Structural obstacles

1. **No parse seam.** `Client.run()` (`Client.java:380-960`) is a single ~580-line
   `if / else if` chain that reads directly from `websocket.recieve()` and replies via
   `websocket.send()`. There is no `parse(String) -> Command` function to unit-test.
   Chain *order* is load-bearing semantics: V3 is attempted before V2 before V1, and move
   commands are guarded by `game != null &&`, so an out-of-game move silently falls through
   to `NOK` rather than producing a specific error.
2. **Serialization scattered across 5 sites in 3 files**, with `Game Start` implemented
   **twice independently** — `Client.createGameStartString()` (built from `Seek` fields,
   seconds) and `Game.playerRejoin()` (built from `Game` fields, ms/1000). They currently
   agree; nothing enforces it.
3. **Version branching is ad hoc** — `protocolVersion < 2` / `<= 1` / `>= 1` checks scattered
   across `Client.java`, `Game.java:609,622,631,641,1302`, `Seek.java:157,217`.
4. **Static global state** — `Seek.seeks`, `Game.games`, `Client.clientConnections`, static
   locks, a static `cleanupTimer`, a static `Database`. Two in-process test scenarios leak
   into each other, so unit-level isolation requires untangling first.

### What makes this tractable

- `Telnet extends Websocket` with the same `recieve`/`send`/`kill` contract, so a black-box
  test driver can just open a TCP socket. No refactor required to start testing.
- `docker-compose.yml` plus `scripts/development/add_user.sh` already provide a seedable
  fixture environment.
- The regexes at `Client.java:50-196` and the builders in `Seek`/`Game` **already are** the
  grammar. Authoring the catalog is transcription, not invention.

---

## 2. Ground rules

These are the non-negotiables. Every phase is judged against them.

**G1 — One source of truth, and the server consumes it.**
If the server does not import generated code, the spec becomes a second artifact that drifts
from the first. That is the exact failure already visible between `server/README.md` and
`Client.java`. Phase 4 is not optional garnish; it is the point.

**G2 — Normativity is decided by evidence, not by reading the server.**
The contract is the intersection of what the server emits and what clients successfully parse.
Mark every field as `frozen` (a client depends on it) or `unconstrained` (nothing reads it).
This distinction cannot be derived from `server/` alone — see Phase 1.

**G3 — Don't change the wire.**
Deployed clients and bots depend on current behavior. This effort documents and tests the
protocol; it does not redesign it. Wire changes are a separate proposal.

**G4 — Prefer generated over written.**
Any table, doc, or fixture a human maintains by hand will drift. If it can be emitted from
the catalog, emit it.

**G5 — No phase may leave the tree less tested than it found it.**

---

## 3. Target architecture

```
protocol/
  catalog/                  # SOURCE OF TRUTH - hand-authored YAML
    types.yaml
    messages.c2s.yaml
    messages.s2c.yaml
    states.yaml
  generators/               # catalog -> artifacts
    docs.ts                 # -> server/README.md protocol tables
    java.ts                 # -> server/src/main/java/tak/protocol/generated/
    vectors.ts              # -> protocol/vectors.json
    asyncapi.ts             # -> protocol/asyncapi.yaml
    clients/{ts,py}.ts      # -> SDKs for alternate implementations
  vectors.json              # GENERATED - language-neutral conformance fixtures
  asyncapi.yaml             # GENERATED - published interop artifact
  conformance/              # session-level suite, runs against any host:port
  transcripts/              # golden session fixtures, one set per protocol version
```

### Catalog format

Named-hole templates. One declaration generates the parse regex, the render format string,
and the documentation row — which is what structurally eliminates the drift class above.

```yaml
spec_version: 1
encoding: iso-8859-1        # NOT utf-8 - see Telnet.java:91
framing: line-lf
protocol_versions: [0, 1, 2, 3]

types:
  username: { kind: string, pattern: "[a-zA-Z][a-zA-Z0-9_]{3,15}" }
  square:   { kind: string, pattern: "[A-Z][0-9]" }
  color:    { kind: enum, values: [W, B, A] }
  flag:     { kind: enum, values: ["0", "1"] }

messages:
  - id: c2s.seek.v3
    direction: client_to_server
    valid_states: [authenticated]
    template: "Seek {size} {time} {incr} {color} {komi} {pieces} {capstones}
               {unrated} {tournament} {trigger_move} {time_amount} {opponent}"
    fields:
      size:         { type: int, min: 3, max: 8 }
      trigger_move: { type: int, normativity: frozen }
      time_amount:  { type: int, unit: seconds, normativity: frozen }
      opponent:     { type: username, optional: true, empty: "" }
    effect: seek_created

  - id: s2c.game.start
    direction: server_to_client
    variants:
      - when: "protocol < 2"
        template: "Game Start {no} {size} {white} vs {black} {your_color}
                   {time} {komi} {pieces} {capstones} {trigger_move} {time_amount}"
      - when: "protocol >= 2"
        template: "Game Start {no} {white} vs {black} {your_color} {size}
                   {time} {incr} {komi} {pieces} {capstones} {unrated}
                   {tournament} {trigger_move} {time_amount} {is_bot}"
```

The `variants` block turns the version matrix from scattered `if (protocolVersion < 2)` into
enumerable data, which is what makes exhaustive version-matrix test generation possible.

### Why a custom catalog rather than an off-the-shelf IDL

Evaluated and rejected as *source of truth*:

- **Protobuf / gRPC** — binary, RPC-shaped. Adopting it means changing the wire (violates G3).
- **AsyncAPI 3.0** — closest standard fit (asymmetric, event-driven, WebSocket bindings, real
  codegen via Modelina). But its generators assume JSON payloads; positional text encoding
  would live in `x-` extensions and the codec would be hand-written per language anyway.
  **Decision: emit AsyncAPI as a generated artifact (Phase 7) rather than author in it** — you
  get interop, doc tooling, and discoverability without contorting the source of truth.
- **ANTLR** — genuinely generates multi-language parsers, but only the parse direction, not
  serializers. Overkill for `Keyword arg1 arg2` grammar. A field-list catalog generates both
  directions in fewer lines.
- **Kaitai Struct** — binary formats only.

---

## 4. Phases

Sizes are rough: S = 1-2 days, M = ~1 week, L = 2+ weeks.

### Phase 0 — Baseline harness and server PR CI

**Depends on:** nothing. Start immediately.
**Size:** S
**Goal:** make server behavior observable and regressions visible, with zero production
refactor.

Deliverables:
- `server/pom.xml`: add JUnit 5 + maven-surefire-plugin.
- `.github/workflows/server-ci.yml`: run on `pull_request` with `paths: ['server/**']`;
  execute `mvn -B test`.
- `protocol/transcripts/`: a driver that opens a TCP socket to a configurable `host:port`,
  sends a scripted line sequence, records the full ordered reply transcript, and diffs it
  against a committed fixture.
- At least 3 golden transcripts: guest login + list; seek + accept + a few moves + resign;
  observe an in-progress game.

Acceptance:
- `mvn test` fails when transcripts diverge.
- CI runs on PRs touching `server/**` and blocks merge on failure.
- Transcripts are captured per protocol version (`Protocol 1` / `Protocol 2` variants).

Notes for the implementer:
- Use `docker-compose.yml` + `scripts/development/add_user.sh` to seed fixture accounts.
- Record raw bytes, not parsed structures. The point is to pin current behavior exactly,
  including ordering and whitespace, *before* anyone interprets it.
- Do **not** "fix" anything that looks wrong. Record it. Phase 1 decides what's a bug.

---

### Phase 1 — Client corroboration survey

**Depends on:** nothing. Runs in parallel with Phase 0.
**Size:** M
**Goal:** establish what deployed clients actually depend on, so the catalog can mark each
field `frozen` vs `unconstrained` (G2).

This phase exists because the current analysis is **entirely server-side**. Nothing has been
validated against `playtak-ui` or any third-party client or bot.

Deliverables:
- `protocol/survey/clients.md` — for each surveyed client: name, protocol version sent,
  which messages it parses, how it parses them (positional split? regex? prefix-only?), and
  which trailing fields it ignores.
- `protocol/survey/census.md` — deployed client/version distribution from production logs.
- A ruling on each drift item in the table in section 1, recorded with evidence.

Method, cheapest first:

1. **Log census.** `Client.java:426` logs `Client !<name>!` on every connection; `Protocol N`
   is handled immediately after at `Client.java:434`. Scrape production logs for the real
   distribution of client names and protocol versions. This directly sizes the conformance
   matrix — if no deployed client sends `Protocol 0` or `1`, those versions are dead weight;
   if bots do, they are frozen forever.
2. **Captured transcripts.** `Client.java:264` already logs every outbound line. Capture a
   real `playtak-ui` session end-to-end. This is worth more than reading source, because it
   shows ordering and timing dependencies that source-reading misses.
3. **Source read.** `github.com/USTakAssociation/playtak-ui` (referenced in
   `server/README.md`), then any bot clients identified by the census. Focus specifically on
   the `Seek new` parser — see the open question below.

Open question this phase must resolve (**blocks Phase 2**):

> The server emits `Seek new` with 14 fields (v<=1) / 15 (v>=2), with `triggerMove` and
> `timeAmount` inserted **before** `opponent`. The README documents 12/13 without them.
> `playtak-ui` works in production, so one of three things is true:
> (a) it parses all 14 -> README merely stale, code is normative, low risk;
> (b) it splits and reads only a leading prefix -> trailing fields are `unconstrained`;
> (c) it reads `opponent` from the wrong index and this is masked because `opponent` is
> usually empty -> a latent production bug, and the fix is a client change, not a spec change.
>
> These imply three different specs. Do not guess.

Also record, for each client:
- Does it require `OK` responses, or ignore them?
- Does it depend on message ordering the server never promised (e.g. does `Seek remove`
  always precede `Game Start` on seek acceptance)?
- Does it tolerate unknown trailing fields? (This determines whether the protocol can be
  extended additively — a major input to how v4 would work.)

**Known scope caveat:** this session did not attach `playtak-ui`; the survey starts from zero.
Budget accordingly.

---

### Phase 2 — Author the catalog

**Depends on:** Phase 1 (for normativity rulings). Phase 0 recommended.
**Size:** M
**Goal:** transcribe the grammar into `protocol/catalog/` as the single source of truth.

Deliverables:
- `protocol/catalog/types.yaml`, `messages.c2s.yaml`, `messages.s2c.yaml`.
- All ~40 messages, including the currently undocumented ones listed in section 1.
- Every field tagged `frozen` or `unconstrained` per Phase 1 findings.
- A written ruling, in-file, wherever code and README conflict.

Acceptance:
- Every regex in `Client.java:50-196` has a catalog entry.
- Every `send(...)` / `sendWithoutLogging(...)` call site in `server/src/main/java/tak/`
  maps to a catalog message id. Enumerate them; leave none unclassified.
- The catalog validates against a JSON Schema committed alongside it.

Notes:
- This is transcription, not design. Where behavior is surprising, encode the surprising
  behavior and note it — do not correct it (G3).
- The `sudo`/admin surface can be a separate file and a later milestone if it slows things
  down; mark it explicitly out of scope rather than silently omitting it.

---

### Phase 3 — Generators: docs and vectors

**Depends on:** Phase 2.
**Size:** S
**Goal:** prove the catalog's value cheaply, and kill the doc-drift class permanently.

Deliverables:
- `protocol/generators/docs.ts` — regenerates the protocol tables in `server/README.md`.
- `protocol/generators/vectors.ts` — emits `protocol/vectors.json`, entries of the shape
  `{ id, protocol_version, wire, fields }`.
- CI check: regenerating docs produces no diff (fails the build if someone hand-edits the
  README tables).

Acceptance:
- The regenerated README differs from the current one **exactly** by the drift items in
  section 1, and by nothing else. Any additional diff is a catalog bug — investigate before
  accepting.
- `vectors.json` covers every message across every applicable protocol version.

Why vectors matter: a language-neutral fixture file is what makes alternate implementations
tractable. A Rust or Go server author gets an unambiguous pass/fail target on day one,
without reading Java. Prior art: the WASM spec tests, the TOML test suite.

---

### Phase 4 — Server consumes generated code

**Depends on:** Phase 3. **Requires Phase 0 transcripts to be green.**
**Size:** M
**Goal:** satisfy G1. Without this the catalog is decorative.

Deliverables:
- `protocol/generators/java.ts` -> `server/src/main/java/tak/protocol/generated/`
  (pattern constants + message builders).
- `Client.java`, `Seek.java`, `Game.java` refactored to use generated constants/builders
  instead of inline regexes and string concatenation.
- The duplicate `Game Start` implementations (`Client.createGameStartString` and
  `Game.playerRejoin`) collapsed into one generated builder.

Acceptance:
- **Equivalence proof:** a test asserts the generated pattern set is byte-identical to the
  previously hand-written regex set. Land this test *before* deleting the originals — it
  turns a risky refactor into a provable no-op.
- All Phase 0 transcripts pass unchanged.
- No behavioral diff. This phase changes zero wire bytes.

Notes:
- Do this incrementally, one message family at a time, with transcripts green after each.
  Start with the seek family (worst drift, worst version branching).

---

### Phase 5 — State model and model-derived tests

**Depends on:** Phase 2.
**Size:** L
**Goal:** test semantics, not just syntax. This is where implementations actually disagree.

Deliverables:
- `protocol/catalog/states.yaml` — connection states
  (`connected -> authenticated -> {idle, seeking, in_game, observing}`) plus per-game
  sub-state (`draw_offered`, `undo_requested`, side-to-move), and legal transitions.
- A generator producing command sequences from the model: legal paths and deliberate
  violations.
- Tests asserting the server accepts or `NOK`s each correctly.

Acceptance:
- Every `valid_states` claim in the catalog has at least one positive and one negative test.
- Documented answers to currently-undefined questions, e.g.: what happens on
  `Game#5 Resign` while in game 7? A draw offer after the game ended? A move when it's not
  your turn? Today the answer is "whatever the `if/else` chain falls through to."

**Effort warning for planners:** this is the phase with no shortcut. The server's state is
*implicit* in `game != null &&` guards and static maps, so writing it down means **deciding**
semantics that are currently accidents. Budget it as a design exercise, not transcription.
It is also the phase most likely to surface genuine bugs — route those to separate issues
rather than fixing them inline (G3).

---

### Phase 6 — Conformance runner and client SDKs

**Depends on:** Phases 3 and 5.
**Size:** M
**Goal:** make alternate implementations viable.

Deliverables:
- `protocol/conformance/` — a standalone binary that points at any `host:port` and reports
  pass/fail per spec clause. Model it on `h2spec`.
- `protocol/generators/clients/{ts,py}.ts` — typed encode/decode SDKs.

Acceptance:
- The runner passes against the current Java server.
- The TS SDK round-trips every entry in `vectors.json`.
- A third party can implement a server against the spec, with the runner as the target,
  without reading `server/`.

---

### Phase 7 — Publish AsyncAPI

**Depends on:** Phase 2.
**Size:** S
**Goal:** standards-compatible interop artifact for external consumers.

Deliverables:
- `protocol/generators/asyncapi.ts` -> `protocol/asyncapi.yaml` (AsyncAPI 3.0), with the
  positional wire template carried in `x-playtak-wire` extensions.
- Rendered documentation site output.

Acceptance:
- Validates against the AsyncAPI 3.0 schema.
- Regenerated in CI; hand edits rejected.

---

## 5. Dependency graph

```
Phase 0 (harness + CI) ─────────────┐
                                    ├──> Phase 4 (server consumes) ──┐
Phase 1 (client survey) ──> Phase 2 ┤                                 ├──> Phase 6
                            (catalog)├──> Phase 3 (docs + vectors) ───┘
                                    ├──> Phase 5 (state model) ───────┘
                                    └──> Phase 7 (asyncapi)
```

Phases 0 and 1 are parallel and unblocked. Phase 2 is the convergence point.

## 6. Decisions requiring a human

1. **Is `sudo`/admin in scope for v1 of the catalog?** ~15 commands, zero external consumers,
   but they are real protocol surface. Recommend: separate file, deferred milestone,
   explicitly marked rather than silently omitted.
2. **Do protocol versions 0 and 1 stay supported?** Phase 1's census answers the factual
   half; the support commitment is a product call.
3. **When code and README conflict, who wins?** Default recommendation is code (deployed
   clients depend on observed behavior), but Phase 1 case (c) above is a scenario where the
   correct fix is a *client* change instead. Do not pre-commit before the survey lands.
4. **Repo layout:** `protocol/` at repo root (assumed above) vs nested under `server/`.
   Root is recommended — the spec outlives any single implementation, which is the point.

## 7. Key file reference

| Concern | Location |
|---|---|
| Command regexes (the de-facto grammar) | `server/src/main/java/tak/Client.java:50-196` |
| Dispatch chain | `server/src/main/java/tak/Client.java:380-960` |
| `Game Start` builder #1 | `server/src/main/java/tak/Client.java:971-987` |
| `Game Start` builder #2 | `server/src/main/java/tak/Game.java:1297-1315` |
| `GameList` / `Observe` payload | `server/src/main/java/tak/Game.java:502-519` |
| `Seek new` / `Seek remove` payload | `server/src/main/java/tak/Seek.java:236-284` |
| Clock messages (`Time` / `Timems`) | `server/src/main/java/tak/Game.java:605-645` |
| Wire encoding (ISO-8859-1) | `server/src/main/java/tak/Telnet.java:91` |
| Client-name logging (census source) | `server/src/main/java/tak/Client.java:423-437` |
| Outbound line logging | `server/src/main/java/tak/Client.java:264` |
| Current (drifted) protocol docs | `server/README.md` |
| Server build/deploy workflow | `.github/workflows/server-cd-dev.yml` |
