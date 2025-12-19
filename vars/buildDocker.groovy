#!/usr/bin/env groovy

/**
 * Build and push Docker images
 * 
 * @param config Map containing:
 *   - imageName: Name of the Docker image (required)
 *   - registry: Docker registry URL (default: docker.io)
 *   - dockerfile: Path to Dockerfile (default: Dockerfile)
 *   - context: Build context (default: .)
 *   - tags: List of tags (default: [BUILD_NUMBER, 'latest'])
 *   - buildArgs: Map of build arguments
 *   - push: Whether to push the image (default: true)
 *   - credentialsId: Jenkins credentials ID for registry auth
 */
def call(Map config = [:]) {
    def imageName = config.imageName ?: error("imageName is required")
    def registry = config.registry ?: 'docker.io'
    def dockerfile = config.dockerfile ?: 'Dockerfile'
    def context = config.context ?: '.'
    def tags = config.tags ?: [env.BUILD_NUMBER, 'latest']
    def buildArgs = config.buildArgs ?: [:]
    def push = config.push != false
    def credentialsId = config.credentialsId ?: 'docker-registry-credentials'

    def fullImageName = "${registry}/${imageName}"
    def buildArgsString = buildArgs.collect { k, v -> "--build-arg ${k}=${v}" }.join(' ')

    echo "Building Docker image: ${fullImageName}"
    echo "Using Dockerfile: ${dockerfile}"
    echo "Build context: ${context}"

    // Build the image
    def primaryTag = tags[0]
    sh """
        docker build \
            -f ${dockerfile} \
            -t ${fullImageName}:${primaryTag} \
            ${buildArgsString} \
            ${context}
    """

    // Tag with additional tags
    tags.drop(1).each { tag ->
        sh "docker tag ${fullImageName}:${primaryTag} ${fullImageName}:${tag}"
    }

    // Push if enabled
    if (push) {
        withCredentials([usernamePassword(
            credentialsId: credentialsId,
            usernameVariable: 'DOCKER_USER',
            passwordVariable: 'DOCKER_PASS'
        )]) {
            sh "echo \$DOCKER_PASS | docker login ${registry} -u \$DOCKER_USER --password-stdin"
            
            tags.each { tag ->
                echo "Pushing ${fullImageName}:${tag}"
                sh "docker push ${fullImageName}:${tag}"
            }
            
            sh "docker logout ${registry}"
        }
    }

    // Return image info
    return [
        imageName: fullImageName,
        tags: tags,
        digest: getImageDigest(fullImageName, primaryTag)
    ]
}

def getImageDigest(String imageName, String tag) {
    try {
        return sh(
            script: "docker inspect --format='{{index .RepoDigests 0}}' ${imageName}:${tag} 2>/dev/null | cut -d'@' -f2",
            returnStdout: true
        ).trim()
    } catch (Exception e) {
        return "unknown"
    }
}

