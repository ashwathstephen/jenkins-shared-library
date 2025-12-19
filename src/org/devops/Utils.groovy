package org.devops

/**
 * Utility class with common helper methods
 */
class Utils implements Serializable {
    
    def script
    
    Utils(script) {
        this.script = script
    }

    /**
     * Check if a file exists in the workspace
     */
    boolean fileExists(String path) {
        return script.fileExists(path)
    }

    /**
     * Read file contents
     */
    String readFile(String path) {
        return script.readFile(path)
    }

    /**
     * Write content to file
     */
    void writeFile(String path, String content) {
        script.writeFile(file: path, text: content)
    }

    /**
     * Execute shell command and return output
     */
    String sh(String command, boolean returnStdout = true) {
        return script.sh(script: command, returnStdout: returnStdout)
    }

    /**
     * Get environment variable with default
     */
    String getEnv(String name, String defaultValue = '') {
        return script.env."${name}" ?: defaultValue
    }

    /**
     * Set environment variable
     */
    void setEnv(String name, String value) {
        script.env."${name}" = value
    }

    /**
     * Parse JSON string to map
     */
    Map parseJson(String json) {
        return script.readJSON(text: json)
    }

    /**
     * Convert map to JSON string
     */
    String toJson(Map data) {
        return groovy.json.JsonOutput.toJson(data)
    }

    /**
     * Get current timestamp in ISO format
     */
    String getTimestamp() {
        return new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))
    }

    /**
     * Calculate duration between two timestamps
     */
    String calculateDuration(long startTime, long endTime) {
        def duration = endTime - startTime
        def seconds = (duration / 1000) % 60
        def minutes = (duration / (1000 * 60)) % 60
        def hours = (duration / (1000 * 60 * 60)) % 24
        
        if (hours > 0) {
            return "${hours}h ${minutes}m ${seconds}s"
        } else if (minutes > 0) {
            return "${minutes}m ${seconds}s"
        } else {
            return "${seconds}s"
        }
    }

    /**
     * Retry a closure with exponential backoff
     */
    def retry(int maxAttempts, int initialDelaySeconds, Closure action) {
        def attempt = 0
        def lastException = null
        
        while (attempt < maxAttempts) {
            try {
                return action()
            } catch (Exception e) {
                lastException = e
                attempt++
                if (attempt < maxAttempts) {
                    def delay = initialDelaySeconds * Math.pow(2, attempt - 1)
                    script.echo "Attempt ${attempt} failed, retrying in ${delay} seconds..."
                    script.sleep(time: delay as int, unit: 'SECONDS')
                }
            }
        }
        
        throw lastException
    }

    /**
     * Mask sensitive values in logs
     */
    String maskSensitive(String text, List<String> sensitivePatterns) {
        def masked = text
        sensitivePatterns.each { pattern ->
            masked = masked.replaceAll(pattern, '****')
        }
        return masked
    }

    /**
     * Validate required parameters
     */
    void validateRequired(Map params, List<String> required) {
        def missing = required.findAll { !params.containsKey(it) || params[it] == null }
        if (missing) {
            throw new IllegalArgumentException("Missing required parameters: ${missing.join(', ')}")
        }
    }

    /**
     * Get Git commit information
     */
    Map getGitInfo() {
        return [
            commit: sh('git rev-parse HEAD').trim(),
            shortCommit: sh('git rev-parse --short HEAD').trim(),
            branch: sh('git rev-parse --abbrev-ref HEAD').trim(),
            author: sh('git log -1 --pretty=%an').trim(),
            message: sh('git log -1 --pretty=%s').trim(),
            timestamp: sh('git log -1 --pretty=%ci').trim()
        ]
    }

    /**
     * Check if running on main/master branch
     */
    boolean isMainBranch() {
        def branch = getEnv('GIT_BRANCH', getEnv('BRANCH_NAME', ''))
        return branch in ['main', 'master', 'origin/main', 'origin/master']
    }

    /**
     * Check if this is a pull request build
     */
    boolean isPullRequest() {
        return getEnv('CHANGE_ID', '') != ''
    }
}

