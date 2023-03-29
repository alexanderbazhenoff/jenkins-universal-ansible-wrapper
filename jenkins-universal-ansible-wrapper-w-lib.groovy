#!/usr/bin/env groovy


@NonCPS
@Grab(group='org.yaml', module='snakeyaml', version='1.5')
import org.yaml.snakeyaml.*


@Library('jenkins-shared-library-alx@devel') _


// Repo URL and a branch of 'ansible-wrapper-settings' to load current pipeline settings from, e.g:
// 'git@github.com:alexanderbazhenoff/ansible-wrapper-settings.git'
def SettingsGitUrl = 'https://github.com/alexanderbazhenoff/ansible-wrapper-settings.git' as String
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
 * @param settingsRelativePath - relative path inside the 'ansible-wrapper-settings' project.
 * @param workspaceSubfolder - subfolder in jenkins workspace where the git project will be cloned.
 * @param printYaml - if true output 'ansible-wrapper-settings' content on a load.
 * @return - map with pipeline settings.
 */
Map loadPipelineSettings(String settingsGitUrl, String settingsGitBranch, String settingsRelativePath,
                            String workspaceSubfolder = 'settings', Boolean printYaml = true) {
    CF.cloneGitToFolder(settingsGitUrl, settingsGitBranch, workspaceSubfolder)
    String pathToLoad = String.format('%s/%s', workspaceSubfolder, settingsRelativePath)
    if (printYaml) CF.outMsg(1, String.format('Loading pipeline settings:\n%s', readFile(pathToLoad)))
    return readYaml(file: pathToLoad)
}

/**
 * Apply ReplaceAll regex items to string.
 *
 * @param text - text to process.
 * @param regexItemsList - list of regex items to apply .replaceAll method.
 * @param replaceItemsList - list of items to replace with. List must be the same length as a regexItemsList, otherwise
 *                           will be replaced with empty line ''.
 * @return - resulting text.
 */
static applyReplaceAllItems(String text, ArrayList regexItemsList, ArrayList replaceItemsList = []) {
    regexItemsList.eachWithIndex{ value, Integer index ->
        text = text.replaceAll(value, replaceItemsList.contains(index) ? replaceItemsList[index] : '')
    }
    return text
}

static checkPipelineParams(ArrayList params) {
    println String.format('Checking params: %s', params)
    Boolean updateParamsRequiredState = false
    params.each {
        println String.format('Looking for: %s', it.name)
//        if (!params.containsKey(it.name))
//            updateParamsRequiredState = true
    }
    return updateParamsRequiredState
}


node('master') {
    CF = new org.alx.commonFunctions() as Object
    wrap([$class: 'TimestamperBuildWrapper']) {

        String settingsRelativePath = String.format('%s/%s.yaml', SettingsRelativePathPrefix,
                applyReplaceAllItems(env.JOB_NAME.toString(), PipelineNameRegexReplace))
        Map pipelineSettings = loadPipelineSettings(SettingsGitUrl, SettingsGitBranch, settingsRelativePath)
        if (checkPipelineParams(pipelineSettings.parameters.required + pipelineSettings.parameters.optional))
            println 'Update parameters required'

    }
}
