package org.devops

/**
 * Kubernetes utility class for cluster operations
 */
class Kubernetes implements Serializable {
    
    def script
    def cluster
    def namespace
    
    Kubernetes(script, String cluster = '', String namespace = 'default') {
        this.script = script
        this.cluster = cluster
        this.namespace = namespace
    }

    /**
     * Configure kubectl context
     */
    void configureContext(String clusterName = null) {
        def targetCluster = clusterName ?: cluster
        
        if (targetCluster.startsWith('eks-')) {
            def parts = targetCluster.split('-')
            def region = parts.length > 1 ? parts[1] : 'us-east-1'
            def name = parts.length > 2 ? parts[2] : parts[1]
            script.sh "aws eks update-kubeconfig --name ${name} --region ${region}"
        } else if (targetCluster.startsWith('gke-')) {
            def parts = targetCluster.split('-')
            script.sh "gcloud container clusters get-credentials ${parts[1]} --zone ${parts[2]} --project ${parts[3]}"
        } else {
            script.sh "kubectl config use-context ${targetCluster}"
        }
    }

    /**
     * Apply Kubernetes manifests
     */
    void apply(String manifestPath, String ns = null) {
        def targetNs = ns ?: namespace
        script.sh "kubectl apply -f ${manifestPath} -n ${targetNs}"
    }

    /**
     * Delete Kubernetes resources
     */
    void delete(String manifestPath, String ns = null) {
        def targetNs = ns ?: namespace
        script.sh "kubectl delete -f ${manifestPath} -n ${targetNs} --ignore-not-found"
    }

    /**
     * Create namespace if not exists
     */
    void createNamespace(String ns = null) {
        def targetNs = ns ?: namespace
        script.sh "kubectl create namespace ${targetNs} --dry-run=client -o yaml | kubectl apply -f -"
    }

    /**
     * Wait for deployment rollout
     */
    void waitForRollout(String deploymentName, int timeoutSeconds = 300, String ns = null) {
        def targetNs = ns ?: namespace
        script.sh "kubectl rollout status deployment/${deploymentName} -n ${targetNs} --timeout=${timeoutSeconds}s"
    }

    /**
     * Get deployment status
     */
    Map getDeploymentStatus(String deploymentName, String ns = null) {
        def targetNs = ns ?: namespace
        def json = script.sh(
            script: "kubectl get deployment ${deploymentName} -n ${targetNs} -o json",
            returnStdout: true
        )
        return script.readJSON(text: json)
    }

    /**
     * Scale deployment
     */
    void scale(String deploymentName, int replicas, String ns = null) {
        def targetNs = ns ?: namespace
        script.sh "kubectl scale deployment/${deploymentName} -n ${targetNs} --replicas=${replicas}"
    }

    /**
     * Restart deployment
     */
    void restart(String deploymentName, String ns = null) {
        def targetNs = ns ?: namespace
        script.sh "kubectl rollout restart deployment/${deploymentName} -n ${targetNs}"
    }

    /**
     * Rollback deployment
     */
    void rollback(String deploymentName, String ns = null) {
        def targetNs = ns ?: namespace
        script.sh "kubectl rollout undo deployment/${deploymentName} -n ${targetNs}"
    }

    /**
     * Get pods for a deployment
     */
    List getPods(String labelSelector, String ns = null) {
        def targetNs = ns ?: namespace
        def json = script.sh(
            script: "kubectl get pods -l ${labelSelector} -n ${targetNs} -o json",
            returnStdout: true
        )
        def result = script.readJSON(text: json)
        return result.items
    }

    /**
     * Get pod logs
     */
    String getLogs(String podName, String containerName = null, int tailLines = 100, String ns = null) {
        def targetNs = ns ?: namespace
        def containerFlag = containerName ? "-c ${containerName}" : ''
        return script.sh(
            script: "kubectl logs ${podName} ${containerFlag} -n ${targetNs} --tail=${tailLines}",
            returnStdout: true
        )
    }

    /**
     * Execute command in pod
     */
    String exec(String podName, String command, String containerName = null, String ns = null) {
        def targetNs = ns ?: namespace
        def containerFlag = containerName ? "-c ${containerName}" : ''
        return script.sh(
            script: "kubectl exec ${podName} ${containerFlag} -n ${targetNs} -- ${command}",
            returnStdout: true
        )
    }

    /**
     * Create secret from literal values
     */
    void createSecret(String secretName, Map<String, String> data, String ns = null) {
        def targetNs = ns ?: namespace
        def literals = data.collect { k, v -> "--from-literal=${k}=${v}" }.join(' ')
        script.sh """
            kubectl create secret generic ${secretName} \
                ${literals} \
                -n ${targetNs} \
                --dry-run=client -o yaml | kubectl apply -f -
        """
    }

    /**
     * Create configmap from literal values
     */
    void createConfigMap(String configMapName, Map<String, String> data, String ns = null) {
        def targetNs = ns ?: namespace
        def literals = data.collect { k, v -> "--from-literal=${k}=${v}" }.join(' ')
        script.sh """
            kubectl create configmap ${configMapName} \
                ${literals} \
                -n ${targetNs} \
                --dry-run=client -o yaml | kubectl apply -f -
        """
    }

    /**
     * Port forward to a pod
     */
    void portForward(String podName, int localPort, int remotePort, String ns = null) {
        def targetNs = ns ?: namespace
        script.sh "kubectl port-forward ${podName} ${localPort}:${remotePort} -n ${targetNs} &"
    }

    /**
     * Get cluster info
     */
    String getClusterInfo() {
        return script.sh(
            script: "kubectl cluster-info",
            returnStdout: true
        )
    }

    /**
     * Check if resource exists
     */
    boolean resourceExists(String resourceType, String resourceName, String ns = null) {
        def targetNs = ns ?: namespace
        def result = script.sh(
            script: "kubectl get ${resourceType} ${resourceName} -n ${targetNs} > /dev/null 2>&1",
            returnStatus: true
        )
        return result == 0
    }
}

