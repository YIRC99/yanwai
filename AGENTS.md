# Local development rules

- Work in the current checkout. Do not use worktrees. Preserve unrelated changes.
- Every local commit must advance both `versionName` and `versionCode` in `version.properties`, including follow-up fixes. Use `powershell -File tools/bump-version.ps1` for a patch, or `-Part minor` for a feature batch.
- `version.properties` is the single version source. The Gradle package, app header next to 言外, analysis cards and diagnostics must use generated `BuildConfig` values, never independently hard-coded version strings.
- Stage the version update with its corresponding changes. Enable the tracked guard with `git config core.hooksPath .githooks`; it checks the staged version against HEAD. Do not bypass the guard to commit an unchanged version.
- Validate code changes with the relevant offline tests and a Debug build. Use `--no-daemon -Pkotlin.compiler.execution.strategy=in-process`; leave device UI testing to the user unless explicitly requested.
- For a long implementation task, make a scoped local commit before finishing. Do not push or publish unless asked. Check that no project server or compilation process was left running.
