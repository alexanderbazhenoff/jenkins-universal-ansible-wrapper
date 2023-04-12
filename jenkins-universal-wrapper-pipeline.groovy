#!/usr/bin/env groovy


@NonCPS
@Grab(group='org.yaml', module='snakeyaml', version='1.5')
import org.yaml.snakeyaml.*


// TODO: change branch
@Library('jenkins-shared-library-alx@devel') _


// Repo URL and a branch of 'universal-wrapper-pipeline-settings' to load current pipeline settings from, e.g:
// 'git@github.com:alexanderbazhenoff/ansible-wrapper-settings.git'. Will be ignored when SETTINGS_GIT_BRANCH pipeline
// parameter present and not blank.
final SettingsGitUrl = 'https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings' as String
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

// Set your ansible installation name from jenkins settings.
final AnsibleInstallationName = 'home_local_bin_ansible' as String

// Jenkins node pipeline parameter name that specifies a name of jenkins node to execute on.
final JenkinsNodeNamePipelineParameter = 'NODE_NAME' as String

// Jenkins node tag pipeline parameter name that specifies a tag of jenkins node to execute on.
final JenkinsNodeTagPipelineParameterName = 'NODE_TAG' as String

// Built-in pipeline parameters, which are mandatory and not present in 'universal-wrapper-pipeline-settings'.
final BuiltinPipelineParameters = [
        [name       : 'SETTINGS_GIT_BRANCH',
         type       : 'string',
         regex      : '(\\*)? +(.*?) +(.*?)? ((\\[(.*?)(: (.*?) (\\d+))?\\])? ?(.*$))?',
         description: 'Git branch of ansible-wrapper-settings project (to override defaults on development).'],
        [name       : JenkinsNodeNamePipelineParameter,
         type       : 'string',
         description: 'Jenkins node name to run.'],
        [name       : JenkinsNodeTagPipelineParameterName,
         type       : 'string',
         default    : 'ansible210',
         description: 'Jenkins node tag to run.'],
        [name       : 'DRY_RUN',
         type       : 'boolean',
         description: String.format('%s (%s).', 'Dry run mode to use for pipeline settings troubleshooting',
                 'will be ignored on pipeline parameters needs to be injected')],
        [name: 'DEBUG_MODE',
         type: 'boolean']
] as ArrayList


/**
 * Clone 'universal-wrapper-pipeline-settings' from git repository, load yaml pipeline settings and return them as a
 * map.
 *
 * @param settingsGitUrl - git repo URL to clone from.
 * @param settingsGitBranch - git branch.
 * @param settingsRelativePath - relative path inside the 'universal-wrapper-pipeline-settings' project.
 * @param printYaml - if true output 'universal-wrapper-pipeline-settings' content on a load.
 * @param workspaceSubfolder - subfolder in jenkins workspace where the git project will be cloned.
 * @return - map with pipeline settings.
 */
Map loadPipelineSettings(String settingsGitUrl, String settingsGitBranch, String settingsRelativePath,
                         Boolean printYaml = true, String workspaceSubfolder = 'settings') {
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
static applyReplaceRegexItems(String text, ArrayList regexItemsList, ArrayList replaceItemsList = []) {
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
 *
 *                                 More info: https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings
 * @return - true when jenkins pipeline parameters update required.
 */
static verifyPipelineParamsArePresents(ArrayList requiredParams, Object currentPipelineParams) {
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
    String defaultString = item.containsKey('default') ? item.default.toString() : ''
    String description = item.containsKey('description') ? item.description : ''
    if (item.type == 'choice' || (item.containsKey('choices') && item.choices instanceof ArrayList))
        param += [choice(name: item.name, choices: item.choices.each { it.toString() }, description: description)]
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

/**
 * Format and print error of pipeline settings item check.
 *
 * @param eventNum - event number to output: 3 is an ERROR, 2 is a WARNING.
 * @param itemName - pipeline settings item name to output.
 * @param errorMsg - details of error to output.
 * @return - 'false' as a failed pipeline check item state.
 */
Boolean pipelineSettingsItemError(Integer eventNum, String itemName, String errorMsg) {
    CF.outMsg(eventNum, String.format("Syntax %s in pipeline parameter '%s': %s.", eventNum == 3 ? 'ERROR' : 'WARNING',
            itemName, errorMsg))
    return false
}

/**
 * Check environment variable name match POSIX shell standards.
 *
 * @param name - variable name to check regex match.
 * @return - true when match.
 */
static checkEnvironmentVariableNameCorrect(String name) {
    return name.matches('[a-zA-Z_]+[a-zA-Z0-9_]*')
}

/**
 * Check pipeline parameters in pipeline settings item for correct keys structure, types and values.
 *
 * @param item - pipeline settings item to check.
 * @return - list of: corrected map item (when fix is possible),
 *                    pipeline parameters item check status (true when ok).
 */
ArrayList pipelineParametersSettingsItemCheck(Map item) {
    Boolean checkOk = true

    // Check 'name' key is present and valid
    if (item.containsKey('name')) {
        if (!checkEnvironmentVariableNameCorrect(item.name.toString()))
            checkOk = pipelineSettingsItemError(3, item as String, "Invalid parameter name")
    } else {
        checkOk = pipelineSettingsItemError(3, item as String, "'name' key is required, but undefined")
        item.name = "''"
    }

    // Check 'assign' sub-key in 'on_empty' key is correct (if defined).
    if (item.get('on_empty') && item.on_empty.get('assign') && item.on_empty.assign.startsWith('$') &&
            !checkEnvironmentVariableNameCorrect(item.on_empty.assign.toString().replaceAll('\$', '')))
        checkOk = pipelineSettingsItemError(3, item as String, String.format("%s: '%s'",
                'Unable to assign due to incorrect variable name', item.on_empty.assign))

    if (item.containsKey('type')) {

        // Convert 'default' value to string (e.g. it's ok when default value in yaml file as number or float).
        if (item.type == 'string' && item.containsKey('default'))
            item.default = item.default.toString()

        // Check 'type' value with other keys data type mismatch.
        String msg = ''
        msg = (item.type == 'boolean' && item.containsKey('default') && !(item.default instanceof Boolean)) ?
                String.format("'type' set as boolean while 'default' key is not. It's %s",
                        item.default.getClass().toString().tokenize('.').last().toLowerCase()) : msg
        msg = (item.type == 'choice' && !item.containsKey('choices')) ?
                "'type' set as choice while no 'choices' list defined" : msg
        checkOk = msg.trim() ? pipelineSettingsItemError(3, item.name as String, msg) : checkOk
    } else {

        // Try to detect 'type' when not defined.
        ArrayList autodetectData = []
        autodetectData = item.containsKey('default') && item.default instanceof Boolean ?
                ['default', 'boolean'] : autodetectData
        autodetectData = item.containsKey('choices') && item.choices instanceof ArrayList ?
                ['choices', 'choice'] : autodetectData
        autodetectData = item.containsKey('action') && item.action ? ['action', 'choice'] : autodetectData

        // Output reason and set 'type' key when autodetect is possible, otherwise print an error message.
        if (autodetectData) {
            Boolean __ = pipelineSettingsItemError(2, item.name as String, String.format("%s by '%s' key: %s",
                    "'type' key not defined, but was detected and set", autodetectData[0], autodetectData[1]))
            item.type = autodetectData[1]
        } else {
            String msg = item.containsKey('default') && (item.default instanceof String || item.default instanceof
                    Integer || item.default instanceof Float || item.default instanceof BigInteger) ?
                    ". Probably 'type' is password or string, but for security reasons autodetect is not possible" : ''
            checkOk = pipelineSettingsItemError(3, item.name as String, String.format('%s%s',
                    "'type' is required, but undefined", msg))
        }
    }

    // Check 'action' was set for choice or string parameter types.
    Boolean actionKeyEnabled = item.containsKey('action') && item.action
    Boolean actionKeyIsForChoices = item.containsKey('choices') && item.choices instanceof ArrayList
    Boolean actionKeyIsForString = item.containsKey('type') && item.type == 'string'
    if (actionKeyEnabled && !actionKeyIsForChoices && !actionKeyIsForString)
        checkOk = pipelineSettingsItemError(3, item.name as String, "'action' is for 'string' or 'choices' types")

    // Check 'action' set for 'type: choices' with a list of choices.
    if (actionKeyEnabled && actionKeyIsForChoices && !(item.choices instanceof ArrayList))
        checkOk = pipelineSettingsItemError(3, item.name as String,
                "'action' is 'True' while 'choices' key value is not a list of choices")

    // Check 'default' and 'choices' keys incompatibility.
    if (item.containsKey('default') && item.containsKey('choices') && item.choices instanceof ArrayList)
        checkOk = pipelineSettingsItemError(3, item.name as String,
                "'default' key is not required for type choice")

    return [item, checkOk]
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

/**
 * Check pipeline parameters format as an ArrayList of pipeline settings key: map items for key structure, types and
 * values.
 *
 * @param parameters - all pipeline parameters to check.
 * @return - list of: corrected parameters ArrayList (when fix is possible),
 *                    the whole pipeline parameters check status (true when ok).
 */
ArrayList checkPipelineParamsFormat(ArrayList parameters) {
    Boolean allPass = true
    ArrayList correctedParams = []
    parameters.each {
        def (Map correctedItem, Boolean itemCheckPass) = pipelineParametersSettingsItemCheck(it as Map)
        allPass = itemCheckPass ? allPass : false
        correctedParams += correctedItem
    }
    return [correctedParams, allPass]
}

/**
 * Get pipeline parameter name from pipeline parameter config item and check this pipeline parameter emptiness state
 * (defined or empty).
 *
 * @param paramItem - pipeline parameter item map (which is a part of parameter settings) to get parameter name.
 * @param pipelineParameters - pipeline parameters for current job build (actually requires a pass of 'params' which is
 *                             class java.util.Collections$UnmodifiableMap).
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param isUndefined - set true to detect pipeline parameter for current job is undefined, or false to detect
 *                      parameter is undefined.
 * @return - list of: parameter name (or '<>' when name wasn't set),
 *                    return true when condition specified in 'isUndefined' method variable met.
 */
static getPipelineParamNameAndDefinedState(Map paramItem, Object pipelineParameters, Object envVariables,
                                           Boolean isUndefined = true) {
    return [paramItem.get('name') ? paramItem.name : '<>', (paramItem.get('name') && pipelineParameters
            .containsKey(paramItem.name) && isUndefined ^ (envVariables[paramItem.name as String]?.trim()).asBoolean())]
}

/**
 * Handle pipeline parameter assignment when 'on_empty' key defined in pipeline settings item.
 *
 * @param settingsItem - settings item from pipeline settings to handle.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @return - list of: true when pipeline parameter needs an assignment,
 *                    string or value of assigned environment variable (when value starts with $),
 *                    true when needs to count an error when pipeline parameter undefined and can't be assigned,
 *                    true when needs to warn when pipeline parameter undefined and can't be assigned.
 */
static handleAssignmentWhenPipelineParamIsUnset(Map settingsItem, Object envVariables) {
    if (!settingsItem.get('on_empty'))
        return [false, '', true, false]
    Boolean fail = settingsItem.on_empty.get('fail') ? settingsItem.on_empty.get('fail').asBoolean() : true
    Boolean warn = settingsItem.on_empty.get('warn').asBoolean()
    if (!settingsItem.on_empty.get('assign'))
        return [false, '', fail, warn]
    Boolean assignment = settingsItem.on_empty.toString().startsWith('$') ? envVariables[settingsItem.on_empty.assign
            .toString().replaceAll('\\$', '')] : settingsItem.on_empty.assign.toString()
    return [true, assignment, fail, warn]
}

/**
 * Checking that all required pipeline parameters was specified for current build.
 *
 * @param pipelineSettings - 'universal-wrapper-pipeline-settings' converted to map. See
 *                           https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings for details.
 * @param pipelineParameters - pipeline parameters for current job build (actually requires a pass of 'params' which is
 *                             class java.util.Collections$UnmodifiableMap).
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @return - true when all required variables are specified.
 */
Boolean checkAllRequiredPipelineParamsAreSet(Map pipelineSettings, Object pipelineParameters, Object envVariables) {
    Boolean allSet = true
    if (pipelineSettings.get('parameters') && pipelineSettings.parameters.get('required')) {
        CF.outMsg(1, 'Checking that all required pipeline parameters was specified for current build.')
        pipelineSettings.parameters.required.each {
            def (String printableParamName, Boolean paramIsUndefined) = getPipelineParamNameAndDefinedState(it as Map,
                    pipelineParameters, envVariables)
            if (paramIsUndefined) {
                String assignMessage = ''
                Boolean assignmentComplete = false
                def (Boolean paramNeedsToBeAssigned, String paramAssignment, Boolean fail, Boolean warn) =
                        handleAssignmentWhenPipelineParamIsUnset(it as Map, envVariables)
                if (paramNeedsToBeAssigned && printableParamName != '<>' && paramAssignment.trim()) {
                    env[it.name.toString()] = paramAssignment
                    assignmentComplete = true
                } else if (printableParamName == '<>' || (paramNeedsToBeAssigned && !paramAssignment.trim())) {
                    assignMessage = paramNeedsToBeAssigned ? String.format("(and can't be assigned with %s variable) ",
                            it.on_empty.get('assign').toString()) : ''
                }
                allSet = !assignmentComplete && fail ? false : allSet
                if (warn || (fail && !allSet))
                    CF.outMsg(fail ? 3 : 2, String.format("'%s' pipeline parameter is required, but undefined %s%s. %s",
                            printableParamName, assignMessage, 'for current job run',
                            'Please specify then re-build again.'))
            }
        }
    }
    return allSet
}

/**
 * Extract parameters arrayList from pipeline settings map (without 'required' and 'optional' map structure).
 *
 * @param pipelineSettings - pipeline settings map.
 * @param builtinPipelineParameters - additional built-in pipeline parameters arrayList.
 * @return - pipeline parameters arrayList.
 */
static extractParamsListFromSettingsMap(Map pipelineSettings, ArrayList builtinPipelineParameters) {
    return (pipelineSettings.get('parameters')) ?
            (pipelineSettings.parameters.get('required') ? pipelineSettings.parameters.required : []) +
            (pipelineSettings.parameters.get('optional') ? pipelineSettings.parameters.optional : []) +
            builtinPipelineParameters : []
}

/**
 * Regex check of current job build pipeline parameters.
 * (Check match when current build pipeline parameter is not empty and a key 'regex' is defined in pipeline settings).
 *
 * @param pipelineSettings - pipeline settings map.
 * @param pipelineParameters - pipeline parameters for current job build (actually requires a pass of 'params' which is
 *                             class java.util.Collections$UnmodifiableMap).
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param builtinPipelineParameters - additional built-in pipeline parameters arrayList.
 * @return - true when all pass.
 */
Boolean regexCheckAllRequiredPipelineParams(Map pipelineSettings, Object pipelineParameters, Object envVariables,
                                            ArrayList builtinPipelineParameters) {
    Boolean allCorrect = true
    ArrayList requiredPipelineParams = extractParamsListFromSettingsMap(pipelineSettings, builtinPipelineParameters)
    if (requiredPipelineParams[0]) {
        requiredPipelineParams.each {
            def (String printableParamName, Boolean paramIsDefined) = getPipelineParamNameAndDefinedState(it as Map,
                    pipelineParameters, envVariables, false)

            // If regex was set, preform string concatenation for regex list items. Otherwise, regex value is string.
            if (it.get('regex')) {
                String regexPattern = ''
                if (it.regex instanceof ArrayList && (it.regex as ArrayList)[0]) {
                    it.regex.each { i -> regexPattern += i.toString() }
                } else if (!(it.regex instanceof ArrayList) && it.regex?.trim()) {
                    regexPattern = it.regex.toString()
                }
                if (regexPattern.trim())
                    CF.outMsg(0, String.format('Found regex for pipeline parameter %s: %s', printableParamName,
                            regexPattern))
                if (paramIsDefined && regexPattern.trim() && !envVariables[it.name as String].matches(regexPattern)) {
                    allCorrect = false
                    CF.outMsg(3, String.format('%s parameter is incorrect due to regex missmatch.',
                            printableParamName))
                }
            }

        }
    }
    return allCorrect
}

/**
 * Processing wrapper pipeline parameters: check all parameters from pipeline settings are presents. If not inject
 * parameters to pipeline.
 *
 * @param pipelineSettings - 'universal-wrapper-pipeline-settings' converted to map. See
 *                           https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings for details.
 * @param currentPipelineParams - pipeline parameters for current job build (actually requires a pass of 'params'
 *                                which is class java.util.Collections$UnmodifiableMap).
 * @param builtinPipelineParameters - built-in pipeline parameters in the same format as pipelineSettings, e.g:
 *                                    'SETTINGS_GIT_BRANCH', 'DEBUG_MODE', etc...
 * @return - list of: true when there is no pipeline parameters in the pipelineSettings,
 *                    true when pipeline parameters processing pass.
 */
ArrayList wrapperPipelineParametersProcessing(Map pipelineSettings, Object currentPipelineParams,
                                              ArrayList builtinPipelineParameters = []) {
    Boolean noPipelineParams = true
    Boolean allPass = true
    Boolean checkPipelineParametersPass
    ArrayList requiredPipelineParams = extractParamsListFromSettingsMap(pipelineSettings, builtinPipelineParameters)
    if (requiredPipelineParams[0]) {
        noPipelineParams = false
        CF.outMsg(1, 'Checking that current pipeline parameters are the same with pipeline settings.')
        if (verifyPipelineParamsArePresents(requiredPipelineParams, currentPipelineParams)) {
            CF.outMsg(1, 'Current pipeline parameters requires an update from pipeline settings.')
            (requiredPipelineParams, checkPipelineParametersPass) =
                    checkPipelineParamsFormat(requiredPipelineParams)
            if (checkPipelineParametersPass) {
                CF.outMsg(1, 'Updating current pipeline parameters.')
                updatePipelineParams(requiredPipelineParams)
            } else {
                allPass = false
            }
        }
    }
    return [noPipelineParams, allPass]
}

/**
 * Get jenkins node by node name or node tag defined in pipeline parameter(s).
 *
 * @param env - environment variables for current job build (actually requires a pass of 'env' which is
 *              class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param nodeParamName - Jenkins node pipeline parameter name that specifies a name of jenkins node to execute on. This
 *                        pipeline parameters will be used to check for jenkins node name on pipeline start. If this
 *                        parameter undefined or blank nodeTagParamName will be used to check.
 * @param nodeTagParamName - Jenkins node tag pipeline parameter name that specifies a tag of jenkins node to execute
 *                           on. This parameter will be used to check for jenkins node selection by tag on pipeline
 *                           start. If this parameter defined nodeParamName will be ignored.
 * @return - null when nodeParamName or nodeTagParamName parameters found. In this case pipeline starts on any jenkins
 *           node. Otherwise, return 'node_name' or [label: 'node_tag'].
 */
static getJenkinsNodeToExecuteByNameOrTag(Object env, String nodeParamName, String nodeTagParamName) {
    def nodeToExecute = null
    if (env.getEnvironment().containsKey(nodeTagParamName) && env.getEnvironment().get(nodeTagParamName)?.trim()) {
        nodeToExecute = [label: env.getEnvironment().get(nodeTagParamName)]
    } else if (env.getEnvironment().containsKey(nodeParamName) && env.getEnvironment().get(nodeParamName)?.trim()) {
        nodeToExecute = env.getEnvironment().get(nodeParamName)
    }
    return nodeToExecute
}

// TODO: other functions is not for library (?)

/**
 * Check or execute wrapper pipeline from pipeline settings.
 *
 * @param pipelineSettings - the whole pipeline settings map (pre-converted from yaml) to check and/or execute.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param check - true to check pipeline settings structure and parameters.
 * @param execute - true to execute pipeline wrapper stages defined in the config, false for dry run.
 * @return - list of: pipeline stages status map (the structure of this map should be: key is the name with spaces cut,
 *                    value should be a map of: [name: name, state: state, url: url]);
 *                    true when checking and execution pass.
 */
ArrayList checkOrExecutePipelineWrapperFromSettings(Map pipelineSettings, Object envVariables, Boolean check = false,
                                                    Boolean execute = true) {
    Map stagesStates = [:]
    Boolean allPass = true
    if (!pipelineSettings.get('stages') && ((check && denvVariables.getEnvironment().get('DEBUG_MODE').asBoolean()) ||
            execute))
        CF.outMsg(execute ? 3 : 0, String.format('No stages to %s in pipeline config.', execute ? 'execute' : 'check'))
    for (stage in pipelineSettings.stages) {
        Boolean checkOk = (check) ? checkStageSettingsItem(stage.toString(), pipelineSettings, envVariables)
                : true
        def (Map currentStageActionsStates, Boolean execOk) = (execute) ? executeStageSettingsItem(stage.toString(),
                pipelineSettings, envVariables) : true
        allPass = checkOk && execOk
        stagesStates = stagesStates + currentStageActionsStates
    }
    return [stagesStates, allPass]
}

/**
 * Check stage item from pipeline settings defined properly.
 *
 * (Check actions in the stage, all ansible playbooks, ansible inventories, jobs, scripts or another action according to
 * requirements described here: https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings)
 *
 * @param stageName - stage settings name to check actions in it.
 * @param pipelineSettings - the whole pipeline settings map (pre-converted from yaml) to check and/or execute.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl). Set 'DEBUG_MODE' environment variable
 *                       (or pipeline parameter) as an element of envVariables for debug mode.
 * @return - true when check pass.
 */
Boolean checkStageSettingsItem(String stageName, Map pipelineSettings, Object envVariables) {
    Boolean allPass = true
    pipelineSettings.stages.get(stageName).eachWithIndex { item, index ->
        CF.outMsg(0, String.format('Checking action no. %s from %s stage', index.toString(), entry))
        def (__, Boolean checkOk) = checkOrExecutePipelineActionItem(item as Map, pipelineSettings, envVariables)
        allPass = checkOk ? allPass : false
    }
    return allPass
}

/**
 * Execute all actions in pipeline stage settings item.
 *
 * @param stageName - stage settings name to check actions in it.
 * @param pipelineSettings - the whole pipeline settings map (pre-converted from yaml) to check and/or execute.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl). Set 'DRY_RUN' environment variable
 *                       (or pipeline parameter) as an element of envVariables to true for dry run mode. Set
 *                       'DEBUG_MODE' for debug mode.
 * @param dryRun - true when dry run mode.
 * @return - arrayList of: all actions in the stage status map (the structure of this map should be: key is the name
 *                         with spaces cut, value should be a map of: [name: name, state: state, url: url]);
 *                         true when all stage actions execution successfully done.
 */
ArrayList executeStageSettingsItem(String stageName, Map pipelineSettings, Object envVariables) {
    Map actionsStates = [:]
    Boolean allPass = true
    pipelineSettings.stages.get(stageName).eachWithIndex { item, index ->
        Boolean checkOk
        (actionsStates, checkOk) = checkOrExecutePipelineActionItem(item as Map, pipelineSettings, envVariables)
        allPass = checkOk ? allPass : false
    }
    return [actionsStates, allPass]
}

/**
 * Check (all set and linked properly) or execute action item from stage.
 *
 * @param actionItem - action item to check or execute.
 * @param pipelineSettings - the whole pipeline settings map (pre-converted from yaml) to check and/or execute.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl). Set 'DRY_RUN' environment variable
 *                       (or pipeline parameter) as an element of envVariables to true for dry run mode on execution.
 *                       Set 'DEBUG_MODE' to enable debug mode both for 'check' or 'execute'.
 * @return - arrayList of: all actions in the stage status map (the structure of this map should be: key is the name
 *                         with spaces cut, value should be a map of: [name: name, state: state, url: url]);
 *                         true when all stage actions execution successfully done.
 */
Boolean checkOrExecutePipelineActionItem(Map actionItem, Map pipelineSettings, Object envVariables) {

}


def jenkinsNodeToExecute = getJenkinsNodeToExecuteByNameOrTag(env, JenkinsNodeNamePipelineParameter,
        JenkinsNodeTagPipelineParameterName)
node(jenkinsNodeToExecute) {
    CF = new org.alx.commonFunctions() as Object
    wrap([$class: 'TimestamperBuildWrapper']) {

        // Load all pipeline settings then check all current pipeline params are equal to params in pipeline settings.
        String settingsRelativePath = String.format('%s/%s.yaml', SettingsRelativePathPrefix,
                applyReplaceRegexItems(env.JOB_NAME.toString(), PipelineNameRegexReplace))
        Map pipelineSettings = loadPipelineSettings(SettingsGitUrl, DefaultSettingsGitBranch, settingsRelativePath,
                (params.get('DEBUG_MODE')) as Boolean)
        String pipelineFailedReasonText = ''
        def (Boolean noPipelineParamsInTheConfig, Boolean pipelineParametersProcessingPass) =
                wrapperPipelineParametersProcessing(pipelineSettings, params, BuiltinPipelineParameters)

        // Check all required pipeline parameters was defined properly for current build.
        if (noPipelineParamsInTheConfig) {
            if (pipelineParametersProcessingPass) CF.outMsg(1, 'No pipeline parameters in the config')
        } else {
            pipelineFailedReasonText += (checkAllRequiredPipelineParamsAreSet(pipelineSettings, params, env) &&
                    regexCheckAllRequiredPipelineParams(pipelineSettings, params, env, BuiltinPipelineParameters)) ?
                    '' : 'Required pipeline parameter(s) was not specified or incorrect.'
        }

        // Check other pipeline settings (stages, playbooks, scripts, inventories, etc) are correct.
        def (Boolean pipelineSettingsCheckOk, __) = checkOrExecutePipelineWrapperFromSettings(pipelineSettings,
                env, true, false)
        pipelineFailedReasonText += pipelineSettingsCheckOk ? '' : 'Pipeline settings contains an error(s).'

        // Interrupt when settings error was found or required pipeline parameters wasn't set, otherwise execute it.
        pipelineFailedReasonText += (!pipelineParametersProcessingPass) ? '\nError(s) in pipeline yaml settings.' : ''
        if (pipelineFailedReasonText.trim())
            error String.format('%s\n%s.', pipelineFailedReasonText, 'Please fix then re-build')

        // Execute wrapper pipeline settings stages.
        def (Boolean allDone, Map pipelineStagesStates) = (checkOrExecutePipelineWrapperFromSettings(pipelineSettings,
                env, false))
    }
}
