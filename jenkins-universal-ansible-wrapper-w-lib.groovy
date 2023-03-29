#!/usr/bin/env groovy


@NonCPS
@Grab(group='org.yaml', module='snakeyaml', version='1.5')
import org.yaml.snakeyaml.*


@Library('jenkins-shared-library-alx@devel') _


// Repo URL and a branch of 'ansible-wrapper-settings' to load current pipeline settings from, e.g:
// 'git@github.com:alexanderbazhenoff/ansible-wrapper-settings.git'. Will be ignored when SETTINGS_GIT_BRANCH pipeline
// parameter present and not blank.
def SettingsGitUrl = 'https://github.com/alexanderbazhenoff/ansible-wrapper-settings.git' as String
def DefaultSettingsGitBranch = 'main' as String

// Prefix for pipeline settings relative path inside the 'ansible-wrapper-settings' project, that will be added
// automatically on yaml load.
def SettingsRelativePathPrefix = 'settings' as String

// Jenkins pipeline name regex, a a string that will be cut from pipeline name to become a filename of yaml pipeline
// settings to be loaded. E.g: ['^prefix_', '_postfix$']
def PipelineNameRegexReplace = ['^admin_'] as ArrayList

// Set your ansible installation name from jenkins settings.
def AnsibleInstallationName = 'home_local_bin_ansible' as String

// System pipeline parameters, which are mandatory and not present in 'ansible-wrapper-settings'.
def SystemPipelineParameters = [
        [name       : 'SETTINGS_GIT_BRANCH',
         type       : 'string', default: '',
         description: 'Git branch of ansible-wrapper-settings project (to override defaults on development).'],
        [name   : 'DEBUG_MODE',
         type   : 'boolean',
         default: false]
] as ArrayList


/**
 * Clone 'ansible-wrapper-settings' from git repository, load yaml pipeline settings and return them as a map.
 *
 * @param settingsGitUrl - git repo URL to clone from.
 * @param settingsGitBranch - git branch.
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

/**
 * Verify all required jenkins pipeline parameters presents.
 *
 * @param requiredParams - an array list of map items to check, e.g: [map_item1, map_item2 ... map_itemN]. While single
 *                         map item format is:
 *                         [
 *                          name: 'PARAMETER_NAME',
 *                          type: 'string|text|choice|boolean|password'
 *                          default: 'default_value',
 *                          choices: ['one', 'two', 'three'],
 *                          description: 'Your jenkins parameter pipeline description.',
 *                          trim: false|true
 *                         ]
 *                         Please note:
 *                         - 'choices' item element is only for 'type: choice'.
 *                         - 'default' item element is for all types except 'type: choice'. In this case default value
 *                           will be the first element from choices list.
 *                         - 'trim' item element is for every string type, e.g: string|text|password. By default
 *                           'trim: false', so you don't need to set them on every item.
 *                         Check readme file for details: https://github.com/alexanderbazhenoff/ansible-wrapper-settings
 * @return - true when jenkins pipeline parameters update required.
 */
static checkPipelineParams(ArrayList requiredParams) {
    Boolean updateParamsRequired = false
    requiredParams.each { if (!params.containsKey(it.name)) updateParamsRequired = true }
    return updateParamsRequired
}

def updatePipelineParams(ArrayList requiredParams) {
    ArrayList newPipelineParams = []
    requiredParams.each {
        it.defaultValue = it.default
        newPipelineParams += it.type(it.remove('default').remove('type'))
    }
    println newPipelineParams
}


node('master') {
    CF = new org.alx.commonFunctions() as Object
    wrap([$class: 'TimestamperBuildWrapper']) {

        String settingsRelativePath = String.format('%s/%s.yaml', SettingsRelativePathPrefix,
                applyReplaceAllItems(env.JOB_NAME.toString(), PipelineNameRegexReplace))
        Map pipelineSettings = loadPipelineSettings(SettingsGitUrl, DefaultSettingsGitBranch, settingsRelativePath)
        if (checkPipelineParams(pipelineSettings.parameters.required + pipelineSettings.parameters.optional +
                SystemPipelineParameters))
            println 'Update parameters required'

    }
}
