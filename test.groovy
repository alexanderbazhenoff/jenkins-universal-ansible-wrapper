#!/usr/bin/env groovy

/**
 * This part of code performs node selection by Jenkins node tag (NODE_TAG_TO_EXECUTE) or node name (NODE_TO_EXECUTE).
 * Until nodeToExecute can be Map or String it was declared as an Object to avoid dynamic type warning when linting.
 */
Object nodeToExecute = null
// groovylint-disable-next-line UnnecessaryGetter
nodeToExecute = (env.getEnvironment().containsKey('NODE_TAG_TO_EXECUTE') && env['NODE_TAG_TO_EXECUTE']?.trim()) ?
        [label: env['NODE_TAG_TO_EXECUTE']] : nodeToExecute
// groovylint-disable-next-line UnnecessaryGetter
nodeToExecute = (!env.getEnvironment().containsKey('NODE_TO_EXECUTE') && env.getEnvironment()
        .containsKey('NODE_TO_EXECUTE') && env['NODE_TO_EXECUTE']?.trim()) ? env['NODE_TO_EXECUTE'] : nodeToExecute

/** After a jenkins node selection pipeline code itself. */
node(nodeToExecute) {
    wrap([$class: 'TimestamperBuildWrapper']) {
        println String.format('Hello, I am a %s node', nodeToExecute)
        // When I edit my code I have removed disable rule line and this one was forgotten:
        /* groovylint-enable UnnecessaryGetter */
    }
}