#!/usr/bin/env groovy


import groovy.json.JsonBuilder


@Library('jenkins-shared-library-alx@devel') _


// Repo URL and a branch of 'ansible-wrapper-settings' to load current pipeline settings from.
def pipelineSettingsGitUrl = 'https://github.com/alexanderbazhenoff/ansible-collection-linux.git' as String
def pipelineSettingsGitBranch = 'main' as String
// Prefix for pipeline settings path inside the 'ansible-wrapper-settings' project, that will be added automatically
// on yaml load.
def pipelineSettingsPathPrefix = 'settings/' as String
// Postfix for pipeline settings path inside the 'ansible-wrapper-settings' project, that will be added automatically.
def pipelineSettingsPathPostfix = '' as String
// Jenkins pipeline name prefix, a string that will be cut from the beginning of yaml settings search filename.
def pipelineNamePrefix = 'admin_' as String
// Jenkins pipeline name postfix, a string that will be cut from the end of yaml settings search filename.
def pipelineNamePostfix = '' as String
// Set your ansible installation name from jenkins settings:
def AnsibleInstallationName = 'home_local_bin_ansible' as String


String getPipelineYamlSettings(String settingsRepoUrl, String settingsRepoBranch, String settingsPath,
                               String settingsFilename) {
    CF.cloneGitToFolder(String projectGitUrl, String projectGitlabBranch, String projectLocalPath = 'settings')
}


node('master') {
    wrap([$class: 'TimestamperBuildWrapper']) {
        CF = new org.alx.commonFunctions() as Object

        String pipelineYamlSettings = readPipelineYamlSettings()
        Map pipelineSettings = parseJson(new JsonBuilder(yaml).toPrettyString())
        CF.outMsg(1, 'test library connection')
    }
}
