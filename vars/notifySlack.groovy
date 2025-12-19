#!/usr/bin/env groovy

/**
 * Send Slack notifications
 * 
 * @param config Map containing:
 *   - channel: Slack channel (required)
 *   - status: Build status (success, failure, unstable, started)
 *   - message: Custom message (optional)
 *   - credentialsId: Slack credentials ID
 *   - includeCommitInfo: Include commit details (default: true)
 *   - includeTestResults: Include test results (default: true)
 */
def call(Map config = [:]) {
    def channel = config.channel ?: error("channel is required")
    def status = config.status ?: currentBuild.currentResult
    def message = config.message ?: ''
    def credentialsId = config.credentialsId ?: 'slack-webhook'
    def includeCommitInfo = config.includeCommitInfo != false
    def includeTestResults = config.includeTestResults != false

    def color = getStatusColor(status)
    def statusText = getStatusText(status)

    def blocks = []

    // Header
    blocks << [
        type: 'header',
        text: [
            type: 'plain_text',
            text: "${env.JOB_NAME} - Build #${env.BUILD_NUMBER}"
        ]
    ]

    // Status section
    def statusFields = [
        [
            type: 'mrkdwn',
            text: "*Status:* ${statusText}"
        ],
        [
            type: 'mrkdwn',
            text: "*Duration:* ${currentBuild.durationString?.replace(' and counting', '') ?: 'N/A'}"
        ]
    ]

    if (message) {
        statusFields << [
            type: 'mrkdwn',
            text: "*Message:* ${message}"
        ]
    }

    blocks << [
        type: 'section',
        fields: statusFields
    ]

    // Commit info
    if (includeCommitInfo) {
        def commitInfo = getCommitInfo()
        if (commitInfo) {
            blocks << [
                type: 'section',
                text: [
                    type: 'mrkdwn',
                    text: "*Commit:* ${commitInfo.message}\n*Author:* ${commitInfo.author}\n*Branch:* ${commitInfo.branch}"
                ]
            ]
        }
    }

    // Test results
    if (includeTestResults) {
        def testResults = getTestResults()
        if (testResults) {
            blocks << [
                type: 'section',
                text: [
                    type: 'mrkdwn',
                    text: "*Tests:* ${testResults.passed} passed, ${testResults.failed} failed, ${testResults.skipped} skipped"
                ]
            ]
        }
    }

    // Actions
    blocks << [
        type: 'actions',
        elements: [
            [
                type: 'button',
                text: [type: 'plain_text', text: 'View Build'],
                url: env.BUILD_URL
            ],
            [
                type: 'button',
                text: [type: 'plain_text', text: 'View Console'],
                url: "${env.BUILD_URL}console"
            ]
        ]
    ]

    // Send notification
    def payload = [
        channel: channel,
        attachments: [[
            color: color,
            blocks: blocks
        ]]
    ]

    withCredentials([string(credentialsId: credentialsId, variable: 'SLACK_WEBHOOK')]) {
        httpRequest(
            url: env.SLACK_WEBHOOK,
            httpMode: 'POST',
            contentType: 'APPLICATION_JSON',
            requestBody: groovy.json.JsonOutput.toJson(payload)
        )
    }

    echo "Slack notification sent to ${channel}"
}

def getStatusColor(String status) {
    switch (status?.toLowerCase()) {
        case 'success':
            return '#36a64f'  // Green
        case 'failure':
        case 'failed':
            return '#dc3545'  // Red
        case 'unstable':
            return '#ffc107'  // Yellow
        case 'started':
        case 'running':
            return '#17a2b8'  // Blue
        default:
            return '#6c757d'  // Gray
    }
}

def getStatusText(String status) {
    switch (status?.toLowerCase()) {
        case 'success':
            return 'SUCCESS'
        case 'failure':
        case 'failed':
            return 'FAILED'
        case 'unstable':
            return 'UNSTABLE'
        case 'started':
            return 'STARTED'
        default:
            return status?.toUpperCase() ?: 'UNKNOWN'
    }
}

def getCommitInfo() {
    try {
        return [
            message: sh(script: 'git log -1 --pretty=%s', returnStdout: true).trim(),
            author: sh(script: 'git log -1 --pretty=%an', returnStdout: true).trim(),
            branch: env.GIT_BRANCH ?: sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
        ]
    } catch (Exception e) {
        return null
    }
}

def getTestResults() {
    try {
        def testResultAction = currentBuild.rawBuild.getAction(hudson.tasks.test.AbstractTestResultAction.class)
        if (testResultAction) {
            return [
                passed: testResultAction.totalCount - testResultAction.failCount - testResultAction.skipCount,
                failed: testResultAction.failCount,
                skipped: testResultAction.skipCount
            ]
        }
    } catch (Exception e) {
        // Test results not available
    }
    return null
}

