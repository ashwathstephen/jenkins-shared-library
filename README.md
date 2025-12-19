# Jenkins Shared Library

[![Jenkins](https://img.shields.io/badge/Jenkins-2.0+-D24939?logo=jenkins&logoColor=white)](https://www.jenkins.io/)
[![Groovy](https://img.shields.io/badge/Groovy-4.0+-4298B8?logo=apache-groovy)](https://groovy-lang.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Production-ready Jenkins Shared Library with reusable pipeline components. Built from real-world experience managing CI/CD for 200+ microservices.

## Features

- Standardized Pipelines: Consistent CI/CD across all projects
- Multi-Language Support: Java, Python, Node.js, Go, Docker
- Security Scanning: Integrated Checkmarx, SonarQube, WhiteSource
- Deployment Strategies: Blue-green, canary, rolling updates
- Kubernetes Integration: Helm deployments, kubectl operations
- Notifications: Slack, Teams, Email with rich formatting
- Auto-Rollback: Automatic rollback on deployment failures

## Available Functions

| Function | Description |
|----------|-------------|
| `buildDocker` | Build and push Docker images |
| `deployK8s` | Deploy to Kubernetes with Helm |
| `runTests` | Execute tests with coverage |
| `securityScan` | Run security scans |
| `notifySlack` | Send Slack notifications |
| `standardPipeline` | Complete CI/CD pipeline |
| `blueGreenDeploy` | Blue-green deployment strategy |

## Setup

### 1. Add Library to Jenkins

Go to Manage Jenkins > Configure System > Global Pipeline Libraries

```
Name: devops-shared-library
Default version: main
Retrieval method: Modern SCM
Source Code Management: Git
Project Repository: https://github.com/ashwathstephen/jenkins-shared-library.git
```

### 2. Use in Jenkinsfile

```groovy
@Library('devops-shared-library') _

standardPipeline(
    appName: 'my-service',
    language: 'java',
    deployEnvs: ['dev', 'staging', 'prod'],
    slackChannel: '#deployments'
)
```

## Repository Structure

```
├── vars/                    # Global pipeline variables
│   ├── buildDocker.groovy
│   ├── deployK8s.groovy
│   ├── runTests.groovy
│   ├── securityScan.groovy
│   ├── notifySlack.groovy
│   ├── standardPipeline.groovy
│   └── blueGreenDeploy.groovy
├── src/
│   └── org/devops/         # Shared classes
│       ├── Docker.groovy
│       ├── Kubernetes.groovy
│       └── Utils.groovy
└── resources/
    └── templates/          # Template files
```

## Usage Examples

### Basic Docker Build and Push

```groovy
@Library('devops-shared-library') _

pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                buildDocker(
                    imageName: 'my-app',
                    registry: 'docker.io/myorg',
                    dockerfile: 'Dockerfile'
                )
            }
        }
    }
}
```

### Kubernetes Deployment

```groovy
@Library('devops-shared-library') _

pipeline {
    agent any
    stages {
        stage('Deploy') {
            steps {
                deployK8s(
                    cluster: 'production',
                    namespace: 'my-namespace',
                    helmChart: './charts/my-app',
                    values: './values-prod.yaml'
                )
            }
        }
    }
}
```

### Full Standard Pipeline

```groovy
@Library('devops-shared-library') _

standardPipeline(
    appName: 'payment-service',
    language: 'java',
    javaVersion: '17',
    buildTool: 'maven',
    dockerRegistry: 'ecr.aws/myorg',
    kubeCluster: 'eks-production',
    helmChart: './charts/payment-service',
    deployEnvs: ['dev', 'staging', 'prod'],
    runSecurityScan: true,
    slackChannel: '#payment-deploys'
)
```

## Security Features

- Checkmarx SAST integration
- Container image scanning
- Secrets detection
- Dependency vulnerability scanning
- Compliance gates

## Author

Ashwath Abraham Stephen
Senior DevOps Engineer | [LinkedIn](https://linkedin.com/in/ashwathstephen) | [GitHub](https://github.com/ashwathstephen)

## License

MIT License - see [LICENSE](LICENSE) for details.
