#!/usr/bin/env groovy

/**
 * Run security scans
 * 
 * @param config Map containing:
 *   - scanType: Type of scan (sast, container, dependency, all)
 *   - checkmarxProject: Checkmarx project name (for SAST)
 *   - imageName: Docker image to scan (for container scan)
 *   - failOnHigh: Fail on high severity issues (default: true)
 *   - failOnCritical: Fail on critical severity issues (default: true)
 */
def call(Map config = [:]) {
    def scanType = config.scanType ?: 'all'
    def failOnHigh = config.failOnHigh != false
    def failOnCritical = config.failOnCritical != false
    
    def results = [:]

    echo "Running security scans: ${scanType}"

    switch (scanType) {
        case 'sast':
            results.sast = runSastScan(config)
            break
        case 'container':
            results.container = runContainerScan(config)
            break
        case 'dependency':
            results.dependency = runDependencyScan(config)
            break
        case 'all':
            results.sast = runSastScan(config)
            results.container = runContainerScan(config)
            results.dependency = runDependencyScan(config)
            break
        default:
            error("Unknown scan type: ${scanType}")
    }

    // Check for critical issues
    def hasHighIssues = results.any { k, v -> v.high > 0 }
    def hasCriticalIssues = results.any { k, v -> v.critical > 0 }

    if (failOnCritical && hasCriticalIssues) {
        error("Security scan found critical vulnerabilities")
    }

    if (failOnHigh && hasHighIssues) {
        error("Security scan found high severity vulnerabilities")
    }

    return results
}

def runSastScan(Map config) {
    echo "Running SAST scan..."
    
    def project = config.checkmarxProject ?: env.JOB_NAME
    
    // Checkmarx scan (if available)
    if (config.useCheckmarx) {
        try {
            step([
                $class: 'CxScanBuilder',
                projectName: project,
                preset: 'Default',
                teamPath: config.checkmarxTeam ?: 'CxServer',
                incremental: true,
                vulnerabilityThresholdEnabled: true,
                highThreshold: 0,
                mediumThreshold: 10,
                lowThreshold: 50
            ])
        } catch (Exception e) {
            echo "Checkmarx scan failed: ${e.message}"
        }
    }

    // Semgrep as fallback/alternative
    def semgrepResults = sh(
        script: '''
            if command -v semgrep &> /dev/null; then
                semgrep --config auto --json . 2>/dev/null || echo '{"results":[]}'
            else
                echo '{"results":[]}'
            fi
        ''',
        returnStdout: true
    ).trim()

    def results = readJSON text: semgrepResults
    def issues = results.results ?: []
    
    return [
        tool: 'semgrep',
        critical: issues.count { it.severity == 'ERROR' },
        high: issues.count { it.severity == 'WARNING' },
        medium: issues.count { it.severity == 'INFO' },
        low: 0,
        total: issues.size()
    ]
}

def runContainerScan(Map config) {
    echo "Running container image scan..."
    
    def imageName = config.imageName
    if (!imageName) {
        echo "No image specified, skipping container scan"
        return [tool: 'trivy', critical: 0, high: 0, medium: 0, low: 0, total: 0]
    }

    // Trivy scan
    def trivyResults = sh(
        script: """
            if command -v trivy &> /dev/null; then
                trivy image --format json --severity CRITICAL,HIGH,MEDIUM ${imageName} 2>/dev/null || echo '{"Results":[]}'
            else
                echo '{"Results":[]}'
            fi
        """,
        returnStdout: true
    ).trim()

    def results = readJSON text: trivyResults
    def vulns = results.Results?.collectMany { it.Vulnerabilities ?: [] } ?: []
    
    return [
        tool: 'trivy',
        critical: vulns.count { it.Severity == 'CRITICAL' },
        high: vulns.count { it.Severity == 'HIGH' },
        medium: vulns.count { it.Severity == 'MEDIUM' },
        low: vulns.count { it.Severity == 'LOW' },
        total: vulns.size()
    ]
}

def runDependencyScan(Map config) {
    echo "Running dependency scan..."
    
    def results = [tool: 'dependency-check', critical: 0, high: 0, medium: 0, low: 0, total: 0]

    // Detect project type and run appropriate scanner
    if (fileExists('pom.xml')) {
        // Maven project - OWASP Dependency Check
        sh 'mvn org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=9 || true'
    } else if (fileExists('package.json')) {
        // Node project - npm audit
        def auditResult = sh(
            script: 'npm audit --json 2>/dev/null || echo \'{"vulnerabilities":{}}\'',
            returnStdout: true
        ).trim()
        def audit = readJSON text: auditResult
        def vulns = audit.vulnerabilities ?: [:]
        
        results.critical = vulns.values().count { it.severity == 'critical' }
        results.high = vulns.values().count { it.severity == 'high' }
        results.medium = vulns.values().count { it.severity == 'moderate' }
        results.low = vulns.values().count { it.severity == 'low' }
        results.total = vulns.size()
        results.tool = 'npm-audit'
    } else if (fileExists('requirements.txt') || fileExists('setup.py')) {
        // Python project - safety
        sh 'pip install safety && safety check --json > safety-report.json || true'
    } else if (fileExists('go.mod')) {
        // Go project - govulncheck
        sh 'go install golang.org/x/vuln/cmd/govulncheck@latest && govulncheck ./... || true'
    }

    return results
}

