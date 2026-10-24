# Contributing to MINOS Code Intelligence

MINOS Code Intelligence is proprietary source-available software. Public visibility of this repository is intended to provide transparency and access to the source; it does not make the project open source.

## External contributions

External code contributions are **not accepted by default**.

Do not submit source-code changes, patches or pull requests unless Fabrice Turleque has explicitly authorized the contribution in writing beforehand and, where requested, the applicable contribution or intellectual-property agreement has been completed.

An unsolicited pull request or patch may be closed without review and must not be interpreted as granting either party rights beyond those that already apply under law, GitHub's binding terms or an explicit written agreement.

## Issues and discussions

Bug reports, questions and feature suggestions may be submitted through the repository's public collaboration features when enabled. Submission of an issue or discussion does not grant a license to MINOS Code Intelligence and does not authorize use, execution, deployment, modification, redistribution or commercialization of the Software.

Do not include confidential information, proprietary third-party source code, credentials, personal data or material you do not have the right to disclose.

## Comment language

Comments and Javadoc of the code (every `minos-*` module, the IntelliJ plugin included) are written in **English**; repository artifacts (documentation, ADRs, OpenSpec changes, commit messages, pull requests) stay in French (`AGENTS.md`). The rule applies to every new or modified comment, and a comment does not cite a milestone number (`M17`, `M21-S6`): it says what the code does.

The language of each module is declared in `scripts/quality/comment-language.json`, read by `python scripts/quality/check-comment-language.py` (job `invariants`). The gate holds two ratchets:

- a production file whose comments mix French and English fails unless the `mixed` list names it; the list only shrinks;
- per module, the number of files with a French comment (`outOfLanguageMaximum`) and of files whose comments cite a milestone (`milestoneCommentMaximum`) can only go down: a pull request that rewrites such a file lowers the maximum, and the gate gives the value.

Files written entirely in French are not translated in bulk: they are counted and rewritten when they are next modified.

## Licensing requests

Requests for evaluation, internal use, execution, deployment, redistribution, integration, commercial use or contribution authorization require prior written permission from the copyright holder.

See [`LICENSE`](LICENSE) for the terms governing MINOS Code Intelligence.
