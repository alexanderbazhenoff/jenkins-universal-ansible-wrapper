#!/usr/bin/env groovy

@Library('jenkins-shared-library-alx@devel') _

node('master') {
    CF = new org.alx.troubleShooting() as Object
    def myLib = library 'jenkins-shared-library-alx@devel'
    def t = myLib.org.alx.OrgAlxGlob.new()
    wrap([$class: 'TimestamperBuildWrapper']) {

        println String.format('fn from src: %s', CF.TestFunctionInSrc())
        println String.format('fn from class from src: %s', t.OrgAlxGlob.GitCredentialsID)

    }
}
