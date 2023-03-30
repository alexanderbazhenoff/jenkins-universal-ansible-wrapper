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

// Built-in pipeline parameters, which are mandatory and not present in 'ansible-wrapper-settings'.
def BuiltinPipelineParameters = [
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
 * Verify all required jenkins pipeline parameters are presents.
 *
 * @param pipelineParams - jenkins built-in 'params' UnmodifiableMap variable with current build pipeline parameters.
 * @param currentPipelineParams - an array list of map items to check, e.g: [map_item1, map_item2 ... map_itemN].
 *                                While single
 *                                map item format is:
 *
 *                                [
 *                                 name: 'PARAMETER_NAME',
 *                                 type: 'string|text|choice|boolean|password'
 *                                 default: 'default_value',
 *                                 choices: ['one', 'two', 'three'],
 *                                 description: 'Your jenkins parameter pipeline description.',
 *                                 trim: false|true
 *                                ]
 *
 *                               Please note:
 *
 *                               - 'choices' key is only for 'type: choice'.
 *                               - 'default' key is for all types except 'type: choice'. This key is incompatible with
 *                                 'type: choice'. For other types this key is optional: it's will be false for boolean,
 *                                 and '' (empty line) for string, password and text parameters.
 *                               - 'trim' key is available for 'type: string'. This key is optional, by default it's
 *                                 false.
 *                               - 'description' key is optional, by default it's '' (empty line).
 *                                 For more details: https://github.com/alexanderbazhenoff/ansible-wrapper-settings
 * @return - true when jenkins pipeline parameters update required.
 */
static verifyPipelineParams(ArrayList requiredParams, Object currentPipelineParams) {
    Boolean updateParamsRequired = false
    requiredParams.each { if (!currentPipelineParams.containsKey(it.name)) updateParamsRequired = true }
    return updateParamsRequired
}

/**
 * Convert pipeline settings map item and add to jenkins pipeline parameters.
 *
 * @param item - pipeline settings map item to convert.
 * @return - jenkins pipeline parameters.
 */
ArrayList pipelineSettingsItemToPipelineParam(Map item) {
    ArrayList param = []
    String defaultString = item.containsKey('default') ? item.default : ''
    String description = item.containsKey('description') ? item.description : ''
    if (item.type == 'choice' || (item.containsKey('choices') && item.choices instanceof ArrayList))
        param += [choice(name: item.name, choices: item.choices, description: description)]
    if (item.type == 'boolean' || (item.containsKey('default') && item.default instanceof Boolean))
        param += [booleanParam(name: item.name, description: description,
                defaultValue: item.containsKey('default') ? item.default : false)]
    if (item.type == 'password')
        param += [password(name: item.name, defaultValue: defaultString, description: description)]
    if (item.type == 'text')
        param += [text(name: item.name, defaultValue: defaultString, description: description)]
    if (item.type == 'string')
        param += [string(name: item.name, defaultValue: defaultString, description: description,
                trim: item.containsKey('trim') ? item.trim : false)]
    return param
}

Boolean pipelineSettingsItemError(Integer eventNum, String item, String errorMsg) {
    CF.outMsg(eventNum, String.format("Syntax %s in pipeline parameter '%s': %s.", eventNum == 3 ? 'ERROR' : 'WARNING',
            item, errorMsg))
    return false
}

Boolean pipelineSettingsItemCheck(Map item) {
    if (!item.containsKey('type')) {
        if ((item.containsKey('default') && item.default instanceof Boolean) ||
                (item.containsKey('choices') && item.choices instanceof ArrayList)) {
                    // TODO: these conditions are hard to read
                    String reason = item.default instanceof Boolean ? " by 'default' key, which is Boolean" :
                            item.choices instanceof ArrayList ? "by 'choices' key, which is an ArrayList" : ''
                    if (item.containsKey('default') && item.containsKey('choices') &&
                            item.choices instanceof ArrayList) {
                        return pipelineSettingsItemError(3, item as String,
                                "'default' key is not required for type choice")
                    } else {
                        def __ = pipelineSettingsItemError(2, item as String, String.format("%s: %s",
                                "default key value not defined, but parameter type was detected", reason))
                        return true
                    }
        } else {
            return pipelineSettingsItemError(3, item as String, "'type' key is required, but wasn't defined")
        }
    } else {
        // TODO: types mismatch processing
    }
    return true
}


/**
 * Inject parameters to current jenkins pipeline.
 *
 * @param requiredParams - array list of jenkins pipeline parameters to inject, e.g:
 *                         [
 *                          [choice(name: 'PARAM1', choices: ['one', 'two'], description: 'description1')],
 *                          [string(name: 'PARAM2', defaultValue: 'default', description: 'description2')]
 *                         ]
 *                         etc... Check pipelineSettingsItemToPipelineParam() function for details.
 */
def updatePipelineParams(ArrayList requiredParams) {
    ArrayList newPipelineParams = []
    currentBuild.displayName = String.format('pipeline_parameters_update--#%s', env.BUILD_NUMBER)
    requiredParams.each { newPipelineParams += pipelineSettingsItemToPipelineParam(it as Map) }
    properties([parameters(newPipelineParams)])
    CF.outMsg(1, "Pipeline parameters was successfully injected. Select 'Build with parameters' and run again.")
    CF.interruptPipelineOk(3)
}

def checkPipelineParams(ArrayList requiredParams) {
    Boolean allPass = true
    requiredParams.each { allPass = pipelineSettingsItemCheck(it) ? pipelineSettingsItemCheck(it) : false }
    return allPass
}

/**
 * Processing wrapper pipeline parameters: check all presents (if not, check syntax and inject).
 *
 * @param pipelineSettings - 'ansible-wrapper-settings' converted to map. See
 *                           https://github.com/alexanderbazhenoff/ansible-wrapper-settings for details.
 * @param currentPipelineParams - pipeline parameters of current build (actually requires a pass of 'params' which is
 *                                class java.util.Collections$UnmodifiableMap)
 * @param builtinPipelineParameters - built-in pipeline parameters in the same format as pipelineSettings, e.g:
 *                                    'SETTINGS_GIT_BRANCH', 'DEBUG_MODE', etc...
 */
def wrapperPipelineParametersProcessing(Map pipelineSettings, Object currentPipelineParams,
                                        ArrayList builtinPipelineParameters) {
    ArrayList requiredPipelineParams = pipelineSettings.parameters.required + pipelineSettings.parameters.optional +
            builtinPipelineParameters
    if (verifyPipelineParams(requiredPipelineParams, currentPipelineParams))
        if (checkPipelineParams(requiredPipelineParams)) {
            updatePipelineParams(requiredPipelineParams)
        } else {
            error 'Injecting pipeline parameters failed. Fix pipeline setting file then run again.'
        }
}


node('master') {
    CF = new org.alx.commonFunctions() as Object
    wrap([$class: 'TimestamperBuildWrapper']) {

        String settingsRelativePath = String.format('%s/%s.yaml', SettingsRelativePathPrefix,
                applyReplaceAllItems(env.JOB_NAME.toString(), PipelineNameRegexReplace))
        Map pipelineSettings = loadPipelineSettings(SettingsGitUrl, DefaultSettingsGitBranch, settingsRelativePath)
        wrapperPipelineParametersProcessing(pipelineSettings, params, BuiltinPipelineParameters)

    }
}
