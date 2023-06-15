#!/usr/bin/env groovy

@Library('jenkins-shared-library-alx@devel') _

node('master') {
    CF = new org.alx.troubleShooting() as Object
    GV = new org.alx.OrgAlxGlob() as Object
    wrap([$class: 'TimestamperBuildWrapper']) {

        println String.format('fn from src: %s', CF.TestFunctionInSrc())
        //def gitCred = library('jenkins-shared-library-alx@devel').org.alx.OrgAlxGlob.GitCredentialsID
        //println gitCred
        println GV.GitCredentialsID


    }
}
