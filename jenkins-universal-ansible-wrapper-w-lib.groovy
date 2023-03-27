#!/usr/bin/env groovy

@Library('jenkins-shared-library') _

node('master') {
    wrap([$class: 'TimestamperBuildWrapper']) {
        CF = new org.alx.commonFunctions() as Object


        CF.outMsg(1, 'test library connection')
    }
}
