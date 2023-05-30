#!/usr/bin/env groovy
import com.thoughtworks.xstream.mapper.Mapper
@NonCPS
@Grab(group = 'org.yaml', module = 'snakeyaml', version = '1.5')
import org.yaml.snakeyaml.*

import java.util.regex.Pattern


// TODO: change branch
@Library('jenkins-shared-library-alx@devel') _


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

// Set your ansible installation name from jenkins settings.
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
         description: 'Jenkins node name to run.'],
        [name       : 'NODE_TAG',
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
static String applyReplaceRegexItems(String text, ArrayList regexItemsList, ArrayList replaceItemsList = []) {
    regexItemsList.eachWithIndex { value, Integer index ->
        text = text.replaceAll(value as CharSequence, replaceItemsList.contains(index) ? replaceItemsList[index] as
                String : '')
    }
    return text
}

/**
 * Get printable key value from map item (e.g. printable 'name' key which convertible to string).
 *
 * @param mapItem - map item to get printable key value from.
 * @param keyName - key name to get.
 * @param nameOnUndefined - printable value when key is absent or not convertible to string.
 * @return - printable key value.
 */
static String getPrintableValueKeyFromMapItem(Map mapItem, String keyName = 'name',
                                              String nameOnUndefined = '<undefined>') {
    return mapItem && mapItem.containsKey(keyName) && detectIsObjectConvertibleToString(mapItem.get(keyName)) ?
            mapItem.get(keyName).toString() : nameOnUndefined
}

/**
 * Verify all required jenkins pipeline parameters are presents.
 *
 * @param pipelineParams - jenkins built-in 'params' UnmodifiableMap variable with current build pipeline parameters.
 * @param currentPipelineParams - an arrayList of map items to check, e.g: [map_item1, map_item2 ... map_itemN].
 *                                While single map item format is:
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
 *                               - 'choices' key is only for 'type: choice'.
 *                               - 'default' key is for all types except 'type: choice'. This key is incompatible with
 *                                 'type: choice'. For other types this key is optional: it's will be false for boolean,
 *                                 and '' (empty line) for string, password and text parameters.
 *                               - 'trim' key is available for 'type: string'. This key is optional, by default it's
 *                                 false.
 *                               - 'description' key is optional, by default it's '' (empty line).
 *
 *                               More info: https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings
 * @return - arrayList of:
 *           - true when jenkins pipeline parameters update required;
 *           - true when no errors.
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
            verifyPipelineParamsOk = configStructureErrorMsgWrapper(true, true, 3, String.format("%s: '%s' %s%s",
                    ignoreMsg, 'name', keyValueIncorrectMsg, paramNameConvertibleToString && !paramNamingCorrect ?
                    " (parameter name didn't met POSIX standards)." : '.'))
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
static Boolean detectPipelineParameterItemIsProbablyChoice(Map paramItem) {
    return paramItem.containsKey('choices') && paramItem.get('choices') instanceof ArrayList
}

/**
 * Detect is pipeline parameter item probably 'boolean' type.
 *
 * @param paramItem - pipeline parameter item to detect.
 * @return - true when 'boolean'.
 */
static Boolean detectPipelineParameterItemIsProbablyBoolean(Map paramItem) {
    return paramItem.containsKey('default') && paramItem.get('default') instanceof Boolean
}

/**
 * Array List to string separated with commas (optional last one by 'and').
 *
 * @param arrayListItems - arrayList to convert items from.
 * @param splitLastByAnd - when true separated the last item with 'and' word.
 * @return - string with arrayList items.
 */
static String arrayListToReadableString(ArrayList arrayListItems, Boolean splitLastByAnd = true) {
    String strByCommas = arrayListItems.toString().replaceAll(',\\s', "', '").replaceAll('[\\[\\]]', "'")
    return splitLastByAnd && arrayListItems?.size() > 1 ? String.format('%s and %s',
            strByCommas.substring(0, strByCommas.lastIndexOf(", '")),
            strByCommas.substring(strByCommas.lastIndexOf(", '") + 2, strByCommas.length())) : strByCommas
}

/**
 * Convert map items to string separated with commas (optional last one by 'and').
 *
 * @param map - map to convert key names from.
 * @param keyNames - when true format key names from the map, otherwise format values.
 * @param splitLastByAnd - when true separated the last item with 'and' word.
 * @return - string with key names.
 */
static String mapItemsToReadableListString(Map map, Boolean keyNames = true, Boolean splitLastByAnd = true) {
    return arrayListToReadableString(keyNames ? map.keySet() as ArrayList : map.values() as ArrayList, splitLastByAnd)
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
 * @param enableCheck - when true enable message and possible return status changes.
 * @param currentState - current overall state value to return from function: false when previous items contains an
 *                       error(s).
 * @return - 'false' as a failed pipeline check item state.
 */
Boolean pipelineSettingsItemError(Integer eventNum, String itemName, String errorMsg, Boolean enableCheck = true,
                                  Boolean currentState = true) {
    return configStructureErrorMsgWrapper(enableCheck, currentState, eventNum,
            String.format("Wrong syntax in pipeline parameter '%s': %s.", itemName, errorMsg))
}

/**
 * Structure error or warning message wrapper.
 *
 * @param enableCheck - true on check mode, false on execution to skip checking.
 * @param structureState - a state of a structure for the whole item: true when ok.
 * @param eventNum - event number: 3 is an error, 2 is a warning.
 * @param msg - error or warning message.
 * @return - a state of a structure for the whole item: true when ok.
 */
Boolean configStructureErrorMsgWrapper(Boolean enableCheck, Boolean structureState, Integer eventNum, String msg) {
    if (enableCheck) {
        CF.outMsg(eventNum, msg)
        structureState = eventNum == 3 ? false : structureState
    }
    return structureState
}

/**
 * Check environment variable name match POSIX shell standards.
 *
 * @param name - variable name to check regex match.
 * @return - true when match.
 */
static Boolean checkEnvironmentVariableNameCorrect(Object name) {
        return detectIsObjectConvertibleToString(name) && name.toString().matches('[a-zA-Z_]+[a-zA-Z0-9_]*')
}

/**
 * Detect if an object will be human readable string after converting to string (exclude lists, maps, etc).
 *
 * @param obj - object to detect.
 * @return - true when object is convertible to human readable string.
 */
static Boolean detectIsObjectConvertibleToString(Object obj) {
    return (obj instanceof String || obj instanceof Integer || obj instanceof Float || obj instanceof BigInteger)
}

/**
 * Detect if an object will be correct after conversion to boolean.
 *
 * @param obj - object to detect.
 * @return - true when object will be correct.
 */
static Boolean detectIsObjectConvertibleToBoolean(Object obj) {
    return (obj?.toBoolean()).toString() == obj?.toString()
}

/**
 * Check pipeline parameters in pipeline settings item for correct keys structure, types and values.
 *
 * @param item - pipeline settings item to check.
 * @return - pipeline parameters item check status (true when ok).
 */
Boolean pipelineParametersSettingsItemCheck(Map item) {
    String printableParameterName = getPrintableValueKeyFromMapItem(item)
    CF.outMsg(0, String.format("Checking pipeline parameter '%s':\n%s", printableParameterName, CF.readableMap(item)))

    // Check 'name' key is present and valid.
    Boolean checkOk = pipelineSettingsItemError(3, printableParameterName, "Invalid parameter name",
            item.containsKey('name') && !checkEnvironmentVariableNameCorrect(item.get('name')), true)
    checkOk = pipelineSettingsItemError(3, printableParameterName, "'name' key is required, but undefined",
            !item.containsKey('name'), checkOk)

    // When 'assign' sub-key is defined inside 'on_empty' key, checking it's correct.
    checkOk = pipelineSettingsItemError(3, printableParameterName, String.format("%s: '%s'",
            'Unable to assign due to incorrect variable name', item.get('on_empty')?.get('assign')), item
            .get('on_empty') && item.on_empty.get('assign') instanceof String && item.on_empty.assign.startsWith('$') &&
            !checkEnvironmentVariableNameCorrect(item.on_empty.assign.toString().replaceAll('[\${}]', '')), checkOk)

    if (item.containsKey('type')) {

        // Check 'type' value with other keys data type mismatch.
        String msg = item.type == 'choice' && !item.containsKey('choices') ?
                "'type' set as choice while no 'choices' list defined" : ''
        if (item.type == 'boolean' && item.containsKey('default') && !(item.default instanceof Boolean))
            msg = String.format("'type' set as boolean while 'default' key is not. It's %s%s",
                    item.get('default').getClass().toString().tokenize('.').last().toLowerCase(),
                    detectPipelineParameterItemIsProbablyBoolean(item) ? ", but it's convertible to boolean" : '')
        checkOk = pipelineSettingsItemError(3, printableParameterName, msg, msg.trim() as Boolean, checkOk)
    } else {

        // Try to detect 'type' when not defined.
        ArrayList autodetectData = detectPipelineParameterItemIsProbablyBoolean(item) ? ['default', 'boolean'] : []
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
    checkOk = pipelineSettingsItemError(3, printableParameterName, "'default' and 'choices' keys are incompatible",
            item.containsKey('choices') && item.containsKey('default'), checkOk)
    return pipelineSettingsItemError(3, printableParameterName, "'choices' value is not a list of items",
            item.containsKey('choices') && !(item.get('choices') instanceof ArrayList), checkOk)
}

/**
 * Inject parameters to current jenkins pipeline.
 *
 * @param requiredParams - arrayList of jenkins pipeline parameters to inject, e.g:
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
    Boolean dryRun = getBooleanPipelineParamState(currentPipelineParams)
    currentBuild.displayName = String.format('pipeline_parameters_update--#%s%s', env.BUILD_NUMBER, dryRun ?
            '-dry_run' : '')
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
 * Check pipeline parameters format as an arrayList of pipeline settings key: map items for key structure, types and
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
 * @return - arrayList of:
 *           - printable parameter name (or '<undefined>' when name wasn't set);
 *           - return true when condition specified in 'isUndefined' method variable met.
 */
static ArrayList getPipelineParamNameAndDefinedState(Map paramItem, Object pipelineParameters, Object envVariables,
                                           Boolean isUndefined = true) {
    return [getPrintableValueKeyFromMapItem(paramItem), (paramItem.get('name') && pipelineParameters
            .containsKey(paramItem.name) && isUndefined ^ (envVariables[paramItem.name as String]?.trim()).asBoolean())]
}

/**
 * Handle pipeline parameter assignment when 'on_empty' key defined in pipeline settings item.
 *
 * @param settingsItem - settings item from pipeline settings to handle.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @return - arrayList of:
 *           - true when pipeline parameter needs an assignment;
 *           - string or value of assigned environment variable (when value starts with $);
 *           - true when needs to count an error when pipeline parameter undefined and can't be assigned;
 *           - true when needs to warn when pipeline parameter undefined and can't be assigned.
 */
static ArrayList handleAssignmentWhenPipelineParamIsUnset(Map settingsItem, Object envVariables) {
    if (!settingsItem.get('on_empty'))
        return [false, '', true, false]
    Boolean fail = settingsItem.on_empty.get('fail') ? settingsItem.on_empty.get('fail').asBoolean() : true
    Boolean warn = settingsItem.on_empty.get('warn').asBoolean()
    if (!settingsItem.on_empty.get('assign'))
        return [false, '', fail, warn]
    def (Boolean assignmentIsPossible, String assignment) = getAssignmentFromEnvVariable(settingsItem.on_empty.assign
            .toString(), envVariables)
    return [assignmentIsPossible, assignment, fail, warn]
}

/**
 * Get assignment from environment variable.
 *
 * @param assignment - string that probably contains environment variable (e.g. $FOO).
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @return arrayList of:
 *         - true when assignment contains variable;
 *         - assigned value or just a string assignment return.
 */
static getAssignmentFromEnvVariable(String assignment, Object envVariables) {
    Boolean assignmentContainsVariable = assignment.matches('\\$[^{].+')
    return [assignmentContainsVariable, assignmentContainsVariable ? envVariables[assignment.replaceAll('\\$', '')] :
            assignment]
}

/**
 * Process assignment from environment variable wrapper.
 *
 * @param assignment - string that probably contains environment variable (e.g. $FOO).
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param keyDescription - an error message prefix to describe what needs to be assigned.
 * @return arrayList of:
 *         - true when no errors: assignment completed or skipped;
 *         - assigned value or just a string assignment return.
 */
ArrayList processAssignmentFromEnvVariable(String assignment, Object envVariables, String keyDescription = 'Key') {
    def (Boolean assignmentIsPossible, String assignData) = getAssignmentFromEnvVariable(assignment, envVariables)
    String assignmentOk = configStructureErrorMsgWrapper(assignmentIsPossible && !assignData?.trim(), true, 3,
            String.format("%s '%s' is empty: specified variable is undefined.", keyDescription, assignment))
    return [assignmentOk, assignData?.trim() ? assignData : assignment]
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
 * @return - arrayList of:
 *           - true when all required variables are specified;
 *           - changed or unchanged environment variables for current job build.
 */
Boolean checkAllRequiredPipelineParamsAreSet(Map pipelineSettings, Object pipelineParameters, Object envVariables) {
    Boolean allSet = true
    if (pipelineSettings.get('parameters') && pipelineSettings.get('parameters').get('required')) {
        CF.outMsg(1, 'Checking that all required pipeline parameters was defined for current build.')
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
static ArrayList extractParamsListFromSettingsMap(Map pipelineSettings, ArrayList builtinPipelineParameters) {
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
 * @param pipelineSettings - arrayList of pipeline parameters from settings.
 * @param pipelineParameters - pipeline parameters for current job build (actually requires a pass of 'params' which is
 *                             class java.util.Collections$UnmodifiableMap).
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param builtinPipelineParameters - additional built-in pipeline parameters arrayList.
 * @return - arrayList of:
 *           - true when all pass;
 *           - changed or unchanged environment variables for current job build.
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
                configStructureErrorMsgWrapper(regexPattern.trim() as Boolean, true, 0, String
                        .format("Found '%s' regex for pipeline parameter '%s'.", regexPattern, printableParamName))
                allCorrect = configStructureErrorMsgWrapper(paramIsDefined && regexPattern.trim() &&
                        !envVariables[it.name as String].matches(regexPattern), allCorrect, 3,
                        String.format('%s parameter is incorrect due to regex mismatch.', printableParamName))
            }

            // Perform regex replacement when regex_replace was set and pipeline parameter is defined for current build.
            if (it.get('regex_replace')) {
                String msgTemplateNoValue =
                        "'%s' sub-key value of 'regex_replace' wasn't defined for '%s' pipeline parameter.%s"
                String msgTemplateWrongType =
                        "Wrong type of '%s' value sub-key of 'regex_replace' for '%s' pipeline parameter.%s"
                String msgRecommendation = ' Please fix them. Otherwise, replacement will be skipped with an error.'

                // Handle 'to' sub-key of 'regex_replace' parameter item key.
                String regexReplacement = it.regex_replace.get('to')
                Boolean regexToKeyIsConvertibleToString = detectIsObjectConvertibleToString(it.regex_replace.get('to'))
                Boolean regexReplacementDone = configStructureErrorMsgWrapper(regexReplacement?.trim() &&
                        !regexToKeyIsConvertibleToString, false, 3, String.format(msgTemplateWrongType, 'to',
                        printableParamName, msgRecommendation))

                // Handle 'regex' sub-key of 'regex_replace' parameter item key.
                String regexPattern = it.regex_replace.get('regex')
                Boolean regexKeyIsConvertibleToString = detectIsObjectConvertibleToString(it.regex_replace.get('regex'))
                if (regexPattern?.length() && regexKeyIsConvertibleToString) {
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
                } else if (regexPattern?.length() && !regexKeyIsConvertibleToString) {
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
 *                           parameters (e.g. 'DEBUG_MODE', etc) converted to arrayList.
 *                           See https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings for details.
 * @param currentPipelineParams - pipeline parameters for current job build (actually requires a pass of 'params'
 *                                which is class java.util.Collections$UnmodifiableMap). Set
 *                                currentPipelineParams.DRY_RUN to 'true' for dry-run mode.
 * @return - arrayList of:
 *           - true when there is no pipeline parameters in the pipelineSettings;
 *           - true when pipeline parameters processing pass.
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
                    getBooleanPipelineParamState(currentPipelineParams) ? ' will be skipped in dry-run mode.' : '...'))
            updatePipelineParams(pipelineParams, allPass, currentPipelineParams)
        }
    }
    return [noPipelineParams, allPass]
}

/**
 * Get Boolean variable enabled state from environment variables.
 *
 * @param env - environment variables for current job build (actually requires a pass of 'env' which is
 *              class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param variableName - Environment variable to get.
 * @return - true when enabled.
 */
static Boolean getBooleanVarStateFromEnv(Object envVariables, String variableName = 'DEBUG_MODE') {
    return envVariables.getEnvironment().get(variableName)?.toBoolean()
}

/**
 * Get Boolean Pipeline parameter state from params object.
 *
 * @param pipelineParams - pipeline parameters for current job build (actually requires a pass of 'params' which is
 *                         class java.util.Collections$UnmodifiableMap).
 * @param parameterName - parameter name.
 * @return - true when enabled.
 */
static Boolean getBooleanPipelineParamState(Object pipelineParams, String parameterName = 'DRY_RUN') {
    return pipelineParams.get(parameterName)?.toBoolean()
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

/**
 * Pipeline parameters processing wrapper: git clone, load all pipeline settings, check all current pipeline
 * parameters are equal in the settings, check pipeline parameters in the settings are correct, check all pipeline
 * parameters was defined properly for current build.
 *
 * @param settingsGitUrl - repo URL of 'universal-wrapper-pipeline-settings' to load current pipeline settings.
 * @param defaultSettingsGitBranch - branch in setting repo.
 * @param settingsRelativePathPrefix - prefix for pipeline settings relative path inside the settings project, that will
 *                                     be added automatically on yaml load.
 * @param pipelineNameRegexReplace - pipeline name regex, a string that will be cut from pipeline name to become a
 *                                   filename of yaml pipeline settings to be loaded.
 * @param builtinPipelineParameters - Built-in pipeline parameters, which are mandatory and not present in pipeline
 *                                    settings.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param pipelineParams - jenkins built-in 'params' UnmodifiableMap variable with current build pipeline parameters.
 * @return - arrayList of:
 *           - pipeline failed reason text;
 *           - true when all pipeline parameters processing pass;
 *           - true when check pipeline parameters format in the settings pass;
 *           - map with all pipeline settings loaded from the yaml file in settings repo;
 *           - environment variables for current job build return.
 */
ArrayList pipelineParamsProcessingWrapper(String settingsGitUrl, String defaultSettingsGitBranch,
                                          String settingsRelativePathPrefix, ArrayList pipelineNameRegexReplace,
                                          ArrayList builtinPipelineParameters, Object envVariables,
                                          Object pipelineParams) {
    // Load all pipeline settings then check all current pipeline params are equal to params in pipeline settings.
    String settingsRelativePath = String.format('%s/%s.yaml', settingsRelativePathPrefix,
            applyReplaceRegexItems(envVariables.JOB_NAME.toString(), pipelineNameRegexReplace))
    Map pipelineSettings = loadPipelineSettings(settingsGitUrl, defaultSettingsGitBranch, settingsRelativePath,
            getBooleanPipelineParamState(pipelineParams, 'DEBUG_MODE'))
    String pipelineFailReasonText = ''
    ArrayList allPipelineParams = extractParamsListFromSettingsMap(pipelineSettings, builtinPipelineParameters)
    def (Boolean noPipelineParamsInTheConfig, Boolean pipelineParamsProcessingPass) =
    wrapperPipelineParametersProcessing(allPipelineParams, pipelineParams)

    // Check pipeline parameters in the settings are correct, all of them was defined properly for current build.
    Boolean checkPipelineParametersPass = true
    if (noPipelineParamsInTheConfig && pipelineParamsProcessingPass) {
        CF.outMsg(1, 'No pipeline parameters in the config.')
    } else if (!noPipelineParamsInTheConfig) {
        checkPipelineParametersPass = checkPipelineParamsFormat(allPipelineParams)
        if (checkPipelineParametersPass || getBooleanPipelineParamState(pipelineParams)) {
            Boolean requiredPipelineParamsSet
            Boolean regexCheckAllRequiredPipelineParamsOk
            (requiredPipelineParamsSet, env) = (checkAllRequiredPipelineParamsAreSet(pipelineSettings, pipelineParams,
                    envVariables))
            (regexCheckAllRequiredPipelineParamsOk, env) = regexCheckAllRequiredPipelineParams(allPipelineParams,
                    pipelineParams, env)
            pipelineFailReasonText += requiredPipelineParamsSet && regexCheckAllRequiredPipelineParamsOk ? '' :
                    'Required pipeline parameter(s) was not specified or incorrect. '
        }
    }
    return [pipelineFailReasonText, pipelineParamsProcessingPass, checkPipelineParametersPass, pipelineSettings, env]
}

/**
 * Check or execute wrapper pipeline from pipeline settings.
 *
 * @param pipelineSettings - the whole pipeline settings map (pre-converted from yaml) to check and/or execute.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param check - true to check pipeline settings structure and parameters.
 * @param execute - true to execute pipeline wrapper stages defined in the config, false for dry run. Please note:
 *                  1. When 'check' is true pipeline settings will be checked, then if 'execute' is true pipeline
 *                  settings will be executed. So you can set both 'check' and 'execute' to true, but it's not
 *                  recommended: use separate function call to check settings first.
 *                  2. You can also set envVariables.DEBUG_MODE to verbose output and/or envVariables.DRY_RUN to
 *                  perform dry run.
 * @return - arrayList of:
 *           - pipeline stages status map (the structure of this map should be: key is the name with spaces cut, value
 *             should be a map of: [name: name, state: state, url: url]);
 *           - true when checking and execution pass (or skipped), false on checking or execution errors;
 *           - return of environment variables ('env') that pass to function in 'envVariables'.
 */
ArrayList checkOrExecutePipelineWrapperFromSettings(Map pipelineSettings, Object envVariables, Boolean check = false,
                                                    Boolean execute = true) {
    Map stagesStates = [:]
    Boolean executeOk = true
    Boolean checkOk = configStructureErrorMsgWrapper(!pipelineSettings.get('stages') && ((check &&
            getBooleanVarStateFromEnv(envVariables)) || execute), true, 0,
            String.format('No stages to %s in pipeline config.', execute ? 'execute' : 'check'))
    for (stageItem in pipelineSettings.stages) {
        (__, checkOk, envVariables) = check ? checkOrExecuteStageSettingsItem(stageItem as Map, pipelineSettings,
                envVariables, checkOk) : [[:], true, envVariables]
        Map currentStageActionsStates = [:]
        if (execute)
            stage(getPrintableValueKeyFromMapItem(stageItem as Map)) {
                (currentStageActionsStates, executeOk, envVariables) = checkOrExecuteStageSettingsItem(stageItem as Map,
                        pipelineSettings, envVariables, executeOk, false)
            }
        stagesStates = stagesStates + currentStageActionsStates
    }
    return [stagesStates, checkOk && executeOk, envVariables]
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
 * @param allPass - current overall state of the structure check/execute pass. Will be changed on error(s) or return
 *                  unchanged.
 * @param check - set false to execute action item, true to check.
 * @return - arrayList of:
 *           - all actions in the stage status map (the structure of this map should be: key is the name with spaces
 *             cut, value should be a map of: [name: stage name and action, state: state, url: info and/or job url]);
 *           - true when all stage actions execution/checking successfully done;
 *           - return of environment variables for current job build.
 */
ArrayList checkOrExecuteStageSettingsItem(Map stageItem, Map pipelineSettings, Object envVariables,
                                          Boolean allPass = true, Boolean check = true) {
    Map actionsRuns = [:]
    Map actionsStates = [:]

    // Handling 'name', 'actions' and 'parallel' stage keys.
    allPass = configStructureErrorMsgWrapper(check && (!stageItem.containsKey('name') ||
            !detectIsObjectConvertibleToString(stageItem.get('name'))), allPass, 3,
            "Unable to convert stage name to a string, probably it's undefined or empty.")
    String printableStageName = getPrintableValueKeyFromMapItem(stageItem)
    Boolean actionsIsNotList = stageItem.containsKey('actions') && !(stageItem.get('actions') instanceof ArrayList)
    allPass = configStructureErrorMsgWrapper(check && (!stageItem.containsKey('actions') || actionsIsNotList),
            allPass, 3, String.format("Incorrect or undefined actions for '%s' stage.", printableStageName))
    allPass = configStructureErrorMsgWrapper(check && (stageItem.containsKey('parallel') &&
            !detectIsObjectConvertibleToBoolean(stageItem.get('parallel'))), allPass, 3, String.format(
            "Unable to determine 'parallel' value for '%s' stage. Remove them or set as boolean.", printableStageName))

    // Creating map and processing items from 'actions' key.
    stageItem.get('actions').eachWithIndex { item, Integer index ->
        actionsRuns[index] = {
            String checkOrExecuteMsg = check ? 'Checking' : 'Executing'
            String actionRunsMsg = String.format("action number %s from '%s' stage", index.toString(), stageItem.name)
            CF.outMsg(check ? 0 : 1, String.format('%s %s', checkOrExecuteMsg, actionRunsMsg))
            Map actionState
            Boolean checkOrExecuteOk
            (actionState, checkOrExecuteOk, envVariables) = checkOrExecutePipelineActionItem(printableStageName,
                    stageItem.get('actions')[index] as Map, pipelineSettings, index, envVariables, check)
            allPass = checkOrExecuteOk ? allPass : false
            actionsStates = actionsStates + actionState
            CF.outMsg(0, String.format('%s %s finished. Total:\n%s', checkOrExecuteMsg, actionRunsMsg,
                    CF.readableMap(actionsStates)))
        }
    }
    if (stageItem.get('parallel')?.toBoolean()) {
        parallel actionsRuns
    } else {
        actionsRuns.each {
            it.value.call()
        }
    }
    return [actionsStates, allPass, envVariables]
}

/**
 * Check list of keys from map is probably string or boolean.
 *
 * @param check - true on check mode, false on execution (to skip messages and no results change).
 * @param listOfKeys - list of keys to check from map.
 * @param map - map to check from.
 * @param isString - check is a string when true, check is a boolean when false.
 * @param index - just an index to print 'that map is a part of <index>' for ident.
 * @param currentStatus - current status to change on error in check mode, or not to change on execution.
 * @return - true when all map items is not empty and correct type.
 */
// TODO: Take a look at parameters and stages check and implement this function.
Boolean checkListOfKeysFromMapProbablyStringOrBoolean(Boolean check, ArrayList listOfKeys, Map map, Boolean isString,
                                                      String index, Boolean currentStatus = true) {
    listOfKeys.each {
        Boolean typeOk = isString ? detectIsObjectConvertibleToString(map.get(it)) :
                detectIsObjectConvertibleToBoolean(map.get(it))
        if (map.containsKey(it) && !typeOk) {
            currentStatus = configStructureErrorMsgWrapper(check, currentStatus, 3,
                    String.format("'%s' key in '#%s' should be a %s.", it, index, isString ? 'string' : 'boolean'))
        } else if (map.containsKey(it) && !map.get(it)?.toString()?.length()) {
            currentStatus = configStructureErrorMsgWrapper(check, currentStatus, 2, String.format(
                    "'%s' key defined for '#%s', but it's empty. Remove a key or define it's value.", it, index))
        }
    }
    return currentStatus
}

/**
 * Incompatible keys in map found error message wrapper.
 *
 * @param keys - arrayList of: First keyName, second keyName, etc...
 * @param keyDescriptionMessagePrefix - just a prefix for an error message what keys are incompatible.
 * @param onlyOneOfThem - just a postfix message that means only one key required.
 * @return - formatted error message.
 */
static String incompatibleKeysMsgWrapper(ArrayList keysForMessage, String keyDescriptionMessagePrefix = 'Keys',
                                         Boolean onlyOneOfThem = true) {
    return String.format("%s %s are incompatible.%s", keyDescriptionMessagePrefix,
            arrayListToReadableString(keysForMessage), onlyOneOfThem ? ' Please define only one of them.' : '')
}

/**
 * Check action item defined properly or execute action item from stage.
 *
 * @param stageName - the name of the current stage from which to test or execute the action item (just for logging
 *                    in all action status map - see @return of this function).
 * @param actionItem - action item to check or execute.
 * @param pipelineSettings - the whole pipeline settings map (pre-converted from yaml) to check and/or execute.
 * @param actionIndex - number of current action in stages.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl). Set 'DRY_RUN' environment variable
 *                       (or pipeline parameter) as an element of envVariables to true for dry run mode on execution.
 *                       Set 'DEBUG_MODE' to enable debug mode both for 'check' or 'execute'.
 * @param check - set false to execute action item, true to check.
 * @return - arrayList of:
 *           - all actions in the stage status map (the structure of this map should be: key is the name with spaces
 *             cut, value should be a map of: [name: stage name and action, state: state, url: info and/or job url]);
 *           - true when all stage actions execution successfully done;
 *           - environment variables ('env').
 */
ArrayList checkOrExecutePipelineActionItem(String stageName, Map actionItem, Map pipelineSettings, Integer actionIndex,
                                           Object envVariables, Boolean check) {
    Boolean actionStructureOk = true
    Boolean actionLinkOk = true
    String actionDescription = '<skipped>'
    Map nodeItem = [:]
    String printableStageAndAction = String.format('%s [%s]', stageName, actionIndex)
    String keyWarnOrErrMsgTemplate = "Wrong format of node %skey '%s' for '%s' action. %s"

    // Check keys are not empty and convertible to required type and check incompatible keys.
    ArrayList stringKeys = ['before_message', 'after_message', 'fail_message', 'success_message', 'dir', 'build_name']
    ArrayList booleanKeys = ['ignore_fail', 'stop_on_fail', 'success_only', 'fail_only']
    actionStructureOk = checkListOfKeysFromMapProbablyStringOrBoolean(check, stringKeys, actionItem, true,
            printableStageAndAction, actionStructureOk)
    actionStructureOk = checkListOfKeysFromMapProbablyStringOrBoolean(check, booleanKeys, actionItem, false,
            printableStageAndAction, actionStructureOk)
    actionStructureOk = configStructureErrorMsgWrapper(check && actionItem.containsKey('success_only') && actionItem
            .containsKey('fail_only'), actionStructureOk, 3, incompatibleKeysMsgWrapper(['success_only', 'fail_only']))
    println 'kuku'

    // Check node keys and sub-keys defined properly.
    Boolean anyJenkinsNode = (actionItem.containsKey('node') && !actionItem.get('node'))
    Boolean nodeIsStringConvertible = detectIsObjectConvertibleToString(actionItem.get('node'))
    if (nodeIsStringConvertible || anyJenkinsNode) {
        println 'kuku1'
        configStructureErrorMsgWrapper(anyJenkinsNode, true, 0, String.format("'node' key in '%s' action is null. %s",
                "This stage will run on any free Jenkins node.", printableStageAndAction))
        println 'kuku1b'
        nodeItem.node = [:]
        nodeItem.node.name = nodeIsStringConvertible ? actionItem.node.toString() : actionItem.get('node')?.get('name')
    } else if (actionItem.get('node') instanceof Map) {
        nodeItem = actionItem.get('node') as Map

        // Check only one of 'node' sub-keys 'name' or 'label' defined and it's correct.
        String incompatibleKeysMessage
        println 'kuku2'
        Boolean onlyNameOrLabelDefined = actionItem.node.containsKey('name') ^ actionItem.node.containsKey('label')
        actionStructureOk = configStructureErrorMsgWrapper(check && !onlyNameOrLabelDefined, actionStructureOk, 2,
                incompatibleKeysMsgWrapper(['name', 'label'], 'Node sub-keys'))
        actionStructureOk = detectNodeSubKeyConvertibleToString(check, !onlyNameOrLabelDefined, actionStructureOk,
                actionItem, printableStageAndAction, keyWarnOrErrMsgTemplate, 'name')
        actionStructureOk = detectNodeSubKeyConvertibleToString(check, !onlyNameOrLabelDefined, actionStructureOk,
                actionItem, printableStageAndAction, keyWarnOrErrMsgTemplate, 'label')
        println 'kuku2b'

        // Check when 'pattern' node sub-key defined and boolean.
        if (checkListOfKeysFromMapProbablyStringOrBoolean(check, ['pattern'], actionItem.node as Map, false,
                printableStageAndAction)) {
            nodeItem.pattern = actionItem.node.get('pattern')?.toBoolean()
        } else {
            println 'kuku3'
            actionStructureOk = configStructureErrorMsgWrapper(check, actionStructureOk, 2, String.format(
                    keyWarnOrErrMsgTemplate, 'sub-', 'pattern', printableStageAndAction, 'Sub-key should be boolean.'))
            println 'kuku3a'
            nodeItem.node.remove('pattern')
        }
    } else if (actionItem.containsKey('node') && !anyJenkinsNode && !(actionItem.get('node') instanceof Map)) {
        println 'kuku4'
        actionStructureOk = configStructureErrorMsgWrapper(check, actionStructureOk, 3,
                String.format(keyWarnOrErrMsgTemplate, '', 'node', printableStageAndAction, 'Key will be ignored.'))
        println 'kuku4a'
    }

    // Check or execute current action when 'action' key is correct and possible success_only/fail_only conditions met.
    Boolean actionIsCorrect = checkListOfKeysFromMapProbablyStringOrBoolean(check, ['action'], actionItem, true,
            printableStageAndAction)
    println 'kuku5'
    actionStructureOk = configStructureErrorMsgWrapper(check && actionItem.containsKey('action') && !actionIsCorrect,
            actionStructureOk, 3, String.format("Wrong format of 'action' key in '%s'.", printableStageAndAction))
    println 'kuku6'
    Boolean successOnlyActionConditionNotMet = actionItem.get('success_only') && currentBuild.result == 'FAILURE'
    Boolean failOnlyActionConditionNotMet = actionItem.get('fail_only') && currentBuild.result != 'FAILURE'
    Boolean allActionConditionsMet = !check && !successOnlyActionConditionNotMet && !failOnlyActionConditionNotMet
    String actionSkipMsgReason = !check && successOnlyActionConditionNotMet && !actionItem.containsKey('fail_only') ?
            'success_only' : ''
    actionSkipMsgReason += !check && failOnlyActionConditionNotMet && !actionItem.containsKey('success_only') ?
            'fail_only' : ''
    configStructureErrorMsgWrapper(!check && actionSkipMsgReason.trim(), true, 0,
            String.format("'%s' will be skipped by conditions met: %s", printableStageAndAction, actionSkipMsgReason))
    println 'checka: ' +  check
    if (actionIsCorrect && (check || allActionConditionsMet)) {
        actionMessageOutputWrapper(check, actionItem, 'before', envVariables)
        dir(!check && actionItem.get('dir') ? actionItem.get('dir').toString() : '') {
            currentBuild.displayName = !check && actionItem.get('build_name') ? actionItem.get('build_name') :
                    currentBuild.displayName
            (actionLinkOk, actionDescription, envVariables) = checkOrExecutePipelineActionLink(actionItem.action
                    as String, nodeItem?.get('node') as Map, pipelineSettings, envVariables, check)
        }

        // Processing post-messages, 'stop_on_fail' or 'ignore_fail' keys.
        actionMessageOutputWrapper(check, actionItem, 'after', envVariables)
        actionMessageOutputWrapper(check, actionItem, actionLinkOk ? 'success' : 'fail', envVariables)
        actionLinkOk = actionItem.get('ignore_fail') && !check ? true : actionLinkOk
        println 'checkb'
        if (actionItem.get('stop_on_fail') && !check)
            error String.format("Terminating current pipeline run due to an error in '%s' %s.", printableStageAndAction,
                    "('stop_on_fail' is enabled for current action)")
    } else if (!actionItem.containsKey('action')) {
        println 'checkb1'
        actionStructureOk = configStructureErrorMsgWrapper(check, actionStructureOk, check ? 3 : 2,
                String.format("No 'action' key specified, nothing to %s '%s' action.",
                        check ? 'check in' : 'perform at', printableStageAndAction))
        println 'checkb2'
    }
    Boolean actionStructureAndLinkOk = actionStructureOk && actionLinkOk
    println 'checkc'
    if (!check && !actionStructureAndLinkOk) currentBuild.result = 'FAILURE'
    println 'checkd'
    // TODO: not empty addPipelineStepsAndUrls
    return [CF.addPipelineStepsAndUrls([:], printableStageAndAction, actionStructureAndLinkOk, actionDescription),
            actionStructureAndLinkOk, envVariables]
}

/**
 * Message output wrapper for action in a stage: before, after, on success or fail.
 *
 * @param check - true on checking action item to skip message output.
 * @param actionItem action item to check or execute where the massage
 * @param messageType - message type (or action item key prefix): before, after, success, fail.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 */
def actionMessageOutputWrapper(Boolean check, Map actionItem, String messageType, Object envVariables) {
    String messageKey = String.format('%s_message', messageType)
    String messageText = getBooleanVarStateFromEnv(envVariables) ? String.format("%s %s: %s", messageType.capitalize(),
            'message', actionItem.get(messageKey)) : actionItem.get(messageKey)
    configStructureErrorMsgWrapper(detectIsObjectConvertibleToString(actionItem.get(messageKey)) && !check, true,
            messageType == 'fail' ? 3 : 1, messageText)
}

/**
 * Detect node sub-key ('name', 'label') in action item is convertible to string.
 *
 * @param check - set false to execute action item, true to check.
 * @param nodeNameOrLabelDefined - pass true when one of node 'name' or 'label' sub-keys defined.
 * @param actionStructureOk - state of action item structure check: true when ok.
 * @param actionItem - action item to check or execute.
 * @param printableStageAndAction - stage name and action name in printable format.
 * @param keyWarnOrErrorMsgTemplate - template for warning on error message.
 * @param nodeSubKeyName - sub-key of node map to check (is convertible to string).
 * @return - modified actionStructureOk (only when check = true, otherwise returns unchanged).
 */
Boolean detectNodeSubKeyConvertibleToString(Boolean check, Boolean nodeNameOrLabelDefined, Boolean actionStructureOk,
                                            Map actionItem, String printableStageAndAction,
                                            String keyWarnOrErrorMsgTemplate, String nodeSubKeyName) {
    return nodeNameOrLabelDefined && !detectIsObjectConvertibleToString(actionItem.node.get(nodeSubKeyName)) ?
            configStructureErrorMsgWrapper(check, actionStructureOk, 3, String.format(keyWarnOrErrorMsgTemplate, 'sub-',
                    nodeSubKeyName, printableStageAndAction, '')) : actionStructureOk
}

// TODO: done the env pass inside other functions and return from this
ArrayList checkOrExecutePipelineActionLink(String actionLink, Map nodeItem, Map pipelineSettings, Object envVariables,
                                           Boolean check, String nodePipelineParameterName = 'NODE_NAME',
                                           String nodeTagPipelineParameterName = 'NODE_TAG') {
    Boolean actionOk
    (actionOk, actionLink) = processAssignmentFromEnvVariable(actionLink, envVariables, 'Action link')
    Boolean actionLinkIsDefined = (pipelineSettings.get('actions') && pipelineSettings.get('actions')?.get(actionLink)
            instanceof Map)
    Map actionLinkItem = actionLinkIsDefined ? pipelineSettings.get('actions')?.get(actionLink) : [:]
    actionOk = configStructureErrorMsgWrapper(!actionLinkIsDefined && check, actionOk, 3,
            String.format("Action '%s' is not defined or incorrect data type in value.", actionLink))
    Map detectByKeys = [repo_url   : { println 'gitlab' },
                        collections: { println 'install_collections' },
                        playbook   : { println 'run_playbook' },
                        pipeline   : { println 'run_pipeline' },
                        stash      : { println 'stash' },
                        unstash    : { println 'unstash' },
                        artifacts  : { println 'copy_artifacts' },
                        script     : { println 'run_script' },
                        report     : { println 'send_report' }]

    // Determining action by defined keys in 'actions' settings item, check that no incompatible keys defined.
    Map keysFound = detectByKeys.findAll { k, v -> actionLinkItem.containsKey(k) }
    String actionDescription = (keysFound) ? keysFound.keySet()[0] : '<undefined or incorrect>'
    configStructureErrorMsgWrapper(check && keysFound?.size() > 1, actionOk, 2, String.format("%s '%s' %s. %s '%s' %s",
            'Keys in', actionLink, incompatibleKeysMsgWrapper(keysFound.keySet() as ArrayList, ''), 'Only',
            actionDescription, 'will be used on action run.'))
    actionOk = configStructureErrorMsgWrapper(!keysFound && actionOk, actionOk, 3, String.format("%s %s '%s'. %s: %s.",
            check ? "Can't" : "Nothing to execute due to can't", "determine any action in", actionLink,
            'Possible keys are', mapItemsToReadableListString(detectByKeys)))

    // Handling node selection keys: if name key exists use value from them, otherwise use label key.
    def currentNodeData = getJenkinsNodeToExecuteByNameOrTag(envVariables, nodePipelineParameterName,
            nodeTagPipelineParameterName)
    def changeNodeData = currentNodeData
    if (nodeItem?.containsKey('name')) {
        ArrayList nodeNames = nodeItem?.get('pattern') && nodeItem?.get('name') ?
                CF.getJenkinsNodes(nodeItem.get('name')) : [nodeItem?.get('name')]
        changeNodeData = !nodeItem.get('name') || !nodeNames ? null : nodeNames[0]
    } else if (!nodeItem?.containsKey('name') && nodeItem?.get('label')) {
        changeNodeData = [label: nodeItem?.get('pattern') && nodeItem?.get('label') ?
                CF.getJenkinsNodes(nodeItem.get('label'), true)?.first() : nodeItem?.get('label')]
    }

    // Executing determined action with possible node change or check without node change.
    if (keysFound) {
        if (!check && currentNodeData.toString() != changeNodeData.toString()) {
            node(changeNodeData) {
                String nodeSelectionPrintable = changeNodeData instanceof Map ? String.format("node with label '%s'",
                        changeNodeData.label) : String.format('%s node', (changeNodeData) ? changeNodeData : 'any')
                CF.outMsg(0, String.format("Executing '%s' action on %s...", actionLink, nodeSelectionPrintable))
                keysFound[actionDescription].call()
            }
        } else {
            keysFound[actionDescription].call()
        }
    }
    return [actionOk, actionDescription, envVariables]
}


def jenkinsNodeToExecute = getJenkinsNodeToExecuteByNameOrTag(env, 'NODE_NAME', 'NODE_TAG')
node(jenkinsNodeToExecute) {
    CF = new org.alx.commonFunctions() as Object
    wrap([$class: 'TimestamperBuildWrapper']) {
        String pipelineFailReasonText
        Boolean pipelineParamsProcessingPass
        Boolean checkPipelineParametersPass
        Map pipelineSettings
        (pipelineFailReasonText, pipelineParamsProcessingPass, checkPipelineParametersPass, pipelineSettings, env) =
                pipelineParamsProcessingWrapper(SettingsGitUrl, DefaultSettingsGitBranch, SettingsRelativePathPrefix,
                        PipelineNameRegexReplace, BuiltinPipelineParameters, env, params)

        // Check other pipeline settings (stages, playbooks, scripts, inventories, etc) are correct.
        Boolean pipelineSettingsCheckOk
        (__, pipelineSettingsCheckOk, env) = checkOrExecutePipelineWrapperFromSettings(pipelineSettings, env, true,
                false)
        pipelineFailReasonText += pipelineSettingsCheckOk && checkPipelineParametersPass ? '' :
                'Pipeline settings contains an error(s).'

        // Skip stages execution on settings error or undefined required pipeline parameter(s), or execute in dry-run.
        pipelineFailReasonText += pipelineParamsProcessingPass ? '' : '\nError(s) in pipeline yaml settings. '
        Boolean allDone
        Map pipelineStagesStates
        if (!pipelineFailReasonText.trim() || getBooleanPipelineParamState(params)) {
            configStructureErrorMsgWrapper(getBooleanPipelineParamState(params), true, 2, String.format('%s %s.',
                    'Dry-run mode enabled. All pipeline and settings errors will be ignored and pipeline stages will',
                    'be emulated skipping the scripts, playbooks and pipeline runs.'))
            (pipelineStagesStates, allDone, env) = checkOrExecutePipelineWrapperFromSettings(pipelineSettings, env)
            pipelineFailReasonText += allDone ? '' : 'Stages execution finished with fail.'
        }
        if (pipelineFailReasonText.trim())
            error String.format('%s\n%s.', pipelineFailReasonText, 'Please fix then re-build')

    }
}
