# Jenkins Universal Wrapper Pipeline

The way to create Jenkins pipelines much easier and faster in yaml config formats.

## About

Jenkins Universal Wrapper Pipeline allows you to create multistage pipelines by describing actions in a stages in yaml
files. You don't need Groovy programming language knowledge, even declarative Jenkins pipeline style. The syntax and
structure of the configs is in many ways reminds of the GitLab, GitHub or Travis CI. It's very similar writing stages,
actions then action description what each of them should do.

## Main features

- Built-in getting git sources.
- Built-in ansible collection(s) installation.
- Built-in reports (email or mattermost).
- Run ansible playbooks or scripts just by inserting their code in action description inside yaml config.
- Node selection and move files between.
- Working with file-artifacts.
- Possible to run actions in a stages in parallel or sequentially.
- Inject pipeline parameters on the first pipeline run by specifying them inside yaml config files.
- You can also extend a pipeline code by running native language as part of pipeline (e.g. Groovy for Jenkins).

## Requirements

1. Jenkins version 2.x or higher (perhaps lower versions are also fine, but tested on 2.190.x).
2. [Linux jenkins node(s)](https://www.jenkins.io/doc/book/installing/linux/) to run pipeline. Most of built-in actions
   except script run probably also works on Windows nodes, but it wasn't tested.
3. This pipeline requires [jenkins shared library](https://github.com/alexanderbazhenoff/jenkins-shared-library)
   connection.
4. [AnsiColor Jenkins plugin](https://plugins.jenkins.io/ansicolor/) for colour console output.
To run ansible inside a wrapper plugin you may need to install
   [Ansible Jenkins plugin](https://plugins.jenkins.io/ansible/) (optional, not required by default).

## Usage

1. Connect [jenkins shared library](https://github.com/alexanderbazhenoff/jenkins-shared-library) with the
   name `jenkins-shared-library-alx` (see
   [official documentation](https://www.jenkins.io/doc/book/pipeline/shared-libraries/#global-shared-libraries)).
2. Install all required [Jenkins plugins](https://www.jenkins.io/doc/book/managing/plugins/) (see
   ['Requirements'](#requirements)).
3. Set up pipeline constants (especially repositories) (see. ['Pipeline constants'](#pipeline-constants).
4. Read [detailed manual](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings) with pipeline
   config format description to create your own, or use example configs (e.g.
   ['example-pipeline'](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings/blob/main/settings/example-pipeline.yaml).
   You should create pipeline from SCM pointed to this repository with the same name as your config file (except name
   prefix - see 'PipelineNameRegexReplace' in ['Pipeline constants'](#pipeline-constants)).

## Pipeline constants

- `SettingsGitUrl`: repository URL of
  ['universal-wrapper-pipeline-settings'](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings/tree/main)
  to load current pipeline settings.
- `DefaultSettingsGitBranch`: repository branch of
  ['universal-wrapper-pipeline-settings'](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings/tree/main).
- `SettingsRelativePathPrefix`: prefix for pipeline settings relative path inside the
  ['universal-wrapper-pipeline-settings'](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings/tree/main)
   project, that will be added automatically on yaml load (e.g. `settings` folder).
- `PipelineNameRegexReplace`: regular expression for jenkins pipeline name, a string that will be cut from pipeline
  name to become a filename of yaml pipeline settings to be loaded.
- `AnsibleInstallationName`: ansible installation name from jenkins Global Configuration Tool or empty for defaults from
  jenkins shared library (see ['Ansible Jenkins plugin'](https://plugins.jenkins.io/ansible/) documentation).
- `BuiltinPipelineParameters`: built-in pipeline parameters, which are mandatory and not present in
  'universal-wrapper-pipeline-settings'. Specified pipeline parameters `UPDATE_PARAMETERS`, `SETTINGS_GIT_BRANCH`,
  `NODE_NAME`, `NODE_TAG`, `DRY_RUN` and `DEBUG_MODE` are system. Modifying them is not recommended.

## URLs

- [Universal wrapper pipeline settings](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings/tree/main)
  repository with config format description.
