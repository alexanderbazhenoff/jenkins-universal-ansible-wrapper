#!/usr/bin/env groovy


import groovy.json.JsonBuilder


@Library('jenkins-shared-library-alx@devel') _


// Repo URL and a branch of 'ansible-wrapper-settings' to load current pipeline settings from.
def SettingsGitUrl = 'https://github.com/alexanderbazhenoff/ansible-collection-linux.git' as String
def SettingsGitBranch = 'main' as String

// Prefix for pipeline settings relative path inside the 'ansible-wrapper-settings' project, that will be added
// automatically on yaml load.
def SettingsRelativePathPrefix = 'settings' as String

// Jenkins pipeline name regex, a a string that will be cut from pipeline name to become a filename of yaml pipeline
// settings to be loaded. E.g: ['^prefix_', '_postfix$']
def PipelineNameRegexReplace = ['^admin_'] as ArrayList

// Set your ansible installation name from jenkins settings.
def AnsibleInstallationName = 'home_local_bin_ansible' as String


/**
 * Clone 'ansible-wrapper-settings' from git repository, load yaml pipeline settings and return them as a map.
 *
 * @param SettingsGitUrl - git repo URL to clone from.
 * @param SettingsGitBranch - git branch.
 * @param settingsRelativeFullPath - relative path inside the 'ansible-wrapper-settings' project.
 * @return
 */
String loadPipelineSettings(String settingsGitUrl, String settingsGitBranch, String settingsRelativeFullPath) {
    Map pipelineSettings = [:]
    CF.cloneGitToFolder(settingsGitUrl, settingsGitBranch, 'settings')
    return pipelineSettings
}

/**
 * Apply ReplaceAll items on string.
 *
 * @param text - text to process.
 * @param regexItemsList - list of regex items to apply .replaceAll method.
 * @param replaceItemsList - list of items to replace with. List must be the same length as a regexItemsList, otherwise
 *                           will be replaced with empty line ''.
 * @return - resulting text.
 */
static applyReplaceAllItems(String text, ArrayList regexItemsList, ArrayList replaceItemsList = []) {
    for (int i = 0; i < regexItemsList.size(); i++) {
        text = text.replaceAll(regexItemsList[i], replaceItemsList[i]?.trim() ? replaceItemsList[i] : '')
    }
    return text
}


node('master') {
    wrap([$class: 'TimestamperBuildWrapper']) {
        CF = new org.alx.commonFunctions() as Object

        String pipelineSettings = loadPipelineSettings(SettingsGitUrl, SettingsGitBranch, String.format('%s/%s',
                SettingsRelativePathPrefix, applyReplaceAllItems(env.JOB_NAME.toString(), PipelineNameRegexReplace)))

        CF.outMsg(1, 'test library connection')
    }
}
