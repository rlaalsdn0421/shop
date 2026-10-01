#!/usr/bin/env node
// PostToolUse hook: when an Edit/Write touches backend/ or frontend/ source,
// run that project's test suite and print the result as hook context.
//
// Disable per-session with SKIP_AUTOTEST=1 (full test suites are slow —
// gradle ~10-20s, vitest ~5-30s — so this is an escape hatch for rapid
// multi-edit stretches where you'll run tests yourself at the end instead).

"use strict";

const { execSync } = require("child_process");
const path = require("path");

if (process.env.SKIP_AUTOTEST) process.exit(0);

let raw = "";
process.stdin.on("data", (chunk) => (raw += chunk));
process.stdin.on("end", () => {
  let data;
  try {
    data = JSON.parse(raw);
  } catch {
    process.exit(0);
  }

  const filePath = (data.tool_input && data.tool_input.file_path) || "";
  if (!filePath) process.exit(0);

  const repoRoot = path.resolve(__dirname, "..", "..");
  const relative = path.relative(repoRoot, filePath).replace(/\\/g, "/");

  const isWindows = process.platform === "win32";

  let cwd;
  let label;
  let cmd;
  if (relative.startsWith("backend/")) {
    cwd = path.join(repoRoot, "backend");
    label = "backend";
    cmd = isWindows ? ".\\gradlew.bat test --console=plain -q" : "./gradlew test --console=plain -q";
  } else if (relative.startsWith("frontend/")) {
    cwd = path.join(repoRoot, "frontend");
    label = "frontend";
    cmd = "npm test";
  } else {
    process.exit(0);
  }

  console.log(`[auto-test] ${label} 변경 감지 → 테스트 실행 중...`);
  try {
    const output = execSync(cmd, { cwd, encoding: "utf8", stdio: "pipe" });
    console.log(`[auto-test] ✅ ${label} 테스트 통과`);
    console.log(output.split("\n").slice(-15).join("\n"));
  } catch (err) {
    console.log(`[auto-test] ❌ ${label} 테스트 실패`);
    const output = (err.stdout || "") + (err.stderr || "");
    console.log(output.split("\n").slice(-40).join("\n"));
  }
});
