package org.devops

/**
 * Docker utility class for building and managing container images
 */
class Docker implements Serializable {
    
    def script
    def registry
    def credentialsId
    
    Docker(script, String registry = 'docker.io', String credentialsId = 'docker-credentials') {
        this.script = script
        this.registry = registry
        this.credentialsId = credentialsId
    }

    /**
     * Build a Docker image
     */
    String build(Map config) {
        def imageName = config.imageName ?: script.error("imageName is required")
        def dockerfile = config.dockerfile ?: 'Dockerfile'
        def context = config.context ?: '.'
        def tag = config.tag ?: 'latest'
        def buildArgs = config.buildArgs ?: [:]
        def noCache = config.noCache ?: false
        def target = config.target

        def fullImageName = "${registry}/${imageName}:${tag}"
        
        def buildArgsString = buildArgs.collect { k, v -> "--build-arg ${k}=${v}" }.join(' ')
        def targetString = target ? "--target ${target}" : ''
        def noCacheString = noCache ? '--no-cache' : ''

        script.sh """
            docker build \
                -f ${dockerfile} \
                -t ${fullImageName} \
                ${buildArgsString} \
                ${targetString} \
                ${noCacheString} \
                ${context}
        """

        return fullImageName
    }

    /**
     * Push image to registry
     */
    void push(String imageName, List<String> tags = ['latest']) {
        login()
        
        tags.each { tag ->
            def fullName = "${registry}/${imageName}:${tag}"
            script.sh "docker push ${fullName}"
            script.echo "Pushed: ${fullName}"
        }
        
        logout()
    }

    /**
     * Tag an image
     */
    void tag(String sourceImage, String targetImage) {
        script.sh "docker tag ${sourceImage} ${targetImage}"
    }

    /**
     * Login to registry
     */
    void login() {
        script.withCredentials([script.usernamePassword(
            credentialsId: credentialsId,
            usernameVariable: 'DOCKER_USER',
            passwordVariable: 'DOCKER_PASS'
        )]) {
            script.sh "echo \$DOCKER_PASS | docker login ${registry} -u \$DOCKER_USER --password-stdin"
        }
    }

    /**
     * Logout from registry
     */
    void logout() {
        script.sh "docker logout ${registry}"
    }

    /**
     * Remove local image
     */
    void removeImage(String imageName) {
        script.sh "docker rmi ${imageName} || true"
    }

    /**
     * Prune unused images
     */
    void prune() {
        script.sh "docker image prune -f"
    }

    /**
     * Get image digest
     */
    String getDigest(String imageName) {
        return script.sh(
            script: "docker inspect --format='{{index .RepoDigests 0}}' ${imageName} | cut -d'@' -f2",
            returnStdout: true
        ).trim()
    }

    /**
     * Check if image exists locally
     */
    boolean imageExists(String imageName) {
        def result = script.sh(
            script: "docker image inspect ${imageName} > /dev/null 2>&1",
            returnStatus: true
        )
        return result == 0
    }

    /**
     * Get image size
     */
    String getImageSize(String imageName) {
        return script.sh(
            script: "docker image inspect ${imageName} --format='{{.Size}}' | numfmt --to=iec-i",
            returnStdout: true
        ).trim()
    }

    /**
     * Build multi-platform image
     */
    void buildMultiPlatform(Map config) {
        def imageName = config.imageName
        def platforms = config.platforms ?: ['linux/amd64', 'linux/arm64']
        def dockerfile = config.dockerfile ?: 'Dockerfile'
        def context = config.context ?: '.'
        def tag = config.tag ?: 'latest'

        def platformString = platforms.join(',')
        def fullImageName = "${registry}/${imageName}:${tag}"

        // Ensure buildx is available
        script.sh "docker buildx create --use --name multiarch || docker buildx use multiarch"

        login()

        script.sh """
            docker buildx build \
                --platform ${platformString} \
                -f ${dockerfile} \
                -t ${fullImageName} \
                --push \
                ${context}
        """

        logout()
    }
}

