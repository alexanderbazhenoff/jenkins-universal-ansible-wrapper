#!/usr/bin/env groovy

@Library('jenkins-shared-library-alx@development') _

node('master') {
    wrap([$class: 'TimestamperBuildWrapper']) {
        CF = new org.alx.commonFunctions() as Object
        dir('a') {
            sh 'pwd'
        }

        CF.outMsg(1, 'test library connection')
    }
}
