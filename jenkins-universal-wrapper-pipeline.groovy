#!/usr/bin/env groovy


/**
 * Jenkins Universal Wrapper Pipeline v1.0.0
 * (c) Aleksandr Bazhenov, 2023-2024
 *
 * This Source Code Form is subject to the terms of the Apache License v2.0.
 * If a copy of this source file was not distributed with this file, You can obtain one at:
 * https://github.com/alexanderbazhenoff/jenkins-universal-wrapper-pipeline/blob/main/LICENSE
 */

import groovy.text.StreamingTemplateEngine

@Library('jenkins-shared-library-alx')

/**
 * Repo URL and a branch of 'universal-wrapper-pipeline-settings' to load current pipeline settings, e.g:
 * 'git@github.com:alexanderbazhenoff/ansible-wrapper-settings.git'. Will be ignored when SETTINGS_GIT_BRANCH
 * pipeline parameter present and not blank.
 */
final String SettingsGitUrl = 'http://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings'
final String DefaultSettingsGitBranch = 'main'

/** Prefix for pipeline settings relative path inside the 'universal-wrapper-pipeline-settings' project, that will be
 * added automatically on yaml load.
 */
final String SettingsRelativePathPrefix = 'settings'

/** Jenkins pipeline name regex, a string that will be cut from pipeline name to become a filename of yaml pipeline
 * settings to be loaded. Example: Your jenkins pipeline name is 'prefix_pipeline-name_postfix'. To load pipeline
 * settings 'pipeline-name.yml' you can use regex list: ['^prefix_','_postfix$']. FYI: All pipeline name prefixes are
 * useful to split your jenkins between your company departments (e.g: 'admin', 'devops, 'qa', 'develop', etc...), while
 * postfixes are useful to mark pipeline as a changed version of original.
 */
final List PipelineNameRegexReplace = ['^(admin|devops|qa)_']

/** Ansible installation name from jenkins Global Configuration Tool, otherwise leave them empty for defaults from
 * jenkins shared library.
 */
final String AnsibleInstallationName = 'home_local_bin_ansible'

/** Built-in pipeline parameters, which are mandatory and not present in 'universal-wrapper-pipeline-settings'. */
final List BuiltinPipelineParameters = [
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
]


/**
 * Clone 'universal-wrapper-pipeline-settings' from git repository, load yaml pipeline settings and return them as map.
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
    if (printYaml) CF.outMsg(0, String.format('Loading pipeline settings:\n%s', readFile(pathToLoad)))
    readYaml(file: pathToLoad)
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
static String applyReplaceRegexItems(String text, List regexItemsList, List replaceItemsList = []) {
    String replacedText = text
    regexItemsList.eachWithIndex { value, Integer index ->
        replacedText = replacedText.replaceAll(value as CharSequence,
                replaceItemsList[index] ? replaceItemsList[index] as String : '')
    }
    replacedText
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
    mapItem && mapItem.containsKey(keyName) && detectIsObjectConvertibleToString(mapItem.get(keyName)) ?
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
List verifyPipelineParamsArePresents(List requiredParams, Object currentPipelineParams) {
    def (Boolean updateParamsRequired, Boolean verifyPipelineParamsOk) = [false, true]
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
            String pipelineParamPosixMsg = String.format("%s: '%s' %s%s", ignoreMsg, 'name', keyValueIncorrectMsg,
                    paramNameConvertibleToString && !paramNamingCorrect ?
                    " (parameter name didn't met POSIX standards)." : '.')
            verifyPipelineParamsOk = errorMsgWrapper(true, true, 3, pipelineParamPosixMsg)
        } else if (it.get('name') && !currentPipelineParams.containsKey(it.get('name'))) {
            updateParamsRequired = true
        }
    }
    [updateParamsRequired, verifyPipelineParamsOk]
}

/**
 * Detect is pipeline parameter item probably 'choice' type.
 *
 * @param paramItem - pipeline parameter item to detect.
 * @return - true when 'choice'.
 */
static Boolean detectPipelineParameterItemIsProbablyChoice(Map paramItem) {
    paramItem.containsKey('choices') && paramItem.get('choices') instanceof ArrayList
}

/**
 * Detect is pipeline parameter item probably 'boolean' type.
 *
 * @param paramItem - pipeline parameter item to detect.
 * @return - true when 'boolean'.
 */
static Boolean detectPipelineParameterItemIsProbablyBoolean(Map paramItem) {
    paramItem.containsKey('default') && paramItem.get('default') instanceof Boolean
}

/**
 * Find non-empty map items from list.
 *
 * @param map - map to find items from.
 * @param keysToCollect - list of keys that needs to be found.
 * @return - only map items specified in listOfKeysToCollect.
 */
static Map findMapItemsFromList(Map map, List keysToCollect) {
    map.findAll { mapKey, mapVal -> keysToCollect.contains(mapKey) && mapVal && mapVal?.toString()?.trim() }
}

/**
 * Hide password string.
 *
 * @param passwordString - password string to hide.
 * @return - password with replaced symbols.
 */
static String hidePasswordString(String passwordString, String replaceSymbol = '*') {
    passwordString?.length() > 0 ? replaceSymbol * passwordString?.length() : ''
}

/**
 * Array List to string separated with commas (optional last one by 'and').
 *
 * @param arrayListItems - arrayList to convert items from.
 * @param splitLastByAnd - when true separated the last item with 'and' word.
 * @return - string with arrayList items.
 */
static String arrayListToReadableString(List arrayListItems, Boolean splitLastByAnd = true) {
    String strByCommas = arrayListItems.toString().replaceAll(',\\s', "', '").replaceAll('[\\[\\]]', "'")
    splitLastByAnd && arrayListItems?.size() > 1 ? String.format('%s and %s',
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
    arrayListToReadableString(keyNames ? map.keySet() as ArrayList : map.values() as ArrayList, splitLastByAnd)
}

/**
 * Convert pipeline settings map item and add to jenkins pipeline parameters.
 *
 * @param item - pipeline settings map item to convert.
 * @return - jenkins pipeline parameters.
 */
List pipelineSettingsItemToPipelineParam(Map item) {
    List param = []
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
    param
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
    errorMsgWrapper(enableCheck, currentState, eventNum, String.format("Wrong syntax in pipeline parameter '%s': %s.",
            itemName, errorMsg))
}

/**
 * Structure or action execution error or warning message wrapper.
 *
 * @param enableCheck - true on check mode, false on execution to skip checking.
 * @param state - current state to pass or change (true when ok), e.g: a state of a structure for the whole item, or
 *                current state of item execution, etc...
 * @param eventNum - event number: 3 is an error, 2 is a warning.
 * @param msg - error or warning message.
 * @return - current state return.
 */
Boolean errorMsgWrapper(Boolean enableCheck, Boolean state, Integer eventNum, String msg) {
    if (enableCheck) CF.outMsg(eventNum, msg)
    (enableCheck && eventNum == 3) ? false : state
}

/**
 * Check environment variable name match POSIX shell standards.
 *
 * @param name - variable name to check regex match.
 * @return - true when match.
 */
static Boolean checkEnvironmentVariableNameCorrect(Object name) {
    detectIsObjectConvertibleToString(name) && name.toString().matches('[a-zA-Z_]+[a-zA-Z0-9_]*')
}

/**
 * Detect if an object will be human readable string after converting to string (exclude lists, maps, etc).
 *
 * @param obj - object to detect.
 * @return - true when object is convertible to human readable string.
 */
static Boolean detectIsObjectConvertibleToString(Object obj) {
    (obj instanceof String || obj instanceof Integer || obj instanceof Float || obj instanceof BigInteger)
}

/**
 * Detect if an object will be correct after conversion to boolean.
 *
 * @param obj - object to detect.
 * @return - true when object will be correct.
 */
static Boolean detectIsObjectConvertibleToBoolean(Object obj) {
    (obj?.toBoolean()).toString() == obj?.toString()
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

    /** Check 'name' key is present and valid. */
    Boolean checkOk = pipelineSettingsItemError(3, printableParameterName, 'Invalid parameter name',
            item.containsKey('name') && !checkEnvironmentVariableNameCorrect(item.get('name')), true)
    checkOk = pipelineSettingsItemError(3, printableParameterName, "'name' key is required, but undefined",
            !item.containsKey('name'), checkOk)

    /** When 'assign' sub-key is defined inside 'on_empty' key, checking it's correct. */
    String pipeParamSettingsAssignErrMsg = String.format("%s: '%s'", 'Unable to assign due to incorrect variable name',
            item.get('on_empty')?.get('assign'))
    Boolean pipeParamSettingsAssignEnableCheck = item.get('on_empty') &&
            item.on_empty.get('assign') instanceof String && item.on_empty.assign.startsWith('$')
    checkOk = pipelineSettingsItemError(3, printableParameterName, pipeParamSettingsAssignErrMsg,
            pipeParamSettingsAssignEnableCheck && !checkEnvironmentVariableNameCorrect(item.on_empty.assign.toString()
                    .replaceAll('[\${}]', '')), checkOk)  // groovylint-disable-line GStringExpressionWithinString

    if (item.containsKey('type')) {
        /** Check 'type' value with other keys data type mismatch. */
        String msg = item.type == 'choice' && !item.containsKey('choices') ?
                "'type' set as choice while no 'choices' list defined" : ''
        if (item.type == 'boolean' && item.containsKey('default') && !(item.default instanceof Boolean))
            msg = String.format("'type' set as boolean while 'default' key is not. It's %s%s",
                    item.get('default').getClass().toString().tokenize('.').last().toLowerCase(),
                    detectPipelineParameterItemIsProbablyBoolean(item) ? ", but it's convertible to boolean" : '')
        checkOk = pipelineSettingsItemError(3, printableParameterName, msg, msg.trim() as Boolean, checkOk)
    } else {
        /** Try to detect 'type' when not defined. */
        List autodetectData = detectPipelineParameterItemIsProbablyBoolean(item) ? ['default', 'boolean'] : []
        autodetectData = detectPipelineParameterItemIsProbablyChoice(item) ? ['choices', 'choice'] : autodetectData

        /** Output reason and 'type' key when autodetect is possible. */
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

    /** Check 'default' and 'choices' keys incompatibility and 'choices' value. */
    checkOk = pipelineSettingsItemError(3, printableParameterName, "'default' and 'choices' keys are incompatible",
            item.containsKey('choices') && item.containsKey('default'), checkOk)
    pipelineSettingsItemError(3, printableParameterName, "'choices' value is not a list of items",
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
// groovylint-disable-next-line MethodReturnTypeRequired, NoDef
def updatePipelineParams(List requiredParams, Boolean finishWithSuccess, Object currentPipelineParams) {
    def (Boolean dryRun, List newPipelineParams) = [getBooleanPipelineParamState(currentPipelineParams), []]
    currentBuild.displayName = String.format('pipeline_parameters_update--#%s%s', env.BUILD_NUMBER, dryRun ?
            '-dry_run' : '')
    requiredParams.each { newPipelineParams += pipelineSettingsItemToPipelineParam(it as Map) }
    if (!dryRun)
        properties([parameters(newPipelineParams)])
    if (finishWithSuccess) {
        List msgArgs = dryRun ? ["n't", 'Disable dry-run mode'] : [' successfully', "Select 'Build with parameters'"]
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
Boolean checkPipelineParamsFormat(List parameters) {
    Boolean allPass = true
    parameters.each {
        allPass = pipelineParametersSettingsItemCheck(it as Map) ? allPass : false
    }
    allPass
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
static List getPipelineParamNameAndDefinedState(Map paramItem, Object pipelineParameters, Object envVariables,
                                                Boolean isUndefined = true) {
    [getPrintableValueKeyFromMapItem(paramItem), (paramItem.get('name') && pipelineParameters
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
 *           - true when pipeline parameter assignment successfully done or skipped, otherwise false;
 *           - string with assigned environment variable(s);
 *           - true when needs to count an error when pipeline parameter undefined and can't be assigned;
 *           - true when needs to warn when pipeline parameter undefined and can't be assigned.
 */
List handleAssignmentWhenPipelineParamIsUnset(Map settingsItem, Object envVariables) {
    if (!settingsItem.get('on_empty'))
        return [false, true, '', true, false]
    Boolean fail = settingsItem.on_empty.get('fail') ? settingsItem.on_empty.get('fail').asBoolean() : true
    Boolean warn = settingsItem.on_empty.get('warn').asBoolean()
    if (!settingsItem.on_empty.get('assign'))
        return [false, true, '', fail, warn]
    def (Boolean assignmentIsPossible, Boolean assignmentOk, String assignment) = getTemplatingFromVariables(
            settingsItem.on_empty.assign.toString(), envVariables)
    [assignmentIsPossible, assignmentOk, assignment, fail, warn]
}

/**
 * Template of assign variables inside string.
 *
 * @param assignment - string that probably contains environment variable(s) (e.g. '$FOO' or '$FOO somewhat $BAR text').
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param additionalVariablesBinding - additional (or non-environment) variables for templating.
 * @return - arrayList of:
 *           - true when assignment is possible;
 *           - true when assignment done without errors or skipped, otherwise false;
 *           - templated string.
 */
List getTemplatingFromVariables(String assignment, Object envVariables, Map additionalVariablesBinding = [:]) {
    Boolean assignmentOk = true
    List mentionedVariables = CF.getVariablesMentioningFromString(assignment)
    if (!mentionedVariables[0])
        return [false, assignmentOk, assignment]
    Map bindingVariables = CF.envVarsToMap(envVariables) + additionalVariablesBinding
    mentionedVariables.each { mentioned ->
        Boolean variableNameIsIncorrect = !checkEnvironmentVariableNameCorrect(mentioned)
        Boolean variableIsUndefined = !bindingVariables.containsKey(mentioned)
        String errMsg = "The value of this variable will be templated with '' (empty string)."
        assignmentOk = errorMsgWrapper(variableNameIsIncorrect, assignmentOk, 3,
                String.format("Incorrect variable name '%s' in '%s' value. %s", mentioned, assignment, errMsg))
        assignmentOk = errorMsgWrapper(variableIsUndefined, assignmentOk, 3,
                String.format("Specified '%s' variable in '%s' value is undefined. %s", mentioned, assignment, errMsg))
        bindingVariables[mentioned] = variableNameIsIncorrect || variableIsUndefined ? '' : bindingVariables[mentioned]
    }
    String assigned = new StreamingTemplateEngine().createTemplate(assignment).make(bindingVariables)
    [true, assignmentOk, assigned]
}

/**
 * Templating or assign map keys with variable(s).
 *
 * @param assignMap - map to assign keys in.
 * @param assignmentKeysList - list of keys in assignMap needs to be assigned.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param allAssignmentsPass - set if you wish to pass previous structure check state.
 * @param additionalVariablesBinding - additional (or non-environment) variables for templating.
 * @param keysDescription - Keys description for error output.
 * @return - arrayList of:
 *           - true when all keys was assigned without errors or assignment skipped, otherwise false;
 *           - map with assigned keys.
 */
List templatingMapKeysFromVariables(Map assignMap, List assignmentKeysList, Object envVariables,
                                    Boolean allAssignmentsPass = true, Map additionalVariablesBinding = [:],
                                    String keysDescription = 'Key') {
    Boolean allAssignmentsPassNew = allAssignmentsPass
    assignmentKeysList.each { currentKey ->
        if (assignMap.containsKey(currentKey) && assignMap[currentKey] instanceof String) {
            // groovylint-disable-next-line NoDef, VariableTypeRequired
            def (Boolean __, Boolean assignOk, String assigned) = getTemplatingFromVariables(assignMap[currentKey]
                    .toString(), envVariables, additionalVariablesBinding)
            allAssignmentsPassNew = errorMsgWrapper(!assignOk, allAssignmentsPass, 3,
                    String.format("%s '%s' with value '%s' wasn't set properly due to undefined variable(s).",
                            keysDescription, currentKey, assignMap[currentKey].toString()))
            assignMap[currentKey] = assigned
        }
    }
    [allAssignmentsPassNew, assignMap]
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
                def (Boolean paramNeedsToBeAssigned, Boolean assignmentOk, String parameterAssignment, Boolean fail,
                        Boolean warn) = handleAssignmentWhenPipelineParamIsUnset(it as Map, envVariables)
                if (paramNeedsToBeAssigned && assignmentOk && printableParameterName != '<undefined>' &&
                        parameterAssignment.trim()) {
                    envVariables[it.name.toString()] = parameterAssignment
                } else if (printableParameterName == '<undefined>' || (paramNeedsToBeAssigned && !assignmentOk)) {
                    assignMessage = assignmentOk ? '' : String.format("(can't be correctly assigned with '%s' %s) ",
                            it.on_empty.get('assign').toString(), 'variable')
                }
                allSet = !paramNeedsToBeAssigned && fail ? false : allSet
                errorMsgWrapper((warn || (fail && !allSet)), true, fail ? 3 : 2, String.format(
                        "'%s' pipeline parameter is required, but undefined %s%s. %s", printableParameterName,
                        assignMessage, 'for current job run', 'Please specify then re-build again.'))
            }
        }
    }
    [allSet, envVariables]
}

/**
 * Extract parameters arrayList from pipeline settings map (without 'required' and 'optional' map structure).
 *
 * @param pipelineSettings - pipeline settings map.
 * @param builtinPipelineParameters - additional built-in pipeline parameters arrayList.
 * @return - pipeline parameters arrayList.
 */
static List extractParamsListFromSettingsMap(Map pipelineSettings, List builtinPipelineParameters) {
    (pipelineSettings.get('parameters')) ? (pipelineSettings.parameters.get('required') ?: []) +
            (pipelineSettings.parameters.get('optional') ?: []) + builtinPipelineParameters : []
}

/**
 * Perform regex check and regex replacement of pipeline parameters for current job build.
 *
 * (Check match when current build pipeline parameter is not empty and a key 'regex' is defined in pipeline settings.
 * Also perform regex replacement of parameter value when 'regex_replace' key is defined).
 *
 * @param allPipelineParams - arrayList of pipeline parameters from settings.
 * @param pipelineParameters - pipeline parameters for current job build (actually requires a pass of 'params' which is
 *                             class java.util.Collections$UnmodifiableMap).
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param builtinPipelineParameters - additional built-in pipeline parameters arrayList.
 * @return - arrayList of:
 *           - true when all pass;
 *           - changed or unchanged environment variables for current job build.
 */
Boolean regexCheckAllRequiredPipelineParams(List allPipelineParams, Object pipelineParameters, Object envVariables) {
    Boolean allCorrect = true
    CF.outMsg(0, 'Starting regex check and regex replacement of pipeline parameters for current build.')
    if (allPipelineParams[0]) {
        allPipelineParams.each {
            def (String printableParamName, Boolean paramIsDefined) = getPipelineParamNameAndDefinedState(it as Map,
                    pipelineParameters, envVariables, false)
            /**
             * If regex was set, preform string concatenation for regex list items. Otherwise, regex value is a string
             * already.
             */
            if (it.get('regex')) {
                String regexPattern = ''
                if (it.regex instanceof ArrayList && (it.regex as ArrayList)[0]) {
                    it.regex.each { i -> regexPattern += i.toString() }
                } else if (!(it.regex instanceof ArrayList) && it.regex?.trim()) {
                    regexPattern = it.regex.toString()
                }
                errorMsgWrapper(regexPattern.trim() as Boolean, true, 0, String.format(
                        "Found '%s' regex for pipeline parameter '%s'.", regexPattern, printableParamName))
                allCorrect = errorMsgWrapper(paramIsDefined && regexPattern.trim() &&
                        !envVariables[it.name as String].matches(regexPattern), allCorrect, 3,
                        String.format('%s parameter is incorrect due to regex mismatch.', printableParamName))
            }
            /**
             * Perform regex replacement when regex_replace was set and pipeline parameter is defined for current build.
             */
            if (it.get('regex_replace')) {
                String msgTemplateNoValue =
                        "'%s' sub-key value of 'regex_replace' wasn't defined for '%s' pipeline parameter.%s"
                String msgTemplateWrongType =
                        "Wrong type of '%s' value sub-key of 'regex_replace' for '%s' pipeline parameter.%s"
                String msgRecommendation = ' Please fix them. Otherwise, replacement will be skipped with an error.'

                /** Handle 'to' sub-key of 'regex_replace' parameter item key. */
                String regexReplacement = it.regex_replace?.get('to')?.trim() ? it.regex_replace.get('to') : ''
                Boolean regexToKeyIsConvertibleToString = detectIsObjectConvertibleToString(it.regex_replace.get('to'))
                String pipelineParamKeysWrongTypeMsg = String.format(msgTemplateWrongType, 'to', printableParamName,
                        msgRecommendation)
                Boolean regexReplacementOk = errorMsgWrapper(regexReplacement?.trim() &&
                        !regexToKeyIsConvertibleToString, false, 3, pipelineParamKeysWrongTypeMsg)

                /** Handle 'regex' sub-key of 'regex_replace' parameter item key. */
                String regexPattern = it.regex_replace.get('regex')
                Boolean regexKeyIsConvertibleToString = detectIsObjectConvertibleToString(it.regex_replace.get('regex'))
                if (regexPattern?.length() && regexKeyIsConvertibleToString) {
                    errorMsgWrapper(!regexReplacement.trim(), false, 0, String.format(msgTemplateNoValue, 'to',
                            printableParamName, 'Regex match(es) will be removed.'))
                    if (paramIsDefined && printableParamName != '<undefined>') {
                        String pipelineParamReplacementMsg = String.format("Replacing '%s' regex to '%s' in '%s' %s...",
                                regexPattern, regexReplacement, printableParamName, 'pipeline parameter value')
                        regexReplacementOk = errorMsgWrapper(true, true, 0, pipelineParamReplacementMsg)
                        envVariables[it.name.toString()] = applyReplaceRegexItems(envVariables[it.name
                                .toString()] as String, [regexPattern], [regexReplacement])
                    }
                    regexReplacementOk = errorMsgWrapper(printableParamName == '<undefined>', regexReplacementOk, 3,
                            String.format("Replace '%s' regex to '%s' is not possible: 'name' key is %s. %s.",
                                    regexPattern, regexReplacement, 'not defined for pipeline parameter item.',
                                    'Please fix pipeline config. Otherwise, replacement will be skipped with an error'))
                } else if (regexPattern?.length() && !regexKeyIsConvertibleToString) {
                    CF.outMsg(3, String.format(msgTemplateWrongType, 'regex', printableParamName, msgRecommendation))
                } else {
                    CF.outMsg(3, String.format(msgTemplateNoValue, 'regex', printableParamName, msgRecommendation))
                }
                allCorrect = regexReplacementOk ? allCorrect : false
            }
        }
    }
    [allCorrect, envVariables]
}

/**
 * Processing wrapper pipeline parameters: check all parameters from pipeline settings are presents. If not inject
 * parameters to pipeline.
 *
 * @param pipelineParams - pipeline parameters in 'universal-wrapper-pipeline-settings' standard and built-in pipeline
 *                           parameters (e.g. 'DEBUG_MODE', etc) converted to arrayList.
 *                           See https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings for details.
 * @param currentPipelineParams - pipeline parameters for current job build (actually requires a pass of 'params'
 *                                which is class java.util.Collections$UnmodifiableMap). Set
 *                                currentPipelineParams.DRY_RUN to 'true' for dry-run mode.
 * @return - arrayList of:
 *           - true when there is no pipeline parameters in the pipelineSettings;
 *           - true when pipeline parameters processing pass.
 */
List wrapperPipelineParametersProcessing(List pipelineParams, Object currentPipelineParams) {
    def (Boolean noPipelineParams, Boolean allPass) = [true, true]
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
    [noPipelineParams, allPass]
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
    envVariables.getEnvironment().get(variableName)?.toBoolean()  // groovylint-disable-line UnnecessaryGetter
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
    pipelineParams.get(parameterName)?.toBoolean()
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
static Object getJenkinsNodeToExecuteByNameOrTag(Object env, String nodeParamName, String nodeTagParamName) {
    Object nodeToExecute = null
    // groovylint-disable-next-line UnnecessaryGetter
    nodeToExecute = (env.getEnvironment().containsKey(nodeTagParamName) && env[nodeTagParamName]?.trim()) ?
            [label: env[nodeTagParamName]] : nodeToExecute
    // groovylint-disable-next-line UnnecessaryGetter
    (env.getEnvironment().containsKey(nodeParamName) && env[nodeParamName]?.trim()) ? env[nodeParamName] : nodeToExecute
}

/**
 * Map to formatted string table with values replacement.
 *
 * @param sourceMap - source map to create text table from.
 * @param replaceKeyName - key name in source map to perform value replacement.
 * @param regexItemsList - list of regex items to apply for value replacement.
 * @param replaceItemsList - list of items for value replacement to replace with. List must be the same length as a
 *                           regexItemsList, otherwise will be replaced with empty line ''
 * @param formattedTable - pass a table header here.
 * @return - formatted string table results.
 */
static String mapToFormattedStringTable(Map sourceMap, String replaceKeyName = 'state',
                                        List regexItemsList = ['true', 'false'],
                                        List replaceItemsList = ['[PASS]', '[FAIL]'], String formattedTable = '') {
    def (Boolean createTable, Map tableColumnSizes) = [false, [:]]
    for (Integer i = 0; i < 2; i++) {
        sourceMap.each { sourceMapEntry ->
            sourceMapEntry.value.each { k, v ->
                String tableEntry = (replaceKeyName?.trim() && k == replaceKeyName) ?
                        applyReplaceRegexItems(v.toString(), regexItemsList, replaceItemsList) : v.toString()
                tableColumnSizes[k] = [tableColumnSizes?.get(k), tableEntry.length() + 2].max()
                Integer padSize = tableColumnSizes[k] - tableEntry.length()
                formattedTable += createTable ? String.format('%s%s', tableEntry, ' ' * padSize) : ''
            }
            formattedTable += createTable ? '\n' : ''
        }
        createTable = !createTable
    }
    formattedTable
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
List pipelineParamsProcessingWrapper(String settingsGitUrl, String defaultSettingsGitBranch,
                                     String settingsRelativePathPrefix, List pipelineNameRegexReplace,
                                     List builtinPipelineParameters, Object envVariables, Object pipelineParams) {
    /** Load all pipeline settings then check all current pipeline params are equal to params in pipeline settings. */
    String settingsRelativePath = String.format('%s/%s.yaml', settingsRelativePathPrefix,
            applyReplaceRegexItems(envVariables.JOB_NAME.toString(), pipelineNameRegexReplace))
    Map pipelineSettings = loadPipelineSettings(settingsGitUrl, defaultSettingsGitBranch, settingsRelativePath,
            getBooleanPipelineParamState(pipelineParams, 'DEBUG_MODE'))
    String pipelineFailReasonText = ''
    List allPipelineParams = extractParamsListFromSettingsMap(pipelineSettings, builtinPipelineParameters)
    def (Boolean noPipelineParamsInTheConfig, Boolean pipelineParamsProcessingPass) =
            wrapperPipelineParametersProcessing(allPipelineParams, pipelineParams)

    /** Check pipeline parameters in the settings are correct, all of them was defined properly for current build. */
    Boolean checkPipelineParametersPass = true
    if (noPipelineParamsInTheConfig && pipelineParamsProcessingPass) {
        CF.outMsg(1, 'No pipeline parameters in the config.')
    } else if (!noPipelineParamsInTheConfig) {
        CF.outMsg(0, 'Checking all pipeline parameters format in the settings.')
        checkPipelineParametersPass = checkPipelineParamsFormat(allPipelineParams)
        if (checkPipelineParametersPass || getBooleanPipelineParamState(pipelineParams)) {
            Boolean requiredPipelineParamsSet
            Boolean regexCheckAllRequiredPipelineParamsOk = true
            (requiredPipelineParamsSet, env) = checkAllRequiredPipelineParamsAreSet(pipelineSettings, pipelineParams,
                    envVariables)
            if (requiredPipelineParamsSet || getBooleanPipelineParamState(pipelineParams)) {
                (regexCheckAllRequiredPipelineParamsOk, env) = regexCheckAllRequiredPipelineParams(allPipelineParams,
                        pipelineParams, env)
            }
            pipelineFailReasonText += requiredPipelineParamsSet && regexCheckAllRequiredPipelineParamsOk ? '' :
                    'Required pipeline parameter(s) was not specified or incorrect. '
        }
    }
    [pipelineFailReasonText, pipelineParamsProcessingPass, checkPipelineParametersPass, pipelineSettings, env]
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
 *           - pipeline stages status map (format described in settings documentation:
 *             https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings);
 *           - true when checking and execution pass (or skipped), false on checking or execution errors;
 *           - return of environment variables ('env') that pass to function in 'envVariables'.
 */
List checkOrExecutePipelineWrapperFromSettings(Map pipelineSettings, Object envVariables, Boolean check = false,
                                               Boolean execute = true) {
    String currentSubjectMsg = 'in pipeline config'
    def (Map universalPipelineWrapperBuiltIns, Boolean executeOk) = [[:], true]
    def (String checkTypeMsg, String executeTypeMsg) = [check ? 'check' : '', execute ? 'execute' : '']
    String checkExecuteTypeMsg = check && execute ? ' and ' : ''
    String functionCallTypes = String.format('%s%s%s', checkTypeMsg, checkExecuteTypeMsg, executeTypeMsg)
    Boolean pipelineSettingsContainsStages = pipelineSettings?.get('stages')?.size()
    Boolean checkOk = errorMsgWrapper(!pipelineSettingsContainsStages && ((check &&
            getBooleanVarStateFromEnv(envVariables)) || execute), true, 0, String.format('No stages %s to %s.',
            functionCallTypes, currentSubjectMsg))
    /** When pipeline stages are in the config starting iterate of it's items for check and/or execute. */
    errorMsgWrapper(pipelineSettingsContainsStages, true, 0, String.format('Starting %s stages %s.', functionCallTypes,
            currentSubjectMsg))
    for (stageItem in pipelineSettings.stages) {
        Boolean stageOk
        (universalPipelineWrapperBuiltIns, stageOk, envVariables) = check ? checkOrExecuteStageSettingsItem(
                universalPipelineWrapperBuiltIns, stageItem as Map, pipelineSettings, envVariables) :
                [universalPipelineWrapperBuiltIns, true, envVariables]
        checkOk = stageOk ? checkOk : false
        if (execute) {
            stage(getPrintableValueKeyFromMapItem(stageItem as Map)) {
                (universalPipelineWrapperBuiltIns, stageOk, envVariables) = checkOrExecuteStageSettingsItem(
                        universalPipelineWrapperBuiltIns, stageItem as Map, pipelineSettings, envVariables, true, false)
                executeOk = stageOk ? executeOk : false
            }
        }
    }
    [universalPipelineWrapperBuiltIns, checkOk && executeOk, envVariables]
}

/**
 * Check or execute all actions in pipeline stage settings item.
 *
 * (Check actions in the stage, all ansible playbooks, ansible inventories, jobs, scripts or another action according to
 * requirements described here: https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings).
 *
 * @param universalPipelineWrapperBuiltIns - pipeline wrapper built-ins variable with report in various formats (see:
 *                                           https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings).
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
 *           - pipeline wrapper built-ins variable;
 *           - true when all stage actions execution/checking successfully done;
 *           - return of environment variables for current job build.
 */
List checkOrExecuteStageSettingsItem(Map universalPipelineWrapperBuiltIns, Map stageItem, Map pipelineSettings,
                                     Object envVariables, Boolean allPass = true, Boolean check = true) {
    def (Map actionsRuns, Boolean itsPass) = [[:], allPass]
    Map uniPipeWrapBuiltInsInChkExec = universalPipelineWrapperBuiltIns
    /** Handling 'name' (with possible assignment), 'actions' and 'parallel' stage keys. */
    itsPass = errorMsgWrapper(check && (!stageItem.containsKey('name') ||
            !detectIsObjectConvertibleToString(stageItem.get('name'))), itsPass, 3,
            "Unable to convert stage name to a string, probably it's undefined or empty.")
    String printableStageName = getPrintableValueKeyFromMapItem(stageItem)
    Boolean actionsIsNotList = stageItem.containsKey('actions') && !(stageItem.get('actions') instanceof ArrayList)
    itsPass = errorMsgWrapper(check && (!stageItem.containsKey('actions') || actionsIsNotList), itsPass, 3,
            String.format("Incorrect or undefined actions for '%s' stage.", printableStageName))
    itsPass = errorMsgWrapper(check && (stageItem.containsKey('parallel') &&
            !detectIsObjectConvertibleToBoolean(stageItem.get('parallel'))), itsPass, 3, String.format(
            "Unable to determine 'parallel' value for '%s' stage. Remove them or set as boolean.", printableStageName))
    (itsPass, stageItem) = templatingMapKeysFromVariables(stageItem, ['name'], envVariables, itsPass)
    List actionsInStage = actionsIsNotList ? [] : stageItem.get('actions') as ArrayList

    /** Creating map and processing items from 'actions' key. */
    actionsInStage.eachWithIndex { item, Integer index ->
        actionsRuns[index] = {
            String checkOrExecuteMsg = check ? 'Checking' : 'Executing'
            String actionRunsMsg = String.format("action#%s from '%s' stage", index.toString(), printableStageName)
            CF.outMsg(check ? 0 : 1, String.format('%s %s', checkOrExecuteMsg, actionRunsMsg))
            Boolean checkOrExecuteOk
            (uniPipeWrapBuiltInsInChkExec, checkOrExecuteOk, envVariables) = checkOrExecutePipelineActionItem(
                    uniPipeWrapBuiltInsInChkExec, printableStageName, actionsInStage[index] as Map,
                    pipelineSettings, index, envVariables, check)
            itsPass = checkOrExecuteOk ? itsPass : false
            CF.outMsg(0, String.format('%s %s finished. Total:\n%s', checkOrExecuteMsg, actionRunsMsg,
                    CF.readableMap(uniPipeWrapBuiltInsInChkExec)))
        }
    }
    if (stageItem.get('parallel')?.toBoolean()) {
        parallel actionsRuns
    } else {
        actionsRuns.each {
            it.value.call()
        }
    }
    Map multilineStagesReportMap = uniPipeWrapBuiltInsInChkExec?.get('multilineReportStagesMap') ?
            uniPipeWrapBuiltInsInChkExec.multilineReportStagesMap as Map : [:]
    String stageStatusDetails = stageItem.actions?.size() ? String.format('%s action%s%s.', actionsInStage?.size(),
            actionsInStage?.size() > 1 ? 's' : '', stageItem.get('parallel') ? ' in parallel' : '') : '<no actions>'
    uniPipeWrapBuiltInsInChkExec.multilineReportStagesMap = CF.addPipelineStepsAndUrls(multilineStagesReportMap,
            printableStageName, itsPass, stageStatusDetails, '', false)
    uniPipeWrapBuiltInsInChkExec = updateWrapperBuiltInsInStringFormat(uniPipeWrapBuiltInsInChkExec,
            'multilineReportStages')
    [uniPipeWrapBuiltInsInChkExec, itsPass, envVariables]
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
Boolean checkListOfKeysFromMapProbablyStringOrBoolean(Boolean check, List listOfKeys, Map map, Boolean isString,
                                                      String index, Boolean currentStatus = true) {
    Boolean currentStatusInCheckListOfMapKeys = currentStatus
    listOfKeys.each {
        Boolean typeOk = isString ? detectIsObjectConvertibleToString(map.get(it)) :
                detectIsObjectConvertibleToBoolean(map.get(it))
        if (map.containsKey(it) && !typeOk) {
            currentStatusInCheckListOfMapKeys = errorMsgWrapper(check, currentStatusInCheckListOfMapKeys, 3,
                    String.format("'%s' key in '%s' should be a %s.", it, index, isString ? 'string' : 'boolean'))
        } else if (map.containsKey(it) && !map.get(it)?.toString()?.length()) {
            currentStatusInCheckListOfMapKeys = errorMsgWrapper(check, currentStatusInCheckListOfMapKeys, 2,
                    String.format("'%s' key defined for '%s', but it's empty. Remove a key or define it's value.", it,
                            index))
        }
    }
    currentStatusInCheckListOfMapKeys
}

/**
 * Incompatible keys in map found error message wrapper.
 *
 * @param keys - arrayList of: First keyName, second keyName, etc...
 * @param keyDescriptionMessagePrefix - just a prefix for an error message what keys are incompatible.
 * @param onlyOneOfThem - just a postfix message that means only one key required.
 * @return - formatted error message.
 */
static String incompatibleKeysMsgWrapper(List keysForMessage, String keyDescriptionMessagePrefix = 'Keys',
                                         Boolean onlyOneOfThem = true) {
    String.format('%s %s are incompatible.%s', keyDescriptionMessagePrefix, arrayListToReadableString(keysForMessage),
            onlyOneOfThem ? ' Please define only one of them.' : '')
}

/**
 * Check keys are not empty and convertible to required type and check incompatible keys.
 *
 * @param actionItem - source (non-templated) action item to check or execute.
 * @param printableStageAndAction - printable stage and action for messaging.
 * @param envVariables - environment variables for current job build.
 * @param check - set false to execute action item, true to check.
 * @return - arrayList of:
 *           - true when action item structure is ok;
 *           - templated action item to check or execute.
 */
List checkKeysNotEmptyAndConvertibleToReqType(Map actionItem, String printableStageAndAction, Object envVariables,
                                              Boolean check) {
    List stringKeys = ['before_message', 'after_message', 'fail_message', 'success_message', 'dir', 'build_name']
    List booleanKeys = ['ignore_fail', 'stop_on_fail', 'success_only', 'fail_only']
    Boolean actionStructureOk = checkListOfKeysFromMapProbablyStringOrBoolean(check, stringKeys, actionItem, true,
            printableStageAndAction, true)
    actionStructureOk = checkListOfKeysFromMapProbablyStringOrBoolean(check, booleanKeys, actionItem, false,
            printableStageAndAction, actionStructureOk)
    actionStructureOk = errorMsgWrapper(check && actionItem.containsKey('success_only') && actionItem
            .containsKey('fail_only'), actionStructureOk, 3, incompatibleKeysMsgWrapper(['success_only', 'fail_only']))
    templatingMapKeysFromVariables(actionItem, stringKeys + ['action', 'node'] as ArrayList, envVariables,
            actionStructureOk, [:], 'Action key')
}

/**
 * Check action item defined properly or execute action item from stage.
 *
 * @param universalPipelineWrapperBuiltIns - pipeline settings built-ins variable with report in various formats (see:
 *                                           https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings).
 * @param stageName - the name of the current stage from which to test or execute the action item (just for logging
 *                    in all action status map - see @return of this function).
 * @param actionItemSource - source (non-templated) action item to check or execute.
 * @param pipelineSettings - the whole pipeline settings map (pre-converted from yaml) to check and/or execute.
 * @param actionIndex - number of current action in stages.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl). Set 'DRY_RUN' environment variable
 *                       (or pipeline parameter) as an element of envVariables to true for dry run mode on execution.
 *                       Set 'DEBUG_MODE' to enable debug mode both for 'check' or 'execute'.
 * @param check - set false to execute action item, true to check.
 * @return - arrayList of:
 *           - pipeline settings built-ins variable;
 *           - true when all stage actions execution successfully done;
 *           - environment variables ('env').
 */
List checkOrExecutePipelineActionItem(Map universalPipelineWrapperBuiltIns, String stageName, Map actionItemSource,
                                      Map pipelineSettings, Integer actionIndex, Object envVariables, Boolean check) {
    def (Boolean actionLinkOk, Map nodeItem, String actionDescription) = [true, [:], '<skipped>']
    String printableStageAndAction = String.format('%s [%s]', stageName, actionIndex)
    String keyWarnOrErrMsgTemplate = "Wrong format of node %skey '%s' for '%s' action. %s"
    def (Boolean actionStructureOk, Map actionItem) = checkKeysNotEmptyAndConvertibleToReqType(actionItemSource,
            printableStageAndAction, envVariables, check)
    /** Check node keys and sub-keys defined properly. */
    Boolean anyJenkinsNode = (actionItem.containsKey('node') && !actionItem.get('node'))
    Boolean nodeIsStringConvertible = detectIsObjectConvertibleToString(actionItem.get('node'))
    if (nodeIsStringConvertible || anyJenkinsNode) {
        errorMsgWrapper(anyJenkinsNode, true, 0, String.format("'node' key in '%s' action is null. %s",
                'This stage will run on any free Jenkins node.', printableStageAndAction))
        nodeItem.node = [:]
        nodeItem.node.name = nodeIsStringConvertible ? actionItem.node.toString() : actionItem.get('node')?.get('name')
    } else if (actionItem.get('node') instanceof Map) {
        nodeItem = actionItem.get('node') as Map
        /** Check only one of 'node' sub-keys 'name' or 'label' defined and it's correct. */
        List nodeSubKeyNames = ['name', 'label']
        Boolean onlyNameOrLabelDefined = actionItem.node.containsKey('name') ^ actionItem.node.containsKey('label')
        actionStructureOk = errorMsgWrapper(check && !onlyNameOrLabelDefined, actionStructureOk, 2,
                incompatibleKeysMsgWrapper(nodeSubKeyNames, 'Node sub-keys'))
        actionStructureOk = detectNodeSubKeyConvertibleToString(check, !onlyNameOrLabelDefined, actionStructureOk,
                actionItem, printableStageAndAction, keyWarnOrErrMsgTemplate, 'name')
        actionStructureOk = detectNodeSubKeyConvertibleToString(check, !onlyNameOrLabelDefined, actionStructureOk,
                actionItem, printableStageAndAction, keyWarnOrErrMsgTemplate, 'label')
        /** Check when 'pattern' node sub-key defined and boolean. */
        if (checkListOfKeysFromMapProbablyStringOrBoolean(check, ['pattern'], actionItem.node as Map, false,
                printableStageAndAction)) {
            nodeItem.pattern = actionItem.node.get('pattern')?.toBoolean()
        } else {
            actionStructureOk = errorMsgWrapper(check, actionStructureOk, 2, String.format(keyWarnOrErrMsgTemplate,
                    'sub-', 'pattern', printableStageAndAction, 'Sub-key should be boolean.'))
            nodeItem.node.remove('pattern')
        }
        /** Templating node sub-keys. */
        (actionStructureOk, nodeItem) = templatingMapKeysFromVariables(nodeItem, nodeSubKeyNames, envVariables,
                actionStructureOk, [:], 'Node (sub-keys of action key)')
    } else if (actionItem.containsKey('node') && !anyJenkinsNode && !(actionItem.get('node') instanceof Map)) {
        actionStructureOk = errorMsgWrapper(check, actionStructureOk, 3, String.format(keyWarnOrErrMsgTemplate, '',
                'node', printableStageAndAction, 'Key will be ignored.'))
    }
    /** Check or execute current action when 'action' key is correct and possible success_/fail_only conditions met. */
    Boolean actionIsCorrect = checkListOfKeysFromMapProbablyStringOrBoolean(check, ['action'], actionItem, true,
            printableStageAndAction)
    actionStructureOk = errorMsgWrapper(check && actionItem.containsKey('action') && !actionIsCorrect,
            actionStructureOk, 3, String.format("Wrong format of 'action' key in '%s'.", printableStageAndAction))
    Boolean successOnlyActionConditionNotMet = actionItem.get('success_only') && currentBuild.result == 'FAILURE'
    Boolean failOnlyActionConditionNotMet = actionItem.get('fail_only') && currentBuild.result != 'FAILURE'
    Boolean allActionConditionsMet = !check && !successOnlyActionConditionNotMet && !failOnlyActionConditionNotMet
    String actionSkipMsgReason = !check && successOnlyActionConditionNotMet && !actionItem.containsKey('fail_only') ?
            'success_only' : ''
    actionSkipMsgReason += !check && failOnlyActionConditionNotMet && !actionItem.containsKey('success_only') ?
            'fail_only' : ''
    errorMsgWrapper(actionSkipMsgReason.trim() as Boolean, true, 0,
            String.format("'%s' will be skipped by conditions met: %s", printableStageAndAction, actionSkipMsgReason))
    if (actionIsCorrect && (check || allActionConditionsMet)) {
        actionMessageOutputWrapper(check, actionItem, 'before', envVariables)
        currentBuild.displayName = !check && actionItem.get('build_name') ? actionItem.get('build_name') :
                currentBuild.displayName
        /** Directory change wrapper. */
        String actionItemCurrentDirectory = actionItem.get('dir')?.toString() ?: ''
        if (!check && actionItemCurrentDirectory.trim()) {
            dir(actionItemCurrentDirectory) {
                (actionLinkOk, actionDescription, universalPipelineWrapperBuiltIns, envVariables) =
                        checkOrExecutePipelineActionLink(actionItem.action as String, nodeItem?.get('node') as Map,
                                pipelineSettings, envVariables, check, universalPipelineWrapperBuiltIns)
            }
        } else {
            (actionLinkOk, actionDescription, universalPipelineWrapperBuiltIns, envVariables) =
                    checkOrExecutePipelineActionLink(actionItem.action as String, nodeItem?.get('node') as Map,
                            pipelineSettings, envVariables, check, universalPipelineWrapperBuiltIns)
        }
        /** Processing post-messages and/or 'ignore_fail' keys. */
        actionMessageOutputWrapper(check, actionItem, 'after', envVariables)
        actionMessageOutputWrapper(check, actionItem, actionLinkOk ? 'success' : 'fail', envVariables)
        actionLinkOk = actionItem.get('ignore_fail') && !check ? true : actionLinkOk
    } else if (!actionItem.containsKey('action')) {
        actionStructureOk = errorMsgWrapper(check, actionStructureOk, check ? 3 : 2,
                String.format("No 'action' key specified, nothing to %s '%s' action.",
                        check ? 'check in' : 'perform at', printableStageAndAction))
    }
    /** Processing action link state, updating results of current build and actions report, stop on fail handle. */
    Boolean actionStructureAndLinkOk = actionStructureOk && actionLinkOk
    Map multilineReportMap = universalPipelineWrapperBuiltIns?.get('multilineReportMap') ?
            universalPipelineWrapperBuiltIns.multilineReportMap as Map : [:]
    universalPipelineWrapperBuiltIns.multilineReportMap = CF.addPipelineStepsAndUrls(multilineReportMap,
            printableStageAndAction, actionStructureAndLinkOk, actionDescription)
    Map universalPipelineWrapperBuiltInsNew = updateWrapperBuiltInsInStringFormat(universalPipelineWrapperBuiltIns)
    if (actionItem.get('stop_on_fail') && !check && !actionLinkOk)
        error String.format("Terminating current pipeline run due to an error in '%s' %s.", printableStageAndAction,
                "('stop_on_fail' is enabled for current action)")
    [universalPipelineWrapperBuiltInsNew, actionStructureAndLinkOk, envVariables]
}

/**
 * Updates 'pipeline wrapper builtins' variable keys, that contains string tables for reports.
 *
 * @param pipelineWrapperBuiltIns - universal pipeline wrapper built-ins map.
 * @param keyNamePrefix - key name prefix: key that will be created in pipelineWrapperBuiltIns, key name prefix plus
 *                        'Map' string to take data from and key name prefix plus 'Failed' to write only failed items.
 * @return - universal pipeline wrapper built-ins map.
 */
Map updateWrapperBuiltInsInStringFormat(Map pipelineWrapperBuiltIns, String keyNamePrefix = 'multilineReport') {
    Map wrapperBuiltInsStatusMap = pipelineWrapperBuiltIns[String.format('%sMap', keyNamePrefix)] as Map
    pipelineWrapperBuiltIns[keyNamePrefix] = mapToFormattedStringTable(wrapperBuiltInsStatusMap)
    pipelineWrapperBuiltIns[String.format('%sFailed', keyNamePrefix)] = CF.grepFailedStates(pipelineWrapperBuiltIns,
            keyNamePrefix, '[FAIL]')
    pipelineWrapperBuiltIns
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
// groovylint-disable-next-line MethodReturnTypeRequired, NoDef
def actionMessageOutputWrapper(Boolean check, Map actionItem, String messageType, Object envVariables) {
    String messageKey = String.format('%s_message', messageType)
    String messageText = getBooleanVarStateFromEnv(envVariables) ? String.format('%s %s: %s', messageType.capitalize(),
            'message', actionItem.get(messageKey)) : actionItem.get(messageKey)
    errorMsgWrapper(detectIsObjectConvertibleToString(actionItem.get(messageKey)) && !check, true,
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
    String nodeNameOrLabelErrMsg = String.format(keyWarnOrErrorMsgTemplate, 'sub-', nodeSubKeyName,
            printableStageAndAction, '')
    nodeNameOrLabelDefined && !detectIsObjectConvertibleToString(actionItem.node.get(nodeSubKeyName)) ?
            errorMsgWrapper(check, actionStructureOk, 3, nodeNameOrLabelErrMsg) : actionStructureOk
}

/**
 * Get dry-run state and pipeline action message.
 *
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param actionName - action name just to print.
 * @param printableActionLinkItem - action link item.
 * @param actionLinkItemKeysFilter - keys to filter from action link item (required keys for the action).
 * @return - arrayList of:
 *           - true when dry-run is enabled;
 *           - action message to print before action run.
 */
static List getDryRunStateAndActionMsg(Object envVariables, String actionName, Map printableActionLinkItem,
                                       List actionLinkItemKeysFilter) {
    Boolean dryRunAction = getBooleanVarStateFromEnv(envVariables, 'DRY_RUN')
    Map printableActionLinkItemTemp = findMapItemsFromList(printableActionLinkItem, actionLinkItemKeysFilter)
    String actionMsgDetails = String.format(' %s', printableActionLinkItemTemp.size() > 0 ?
            printableActionLinkItemTemp.toString() : '')
    [dryRunAction, String.format('%s%s%s', dryRunAction ? 'dry-run of ' : '', actionName, actionMsgDetails)]
}

/**
 * Check or execute action link.
 *
 * @param actionLink - action link.
 * @param nodeItem - map with node-related keys.
 * @param pipelineSettings - all universal pipeline settings to take action from by action link.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param check - true when check, false when exectue.
 * @param universalPipelineWrapperBuiltIns - pipeline wrapper built-ins variable with report in various formats (see:
 *                                           https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings).
 * @param nodePipelineParameterName - jenkins pipeline parameter name of specified node name.
 * @param nodeTagPipelineParameterName - jenkins pipeline parameter name of specified node tag.
 * @return - arrayList of:
 *           - true when action link is ok;
 *           - action description for logging in reports;
 *           - universtal pipeline built-ins return;
 *           - environment variables return.
 */
List checkOrExecutePipelineActionLink(String actionLink, Map nodeItem, Map pipelineSettings, Object envVariables,
                                      Boolean check, Map universalPipelineWrapperBuiltIns,
                                      String nodePipelineParameterName = 'NODE_NAME',
                                      String nodeTagPipelineParameterName = 'NODE_TAG') {
    String actionDetails = ''
    def (Boolean actionLinkIsDefined, Map actionLinkItem) = getMapSubKey(actionLink, pipelineSettings)
    Boolean actionOk = errorMsgWrapper(!actionLinkIsDefined && check, true, 3,
            String.format("Action '%s' is not defined or incorrect data type in value.", actionLink))
    /** Every action closure return actionOk, actionMsg and universalPipelineWrapperBuiltIns. */
    Map detectByKeys = [
            repo_url   : {
                actionCloneGit(actionLink, actionLinkItem, envVariables, check, actionOk,
                        universalPipelineWrapperBuiltIns)
            },
            collections: {
                actionInstallAnsibleCollections(actionLink, actionLinkItem, envVariables, check, actionOk,
                        universalPipelineWrapperBuiltIns)
            },
            playbook   : {
                // TODO: check order of function return
                actionAnsiblePlaybookOrScriptRun(actionLink, pipelineSettings, envVariables, check, actionOk,
                                universalPipelineWrapperBuiltIns, false)
            },
            pipeline   : {
                actionDownstreamJobRun(actionLink, actionLinkItem, envVariables, check, actionOk,
                        universalPipelineWrapperBuiltIns)
            },
            stash      : {
                actionUnStash(actionLink, actionLinkItem, envVariables, check, actionOk,
                        universalPipelineWrapperBuiltIns)
            },
            unstash    : {
                actionUnStash(actionLink, actionLinkItem, envVariables, check, actionOk,
                        universalPipelineWrapperBuiltIns, false)
            },
            artifacts  : {
                actionArchiveArtifacts(actionLink, actionLinkItem, envVariables, check, actionOk,
                        universalPipelineWrapperBuiltIns)
            },
            script     : {
                actionAnsiblePlaybookOrScriptRun(actionLink, pipelineSettings, envVariables, check, actionOk,
                        universalPipelineWrapperBuiltIns, true)
            },
            report     : {
                // TODO: fix change of pipelineSettings here
                actionSendReport(actionLink, actionLinkItem, envVariables, check, actionOk,
                        universalPipelineWrapperBuiltIns)
            }
    ]

    /** Determining action by defined keys in 'actions' settings item, check that no incompatible keys defined. */
    Map keysFound = detectByKeys.findAll { k, v -> actionLinkItem.containsKey(k) }
    errorMsgWrapper(check && keysFound?.size() > 1, actionOk, 2, String.format("%s '%s' %s. %s '%s' %s", 'Keys in',
            actionLink, incompatibleKeysMsgWrapper(keysFound.keySet() as ArrayList, ''), 'Only', keysFound.keySet()[0],
            'will be used on action run.'))
    actionOk = errorMsgWrapper(!keysFound && actionOk, actionOk, 3, String.format("%s %s '%s'. %s: %s.",
            check ? "Can't" : "Nothing to execute due to can't", 'determine any action in', actionLink,
            'Possible keys are', mapItemsToReadableListString(detectByKeys)))

    /** Handling node selection keys: if name key exists use value from them, otherwise use label key. */
    Object currentNodeData = getJenkinsNodeToExecuteByNameOrTag(envVariables, nodePipelineParameterName,
            nodeTagPipelineParameterName)
    Object changeNodeData = currentNodeData
    if (nodeItem?.containsKey('name')) {
        List nodeNames = nodeItem?.get('pattern') && nodeItem?.get('name') ? CF.getJenkinsNodes(nodeItem.get('name')) :
                [nodeItem?.get('name')]
        changeNodeData = !nodeItem.get('name') || !nodeNames ? null : nodeNames[0]
    } else if (!nodeItem?.containsKey('name') && nodeItem?.get('label')) {
        changeNodeData = [label: nodeItem?.get('pattern') && nodeItem?.get('label') ?
                CF.getJenkinsNodes(nodeItem.get('label'), true)?.first() : nodeItem?.get('label')]
    }

    /** Executing determined action with possible node change or check without node change. */
    if (keysFound) {
        if (!check && currentNodeData.toString() != changeNodeData.toString()) {
            node(changeNodeData) {
                String nodeSelectionPrintable = changeNodeData instanceof Map ? String.format("node with label '%s'",
                        changeNodeData.label) : String.format('%s node', (changeNodeData) ?: 'any')
                CF.outMsg(0, String.format("Executing '%s' action on %s...", actionLink, nodeSelectionPrintable))
                // TODO: assigning from .call() is ok, but why it's marked as can't assign Object to Map?
                (actionOk, actionDetails, universalPipelineWrapperBuiltIns) = keysFound[keysFound.keySet()[0]].call()
            }
        } else {
            (actionOk, actionDetails, universalPipelineWrapperBuiltIns) = keysFound[keysFound.keySet()[0]].call()
        }
    }
    actionDetails = String.format('%s: %s', actionLink, (keysFound) ? actionDetails : '<undefined or incorrect key(s)>')
    if (!check && !actionOk) currentBuild.result = 'FAILURE'
    universalPipelineWrapperBuiltIns.currentBuild_result = currentBuild.result?.trim() ?: 'SUCCESS'
    [actionOk, actionDetails, universalPipelineWrapperBuiltIns, envVariables]
}

/**
 * Pipeline action: clone git sources from URL.
 *
 * @param actionLink - message prefix for possible errors.
 * @param actionLinkItem - action link item to check or execute.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl). Set 'DRY_RUN' environment variable
 *                       (or pipeline parameter) as an element of envVariables to true for dry run mode on execution.
 * @param check - set false to execute action item, true to check.
 * @param actionOk - just to pass previous action execution/checking state.
 * @param universalPipelineWrapperBuiltIns - pipeline wrapper built-ins variable with report in various formats (see:
 *                                           https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings).
 * @param gitDefaultCredentialsId - Git credentials ID for git authorisation to clone project.
 * @return - arrayList of:
 *           - true when success, false when failed;
 *           - action details for logging.
 */
List actionCloneGit(String actionLink, Map actionLinkItem, Object envVariables, Boolean check, Boolean actionOk,
                    Map universalPipelineWrapperBuiltIns, String gitDefaultCredentials = GV.GIT_CREDENTIALS_ID) {
    def (String actionName, Boolean newActionOk) = ['git clone', actionOk]
    List stringKeys = ['repo_url', 'repo_branch', 'directory', 'credentials']
    (newActionOk, actionLinkItem) = checkAndTemplateKeysActionWrapper(envVariables, universalPipelineWrapperBuiltIns,
            check, newActionOk, actionLink, actionLinkItem, stringKeys)
    Boolean repoUrlIsDefined = actionLinkItem?.get('repo_url')
    newActionOk = errorMsgWrapper(!repoUrlIsDefined, newActionOk, 3,
            String.format("Unable to %s: 'repo_url' is not defined for %s.", actionName, actionLink))
    Map printableActionLinkItem = actionLinkItem + [credentials: actionLinkItem.get('credentials') ?
            hidePasswordString(actionLinkItem.credentials as String) : null]
    Closure actionClosure = {
        CF.cloneGitToFolder(actionLinkItem?.get('repo_url'), actionLinkItem.get('repo_branch') ?: 'main',
                actionLinkItem?.get('directory') ?: '', actionLinkItem?.get('credentials') ?: gitDefaultCredentials)
        [newActionOk, universalPipelineWrapperBuiltIns, null]
    }
    /** Returns newActionOk, actionMsg, universalPipelineWrapperBuiltIns, additionalObject (will be ignored). */
    actionClosureWrapperWithTryCatch(check, envVariables, actionClosure, actionLink, actionName,
            printableActionLinkItem, stringKeys, newActionOk, universalPipelineWrapperBuiltIns)
}

/**
 * Pipeline action closure wrapper.
 *
 * @param check - set false to execute action item, true to check.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl). Set 'DRY_RUN' environment variable
 *                       (or pipeline parameter) as an element of envVariables to true for execution skip.
 * @param actionClosure - pipeline action closure to execute.
 * @param actionLink - message prefix for possible errors.
 * @param actionName - type of the current action to output messages and logging.
 * @param printableActionLinkItem - just a printable version of actionLinkItem when you need to hide or replace some
 *                                  key values.
 * @param actionKeysFilterLists - list of keys that is required for current action.
 * @param actionOk - just to pass previous action execution/checking state.
 * @param universalPipelineWrapperBuiltIns - pipeline wrapper built-ins variable (see:
 *                                           https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings).
 * @return - arrayList of:
 *           - true when success, false when failed;
 *           - action details for logging;
 *           - pipeline wrapper built-ins variable return;
 *           - additional untyped object (e.g. for run wrapper object return of downstream pipeline runs).
 */
List actionClosureWrapperWithTryCatch(Boolean check, Object envVariables, Closure actionClosure, String actionLink,
                                      String actionName, Map printableActionLinkItem, List actionKeysFilterLists,
                                      Boolean actionOk, Map universalPipelineWrapperBuiltIns) {
    def (Object additionalObject, Boolean newActionOk) = [null, actionOk]
    def (Boolean dryRunAction, String actionMsg) = getDryRunStateAndActionMsg(envVariables, actionName,
            printableActionLinkItem, actionKeysFilterLists)
    if (!check && !dryRunAction) {
        try {
            CF.outMsg(0, String.format('Performing %s', actionMsg))
            (newActionOk, universalPipelineWrapperBuiltIns, additionalObject) = actionClosure.call()
        } catch (Exception err) {
            newActionOk = errorMsgWrapper(true, newActionOk, 3, String.format("Error %s in '%s': %s", actionMsg,
                    actionLink, CF.readableError(err)))
        }
    }
    [newActionOk, actionMsg, universalPipelineWrapperBuiltIns, additionalObject]
}

/**
 * Update environment variables from map keys (e.g. universalPipelineWrapperBuiltIns).
 *
 * @param mapToUpdateFrom - map to update from.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @return - updated environment variables.
 */
static Object updateEnvFromMapKeys(Map mapToUpdateFrom, Object envVariables) {
    mapToUpdateFrom.each { mapToUpdateFromKey, mapToUpdateFromValue ->
        envVariables[mapToUpdateFromKey.toString()] = mapToUpdateFromValue.toString()
    }
    envVariables
}

/**
 * Pipeline action: install ansible collections from ansible galaxy.
 *
 * @param actionLink - message prefix for possible errors.
 * @param actionLinkItem - action link item to check or execute.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl). Set 'DRY_RUN' environment variable
 *                       (or pipeline parameter) as an element of envVariables to true for dry run mode on execution.
 * @param check - set false to execute action item, true to check.
 * @param actionOk - just to pass previous action execution/checking state.
 * @param universalPipelineWrapperBuiltIns - pipeline wrapper built-ins variable with report in various formats (see:
 *                                           https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings).
 * @return - arrayList of:
 *           - true when success, false when failed;
 *           - action details for logging.
 */
List actionInstallAnsibleCollections(String actionLink, Map actionLinkItem, Object envVariables, Boolean check,
                                     Boolean actionOk, Map universalPipelineWrapperBuiltIns) {
    def (String actionName, Boolean newActionOk) = ['install ansible collection', actionOk]
    Boolean collectionsKeyIsCorrect = actionLinkItem?.get('collections') instanceof ArrayList ||
            actionLinkItem?.get('collections') instanceof String
    newActionOk = errorMsgWrapper(!collectionsKeyIsCorrect, newActionOk, 3, String.format(
            "Unable to %s in '%s' action: 'collections' key should be string or list.", actionName, actionLink))
    List ansibleCollections = (collectionsKeyIsCorrect && actionLinkItem.collections instanceof String) ?
            [actionLinkItem.collections] : []
    ansibleCollections = (collectionsKeyIsCorrect && actionLinkItem.collections instanceof ArrayList) ?
            actionLinkItem.collections as ArrayList : []
    ansibleCollections.eachWithIndex { ansibleEntry, Integer ansibleCollectionsListIndex ->
        Boolean ansibleEntryIsString = ansibleEntry instanceof String
        if (ansibleEntryIsString) {
            def (Boolean __, Boolean assignOk, String assignment) = getTemplatingFromVariables(ansibleEntry as String,
                    envVariables, universalPipelineWrapperBuiltIns)
            ansibleCollections[ansibleCollectionsListIndex] = assignment
            newActionOk = errorMsgWrapper(!assignOk, assignOk, 3,
                    String.format("'%s' %s item in '%s' action wasn't set properly due to undefined variable(s).",
                            ansibleEntry, actionName, actionLink))
        }
        newActionOk = errorMsgWrapper(!ansibleEntryIsString, newActionOk, 3, String.format(
                "'%s' %s item in '%s' should be string.", ansibleEntry.toString(), actionName, actionLink))
    }
    Closure actionClosure = {
        ansibleCollections.each { ansibleCollectionsItem ->
            sh String.format('ansible-galaxy collection install %s -f', ansibleCollectionsItem)
        }
        [newActionOk, universalPipelineWrapperBuiltIns, null]
    }
    actionClosureWrapperWithTryCatch(check, envVariables, actionClosure, actionLink, actionName, actionLinkItem,
            ['collections'], newActionOk, universalPipelineWrapperBuiltIns)
}

/**
 * Check type for list of keys from map and template map keys pipeline action wrapper.
 *
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param universalPipelineWrapperBuiltIns - pipeline wrapper built-ins variable with report in various formats (see:
 *                                           https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings).
 * @param check - set false to execute action item, true to check.
 * @param actionOk - just to pass previous action execution/checking state.
 * @param messagePrefix - message prefix for possible errors.
 * @param mapToCheckAndTemplate - map to check and templating keys in.
 * @param keyInfo - action key description for error message.
 * @param stringKeys - list of action item keys which should be strings.
 * @param booleanKeys - list of action item keys which should be booleans.
 * @param templateKeys - true when templating keys is required.
 * @return - arrayList of:
 *           - true when checking keys type and templating done without errors;
 *           - templated action link item.
 */
List checkAndTemplateKeysActionWrapper(Object envVariables, Map universalPipelineWrapperBuiltIns, Boolean check,
                                       Boolean actionOk, String messagePrefix, Map mapToCheckAndTemplate,
                                       List stringKeys, String keyDescription = 'action key', List booleanKeys = [],
                                       Boolean templateKeys = true) {
    Boolean newActionOk = checkListOfKeysFromMapProbablyStringOrBoolean(check && stringKeys.size() > 0, stringKeys,
            mapToCheckAndTemplate, true, messagePrefix, actionOk)
    newActionOk = checkListOfKeysFromMapProbablyStringOrBoolean(check && booleanKeys.size() > 0, booleanKeys,
            mapToCheckAndTemplate, false, messagePrefix, newActionOk)
    if (templateKeys) {
        (newActionOk, mapToCheckAndTemplate) = templatingMapKeysFromVariables(mapToCheckAndTemplate, stringKeys,
                envVariables, newActionOk, universalPipelineWrapperBuiltIns, String.format("'%s' %s", messagePrefix,
                keyDescription))
    }
    [newActionOk, mapToCheckAndTemplate]
}

/**
 * Get map sub-key wrapper.
 *
 * @param subKeyNameToGet - sub-key name to get (e.g. action link to get item from 'action' key of pipeline settings).
 * @param map - map to get sub-key from (e.g. pipeline settings).
 * @param keyNameToGetFrom - key name to get sub-key from (e.g. 'action').
 * @return - arrayList of:
 *           - true getting sub-key successfully done;
 *           - sub-key item data (e.g. action link item).
 */
static List getMapSubKey(String subKeyNameToGet, Map mapToGetFrom, String keyNameToGetFrom = 'actions') {
    Boolean subKeyDefined = (subKeyNameToGet && mapToGetFrom?.get(keyNameToGetFrom) &&
            mapToGetFrom.get(keyNameToGetFrom)?.containsKey(subKeyNameToGet))
    [subKeyDefined, subKeyDefined ? mapToGetFrom.get(keyNameToGetFrom)?.get(subKeyNameToGet) : [:]]
}

/**
 * Check value type, template and check mandatory map keys, then filter required keys from this map.
 *
 * @param map - map to check and filter keys.
 * @param mandatoryKeysToCheck - list of mandatory keys to check.
 * @param stringKeys - list of map keys which should be strings (also keys to filter).
 * @param booleanKeys - list of map keys which should be booleans (also keys to filter).
 * @param state - current state to pass or change (true when ok).
 * @param enableCheck - true on check mode, false to skip checking.
 * @param errorMessageTemplate - description of all keys just to print message (e.g. action key).
 * @param envVariables - environment variables ('env' which is class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param universalPipelineWrapperBuiltIns - pipeline wrapper built-ins variable.
 * @return - arrayList of:
 *           - list of mandatory key values;
 *           - map with filtered keys;
 *           - current state return.
 */
List checkMandatoryKeysTemplateAndFilterMapWrapper(Map map, List mandatoryKeysToCheck, List stringKeys,
                                                   List booleanKeys, Boolean state, Boolean enableCheck,
                                                   String keysDescription, Object envVariables,
                                                   Map universalPipelineWrapperBuiltIns) {
    List mandatoryKeyValues = []
    def (Boolean newState, Map newMap) = checkAndTemplateKeysActionWrapper(envVariables,
            universalPipelineWrapperBuiltIns, enableCheck, state, keysDescription, map, stringKeys,
            String.format("'%s' key", keysDescription), booleanKeys)
    mandatoryKeysToCheck.eachWithIndex { mandatoryItem, Integer mandatoryItemIndex ->
        mandatoryKeyValues[mandatoryItemIndex] = newMap?.get(mandatoryItem as String) ?: ''
        newState = errorMsgWrapper(enableCheck && !mandatoryKeyValues[mandatoryItemIndex].trim(), newState, 3,
                String.format("Mandatory key '%s' in '%s' is undefined or empty.", mandatoryItem, keysDescription))
    }
    [mandatoryKeyValues, findMapItemsFromList(newMap, stringKeys + booleanKeys as ArrayList), newState]
}

/**
 * Pipeline action: run playbook or script.
 *
 * @param actionLink - message prefix for possible errors.
 * @param pipelineSettings - all universal pipeline settings to get script or playbook from.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param check - set false to execute action item, true to check.
 * @param actionOk - just to pass previous action execution/checking state.
 * @param universalPipelineWrapperBuiltIns - pipeline wrapper built-ins variable with report in various formats.
 * @param scriptRun - true when script run (including groovy code run 'as a prt of pipeline), false when playbook.
 * @return - arrayList of:
 *           - true when success, false when failed;
 *           - action details for logging;
 *           - universal pipeline wrapper built-ins.
 */
List actionAnsiblePlaybookOrScriptRun(String actionLink, Map pipelineSettings, Object envVariables, Boolean check,
                                      Boolean actionOk, Map universalPipelineWrapperBuiltIns, Boolean scriptRun) {
    Closure actionClosure
    def (Map checkOrExecuteData, Map checkOrExecuteDataHandled, Map executionLinkNames) = [[:], [:], [:]]
    String actionName = String.format('%s run', scriptRun ? 'script' : 'ansible playbook')
    List stringKeys = scriptRun ? ['script'] : ['playbook', 'inventory']
    List pipelineConfigKeys = scriptRun ? ['scripts'] : ['playbooks', 'inventories']
    def (List stringSubKeys, List booleanSubKeys, Boolean newActionOk) = [['script', 'jenkins'], ['pipeline'], actionOk]

    /** Checking required script or playbook keys. Setting up execution data and printable link names. */
    def (Boolean __, Map actionLinkItem) = getMapSubKey(actionLink, pipelineSettings)
    (newActionOk, actionLinkItem) = checkAndTemplateKeysActionWrapper(envVariables, universalPipelineWrapperBuiltIns,
            check, newActionOk, actionLink, actionLinkItem, stringKeys)
    stringKeys.eachWithIndex { stringKeyName, Integer actionLinkKeysIndex ->
        Boolean actionLinkItemKeyIsDefined = actionLinkItem.containsKey(stringKeyName)
        newActionOk = errorMsgWrapper(check && actionLinkItemKeyIsDefined && !(actionLinkItem?.get(stringKeyName)
                instanceof String), newActionOk, 3, String.format("'%s' %s item in '%s' should be string.",
                actionLinkItem?.get(stringKeyName), stringKeyName, actionLink))
        String executionLinkName = stringKeyName == 'inventory' && !actionLinkItemKeyIsDefined ? 'default' :
                actionLinkItem?.get(stringKeyName)?.toString()
        def (Boolean subKeyIsDefined, Object subKeyValue) = getMapSubKey(executionLinkName, pipelineSettings,
                pipelineConfigKeys[actionLinkKeysIndex] as String)
        newActionOk = errorMsgWrapper(check && !subKeyIsDefined, newActionOk, 3,
                String.format("%s '%s' wasn't found in '%s' section of pipeline config file.", stringKeyName,
                        executionLinkName, pipelineConfigKeys[actionLinkKeysIndex] as String))
        checkOrExecuteData[stringKeyName] = subKeyIsDefined ? subKeyValue : [:]
        executionLinkNames[stringKeyName] = executionLinkName
    }
    env = check ? env : updateEnvFromMapKeys(universalPipelineWrapperBuiltIns, envVariables)
    if (scriptRun) {
        /** Check script keys. */
        checkOrExecuteData = (checkOrExecuteData.containsKey(stringKeys[0]) && checkOrExecuteData?.get(stringKeys[0])
                instanceof Map) ? checkOrExecuteData.script as Map : [:]
        (newActionOk, checkOrExecuteData) = checkAndTemplateKeysActionWrapper(envVariables,
                universalPipelineWrapperBuiltIns, check, newActionOk, executionLinkNames?.get(stringKeys[0]) as String,
                checkOrExecuteData, stringSubKeys, String.format('%s key', executionLinkNames?.get(stringKeys[0])),
                booleanSubKeys, false)
        Boolean asPartOfPipelineContentDefined = checkOrExecuteData.containsKey(stringSubKeys[1])
        Boolean wrongScriptKeysSequence = checkOrExecuteData?.get(booleanSubKeys[0]) && !asPartOfPipelineContentDefined
        newActionOk = errorMsgWrapper(wrongScriptKeysSequence, newActionOk, 3,
                String.format("Key '%s' is undefined in '%s', but this script was set to run as 'a part of pipeline'.",
                        executionLinkNames?.get(stringKeys[0]), stringSubKeys[1]))
        Boolean scriptContentDefined = checkOrExecuteData.containsKey(stringSubKeys[0])
        wrongScriptKeysSequence = !checkOrExecuteData?.get(booleanSubKeys[0]) && !scriptContentDefined
        newActionOk = errorMsgWrapper(wrongScriptKeysSequence, newActionOk, 3, String.format(
                "Key '%s' is undefined in '%s'.", stringSubKeys[0], executionLinkNames?.get(stringKeys[0])))

        /** Setting up closure depending on script type. */
        def (String scriptText, String pipelineCodeText) = [checkOrExecuteData?.get(stringSubKeys[0]),
                                                            checkOrExecuteData?.get(stringSubKeys[1])]
        // TODO: is reassignment is on Map universalPipelineWrapperBuiltIns = [:]?
        pipelineCodeText = String.format('%s\n%s\n%s', 'Map universalPipelineWrapperBuiltIns = [:]', pipelineCodeText,
                'return universalPipelineWrapperBuiltIns')
        actionClosure = (checkOrExecuteData?.get(booleanSubKeys[0]) && asPartOfPipelineContentDefined) ? {
            Map universalPipelineWrapperBuiltInsUpdate = evaluate(pipelineCodeText) as Map
            [newActionOk, universalPipelineWrapperBuiltIns + universalPipelineWrapperBuiltInsUpdate, null]
        } : (!checkOrExecuteData?.get(booleanSubKeys[0]) && scriptContentDefined) ? {
            sh scriptText
            [newActionOk, universalPipelineWrapperBuiltIns, null]
        } : { }
    } else {
        /** Templating playbook keys. Setting up playbook, inventory and playbook execution closure. */
        String ansibleInstallationName = universalPipelineWrapperBuiltIns.ansibleCurrentInstallationName
        stringKeys.each { stringKeyName ->
            Map checkOrExecuteDataTemplatedPart
            (newActionOk, checkOrExecuteDataTemplatedPart) = checkAndTemplateKeysActionWrapper(envVariables,
                    universalPipelineWrapperBuiltIns, check, newActionOk, executionLinkNames[stringKeyName] as String,
                    checkOrExecuteData, [stringKeyName], String.format('%s key', stringKeyName))
            checkOrExecuteDataHandled = checkOrExecuteDataHandled + checkOrExecuteDataTemplatedPart
        }
        checkOrExecuteData = checkOrExecuteDataHandled
        def (String ansiblePlaybookText, String ansibleInventoryText) = [checkOrExecuteDataHandled?.get(stringKeys[0]),
                                                                         checkOrExecuteDataHandled?.get(stringKeys[1])]
        actionClosure = {
            Map universalPipelineWrapperBuiltInsSaved = universalPipelineWrapperBuiltIns
            newActionOk = CF.runAnsible(ansiblePlaybookText, ansibleInventoryText, '', '', '', [],
                    ansibleInstallationName?.trim() ? ansibleInstallationName : GV.ANSIBLE_INSTALLATION_NAME)
            [newActionOk, universalPipelineWrapperBuiltInsSaved, null]
        }
    }

    /** Run action closure and finally update env from changed universalPipelineWrapperBuiltIns keys. */
    String actionMsg
    (newActionOk, actionMsg, universalPipelineWrapperBuiltIns) = actionClosureWrapperWithTryCatch(check, envVariables,
            actionClosure, actionLink, actionName, executionLinkNames, stringKeys, newActionOk,
            universalPipelineWrapperBuiltIns)
    env = check ? env : updateEnvFromMapKeys(universalPipelineWrapperBuiltIns, envVariables)
    [newActionOk, actionMsg, universalPipelineWrapperBuiltIns as Map]
}

/**
 * Pipeline action: run downstream jenkins pipeline.
 *
 * @param actionLink - message prefix for possible errors.
 * @param actionLinkItem - action link item to check or execute.
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param check - set false to execute action item, true to check.
 * @param actionOk - just to pass previous action execution/checking state.
 * @param universalPipelineWrapperBuiltIns - pipeline wrapper built-ins variable with report in various formats.
 * @return - arrayList of:
 *           - true when success, false when failed;
 *           - action details for logging.
 */
List actionDownstreamJobRun(String actionLink, Map actionLinkItem, Object envVariables, Boolean check, Boolean actionOk,
                            Map universalPipelineWrapperBuiltIns) {
    String actionMsg
    Object runWrapper
    def (List pipelineParameters, List printablePipelineParameters) = [[], []]
    // groovylint-disable-next-line DuplicateListLiteral
    def (List stringKeys, List booleanKeys, Boolean newActionOk) = [['pipeline'], ['propagate', 'wait'], actionOk]
    (newActionOk, actionLinkItem) = checkAndTemplateKeysActionWrapper(envVariables, universalPipelineWrapperBuiltIns,
            check, newActionOk, actionLink, actionLinkItem, stringKeys, String.format("'%s' key", actionLink),
            booleanKeys)
    Boolean downstreamJobNameDefined = actionLinkItem?.get(stringKeys[0]) instanceof String &&
            actionLinkItem?.get(stringKeys[0])?.trim()
    String downstreamJobName = downstreamJobNameDefined ? actionLinkItem?.get(stringKeys[0]) : '<undefined>'
    String actionName = String.format("downstream job '%s' run", downstreamJobName)
    newActionOk = errorMsgWrapper(check && !downstreamJobNameDefined, newActionOk, 3,
            String.format("Nothing to execute. '%s' key in '%s' action is mandatory.", stringKeys[0], actionLink))
    Boolean dryRunMode = getBooleanVarStateFromEnv(envVariables, 'DRY_RUN')
    def (Boolean propagatePipelineErrors, Boolean waitForPipelineComplete) = booleanKeys.collect { String booleanKey ->
        actionLinkItem.containsKey(booleanKey) ? actionLinkItem.get(booleanKey) : true
    }

    /** Processing downstream job parameters. */
    String kName = 'parameters'
    newActionOk = errorMsgWrapper(check && actionLinkItem.containsKey(kName) &&
            !(actionLinkItem?.get(kName) instanceof ArrayList), newActionOk, 3, String.format("%s key in '%s' %s.",
            kName, actionLink, 'action should be a list or just absent'))
    List pipelineParametersList = actionLinkItem?.get(kName) instanceof ArrayList ?
            actionLinkItem?.get(kName) as ArrayList : [] as ArrayList
    (newActionOk, pipelineParameters, printablePipelineParameters) = listOfMapsToTemplatedJobParams(
            pipelineParametersList, envVariables, String.format("'%s' action", actionLink),
            universalPipelineWrapperBuiltIns, check, newActionOk)

    /** Processing copy_artifacts parameters. */
    kName = 'copy_artifacts'
    newActionOk = errorMsgWrapper(check && actionLinkItem.containsKey(kName) &&
            !(actionLinkItem?.get(kName) instanceof Map), newActionOk, 3, String.format("%s key in '%s' %s.", kName,
            actionLink, 'action should be a map or just absent'))
    Map copyArtifactsKeys = actionLinkItem?.get(kName) instanceof Map ? actionLinkItem?.get(kName) as Map : [:]
    List copyArtifactsStringKeys = ['filter', 'excludes', 'target_directory']
    List copyArtifactsBooleanKeys = ['optional', 'flatten', 'fingerprint']
    (newActionOk, copyArtifactsKeys) = checkAndTemplateKeysActionWrapper(envVariables, universalPipelineWrapperBuiltIns,
            check, newActionOk, actionLink, copyArtifactsKeys, copyArtifactsStringKeys, String.format("%s key in '%s'",
            kName, actionLink), copyArtifactsBooleanKeys)
    String copyArtifactsFilter = copyArtifactsKeys?.get(copyArtifactsStringKeys[0] as String) ?: ''
    newActionOk = errorMsgWrapper(actionLinkItem.containsKey(kName) && !copyArtifactsFilter.trim(), newActionOk, 3,
            String.format("Mandatory key '%s' of '%s' in '%s' action is undefined.", copyArtifactsStringKeys[0], kName,
                    actionLink))

    /** Setting up action closure and run downstream job/pipeline. */
    Closure actionClosure = downstreamJobNameDefined ? {
        Object jobRunWrapper = CF.dryRunJenkinsJob(downstreamJobName, pipelineParameters, dryRunMode, false,
                propagatePipelineErrors, waitForPipelineComplete, envVariables, 'DRY_RUN', printablePipelineParameters)
        [newActionOk, universalPipelineWrapperBuiltIns, jobRunWrapper]
    } : {
        [false, universalPipelineWrapperBuiltIns, null]
    }
    errorMsgWrapper(!check && !dryRunMode, true, 0, String.format('%s parameters: %s', actionName,
            CF.readableJobParams(printablePipelineParameters)))
    (newActionOk, actionMsg, universalPipelineWrapperBuiltIns, runWrapper) = actionClosureWrapperWithTryCatch(check,
            envVariables, actionClosure, actionLink, actionName, actionLinkItem.findAll { k, v -> k != stringKeys[0] },
            stringKeys + booleanKeys + [kName] as ArrayList, newActionOk, universalPipelineWrapperBuiltIns)
    String downstreamJobRunResults = runWrapper?.result?.trim() ? runWrapper.result : ''
    String copyArtifactsBuildSelector = runWrapper?.number?.toString() ?: ''
    String downstreamJobConsoleUrl = runWrapper?.absoluteUrl ? String.format(' %sconsole', runWrapper.absoluteUrl) : ''
    actionMsg += downstreamJobConsoleUrl
    Boolean getStatusFromDownstreamJobRunIsPossible = downstreamJobNameDefined && waitForPipelineComplete &&
            downstreamJobRunResults.trim()
    errorMsgWrapper(!check && !dryRunMode && getStatusFromDownstreamJobRunIsPossible, newActionOk, 0,
            String.format("%s%s finished with '%s'.", actionName, downstreamJobConsoleUrl, downstreamJobRunResults))

    /** Copy artifacts from downstream job. */
    String copyArtifactsErrMsg = String.format("Unable to copy artifacts from %s%s in '%s'", actionName,
            downstreamJobConsoleUrl, actionLink)
    String copyArtifactsErrReason = waitForPipelineComplete ? '' : ' defined not to wait for completion.'
    copyArtifactsErrReason += !check && !dryRunMode && !copyArtifactsBuildSelector.trim() ? String.format(
            " Build number of %s is undefined. Perhaps this job is still running or wasn't started.", actionName) : ''
    Boolean copyingArtifactsConditionsMet = !check && copyArtifactsFilter.trim() && !copyArtifactsErrReason.trim()
    errorMsgWrapper(copyingArtifactsConditionsMet, true, 0, String.format("%s '%s' job build no. %s parameters %s.",
            'Copying artifacts from', downstreamJobName, copyArtifactsBuildSelector, CF.readableMap(copyArtifactsKeys)))
    if (copyingArtifactsConditionsMet && !dryRunMode) {
        try {
            copyArtifacts(
                    projectName: downstreamJobName,
                    selector: specific(copyArtifactsBuildSelector),
                    filter: copyArtifactsFilter,
                    excludes: copyArtifactsKeys?.get(copyArtifactsStringKeys[1] as String) ?: '',
                    target: copyArtifactsKeys?.get(copyArtifactsStringKeys[2] as String) ?: '',
                    optional: copyArtifactsKeys?.get(copyArtifactsBooleanKeys[0] as String) ?: false,
                    flatten: copyArtifactsKeys?.get(copyArtifactsBooleanKeys[1] as String) ?: false,
                    fingerprintArtifacts: copyArtifactsKeys?.get(copyArtifactsBooleanKeys[2] as String) ?: false,
            )
        } catch (Exception err) {
            copyArtifactsErrReason += String.format(' %s', CF.readableError(err))
        }
    }
    newActionOk = errorMsgWrapper(copyArtifactsErrReason.trim() as Boolean, newActionOk, 3, String.format('%s:%s',
            copyArtifactsErrMsg, copyArtifactsErrReason))
    [newActionOk, actionMsg, universalPipelineWrapperBuiltIns as Map]
}

/**
 * Pipeline action: archive artifacts.
 *
 * @param actionLink - message prefix for possible errors.
 * @param actionLinkItem - action link item to check or execute.
 * @param envVariables - environment variables ('env' which is class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param check - set false to execute action item, true to check.
 * @param actionOk - just to pass previous action execution/checking state.
 * @param universalPipelineWrapperBuiltIns - pipeline wrapper built-ins variable with report in various formats.
 * @return - arrayList of:
 *           - true when success, false when failed;
 *           - action details for logging.
 */
List actionArchiveArtifacts(String actionLink, Map actionLinkItem, Object envVariables, Boolean check, Boolean actionOk,
                            Map universalPipelineWrapperBuiltIns) {
    String actionMsg
    def (String actionName, List mandatoryKeyValues, Boolean newActionOk) = ['archive artifacts', [], actionOk]
    def (List stringKeys, List booleanKeys) = [['artifacts', 'excludes'], ['allow_empty', 'fingerprint']]
    (mandatoryKeyValues, actionLinkItem, newActionOk) = checkMandatoryKeysTemplateAndFilterMapWrapper(actionLinkItem,
            [stringKeys[0] as String], stringKeys, booleanKeys, newActionOk, check, actionLink, envVariables,
            universalPipelineWrapperBuiltIns)
    Closure actionClosure = mandatoryKeyValues[0].trim() ? {
        archiveArtifacts(
                artifacts: mandatoryKeyValues[0],
                excludes: actionLinkItem?.get(stringKeys[1]) ?: '',
                allowEmptyArchive: actionLinkItem?.get(booleanKeys[0]) ?: false,
                fingerprint: actionLinkItem?.get(booleanKeys[1]) ?: false
        )
        [newActionOk, universalPipelineWrapperBuiltIns, null]
    } : {
        [false, universalPipelineWrapperBuiltIns, null]
    }
    errorMsgWrapper(!check && !getBooleanVarStateFromEnv(envVariables, 'DRY_RUN'), true, 0,
            String.format('%s parameters: %s', actionName.capitalize(), CF.readableMap(actionLinkItem)))
    (newActionOk, actionMsg, universalPipelineWrapperBuiltIns) = actionClosureWrapperWithTryCatch(check, envVariables,
            actionClosure, actionLink, actionName, actionLinkItem, stringKeys + booleanKeys as ArrayList, newActionOk,
            universalPipelineWrapperBuiltIns)
    actionMsg += String.format(' %sartifact/', envVariables.BUILD_URL)
    [newActionOk, actionMsg, universalPipelineWrapperBuiltIns as Map]
}

/**
 * Pipeline action: stash/unstash files.
 *
 * @param actionLink - message prefix for possible errors.
 * @param actionLinkItem - action link item to check or execute.
 * @param envVariables - environment variables ('env' which is class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param check - set false to execute action item, true to check.
 * @param actionOk - just to pass previous action execution/checking state.
 * @param universalPipelineWrapperBuiltIns - pipeline wrapper built-ins variable with report in various formats.
 * @param stashFiles - true to stash files, false to unstash files.
 * @return - arrayList of:
 *           - true when success, false when failed;
 *           - action details for logging.
 */
List actionUnStash(String actionLink, Map actionLinkItem, Object envVariables, Boolean check, Boolean actionOk,
                   Map universalPipelineWrapperBuiltIns, Boolean stashFiles = true) {
    String actionName = String.format('%sstash files', stashFiles ? '' : 'un')
    List stringKeys = stashFiles ? ['stash', 'includes', 'excludes'] : ['unstash']
    List booleanKeys = stashFiles ? ['default_excludes', 'allow_empty'] : []
    def (List mandatoryKeyValues, Boolean newActionOk) = [[], actionOk]
    (mandatoryKeyValues, actionLinkItem, newActionOk) = checkMandatoryKeysTemplateAndFilterMapWrapper(actionLinkItem,
            [stringKeys[0] as String], stringKeys, booleanKeys, newActionOk, check, actionLink, envVariables,
            universalPipelineWrapperBuiltIns)
    Closure actionClosure = stashFiles ? {
        stash(
                name: mandatoryKeyValues[0],
                includes: actionLinkItem?.get(stringKeys[1]) ?: '',
                excludes: actionLinkItem?.get(stringKeys[2]) ?: '',
                useDefaultExcludes: actionLinkItem?.get(booleanKeys[0]) ?: true,
                allowEmpty: actionLinkItem?.get(booleanKeys[1]) ?: false
        )
        [newActionOk, universalPipelineWrapperBuiltIns, null]
    } : {
        unstash(name: mandatoryKeyValues[0])
        [newActionOk, universalPipelineWrapperBuiltIns, null]
    }
    errorMsgWrapper(!check && !getBooleanVarStateFromEnv(envVariables, 'DRY_RUN'), true, 0,
            String.format('%s parameters: %s', actionName.capitalize(), CF.readableMap(actionLinkItem)))
    actionClosureWrapperWithTryCatch(check, envVariables, actionClosure, actionLink, actionName, actionLinkItem,
            stringKeys + booleanKeys as ArrayList, newActionOk, universalPipelineWrapperBuiltIns)
}

/**
 * Convert list of maps with job parameter keys to jenkins job parameters with variables assigning.
 *
 * @param listOfMapItems - list of maps with job parameters with structure: [name: 'name of downstream job',
 *                         type: 'parameter type (string, text, password or boolean), parameter: 'parameter value']
 * @param envVariables - environment variables for current job build (actually requires a pass of 'env' which is
 *                       class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param keyDescription - parameters description (where parameters was taken).
 * @param universalPipelineWrapperBuiltIns - pipeline wrapper built-ins variable with report in various formats.
 * @param check - set false to execute action item, true to check.
 * @param allPass - just to pass previous action execution/checking state.
 * @param pipelineParameters - jenkins job parameters to add to them.
 * @param printablePipelineParameters - the same as pipelineParameters, but values of 'password' parameters was hidden.
 * @return arrayList of:
 *         - true when all pass;
 *         - pipeline parameters return;
 *         - printable pipeline parameters return.
 */
List listOfMapsToTemplatedJobParams(List listOfMapItems, Object envVariables, String keyDescription,
                                    Map universalPipelineWrapperBuiltIns, Boolean check, Boolean allPass = true,
                                    List pipelineParameters = [], List printablePipelineParameters = []) {
    Boolean itsPass = allPass
    def (List newPipelineParameters, List newPrintPipeParameters) = [pipelineParameters, printablePipelineParameters]
    listOfMapItems.eachWithIndex { listItem, Integer listItemIndex ->
        def (List stringParamKeysList, List paramTypes) = [['name', 'type'], ['string', 'boolean', 'password', 'text']]
        List allParamKeysList = stringParamKeysList + ['value']
        String errMsgSubject = String.format('pipeline parameter no. %s of %s', listItemIndex.toString(),
                keyDescription)
        if (listItem instanceof Map) {
            /** Checking pipeline parameter item keys types and defined states. */
            Map filteredListItem = findMapItemsFromList(listItem as Map, allParamKeysList)
            itsPass = errorMsgWrapper(filteredListItem?.size() != 3, itsPass, 3, String.format('%s %s: %s required.',
                    'Wrong set of keys in', errMsgSubject, arrayListToReadableString(allParamKeysList)))
            Boolean stringKeysOk = checkListOfKeysFromMapProbablyStringOrBoolean(check, stringParamKeysList,
                    filteredListItem, true, keyDescription, itsPass)
            Boolean valueKeyWrong = filteredListItem?.get('value') instanceof Map
            itsPass = errorMsgWrapper(valueKeyWrong, itsPass, 3, String.format("'value' key in %s %s. %s.",
                    errMsgSubject, "shouldn't be map", 'In most cases, strings or a boolean are sufficient'))
            Boolean typeKeyOk = filteredListItem?.size() == 3 && paramTypes.any { String entry ->
                entry.contains(filteredListItem?.get(stringParamKeysList[1]) as String)
            }
            itsPass = errorMsgWrapper(!typeKeyOk, itsPass, 3, String.format('Wrong in %s. Should be: %s.',
                    errMsgSubject, arrayListToReadableString(paramTypes)))

            /** Assign variables to pipeline parameter item, hide passwords, convert them to pipeline parameter. */
            (itsPass, filteredListItem) = templatingMapKeysFromVariables(filteredListItem, allParamKeysList,
                    envVariables, itsPass, universalPipelineWrapperBuiltIns, errMsgSubject)
            if (filteredListItem?.size() == 3 && stringKeysOk) {
                newPipelineParameters = CF.itemKeyToJobParam(filteredListItem?.get(stringParamKeysList[0]),
                        filteredListItem?.get('value'), filteredListItem?.get(stringParamKeysList[1]), false,
                        newPipelineParameters)
                newPrintPipeParameters = (filteredListItem?.get(stringParamKeysList[1]) == paramTypes[3]) ?
                        CF.itemKeyToJobParam(filteredListItem?.get(stringParamKeysList[0]),
                                hidePasswordString(filteredListItem?.get('value') as String), filteredListItem
                                ?.get(stringParamKeysList[1]), false, newPipelineParameters) : newPipelineParameters
            }
            itsPass = filteredListItem?.size() == 3 && stringKeysOk ? itsPass : false
        } else {
            itsPass = errorMsgWrapper(true, itsPass, 3, String.format('Wrong structure in %s: should be map.',
                    errMsgSubject))
        }
    }
    [itsPass, newPipelineParameters, newPrintPipeParameters]
}

/**
 * Pipeline action: send report to email/mattermost.
 *
 * @param actionLink - message prefix for possible errors.
 * @param actionLinkItem - action link item to check or execute.
 * @param envVariables - environment variables ('env' which is class org.jenkinsci.plugins.workflow.cps.EnvActionImpl).
 * @param check - set false to execute action item, true to check.
 * @param actionOk - just to pass previous action execution/checking state.
 * @param universalPipelineWrapperBuiltIns - pipeline wrapper built-ins variable with report in various formats.
 * @param stashFiles - true to stash files, false to unstash files.
 * @return - arrayList of:
 *           - true when success, false when failed;
 *           - action details for logging.
 */
List actionSendReport(String actionLink, Map actionLinkItem, Object envVariables, Boolean check, Boolean actionOk,
                      Map universalPipelineWrapperBuiltIns) {
    def (List mandatoryKeys, List mandatoryKeyValues, Boolean newActionOk) = [['report'], [], actionOk]
    String reportTarget = actionLinkItem?.get(mandatoryKeys[0]) instanceof String ?
            actionLinkItem.get(mandatoryKeys[0]) : ''
    newActionOk = errorMsgWrapper(!check && !reportTarget.trim(), newActionOk, 3,
            String.format("Unable to detect report target: '%s' action key in '%s' is undefined or incorrect.",
                    mandatoryKeys[0], actionLink))
    mandatoryKeys += reportTarget == 'email' ? ['to'] : []
    mandatoryKeys += reportTarget == 'mattermost' ? ['url', 'text'] : []
    List stringKeys = reportTarget == 'email' ? ['reply_to', 'subject', 'body'] : []
    // TODO: pipeline setting change?
    (mandatoryKeyValues, actionLinkItem, newActionOk) = checkMandatoryKeysTemplateAndFilterMapWrapper(actionLinkItem,
            mandatoryKeys, mandatoryKeys + stringKeys as ArrayList, [], newActionOk, check, actionLink, envVariables,
            universalPipelineWrapperBuiltIns)
    String actionName = String.format('send report to %s', reportTarget.trim() ? reportTarget : '<undefined>')
    Closure actionClosure = mandatoryKeyValues[0] == 'email' ? {
        emailext(
                to: mandatoryKeyValues[1],
                replyTo: actionLinkItem?.get(stringKeys[0]) ?: '$DEFAULT_REPLYTO',
                subject: actionLinkItem?.get(stringKeys[1]) ?: '',
                body: actionLinkItem?.get(stringKeys[2]) ?: ''
        )
        [newActionOk, universalPipelineWrapperBuiltIns, null]
    } : mandatoryKeyValues[0] == 'mattermost' ? {
        Boolean sendReportStatus = CF.sendMattermostChannelSingleMessage(mandatoryKeyValues[1], mandatoryKeyValues[2],
                getBooleanVarStateFromEnv(envVariables) ? 2 : 0)
        [sendReportStatus && newActionOk, universalPipelineWrapperBuiltIns, null]
    } : {
        [newActionOk, universalPipelineWrapperBuiltIns, null]
    }
    List msgKeys = reportTarget == 'email' ? [mandatoryKeys[1]] : []
    String actionMsg
    (newActionOk, actionMsg, universalPipelineWrapperBuiltIns) = actionClosureWrapperWithTryCatch(check, envVariables,
            actionClosure, actionLink, actionName, actionLinkItem, msgKeys, newActionOk,
            universalPipelineWrapperBuiltIns)
    [newActionOk, actionMsg.replaceAll('\\s\\[]', ''), universalPipelineWrapperBuiltIns as Map]
}


/** Pipeline entry point. */
Object jenkinsNodeToExecute = getJenkinsNodeToExecuteByNameOrTag(env, 'NODE_NAME', 'NODE_TAG')
node(jenkinsNodeToExecute) {
    CF = new org.alx.commonFunctions() as Object
    GV = new org.alx.OrgAlxGlobals() as Object
    wrap([$class: 'TimestamperBuildWrapper']) {
        String pipelineFailReasonText
        Boolean pipelineParamsProcessingPass
        Boolean checkPipelineParametersPass
        Map pipelineSettings
        (pipelineFailReasonText, pipelineParamsProcessingPass, checkPipelineParametersPass, pipelineSettings, env) =
                pipelineParamsProcessingWrapper(SettingsGitUrl, DefaultSettingsGitBranch, SettingsRelativePathPrefix,
                        PipelineNameRegexReplace, BuiltinPipelineParameters, env, params)

        /** When params are set check other pipeline settings (stages, playbooks, scripts, inventories) are correct. */
        Boolean pipelineSettingsCheckOk = true
        if ((!pipelineFailReasonText.trim() && checkPipelineParametersPass) || getBooleanPipelineParamState(params)) {
            (__, pipelineSettingsCheckOk, env) = checkOrExecutePipelineWrapperFromSettings(pipelineSettings, env, true,
                    false)
        }
        pipelineFailReasonText += pipelineSettingsCheckOk && checkPipelineParametersPass ? '' :
                'Pipeline settings contains an error(s).'
        /**
         * Skip stages execution on settings error or undefined required pipeline parameter(s), or execute in dry-run.
         */
        pipelineFailReasonText += pipelineParamsProcessingPass ? '' : '\nError(s) in pipeline yaml settings. '
        Map universalPipelineWrapperBuiltIns = [:]
        universalPipelineWrapperBuiltIns.ansibleCurrentInstallationName = AnsibleInstallationName?.trim() ?
                AnsibleInstallationName : ''
        if (!pipelineFailReasonText.trim() || getBooleanPipelineParamState(params)) {
            Boolean allDone
            errorMsgWrapper(getBooleanPipelineParamState(params), true, 2, String.format('%s %s.',
                    'Dry-run mode enabled. All pipeline and settings errors will be ignored and pipeline stages will',
                    'be emulated skipping the scripts, playbooks and pipeline runs.'))
            (universalPipelineWrapperBuiltIns, allDone, env) = checkOrExecutePipelineWrapperFromSettings(
                    pipelineSettings, env)
            pipelineFailReasonText += allDone ? '' : 'Stages execution finished with fail.'
            String overallResults = universalPipelineWrapperBuiltIns.get('multilineReport') ?
                    universalPipelineWrapperBuiltIns.multilineReport.replaceAll('\\[PASS\\]', '\033[0;32m[PASS]\033[0m')
                            .replaceAll('\\[FAIL\\]', '\033[0;31m[FAIL]\033[0m') : 'n/a'
            CF.outMsg(allDone ? 1 : 3, String.format('%s\nOVERALL:\n\n%s\n%s', '-' * 80, overallResults, '-' * 80))
        }
        if (pipelineFailReasonText.trim())
            error String.format('%s\n%s.', pipelineFailReasonText, 'Please fix then re-build')
    }
}
