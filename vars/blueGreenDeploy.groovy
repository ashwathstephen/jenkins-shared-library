#!/usr/bin/env groovy

/**
 * Blue-Green Deployment Strategy
 * 
 * Implements zero-downtime deployments using blue-green strategy.
 * Supports automatic rollback on health check failures.
 * 
 * @param config Map containing:
 *   - cluster: Kubernetes cluster name (required)
 *   - namespace: Kubernetes namespace (required)
 *   - appName: Application name (required)
 *   - helmChart: Path to Helm chart (required)
 *   - imageTag: Docker image tag (required)
 *   - healthCheckPath: Health check endpoint (default: /health)
 *   - healthCheckTimeout: Timeout in seconds (default: 300)
 *   - trafficSwitchDelay: Delay before switching traffic in seconds (default: 30)
 */
def call(Map config = [:]) {
    def cluster = config.cluster ?: error("cluster is required")
    def namespace = config.namespace ?: error("namespace is required")
    def appName = config.appName ?: error("appName is required")
    def helmChart = config.helmChart ?: error("helmChart is required")
    def imageTag = config.imageTag ?: error("imageTag is required")
    def healthCheckPath = config.healthCheckPath ?: '/health'
    def healthCheckTimeout = config.healthCheckTimeout ?: 300
    def trafficSwitchDelay = config.trafficSwitchDelay ?: 30

    echo "Starting Blue-Green Deployment"
    echo "  Application: ${appName}"
    echo "  Namespace: ${namespace}"
    echo "  Image Tag: ${imageTag}"

    // Determine current active color
    def currentColor = getCurrentActiveColor(namespace, appName)
    def newColor = currentColor == 'blue' ? 'green' : 'blue'
    
    echo "Current active: ${currentColor}"
    echo "Deploying to: ${newColor}"

    try {
        // Step 1: Deploy to inactive color
        echo "Step 1: Deploying new version to ${newColor}"
        deployToColor(config, newColor, imageTag)

        // Step 2: Wait for deployment to be ready
        echo "Step 2: Waiting for ${newColor} deployment to be ready"
        waitForDeployment(namespace, "${appName}-${newColor}", healthCheckTimeout)

        // Step 3: Run health checks
        echo "Step 3: Running health checks on ${newColor}"
        def healthCheckPassed = runHealthChecks(namespace, "${appName}-${newColor}", healthCheckPath)
        
        if (!healthCheckPassed) {
            error("Health checks failed for ${newColor} deployment")
        }

        // Step 4: Optional delay before traffic switch
        if (trafficSwitchDelay > 0) {
            echo "Step 4: Waiting ${trafficSwitchDelay} seconds before switching traffic"
            sleep(time: trafficSwitchDelay, unit: 'SECONDS')
        }

        // Step 5: Switch traffic to new color
        echo "Step 5: Switching traffic to ${newColor}"
        switchTraffic(namespace, appName, newColor)

        // Step 6: Verify traffic switch
        echo "Step 6: Verifying traffic switch"
        sleep(time: 10, unit: 'SECONDS')
        
        def trafficVerified = verifyTraffic(namespace, appName, newColor)
        if (!trafficVerified) {
            echo "Traffic verification failed, rolling back"
            switchTraffic(namespace, appName, currentColor)
            error("Traffic verification failed")
        }

        // Step 7: Scale down old deployment (optional)
        if (config.scaleDownOld != false) {
            echo "Step 7: Scaling down ${currentColor} deployment"
            scaleDeployment(namespace, "${appName}-${currentColor}", 0)
        }

        echo "Blue-Green deployment completed successfully"
        echo "Active color is now: ${newColor}"

        return [
            success: true,
            activeColor: newColor,
            previousColor: currentColor,
            imageTag: imageTag
        ]

    } catch (Exception e) {
        echo "Deployment failed: ${e.message}"
        echo "Rolling back to ${currentColor}"
        
        // Rollback: Switch traffic back to current color
        switchTraffic(namespace, appName, currentColor)
        
        // Scale down failed deployment
        scaleDeployment(namespace, "${appName}-${newColor}", 0)
        
        throw e
    }
}

def getCurrentActiveColor(String namespace, String appName) {
    def activeColor = sh(
        script: """
            kubectl get service ${appName} -n ${namespace} \
                -o jsonpath='{.spec.selector.color}' 2>/dev/null || echo 'blue'
        """,
        returnStdout: true
    ).trim()
    
    return activeColor ?: 'blue'
}

def deployToColor(Map config, String color, String imageTag) {
    def releaseName = "${config.appName}-${color}"
    
    sh """
        helm upgrade --install ${releaseName} ${config.helmChart} \
            --namespace ${config.namespace} \
            --set color=${color} \
            --set image.tag=${imageTag} \
            --set replicaCount=${config.replicaCount ?: 3} \
            --wait \
            --timeout 5m
    """
}

def waitForDeployment(String namespace, String deploymentName, int timeout) {
    sh """
        kubectl rollout status deployment/${deploymentName} \
            -n ${namespace} \
            --timeout=${timeout}s
    """
}

def runHealthChecks(String namespace, String deploymentName, String healthCheckPath) {
    // Get pod IPs
    def podIPs = sh(
        script: """
            kubectl get pods -n ${namespace} \
                -l app=${deploymentName} \
                -o jsonpath='{.items[*].status.podIP}'
        """,
        returnStdout: true
    ).trim().split(' ')

    if (podIPs.size() == 0 || podIPs[0] == '') {
        echo "No pods found for ${deploymentName}"
        return false
    }

    // Check health of each pod
    def allHealthy = true
    podIPs.each { ip ->
        if (ip) {
            def healthStatus = sh(
                script: """
                    kubectl run health-check-\$\$ --rm -i --restart=Never \
                        --image=curlimages/curl:latest \
                        -n ${namespace} \
                        -- curl -sf http://${ip}:8080${healthCheckPath} || echo 'unhealthy'
                """,
                returnStdout: true
            ).trim()
            
            if (healthStatus == 'unhealthy') {
                echo "Pod ${ip} is unhealthy"
                allHealthy = false
            }
        }
    }

    return allHealthy
}

def switchTraffic(String namespace, String appName, String targetColor) {
    sh """
        kubectl patch service ${appName} -n ${namespace} \
            -p '{"spec":{"selector":{"color":"${targetColor}"}}}'
    """
}

def verifyTraffic(String namespace, String appName, String expectedColor) {
    def actualColor = sh(
        script: """
            kubectl get service ${appName} -n ${namespace} \
                -o jsonpath='{.spec.selector.color}'
        """,
        returnStdout: true
    ).trim()
    
    return actualColor == expectedColor
}

def scaleDeployment(String namespace, String deploymentName, int replicas) {
    sh """
        kubectl scale deployment/${deploymentName} \
            -n ${namespace} \
            --replicas=${replicas} || true
    """
}

