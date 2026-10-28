# Contributing to Glassio

Thank you for your interest in improving Glassio, an Apple TV-style home screen for Google TV and Android TV.

## Before you start

- Check existing issues and pull requests to avoid duplicating work.
- For a substantial feature or a change to the app's architecture, open an issue or discussion first so the approach can be agreed on.
- Keep changes focused. Avoid unrelated refactors in the same pull request.
- Do not commit API keys, access tokens, passwords, signing keys, private URLs, or other secrets.

## Suggested workflow

1. Fork `mohammadmrh12/glassio`.
2. Create a branch for your change, for example `fix/poster-focus-shadow` or `feat/backup-restore`.
3. Make the change and keep the code consistent with the surrounding project.
4. Run the relevant build, tests, and checks available in the repository.
5. Test UI changes on Google TV or Android TV when possible, especially remote-control focus, screen scaling, and navigation.
6. Commit your changes with a clear message.
7. Open a pull request against the appropriate branch in the original repository and complete the pull request template.

## Pull request expectations

Please include:
- The problem being solved and why the change is useful.
- A summary of the implementation.
- The tests or build checks you ran, and any checks you could not run.
- Screenshots or a short recording for visible UI changes, where practical.
- Any compatibility, migration, configuration, or security implications.

Keep pull requests small enough to review. Be responsive to review feedback and explain any trade-offs.

## Bug reports

When reporting a bug, include steps to reproduce it, expected and actual behavior, the affected device/Android TV version, and relevant logs or screenshots. Remove personal information and secrets from logs before sharing them.

## Security issues

Do not report exploitable vulnerabilities in public issues. Follow the instructions in [SECURITY.md](SECURITY.md).

By participating, you agree to follow the project's [Code of Conduct](CODE_OF_CONDUCT.md).
