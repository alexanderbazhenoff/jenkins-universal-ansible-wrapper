<!-- markdownlint-disable MD033 MD041 -->
<div align='center'>

# Jenkins Universal Wrapper Pipeline

Fast and easy way to create Jenkins pipelines through yaml configuration files.

[![Super-Linter](https://github.com/alexanderbazhenoff/jenkins-universal-wrapper-pipeline/actions/workflows/super-linter.yml/badge.svg?branch=main)](https://github.com/marketplace/actions/super-linter)
[![Release CI](https://github.com/alexanderbazhenoff/jenkins-universal-wrapper-pipeline/actions/workflows/release-ci.yml/badge.svg?branch=main)](CHANGELOG.md)
[![GitHub Release](https://img.shields.io/github/v/release/alexanderbazhenoff/jenkins-universal-wrapper-pipeline)](https://github.com/alexanderbazhenoff/jenkins-universal-wrapper-pipeline/releases)
[![GitHub License](https://img.shields.io/github/license/alexanderbazhenoff/jenkins-universal-wrapper-pipeline)](LICENSE)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg?style=flat-square)](https://makeapullrequest.com)
[![Tweet](https://img.shields.io/twitter/url/http/shields.io.svg?style=social)](https://twitter.com/intent/tweet?text=Create+your+pipelines+easier+and+faster%21%20&url=https://github.com/alexanderbazhenoff/jenkins-universal-wrapper-pipeline&hashtags=devops,cicd,jenkins,ansible,yaml)

<span style="font-size:0.8em;">[**English**](README.md) • [Russian](README_RUS.md)</span>
</div>

## About

Jenkins Universal Wrapper Pipeline allows you to create multistage pipelines by describing actions in a stages in yaml
files. You don't need Groovy programming language knowledge, even declarative Jenkins pipeline style. Just create
configuration file and describe all stages and actions should be done.The syntax and structure of the configs is in many
ways reminds of the GitLab, GitHub or Travis CI. It's very similar writing stages, actions then action description what
each of them should do.

## Main features

- Built-in getting git sources of another repository.
- Built-in ansible collection(s) installation.
- Built-in reports send (email or mattermost).
- Run ansible playbooks or scripts just by inserting their code in action description inside yaml config. You can also
  run whatever you want wrapped in a scripts run: puppet, terraform, etc.
- Node selection and move required files between.
- Working with file-artifacts.
- Able to run actions in a stages in parallel or sequentially.
- Inject pipeline parameters on the first pipeline run by specifying them inside yaml config file.
- You can also extend a pipeline features code and run native language 'as part of pipeline' (e.g. Groovy for
  Jenkins) by adding code in pipeline actions inside yaml config.

## Requirements

1. Jenkins version 2.x or higher (perhaps lower versions are also fine, but tested on versions 2.190.x).
2. [Linux jenkins node(s)](https://www.jenkins.io/doc/book/installing/linux/) to run a pipeline. Most of the built-in
   actions except scripts and ansible playbook run (such as getting sources, stash/unstash files, node selection,
   working with artifact files, inject pipeline parameters and running code 'as a prt of pipeline') probably also works
   on Windows nodes, but it wasn't tested. Running bat and Powershell on Windows nodes currently not supported. Running
   terraform and puppet can be done by saving the necessary files and running them inside a script, similar to how you
   run them through the command-line.
3. This pipeline requires [jenkins shared library](https://github.com/alexanderbazhenoff/jenkins-shared-library)
   connection.
4. [AnsiColor Jenkins plugin](https://plugins.jenkins.io/ansicolor/) for colour console output.
5. To run ansible inside a wrapper plugin you may need to install
   [Ansible Jenkins plugin](https://plugins.jenkins.io/ansible/) (optional, not required by default).

## Setting up

1. Connect [jenkins shared library](https://github.com/alexanderbazhenoff/jenkins-shared-library) with the
   name `jenkins-shared-library-alx` (see
   [official documentation](https://www.jenkins.io/doc/book/pipeline/shared-libraries/#global-shared-libraries)).
2. Install all required [Jenkins plugins](https://www.jenkins.io/doc/book/managing/plugins/) on Jenkins server (see
   ['Requirements'](#requirements)).
3. Set up pipeline constants (especially repositories) (see. ['Pipeline constants'](#pipeline-constants).
4. Read [detailed manual](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings) with pipeline
   config format description to create your own, or use example configs (e.g.
   ['example-pipeline'](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings/blob/main/settings/example-pipeline.yaml).
   You should create pipeline from SCM with the same name as your config file (except name prefix and extension - see
   'PipelineNameRegexReplace' in ['Pipeline constants'](#pipeline-constants)) configured to fetch this repository and
   code in [jenkins-universal-wrapper-pipeline.groovy](jenkins-universal-wrapper-pipeline.groovy) file.
5. Some used methods in pipeline code may require administrators to approve a usage of them (see
   ["In-process Script Approval"](https://www.jenkins.io/doc/book/managing/script-approval/) in official documentation).

## Pipeline constants

You can specify some pipeline settings via constants, or override them via environment variables without modifying
pipeline code (see ['Constants override'](#constants-override)): for example, if you wish to redirect settings files
repository, change branch or relative path to yaml files inside them. Environment variables will override existing
constant values.

- `SettingsGitUrl` pipeline constant or `JUWP_SETTINGS_GIT_URL` environment variable: repository URL of
  ['universal-wrapper-pipeline-settings'](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings/tree/main)
  to load current pipeline settings.
- `DefaultSettingsGitBranch` pipeline constant or `JUWP_DEFAULT_SETTINGS_GIT_BRANCH` environment variable: repository
  branch of
  ['universal-wrapper-pipeline-settings'](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings/tree/main).
- `SettingsRelativePathPrefix` constant or `JUWP_RELATIVE_PATH_PREFIX` environment variable: prefix for pipeline
  settings relative path to yaml files inside the
  ['universal-wrapper-pipeline-settings'](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings/tree/main)
  repository that will be added automatically on a yaml load (e.g. `settings` folder).
- `PipelineNameRegexReplace` list constant or `JUWP_PIPELINE_NAME_REGEX_REPLACE` environment variable (comma separated
  list of regular expressions, e.g: `'value1, value2, value3'`): regular expression for jenkins pipeline name, a
  string that will be cut from pipeline name to become a filename of yaml pipeline settings to be loaded.
- `AnsibleInstallationName` constant: ansible installation name from Jenkins Global Configuration Tool or empty for
  defaults from [jenkins shared library](https://github.com/alexanderbazhenoff/jenkins-shared-library) (see
  ['Ansible Jenkins plugin'](https://plugins.jenkins.io/ansible/) documentation). *Not used and will probably be removed
  soon as recent changes in [jenkins shared library](https://github.com/alexanderbazhenoff/jenkins-shared-library) runs
  ansible playbooks through a shell call by default.*
- `BuiltinPipelineParameters` constant contains a built-in pipeline parameters, which are mandatory and not present in
  'universal-wrapper-pipeline-settings': pipeline parameters `UPDATE_PARAMETERS`, `SETTINGS_GIT_BRANCH`, `NODE_NAME`,
  `NODE_TAG`, `DRY_RUN` and `DEBUG_MODE` specified here are system. Modifying them is not recommended.

## Constants override

You can also override [pipeline constants](#pipeline-constants) without code pipeline changes using predefined
environment variable(s). Set them in node settings (via selecting node in "Manage Jenkins Nodes" menu), or better in an
option 'Prepare an environment for the run' in your pipeline settings. As official Jenkins manual of
[Environment Injector](https://plugins.jenkins.io/envinject/) described, enable 'Prepare an environment for the run'
and add your environment variables to the field 'Properties Content' in a dropped-down menu filed, e.g.:

```properties
JUWP_SETTINGS_GIT_URL=http://github.com/my_usrrname/my_universal-wrapper-pipeline-settings-repository
JUWP_DEFAULT_SETTINGS_GIT_BRANCH=my_branch
```

## URLs

- [Wiki](https://github.com/alexanderbazhenoff/jenkins-universal-wrapper-pipeline/wiki).
- [Universal wrapper pipeline settings](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings/tree/main)
  repository with config format description and example pipeline configs.
