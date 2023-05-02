#!/usr/bin/env groovy


@NonCPS
@Grab(group = 'org.yaml', module = 'snakeyaml', version = '1.5')
import org.yaml.snakeyaml.*

import java.util.regex.Pattern


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

// TODO: replace action parameters key for variables in stages handling
// TODO: redo stages parsing
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
    regexItemsList.eachWithIndex { value, Integer index ->
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
 * @return - ArrayList of: true when jenkins pipeline parameters update required;
 *                         true when no errors.
 */
ArrayList verifyPipelineParamsArePresents(ArrayList requiredParams, Object currentPipelineParams) {
    Boolean updateParamsRequired = false
    Boolean verifyPipelineParamsOk = true
    String ignoreMsg = 'Skipping parameter from pipeline settings'
    String keyValueIncorrectMsg = 'key for pipeline parameter is undefined or incorrect value specified'
    requiredParams.each {
        Boolean paramNameConvertibleToString = detectIsObjectConvertibleToString(it.get('name'))
        Boolean paramNamingCorrect = checkEnvironmentVariableNameCorrect(it.get('name'))
        if (!it.get('type') && !detectPipelineParameterItemIsProbablyChoice(it as Map) &&
                !detectPipelineParameterItemIsProbablyBoolean(it as Map)) {
            CF.outMsg(3, String.format("%s: '%s' %s.", 'Parameter from pipeline settings might be ignored', 'type',
                    keyValueIncorrectMsg))
            verifyPipelineParamsOk = false
        }
        if (!it.get('name') || !paramNameConvertibleToString || !paramNamingCorrect) {
            verifyPipelineParamsOk = false
            String namingErrorReasonMsg = paramNameConvertibleToString && !paramNamingCorrect ?
                    " (parameter name didn't met POSIX standards)." : '.'
            CF.outMsg(3, String.format("%s: '%s' %s%s", ignoreMsg, 'name', keyValueIncorrectMsg, namingErrorReasonMsg))
        } else if (it.get('name') && !currentPipelineParams.containsKey(it.get('name'))) {
            updateParamsRequired = true
        }
    }
    return [updateParamsRequired, verifyPipelineParamsOk]
}

/**
 * Detect is pipeline parameter item probably 'choice' type.
 *
 * @param paramItem - pipeline parameter item to detect.
 * @return - true when 'choice'.
 */
static detectPipelineParameterItemIsProbablyChoice(Map paramItem) {
    return paramItem.containsKey('choices') && paramItem.get('choices') instanceof ArrayList
}

/**
 * Detect is pipeline parameter item probably 'boolean' type.
 *
 * @param paramItem - pipeline parameter item to detect.
 * @return - true when 'boolean'.
 */
static detectPipelineParameterItemIsProbablyBoolean(Map paramItem) {
    return paramItem.containsKey('default') && paramItem.get('default') instanceof Boolean
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
    if (item.get('name') && detectIsObjectConvertibleToString(item.get('name')) &&
            checkEnvironmentVariableNameCorrect(item.get('name'))) {
        if (item.get('type') == 'choice' || detectPipelineParameterItemIsProbablyChoice(item))
            param += [choice(name: item.name, choices: item.choices.each { it.toString() }, description: description)]
        if (item.get('type') == 'boolean' || detectPipelineParameterItemIsProbablyBoolean(item))
            param += [booleanParam(name: item.name, description: description,
                    defaultValue: item.containsKey('default') ? item.default.toBoolean() : false)]
        if (item.get('type') == 'password')
            param += [password(name: item.name, defaultValue: defaultString, description: description)]
        if (item.get('type') == 'text')
            param += [text(name: item.name, defaultValue: defaultString, description: description)]
        if (item.get('type') == 'string')
            param += [string(name: item.name, defaultValue: defaultString, description: description,
                    trim: item.containsKey('trim') ? item.trim : false)]
    }
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
    CF.outMsg(eventNum, String.format("Wrong syntax in pipeline parameter '%s': %s.", itemName, errorMsg))
    return false
}

/**
 * Check environment variable name match POSIX shell standards.
 *
 * @param name - variable name to check regex match.
 * @return - true when match.
 */
static checkEnvironmentVariableNameCorrect(Object name) {
        return detectIsObjectConvertibleToString(name) && name.toString().matches('[a-zA-Z_]+[a-zA-Z0-9_]*')
}

/**
 * Detect if an object will be human readable string after converting to string (exclude lists, maps, etc).
 *
 * @param obj - object to detect.
 * @return - true when object is convertible to human readable string.
 */
static detectIsObjectConvertibleToString(Object obj) {
    return (obj instanceof String || obj instanceof Integer || obj instanceof Float || obj instanceof BigInteger)
}

/**
 * Detect if an object will be correct after conversion to boolean.
 *
 * @param obj - object to detect.
 * @return - ttue when object will be correct.
 */
static detectIsObjectConvertibleToBoolean(Object obj) {
    return (obj?.toBoolean()).toString() == obj?.toString()
}

/**
 * Check pipeline parameters in pipeline settings item for correct keys structure, types and values.
 *
 * @param item - pipeline settings item to check.
 * @return - pipeline parameters item check status (true when ok).
 */
Boolean pipelineParametersSettingsItemCheck(Map item) {
    Boolean checkOk = true

    // Check 'name' key is present and valid.
    if (item.containsKey('name') && !checkEnvironmentVariableNameCorrect(item.get('name'))) {
        checkOk = pipelineSettingsItemError(3, item as String, "Invalid parameter name")
    } else if (!item.containsKey('name')) {
        checkOk = pipelineSettingsItemError(3, item as String, "'name' key is required, but undefined")
    }
    String printableParameterName = item.get('name') && detectIsObjectConvertibleToString(item.name) ?
            item.name.toString() : '<undefined>'

    // When 'assign' sub-key is defined inside 'on_empty' key, checking it's correct.
    if (item.get('on_empty') && item.on_empty.get('assign') instanceof String && item.on_empty.assign.startsWith('$') &&
            !checkEnvironmentVariableNameCorrect(item.on_empty.assign.toString().replaceAll('[\${}]', '')))
        checkOk = pipelineSettingsItemError(3, printableParameterName, String.format("%s: '%s'",
                'Unable to assign due to incorrect variable name', item.on_empty.assign))

    if (item.containsKey('type')) {

        // Check 'type' value with other keys data type mismatch.
        String msg = item.type == 'choice' && !item.containsKey('choices') ?
                "'type' set as choice while no 'choices' list defined" : ''
        if (item.type == 'boolean' && item.containsKey('default') && !(item.default instanceof Boolean))
            msg = String.format("'type' set as boolean while 'default' key is not. It's %s%s",
                    item.get('default').getClass().toString().tokenize('.').last().toLowerCase(),
                    detectPipelineParameterItemIsProbablyBoolean(item) ? ", but it's convertible to boolean" : '')
        checkOk = msg.trim() ? pipelineSettingsItemError(3, printableParameterName, msg) : checkOk
    } else {

        // Try to detect 'type' when not defined. detectPipelineParameterItemIsProbablyBoolean(
        ArrayList autodetectData = []
        autodetectData = detectPipelineParameterItemIsProbablyBoolean(item) ? ['default', 'boolean'] : autodetectData
        autodetectData = detectPipelineParameterItemIsProbablyChoice(item) ? ['choices', 'choice'] : autodetectData

        // Output reason and 'type' key when autodetect is possible.
        if (autodetectData) {
            checkOk = pipelineSettingsItemError(3, printableParameterName, String.format("%s by '%s' key: %s",
                    "'type' key is not defined, but was detected", autodetectData[0], autodetectData[1]))
        } else {
            String msg = item.containsKey('default') && detectIsObjectConvertibleToString(item.default) ?
                    ". Probably 'type' is password, string or text" : ''
            checkOk = pipelineSettingsItemError(3, printableParameterName, String.format('%s%s',
                    "'type' is required, but wasn't defined", msg))
        }
    }

    // Check 'default' and 'choices' keys incompatibility and 'choices' value.
    if (item.containsKey('choices')) {
        checkOk = item.containsKey('default') ? pipelineSettingsItemError(3, printableParameterName,
                "'default' and 'choices' keys are incompatible") : checkOk
        checkOk = !(item.get('choices') instanceof ArrayList) ? pipelineSettingsItemError(3, printableParameterName,
                "'choices' value is not a list of items") : checkOk
    }
    return checkOk
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
 * @param finishWithFail - when true finish with success parameters injection. Otherwise, with fail.
 * @param currentPipelineParams - pipeline parameters for current job build (actually requires a pass of 'params'
 *                                which is class java.util.Collections$UnmodifiableMap). When
 *                                currentPipelineParams.DRY_RUN is 'true' pipeline parameters update won't be performed.
 */
def updatePipelineParams(ArrayList requiredParams, Boolean finishWithSuccess, Object currentPipelineParams) {
    ArrayList newPipelineParams = []
    Boolean dryRun = currentPipelineParams.get('DRY_RUN').asBoolean()
    currentBuild.displayName = String.format('pipeline_parameters_update--#%s%s', env.BUILD_NUMBER,
            dryRun ? '-dry_run' : '')
    requiredParams.each { newPipelineParams += pipelineSettingsItemToPipelineParam(it as Map) }
    if (!dryRun)
        properties([parameters(newPipelineParams)])
    if (finishWithSuccess) {
        ArrayList msgArgs = dryRun ? ["n't", 'Disable dry-run mode'] : [' successfully',
                                                                        "Select 'Build with parameters'"]
        CF.outMsg(2, String.format('Pipeline parameters was%s injected. %s and run again.', msgArgs[0], msgArgs[1]))
        CF.interruptPipelineOk(3)
    } else {
        error 'Pipeline parameters injection failed. Check pipeline config and run again.'
    }
}

/**
 * Check pipeline parameters format as an ArrayList of pipeline settings key: map items for key structure, types and
 * values.
 *
 * @param parameters - all pipeline parameters to check.
 * @return - the whole pipeline parameters check status (true when ok).
 */
Boolean checkPipelineParamsFormat(ArrayList parameters) {
    Boolean allPass = true
    parameters.each {
        allPass = pipelineParametersSettingsItemCheck(it as Map) ? allPass : false
    }
    return allPass
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
 * @param isUndefined - condition to check state: set true to detect pipeline parameter for current job is undefined, or
 *                      false to detect parameter is defined.
 * @return - list of: printable parameter name (or '<undefined>' when name wasn't set),
 *                    return true when condition specified in 'isUndefined' method variable met.
 */
static getPipelineParamNameAndDefinedState(Map paramItem, Object pipelineParameters, Object envVariables,
                                           Boolean isUndefined = true) {
    return [paramItem.get('name') ? paramItem.name : '<undefined>', (paramItem.get('name') && pipelineParameters
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
    Boolean assignment = settingsItem.on_empty.toString().matches('\\$[^{].+') ? envVariables[settingsItem.on_empty
            .assign.toString().replaceAll('\\$', '')] : settingsItem.on_empty.assign.toString()
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
 * @return - ArrayList of: true when all required variables are specified;
 *                         changed or unchanged environment variables for current job build.
 */
Boolean checkAllRequiredPipelineParamsAreSet(Map pipelineSettings, Object pipelineParameters, Object envVariables) {
    Boolean allSet = true
    if (pipelineSettings.get('parameters') && pipelineSettings.get('parameters').get('required')) {
        CF.outMsg(1, 'Checking that all required pipeline parameters was specified for current build.')
        pipelineSettings.parameters.required.each {
            def (String printableParameterName, Boolean parameterIsUndefined) = getPipelineParamNameAndDefinedState(it
                    as Map, pipelineParameters, envVariables)
            if (parameterIsUndefined) {
                String assignMessage = ''
                Boolean assignmentComplete = false
                def (Boolean paramNeedsToBeAssigned, String parameterAssignment, Boolean fail, Boolean warn) =
                        handleAssignmentWhenPipelineParamIsUnset(it as Map, envVariables)
                if (paramNeedsToBeAssigned && printableParameterName != '<undefined>' && parameterAssignment.trim()) {
                    envVariables[it.name.toString()] = parameterAssignment
                    assignmentComplete = true
                } else if (printableParameterName == '<undefined>' || (paramNeedsToBeAssigned &&
                        !parameterAssignment.trim())) {
                    assignMessage = paramNeedsToBeAssigned ? String.format("(can't be assigned with '%s' variable) ",
                            it.on_empty.get('assign').toString()) : ''
                }
                allSet = !assignmentComplete && fail ? false : allSet
                if (warn || (fail && !allSet))
                    CF.outMsg(fail ? 3 : 2, String.format("'%s' pipeline parameter is required, but undefined %s%s. %s",
                            printableParameterName, assignMessage, 'for current job run',
                            'Please specify then re-build again.'))
            }
        }
    }
    return [allSet, envVariables]
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
            (pipelineSettings.parameters.get('required') ? pipelineSettings.parameters.get('required') : []) +
                    (pipelineSettings.parameters.get('optional') ? pipelineSettings.parameters.get('optional') : []) +
                    builtinPipelineParameters : []
}

/**
 * Perform regex check and regex replacement of current job build pipeline parameters.
 *
 * (Check match when current build pipeline parameter is not empty and a key 'regex' is defined in pipeline settings.
 * Also perform regex replacement of parameter value when 'regex_replace' key is defined).
 *
 * @param pipelineSettings - ArrayList of pipeline parameters from settings.
 * @param pipelineParameters - pipeline parameters for current job build (actually requires a pass of 'params' which is
 *                             class java.util.Collections$UnmodifiableMap).
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param builtinPipelineParameters - additional built-in pipeline parameters arrayList.
 * @return - ArrayList of: true when all pass;
 *                         changed or unchanged environment variables for current job build.
 */
Boolean regexCheckAllRequiredPipelineParams(ArrayList allPipelineParams, Object pipelineParameters,
                                            Object envVariables) {
    Boolean allCorrect = true
    if (allPipelineParams[0]) {
        allPipelineParams.each {
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
                    CF.outMsg(0, String.format("Found '%s' regex for pipeline parameter '%s'.", regexPattern,
                            printableParamName))
                if (paramIsDefined && regexPattern.trim() && !envVariables[it.name as String].matches(regexPattern)) {
                    allCorrect = false
                    CF.outMsg(3, String.format('%s parameter is incorrect due to regex missmatch.', printableParamName))
                }
            }

            // Perform regex replacement when regex_replace was set and pipeline parameter is defined for current build.
            if (it.get('regex_replace')) {
                Boolean regexReplacementDone = false
                String msgTemplateNoValue =
                        "'%s' sub-key value of 'regex_replace' wasn't defined for '%s' pipeline parameter.%s"
                String msgTemplateWrongType =
                        "Wrong type of '%s' value sub-key of 'regex_replace' for '%s' pipeline parameter.%s"
                String msgRecommendation = ' Please fix them. Otherwise, replacement will be skipped with an error.'

                // Handle 'to' sub-key of 'regex_replace' parameter item key.
                String regexReplacement = it.regex_replace.get('to')
                Boolean regexToKeyIsConvertibleToString = detectIsObjectConvertibleToString(it.regex_replace.get('to'))
                if (regexReplacement?.trim() && !regexToKeyIsConvertibleToString) {
                    CF.outMsg(3, String.format(msgTemplateWrongType, 'to', printableParamName, msgRecommendation))
                }

                // Handle 'regex' sub-key of 'regex_replace' parameter item key.
                String regexPattern = it.regex_replace.get('regex')
                Boolean regexKeyIsConvertibleToString = detectIsObjectConvertibleToString(it.regex_replace.get('regex'))
                if (regexPattern?.trim() && regexKeyIsConvertibleToString) {
                    if (!regexReplacement?.trim()) {
                        CF.outMsg(0, String.format(msgTemplateNoValue, 'to', printableParamName,
                                'Regex match(es) will be removed.' ))
                        regexReplacement = ''
                    }
                    if (paramIsDefined && printableParamName != '<undefined>') {
                        CF.outMsg(0, String.format("Replacing '%s' regex to '%s' in '%s' pipeline parameter value...",
                                regexPattern, regexReplacement, printableParamName))
                        envVariables[it.name.toString()] = applyReplaceRegexItems(envVariables[it.name.toString()] as
                                String, [regexPattern], [regexReplacement])
                        regexReplacementDone = true
                    } else if (printableParamName == '<undefined>') {
                        CF.outMsg(3, String.format("Replace '%s' regex to '%s' is not possible: 'name' key is %s. %s.",
                                regexPattern, regexReplacement, 'not defined for pipeline parameter item.',
                                'Please fix pipeline config. Otherwise, replacement will be skipped with an error.'))
                    }
                } else if (regexPattern?.trim() && !regexKeyIsConvertibleToString) {
                    CF.outMsg(3, String.format(msgTemplateWrongType, 'regex', printableParamName, msgRecommendation))
                } else {
                    CF.outMsg(3, String.format(msgTemplateNoValue, 'regex', printableParamName, msgRecommendation))
                }
                allCorrect = regexReplacementDone ? allCorrect : false
            }

        }
    }
    return [allCorrect, envVariables]
}

/**
 * Processing wrapper pipeline parameters: check all parameters from pipeline settings are presents. If not inject
 * parameters to pipeline.
 *
 * @param pipelineSettings - pipeline parameters in 'universal-wrapper-pipeline-settings' standard and built-in pipeline
 *                           parameters (e.g. 'DEBUG_MODE', etc) converted to ArrayList.
 *                           See https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings for details.
 * @param currentPipelineParams - pipeline parameters for current job build (actually requires a pass of 'params'
 *                                which is class java.util.Collections$UnmodifiableMap). Set
 *                                currentPipelineParams.DRY_RUN to 'true' for dry-run mode.
 * @return - list of: true when there is no pipeline parameters in the pipelineSettings,
 *                    true when pipeline parameters processing pass.
 */
ArrayList wrapperPipelineParametersProcessing(ArrayList pipelineParams, Object currentPipelineParams) {
    Boolean noPipelineParams = true
    Boolean allPass = true
    if (pipelineParams[0]) {
        noPipelineParams = false
        Boolean updateParamsRequired
        CF.outMsg(1, 'Checking that current pipeline parameters are the same with pipeline settings...')
        (updateParamsRequired, allPass) = verifyPipelineParamsArePresents(pipelineParams, currentPipelineParams)
        if (currentPipelineParams.get('UPDATE_PARAMETERS') || updateParamsRequired) {
            CF.outMsg(1, String.format('Current pipeline parameters requires an update from settings. Updating%s',
                    currentPipelineParams.get('DRY_RUN').asBoolean() ? ' will be skipped in dry-run mode.' : '...'))
            updatePipelineParams(pipelineParams, allPass, currentPipelineParams)
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
 * @param execute - true to execute pipeline wrapper stages defined in the config, false for dry run. Please note:
 *
 *        1. When 'check' is true pipeline settings will be checked, then if 'execute' is true pipeline settings will be
 *        executed. So you can set both 'check' and 'execute' to true, but it's not recommended: use separate function
 *        call to check settings first.
 *        2. You can also set envVariables.DEBUG_MODE to verbose output and/or envVariables.DRY_RUN to perform dry run.
 * @return - list of: pipeline stages status map (the structure of this map should be: key is the name with spaces cut,
 *                    value should be a map of: [name: name, state: state, url: url]);
 *                    true when checking and execution pass, false on checking or execution errors (or skipped);
 *                    return of environment variables ('env') that pass to function in 'envVariables'.
 */
ArrayList checkOrExecutePipelineWrapperFromSettings(Map pipelineSettings, Object envVariables, Boolean check = false,
                                                    Boolean execute = true) {
    Map stagesStates = [:]
    Boolean allPass = true
    if (!pipelineSettings.get('stages') && ((check && envVariables.getEnvironment().get('DEBUG_MODE').asBoolean()) ||
            execute))
        CF.outMsg(execute ? 3 : 0, String.format('No stages to %s in pipeline config.', execute ? 'execute' : 'check'))
    for (stageItem in pipelineSettings.stages) {
        Boolean checkOk
        (__, checkOk, envVariables) = check ? checkOrExecuteStageSettingsItem(stageItem as Map, pipelineSettings,
                envVariables, true) : [[:], false, envVariables]
        Map currentStageActionsStates
        Boolean execOk
        (currentStageActionsStates, execOk, envVariables) = execute ? checkOrExecuteStageSettingsItem(stageItem as Map,
                pipelineSettings, envVariables, false) : [[:], false, envVariables]
        allPass = checkOk && execOk
        stagesStates = stagesStates + currentStageActionsStates
    }
    return [stagesStates, allPass, envVariables]
}

/**
 * Check or execute all actions in pipeline stage settings item.
 *
 * (Check actions in the stage, all ansible playbooks, ansible inventories, jobs, scripts or another action according to
 * requirements described here: https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings).
 *
 * @param stageItem - stage settings item to check/execute actions in it.
 * @param pipelineSettings - the whole pipeline settings map (pre-converted from yaml) to check and/or execute.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl). Set 'DRY_RUN' environment variable
 *                       (or pipeline parameter) as an element of envVariables to true for dry run mode on
 *                       stage execute. Set 'DEBUG_MODE' to enable debug mode both for 'check' or 'execute'.
 * @param check - set false to execute action item, true to check.
 * @return - arrayList of: all actions in the stage status map (the structure of this map should be: key is the name
 *                         with spaces cut, value should be a map of: [name: stage name and action, state: state,
 *                         url: info and/or job url]);
 *                         true when all stage actions execution successfully done.
 */
// TODO: make a parallel option
// TODO: /// Continue format checking from here
// TODO: done the env pass inside other functions and return from this
ArrayList checkOrExecuteStageSettingsItem(Map stageItem, Map pipelineSettings, Object envVariables, Boolean check) {
    Map actionsRuns = [:]
    Boolean allPass = true

    // Handling 'name', 'actions' and 'parallel' stage keys.
    allPass = !stageItem.containsKey('name') || !detectIsObjectConvertibleToString(stageItem.get('name')) ?
            !configStructureErrorMsgWrapper(true, false, 3,
                    "Unable to convert stage name to a string, just undefined or empty.") : allPass
    Boolean actionsIsNotList = stageItem.containsKey('actions') && !(stageItem.get('actions') instanceof ArrayList)
    allPass = !stageItem.containsKey('actions') || actionsIsNotList ? !configStructureErrorMsgWrapper(true, false, 3,
                    'Actions are not defined for current stage or just empty.') : allPass
    allPass = stageItem.containsKey('parallel') && !detectIsObjectConvertibleToBoolean(stageItem.get('parallel')) ?
            !check ^ !configStructureErrorMsgWrapper(true, false, 3,
                    "Unable to determine 'parallel' value for current stage. Remove them or set as boolean.") : allPass
    String printableStageName = stageItem.get('name') ? stageItem.name.toString() : '<undefined>'

    // Creating map and processing items from 'actions' key.
    stageItem.get('actions').eachWithIndex { item, index ->
        actionsRuns[index] = {
            CF.outMsg(0, String.format("%s action number %s from '%s' stage", check ? 'Checking' : 'Executing',
                    index.toString(), stageItem.name))
            Map actionState = [:]
            Boolean checkOrExecuteOk
            (actionState, checkOrExecuteOk, envVariables) = checkOrExecutePipelineActionItemEmulate(printableStageName,
                    stageItem.get('actions')[index] as Map, pipelineSettings, index, envVariables, check)
            allPass = checkOrExecuteOk ? allPass : false
            println 'index: ' + index
            //actionsStates = actionsStates + actionStates
        }
    }
    if (stageItem.get('parallel')?.toBoolean()) {
        parallel actionsRuns
    } else {
        actionsRuns.each {
            println String.format('action value class: %s', it.value.getClass())
            it.value.call()
        }
    }
    return [[:], allPass, envVariables]
}

ArrayList checkOrExecutePipelineActionItemEmulate(String stageName, Map actionItem, Map pipelineSettings, Integer index,
                                                  Object envVariables, Boolean check) {
    CF.outMsg(1, String.format("%s action in stage '%s': %s", check ? 'Checking' : 'Executing', stageName,
            actionItem.toString()))
    Map actionState = [:]
    println 'index2: ' + index
    String actionMapIndex = String.format('%s_%s', stageName.replaceAll('<\\|>', ''), index.toString())
    println 'index3: ' + index
    actionState[actionMapIndex] = [name : stageName, state: true, jobUrl: actionItem.toString()]
    println 'index4: ' + index
    return [actionState, true, envVariables]
}

/**
 * Check all action items defined properly or execute action item from stage.
 *
 * @param stageName - the name of the current stage from which to test or execute the action item (just for logging
 *                    in all action status map - see @return of this function).
 * @param actionItem - action item to check or execute.
 * @param pipelineSettings - the whole pipeline settings map (pre-converted from yaml) to check and/or execute.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl). Set 'DRY_RUN' environment variable
 *                       (or pipeline parameter) as an element of envVariables to true for dry run mode on execution.
 *                       Set 'DEBUG_MODE' to enable debug mode both for 'check' or 'execute'.
 * @param check - set false to execute action item, true to check.
 * @return - arrayList of: all actions in the stage status map (the structure of this map should be: key is the name
 *                         with spaces cut, value should be a map of: [name: stage name and action, state: state,
 *                         url: info and/or job url]);
 *                         true when all stage actions execution successfully done.
 */
// TODO: /// Continue format checking from here
// TODO: done the env pass inside other functions and return from this
ArrayList checkOrExecutePipelineActionItem(String stageName, Map actionItem, Map pipelineSettings,
                                           Object envVariables, Boolean check) {
    Boolean actionStructureOk = true
    Boolean actionLinkOk = true
    String actionDescription = ''
    Map nodeItem = [:]
    String actionItemName = actionItem.get('name') ? actionItem.name : '<undefined>'
    String printableStageAndAction = String.format('%s | %s', stageName, actionItemName)
    String warningMsgTemplate = "'%s' key defined for current action, but it's empty. Remove this key or define value."
    CF.outMsg(0, String.format("%s '%s'...", check ? 'Checking' : 'Executing', printableStageAndAction))
    if (actionItem.get('action')) {

        // Check 'name' and 'node' keys correct.
        if (check && actionItem.find { it.key == 'name' }?.key && !actionItem.get('name'))
            CF.outMsg(2, CF.outMsg(String.format(warningMsgTemplate, 'name')))
        if (check && actionItem.find { it.key == 'node' }?.key && !actionItem.get('node')) {
            CF.outMsg(2, CF.outMsg(String.format(warningMsgTemplate, 'node')))
        } else if (check && actionItem.get('node')) {
            String keyWarnOrErrMsgTemplate = "Wrong format of node %skey '%s' for '%s' action. %s"

            // Check node keys and sub-keys defined properly.
            if (detectIsObjectConvertibleToString(actionItem.get('node'))) {
                nodeItem.node.name = actionItem.node.get('name')
            } else if (actionItem.get('node') instanceof Map) {
                nodeItem = actionItem.get('node') as Map

                // Check only one of node sub-keys 'name' or 'label' defined and it's correct.
                Boolean nodeNameOrLabelDefined = actionItem.node.get('name') ^ actionItem.node.get('label')
                if (!nodeNameOrLabelDefined)
                    configStructureErrorMsgWrapper(check, actionStructureOk, 2, String.format('%s %s',
                            "Node sub-keys 'name' and 'label' are incompatible.",
                            "Define only one of them, otherwise 'label' sub-key will be ignored."))
                (nodeItem, actionStructureOk) = detectNodeSubkeyConvertibleToString(check, nodeNameOrLabelDefined,
                        actionStructureOk, actionItem, nodeItem, printableStageAndAction, printableStageAndAction,
                        'name')
                (nodeItem, actionStructureOk) = detectNodeSubkeyConvertibleToString(check, nodeNameOrLabelDefined,
                        actionStructureOk, actionItem, nodeItem, printableStageAndAction, printableStageAndAction,
                        'label')

                // Check when node sub-key defined it's boolean.
                if (actionItem.node.get('pattern') instanceof Boolean) {
                    nodeItem.pattern = actionItem.node.get('pattern')
                } else {
                    actionStructureOk = configStructureErrorMsgWrapper(check, actionStructureOk, 2,
                            String.format(keyWarnOrErrMsgTemplate, 'sub-', 'pattern', printableStageAndAction,
                                    'Sub-key should be boolean.'))
                    nodeItem.node.remove('pattern')
                }
            } else {
                actionStructureOk = configStructureErrorMsgWrapper(check, actionStructureOk, 3,
                        String.format(keyWarnOrErrMsgTemplate, '', 'node', printableStageAndAction,
                                'Key will be ignored.'))
            }
        }
        (actionLinkOk, actionDescription) = checkOrExecutePipelineActionLink(actionItem.action as String, nodeItem,
                pipelineSettings, envVariables, check)
    } else {
        CF.outMsg(3, String.format("No 'action' key specified, nothing to %s '%s' action.",
                check ? 'check in' : 'perform at', printableStageAndAction))
        actionStructureOk = false
    }
    Boolean actionStructureAndLinkOk = actionStructureOk && actionLinkOk
    return [CF.addPipelineStepsAndUrls([:], printableStageAndAction, actionStructureAndLinkOk, actionDescription),
            actionStructureAndLinkOk]
}

/**
 * Action structure error or warning message wrapper.
 *
 * @param check - true on check mode, false on execution.
 * @param actionStructureState - a state of the whole action item: true when ok.
 * @param eventNum - event number: 3 is an error, 2 is a warning.
 * @param msg - error or warning message.
 * @return - a state of the whole action item: true when ok.
 */
Boolean configStructureErrorMsgWrapper(Boolean check, Boolean actionStructureState, Integer eventNum, String msg) {
    if (check) {
        CF.outMsg(eventNum, msg)
        actionStructureState = eventNum == 3 ? false : actionStructureState
    }
    return actionStructureState
}

/**
 * Detect node subkey ('name', 'label') in action item is convertible to string.
 *
 * @param check - set false to execute action item, true to check.
 * @param nodeNameOrLabelDefined - pass true when only one of node 'name' or 'label' sub-keys defined.
 * @param actionStructureOk - state of action item structure check: true when ok.
 * @param actionItem - action item to check or execute.
 * @param nodeItem - node item as a part of actionItem.
 * @param printableStageAndAction - stage name and action name in printable format.
 * @param keyWarnOrErrorMsgTemplate - Template for warning on error message.
 * @param nodeSubkeyName - sub-key of node map to check (is convertible to string).
 * @return - arrayList of: modified nodeItem,
 *                         modified actionStructureOk (only when check = true, otherwise returns unchanged).
 */
ArrayList detectNodeSubkeyConvertibleToString(Boolean check, Boolean nodeNameOrLabelDefined, Boolean actionStructureOk,
                                              Map actionItem, Map nodeItem, String printableStageAndAction,
                                              String keyWarnOrErrorMsgTemplate, String nodeSubkeyName) {
    if (nodeNameOrLabelDefined && !detectIsObjectConvertibleToString(actionItem.node.get(nodeSubkeyName)))
        actionStructureOk = configStructureErrorMsgWrapper(check, actionStructureOk, 3,
                String.format(keyWarnOrErrorMsgTemplate, 'sub-', nodeSubkeyName, printableStageAndAction, ''))
    nodeItem.node.remove(nodeSubkeyName)
    return [nodeItem, actionStructureOk]
}

ArrayList checkOrExecutePipelineActionLink(String actionItemAction, Map nodeItem, Map pipelineSettings,
                                           Object envVariables, Boolean check) {
    return []
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
        ArrayList allPipelineParams = extractParamsListFromSettingsMap(pipelineSettings, BuiltinPipelineParameters)
        def (Boolean noPipelineParamsInTheConfig, Boolean pipelineParametersProcessingPass) =
                wrapperPipelineParametersProcessing(allPipelineParams, params)

        // Check pipeline parameters in the settings are correct, all of them was defined properly for current build.
        Boolean checkPipelineParametersPass
        if (noPipelineParamsInTheConfig) {
            if (pipelineParametersProcessingPass) CF.outMsg(1, 'No pipeline parameters in the config.')
        } else {
            checkPipelineParametersPass = checkPipelineParamsFormat(allPipelineParams)
            if (checkPipelineParametersPass || params.get('DRY_RUN').asBoolean()) {
                Boolean requiredPipelineParamsSet
                Boolean regexCheckAllRequiredPipelineParamsOk
                (requiredPipelineParamsSet, env) = (checkAllRequiredPipelineParamsAreSet(pipelineSettings, params, env))
                (regexCheckAllRequiredPipelineParamsOk, env) = regexCheckAllRequiredPipelineParams(allPipelineParams,
                        params, env)
                pipelineFailedReasonText += requiredPipelineParamsSet && regexCheckAllRequiredPipelineParamsOk ? '' :
                        'Required pipeline parameter(s) was not specified or incorrect.'
            }
        }

        // Check other pipeline settings (stages, playbooks, scripts, inventories, etc) are correct.
        Boolean pipelineSettingsCheckOk
        (pipelineSettingsCheckOk, __, env) = checkOrExecutePipelineWrapperFromSettings(pipelineSettings, env, true,
                false)
        pipelineFailedReasonText += pipelineSettingsCheckOk && checkPipelineParametersPass ? '' :
                'Pipeline settings contains an error(s).'

        // Interrupt when settings error was found or required pipeline parameters wasn't set, otherwise execute it.
        pipelineFailedReasonText += (!pipelineParametersProcessingPass) ? '\nError(s) in pipeline yaml settings.' : ''
        if (pipelineFailedReasonText.trim())
            error String.format('%s\n%s.', pipelineFailedReasonText, 'Please fix then re-build')

        // Execute wrapper pipeline settings stages.
        Boolean allDone
        Map pipelineStagesStates
        (allDone, pipelineStagesStates, env) = checkOrExecutePipelineWrapperFromSettings(pipelineSettings, env, false)
    }
}
