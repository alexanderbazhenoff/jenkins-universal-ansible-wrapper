#!/usr/bin/env groovy


/**
 * Jenkins Universal Wrapper Pipeline v1.0.0 (c) Aleksandr Bazhenov, 2023
 *
 * This Source Code Form is subject to the terms of the Apache License v2.0.
 * If a copy of this source file was not distributed with this file, You can obtain one at:
 * https://github.com/alexanderbazhenoff/jenkins-universal-wrapper-pipeline/blob/main/LICENSE
 */


@Library('jenkins-shared-library-alx') _

// @NonCPS
// @Grab(group = 'org.yaml', module = 'snakeyaml', version = '1.5')
// import org.yaml.snakeyaml.*
// import groovy.text.StreamingTemplateEngine

// Repo URL and a branch of 'universal-wrapper-pipeline-settings' to load current pipeline settings, e.g:
// 'git@github.com:alexanderbazhenoff/ansible-wrapper-settings.git'. Will be ignored when SETTINGS_GIT_BRANCH pipeline
// parameter present and not blank.
final SettingsGitUrl = 'http://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings' as String
final DefaultSettingsGitBranch = 'main' as String

// Prefix for pipeline settings relative path inside the 'universal-wrapper-pipeline-settings' project, that will be
// added automatically on yaml load.
final SettingsRelativePathPrefix = 'settings' as String

// Jenkins pipeline name regex, a string that will be cut from pipeline name to become a filename of yaml pipeline
// settings to be loaded. Example: Your jenkins pipeline name is 'prefix_pipeline-name_postfix'. To load pipeline
// settings 'pipeline-name.yml' you can use regex list: ['^prefix_','_postfix$']. FYI: All pipeline name prefixes are
// useful to split your jenkins between your company departments (e.g: 'admin', 'devops, 'qa', 'develop', etc...), while
// postfixes are useful to mark pipeline as a changed version of original.
final PipelineNameRegexReplace = ['^(admin|devops|qa)_'] as ArrayList

// Ansible installation name from jenkins Global Configuration Tool or empty for defaults from jenkins shared library.
final AnsibleInstallationName = 'home_local_bin_ansible' as String

// Built-in pipeline parameters, which are mandatory and not present in 'universal-wrapper-pipeline-settings'.
final BuiltinPipelineParameters = [
        [name       : 'UPDATE_PARAMETERS',
         type       : 'boolean',
         default    : false,
         description: 'Update pipeline parameters from settings file only.'],
        [name       : 'SETTINGS_GIT_BRANCH',
         type       : 'string',
         regex      : '(\\*)? +(.*?) +(.*?)? ((\\[(.*?)(: (.*?) (\\d+))?\\])? ?(.*$))?',
         description: 'Git branch of ansible-wrapper-settings project (to override defaults on development).'],
        [name       : 'NODE_NAME',
         type       : 'string',
         description: 'Name of Jenkins node to run directly on.'],
        [name       : 'NODE_TAG',
         type       : 'string',
         default    : 'ansible210',
         description: 'Run on Jenkins node with specified tag.'],
        [name       : 'DRY_RUN',
         type       : 'boolean',
         description: String.format('%s (%s).', 'Dry run mode to use for pipeline settings troubleshooting',
                 'will be ignored on pipeline parameters needs to be injected')],
        [name: 'DEBUG_MODE',
         type: 'boolean']
] as ArrayList
