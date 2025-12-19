#!/usr/bin/env groovy

/**
 * Deploy to Kubernetes using Helm
 * 
 * @param config Map containing:
 *   - cluster: Kubernetes cluster name (required)
 *   - namespace: Kubernetes namespace (required)
 *   - helmChart: Path to Helm chart (required)
 *   - releaseName: Helm release name (default: appName)
 *   - values: Path to values file
 *   - setValues: Map of --set values
 *   - timeout: Deployment timeout (default: 5m)
 *   - wait: Wait for deployment (default: true)
 *   - atomic: Rollback on failure (default: true)
 *   - dryRun: Dry run mode (default: false)
 */
def call(Map config = [:]) {
    def cluster = config.cluster ?: error("cluster is required")
    def namespace = config.namespace ?: error("namespace is required")
    def helmChart = config.helmChart ?: error("helmChart is required")
    def releaseName = config.releaseName ?: config.appName ?: 'app'
    def values = config.values
    def setValues = config.setValues ?: [:]
    def timeout = config.timeout ?: '5m'
    def wait = config.wait != false
    def atomic = config.atomic != false
    def dryRun = config.dryRun ?: false

    echo "Deploying to Kubernetes"
    echo "  Cluster: ${cluster}"
    echo "  Namespace: ${namespace}"
    echo "  Release: ${releaseName}"
    echo "  Chart: ${helmChart}"

    // Configure kubectl context
    configureKubeContext(cluster)

    // Ensure namespace exists
    sh "kubectl create namespace ${namespace} --dry-run=client -o yaml | kubectl apply -f -"

    // Build helm upgrade command
    def helmCmd = """
        helm upgrade --install ${releaseName} ${helmChart} \
            --namespace ${namespace} \
            --timeout ${timeout} \
            ${wait ? '--wait' : ''} \
            ${atomic ? '--atomic' : ''} \
            ${dryRun ? '--dry-run' : ''}
    """

    // Add values file if specified
    if (values) {
        helmCmd += " -f ${values}"
    }

    // Add --set values
    setValues.each { k, v ->
        helmCmd += " --set ${k}=${v}"
    }

    // Execute deployment
    sh helmCmd

    // Get deployment status
    if (!dryRun) {
        def status = getDeploymentStatus(namespace, releaseName)
        echo "Deployment Status: ${status}"
        return status
    }

    return [status: 'dry-run']
}

def configureKubeContext(String cluster) {
    // Support for different cluster types
    if (cluster.startsWith('eks-')) {
        def region = cluster.split('-')[1] ?: 'us-east-1'
        def clusterName = cluster.replaceFirst('eks-', '')
        sh "aws eks update-kubeconfig --name ${clusterName} --region ${region}"
    } else if (cluster.startsWith('gke-')) {
        // GKE cluster
        def parts = cluster.split('-')
        sh "gcloud container clusters get-credentials ${parts[1]} --zone ${parts[2]} --project ${parts[3]}"
    } else {
        // Assume kubeconfig is already configured
        sh "kubectl config use-context ${cluster}"
    }
}

def getDeploymentStatus(String namespace, String releaseName) {
    def status = sh(
        script: "helm status ${releaseName} -n ${namespace} -o json 2>/dev/null || echo '{}'",
        returnStdout: true
    ).trim()
    
    def replicas = sh(
        script: "kubectl get deployment -n ${namespace} -l app.kubernetes.io/instance=${releaseName} -o jsonpath='{.items[0].status.readyReplicas}' 2>/dev/null || echo '0'",
        returnStdout: true
    ).trim()

    return [
        release: releaseName,
        namespace: namespace,
        readyReplicas: replicas,
        helmStatus: status
    ]
}

