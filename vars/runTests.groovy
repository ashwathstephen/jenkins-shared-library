#!/usr/bin/env groovy

/**
 * Run tests with coverage reporting
 * 
 * @param config Map containing:
 *   - language: Programming language (java, python, node, go)
 *   - testCommand: Custom test command (optional)
 *   - coverageThreshold: Minimum coverage percentage (default: 80)
 *   - publishResults: Publish test results (default: true)
 *   - failOnLowCoverage: Fail if coverage is below threshold (default: true)
 */
def call(Map config = [:]) {
    def language = config.language ?: 'java'
    def testCommand = config.testCommand
    def coverageThreshold = config.coverageThreshold ?: 80
    def publishResults = config.publishResults != false
    def failOnLowCoverage = config.failOnLowCoverage != false

    echo "Running tests for ${language} project"
    echo "Coverage threshold: ${coverageThreshold}%"

    def result = [:]

    switch (language.toLowerCase()) {
        case 'java':
            result = runJavaTests(testCommand, coverageThreshold)
            break
        case 'python':
            result = runPythonTests(testCommand, coverageThreshold)
            break
        case 'node':
        case 'nodejs':
        case 'javascript':
            result = runNodeTests(testCommand, coverageThreshold)
            break
        case 'go':
        case 'golang':
            result = runGoTests(testCommand, coverageThreshold)
            break
        default:
            if (testCommand) {
                sh testCommand
                result = [coverage: 0, passed: true]
            } else {
                error("Unsupported language: ${language}. Please provide a custom testCommand.")
            }
    }

    // Publish test results
    if (publishResults) {
        publishTestResults(language)
    }

    // Check coverage threshold
    if (failOnLowCoverage && result.coverage < coverageThreshold) {
        error("Coverage ${result.coverage}% is below threshold ${coverageThreshold}%")
    }

    return result
}

def runJavaTests(String customCommand, int threshold) {
    def cmd = customCommand ?: 'mvn clean test jacoco:report'
    sh cmd

    // Parse JaCoCo coverage
    def coverage = 0
    if (fileExists('target/site/jacoco/jacoco.xml')) {
        def report = readFile('target/site/jacoco/jacoco.xml')
        def matcher = report =~ /INSTRUCTION.*?covered="(\d+)".*?missed="(\d+)"/
        if (matcher.find()) {
            def covered = matcher.group(1) as int
            def missed = matcher.group(2) as int
            coverage = Math.round((covered / (covered + missed)) * 100)
        }
    }

    return [coverage: coverage, passed: true, tool: 'jacoco']
}

def runPythonTests(String customCommand, int threshold) {
    def cmd = customCommand ?: 'python -m pytest --cov=. --cov-report=xml --cov-report=term -v'
    sh cmd

    // Parse coverage.xml
    def coverage = 0
    if (fileExists('coverage.xml')) {
        def report = readFile('coverage.xml')
        def matcher = report =~ /line-rate="([0-9.]+)"/
        if (matcher.find()) {
            coverage = Math.round((matcher.group(1) as float) * 100)
        }
    }

    return [coverage: coverage, passed: true, tool: 'pytest-cov']
}

def runNodeTests(String customCommand, int threshold) {
    def cmd = customCommand ?: 'npm test -- --coverage --watchAll=false'
    sh cmd

    // Parse coverage summary
    def coverage = 0
    if (fileExists('coverage/coverage-summary.json')) {
        def report = readJSON file: 'coverage/coverage-summary.json'
        coverage = Math.round(report.total.lines.pct as float)
    }

    return [coverage: coverage, passed: true, tool: 'jest']
}

def runGoTests(String customCommand, int threshold) {
    def cmd = customCommand ?: 'go test -v -coverprofile=coverage.out ./...'
    sh cmd
    sh 'go tool cover -func=coverage.out'

    // Parse coverage
    def coverage = 0
    def output = sh(script: 'go tool cover -func=coverage.out | tail -1', returnStdout: true)
    def matcher = output =~ /(\d+\.?\d*)%/
    if (matcher.find()) {
        coverage = Math.round(matcher.group(1) as float)
    }

    return [coverage: coverage, passed: true, tool: 'go-cover']
}

def publishTestResults(String language) {
    switch (language.toLowerCase()) {
        case 'java':
            junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
            jacoco execPattern: '**/target/jacoco.exec'
            break
        case 'python':
            junit allowEmptyResults: true, testResults: '**/test-results.xml'
            break
        case 'node':
        case 'nodejs':
            junit allowEmptyResults: true, testResults: '**/junit.xml'
            break
        case 'go':
            // Go test results
            break
    }
}

