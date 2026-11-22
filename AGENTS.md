# AGENTS.md

Project-specific agent instructions for **authmePAL** (Paper authentication plugin, Java).

## Platform

- **Target/main server:** Paper **26.3** (Java 25). This is the production target. The **only local test server** is `C:\Tools\global-test-server-26.3` (Paper 26.3, Java 25, port **25566**, Craftwire + MCP bridges); use it for target testing. There is no local 1.21.11 server (retired 2026-11-23); older clients join via ViaVersion + ViaBackwards. From MC 26.1 Paper is deobfuscated and reobfuscation is unsupported; use Mojang mappings for 26.x work.
- **Java:** 25 (toolchain). Emits Java 25 bytecode.
- **Plugin name:** `authmePAL`. PacketEvents 2.13.0 (spigot) for handshake/session work.

## Build & run

- Build: `./gradlew build`
- Tests: `./gradlew test` (JUnit 5 present under src/test/java).
- Run server: `./gradlew runServer` (currently pinned to `minecraftVersion("26.2")` in build.gradle.kts; bump to 26.3 to match the target before release).
- Compile only: `./gradlew compileJava`.
- Shared test server and Minecraft MCP usage: `../GLOBAL_TEST_SERVER.md` (the old `run/` dir was removed; copy the built jar into the global server's `plugins/`).

## Architecture

- Packages: `org.authmePAL.authme`, `command`, `decision`, `handshake`, `migration`, `mojang`, `session`, `storage`.
- Handshake/session/storage are the core; migration handles legacy data.

## Critical gotchas

- **UTF-8 encoding.** Windows PowerShell writes ASCII by default and corrupts characters like `§` into `?`.
  Write with explicit UTF-8; prefer the dedicated edit/write tools.
- PacketEvents is a hard runtime dependency for handshake work; keep version in sync with Paper.

## Agent notes

- Read AGENTS.md first (subagents start fresh).
- mempalace: query before answering about prior decisions for authmePAL (if stored).

## Orchestration loop control

Reference: [Improved loop control](https://github.com/evermeer/CodingAgentOrchestration/blob/main/docs/part-5-optimisation.md#improved-loop-control)

When operating inside a controlled orchestration loop, strictly follow this output schema and the rules below.

### Output format (strict JSON only)

Always return:

```json
{
  "status": "continue" | "end",
  "action": {
    "tool": "<tool_name OR 'final_answer'>",
    "args": { }
  },
  "message": "<short user-facing explanation>"
}
```

### Field definitions

- **status**: Controls the loop execution.
  - `"continue"` -> system executes the action and loops again
  - `"end"` -> system stops immediately
- **action.tool**: Must match one of the available tools exactly. Use `"final_answer"` when no tool is needed.
- **action.args**: Arguments for the tool. Must always be valid JSON (empty `{}` if not needed).
- **message**: Short explanation of what you did or concluded. No reasoning chain-of-thought.

### Strict rules

1. **STATUS IS AUTHORITATIVE**
   - If task is complete -> MUST return `"status": "end"`
   - If more work is needed -> MUST return `"status": "continue"`

2. **NO CONTRADICTIONS**
   - NEVER say work is finished while using `"continue"`
   - NEVER continue after reaching a valid final answer

3. **STOP CONDITIONS (MANDATORY)**
   You MUST return `"status": "end"` if:
   - The user request is fulfilled
   - A correct final answer is available
   - Further steps would not improve the result
   - You are repeating actions or not making progress

4. **TOOL USAGE DISCIPLINE**
   - Only call tools when necessary
   - Do NOT explore or "double-check" without reason
   - If no tool is needed -> use `"tool": "final_answer"`

5. **LOOP PREVENTION**
   If you detect repeated tool calls or no new information in the last steps -> you MUST terminate with `"status": "end"`.

6. **FAIL-SAFE DEFAULT**
   If unsure whether to continue -> return `"status": "end"`.
