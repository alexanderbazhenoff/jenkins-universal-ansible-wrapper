#!/usr/bin/env groovy

@Library('jenkins-shared-library-alx@devel') _

node('master') {
    CF = new org.alx.troubleShooting() as Object
    wrap([$class: 'TimestamperBuildWrapper']) {

        println String.format('fn from src: %s', CF.TestFunctionInSrc())
        println String.format('fn from class from src: %s', CF.OrgAlxGlob.sayHi())

    }
}
