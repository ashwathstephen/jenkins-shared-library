#!/usr/bin/env groovy

/**
 * Standard CI/CD Pipeline
 * 
 * A complete pipeline for building, testing, and deploying applications.
 * Supports Java, Python, Node.js, and Go projects.
 * 
 * @param config Map containing:
 *   - appName: Application name (required)
 *   - language: Programming language (java, python, node, go)
 *   - buildTool: Build tool (maven, gradle, npm, pip, go)
 *   - javaVersion: Java version (default: 17)
 *   - nodeVersion: Node.js version (default: 18)
 *   - pythonVersion: Python version (default: 3.11)
 *   - dockerRegistry: Docker registry URL
 *   - kubeCluster: Kubernetes cluster name
 *   - helmChart: Path to Helm chart
 *   - deployEnvs: List of environments to deploy (dev, staging, prod)
 *   - runSecurityScan: Run security scans (default: true)
 *   - slackChannel: Slack channel for notifications
 */
def call(Map config = [:]) {
    def appName = config.appName ?: error("appName is required")
    def language = config.language ?: detectLanguage()
    def buildTool = config.buildTool ?: detectBuildTool(language)
    def deployEnvs = config.deployEnvs ?: ['dev']
    def runSecurityScan = config.runSecurityScan != false
    def slackChannel = config.slackChannel

    pipeline {
        agent any

        options {
            timeout(time: 1, unit: 'HOURS')
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '10'))
            timestamps()
        }

        environment {
            APP_NAME = "${appName}"
            DOCKER_REGISTRY = "${config.dockerRegistry ?: 'docker.io'}"
            IMAGE_TAG = "${env.BUILD_NUMBER}"
        }

        stages {
            stage('Checkout') {
                steps {
                    checkout scm
                    script {
                        env.GIT_COMMIT_SHORT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                        env.GIT_BRANCH_NAME = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
                    }
                }
            }

            stage('Setup') {
                steps {
                    script {
                        setupEnvironment(language, config)
                    }
                }
            }

            stage('Build') {
                steps {
                    script {
                        buildApplication(language, buildTool, config)
                    }
                }
            }

            stage('Test') {
                steps {
                    script {
                        runTests(
                            language: language,
                            coverageThreshold: config.coverageThreshold ?: 80
                        )
                    }
                }
                post {
                    always {
                        junit allowEmptyResults: true, testResults: '**/test-results/*.xml'
                    }
                }
            }

            stage('Security Scan') {
                when {
                    expression { runSecurityScan }
                }
                parallel {
                    stage('SAST') {
                        steps {
                            script {
                                securityScan(scanType: 'sast')
                            }
                        }
                    }
                    stage('Dependency Scan') {
                        steps {
                            script {
                                securityScan(scanType: 'dependency')
                            }
                        }
                    }
                }
            }

            stage('Build Docker Image') {
                when {
                    expression { config.dockerRegistry }
                }
                steps {
                    script {
                        def imageInfo = buildDocker(
                            imageName: appName,
                            registry: config.dockerRegistry,
                            tags: [env.BUILD_NUMBER, env.GIT_COMMIT_SHORT, 'latest']
                        )
                        env.DOCKER_IMAGE = "${imageInfo.imageName}:${env.BUILD_NUMBER}"
                    }
                }
            }

            stage('Container Scan') {
                when {
                    expression { config.dockerRegistry && runSecurityScan }
                }
                steps {
                    script {
                        securityScan(
                            scanType: 'container',
                            imageName: env.DOCKER_IMAGE
                        )
                    }
                }
            }

            stage('Deploy') {
                when {
                    expression { config.kubeCluster && config.helmChart }
                }
                stages {
                    stage('Deploy to Dev') {
                        when {
                            expression { 'dev' in deployEnvs }
                        }
                        steps {
                            script {
                                deployToEnvironment('dev', config)
                            }
                        }
                    }

                    stage('Deploy to Staging') {
                        when {
                            expression { 'staging' in deployEnvs }
                        }
                        steps {
                            script {
                                deployToEnvironment('staging', config)
                            }
                        }
                    }

                    stage('Deploy to Production') {
                        when {
                            expression { 'prod' in deployEnvs || 'production' in deployEnvs }
                            branch 'main'
                        }
                        steps {
                            script {
                                // Manual approval for production
                                input message: 'Deploy to Production?', ok: 'Deploy'
                                deployToEnvironment('prod', config)
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                cleanWs()
            }
            success {
                script {
                    if (slackChannel) {
                        notifySlack(channel: slackChannel, status: 'success')
                    }
                }
            }
            failure {
                script {
                    if (slackChannel) {
                        notifySlack(channel: slackChannel, status: 'failure')
                    }
                }
            }
        }
    }
}

def detectLanguage() {
    if (fileExists('pom.xml') || fileExists('build.gradle')) {
        return 'java'
    } else if (fileExists('package.json')) {
        return 'node'
    } else if (fileExists('requirements.txt') || fileExists('setup.py') || fileExists('pyproject.toml')) {
        return 'python'
    } else if (fileExists('go.mod')) {
        return 'go'
    }
    return 'unknown'
}

def detectBuildTool(String language) {
    switch (language) {
        case 'java':
            return fileExists('pom.xml') ? 'maven' : 'gradle'
        case 'node':
            return fileExists('yarn.lock') ? 'yarn' : 'npm'
        case 'python':
            return fileExists('pyproject.toml') ? 'poetry' : 'pip'
        case 'go':
            return 'go'
        default:
            return 'unknown'
    }
}

def setupEnvironment(String language, Map config) {
    switch (language) {
        case 'java':
            def javaVersion = config.javaVersion ?: '17'
            sh "java -version || echo 'Java not found'"
            break
        case 'node':
            def nodeVersion = config.nodeVersion ?: '18'
            sh "node --version && npm --version"
            break
        case 'python':
            def pythonVersion = config.pythonVersion ?: '3.11'
            sh "python3 --version && pip3 --version"
            break
        case 'go':
            sh "go version"
            break
    }
}

def buildApplication(String language, String buildTool, Map config) {
    switch (language) {
        case 'java':
            if (buildTool == 'maven') {
                sh 'mvn clean package -DskipTests'
            } else {
                sh './gradlew build -x test'
            }
            break
        case 'node':
            if (buildTool == 'yarn') {
                sh 'yarn install && yarn build'
            } else {
                sh 'npm ci && npm run build'
            }
            break
        case 'python':
            sh 'pip install -r requirements.txt'
            break
        case 'go':
            sh 'go build ./...'
            break
    }
}

def deployToEnvironment(String environment, Map config) {
    echo "Deploying to ${environment}"
    
    deployK8s(
        cluster: config.kubeCluster,
        namespace: "${config.appName}-${environment}",
        helmChart: config.helmChart,
        releaseName: config.appName,
        values: config.valuesFiles?."${environment}" ?: "./values-${environment}.yaml",
        setValues: [
            'image.tag': env.BUILD_NUMBER,
            'image.repository': "${config.dockerRegistry}/${config.appName}"
        ]
    )
}

