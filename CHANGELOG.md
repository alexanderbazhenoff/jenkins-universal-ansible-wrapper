<!-- markdownlint-disable MD007 MD024 MD041 -->
# Changelog

## [1.0.1](https://github.com/alexanderbazhenoff/jenkins-universal-wrapper-pipeline/pull/1) (2024-03-18)

### Features

- Send report via [Telegram messenger](https://telegram.org/) using
  [Telegram Bot](https://core.telegram.org/bots/tutorial) has been added.

### Improvements

- CI: Automated Wiki Build has been added.
- CI: Switched from [Super-Linter](https://github.com/super-linter/super-linter)
  to [MegaLinter](https://megalinter.io/).
- Various typo fixes.

## [1.0.0](https://github.com/alexanderbazhenoff/jenkins-universal-wrapper-pipeline/pull/1) (2024-02-24)

First version as it was written mostly in 2023.

### Features

- Built-in getting git sources.
- Built-in ansible collection(s) installation.
- Built-in reports (email or mattermost).
- Run ansible playbooks or scripts just by inserting their code in action description inside yaml config.
- Node selection and move files between.
- Working with file-artifacts.
- Possible to run actions in a stages in parallel or sequentially.
- Inject pipeline parameters on the first pipeline run by specifying them inside yaml config files.
- You can also extend a pipeline code by running native language as part of pipeline (e.g. Groovy for Jenkins).
