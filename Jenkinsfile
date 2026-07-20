pipeline {
  agent any

  options {
    buildDiscarder(logRotator(numToKeepStr: '30'))
    disableConcurrentBuilds(abortPrevious: true)
    skipDefaultCheckout(true)
    timestamps()
  }

  parameters {
    booleanParam(
      name: 'PUBLISH_IMAGES',
      defaultValue: true,
      description: 'Push successful main/tag images to GHCR.'
    )
    booleanParam(
      name: 'DEPLOY_LOCAL',
      defaultValue: false,
      description: 'After publishing main, replace the local Voyager app/frontend and roll back on failed health checks.'
    )
    booleanParam(
      name: 'RUN_E2E',
      defaultValue: false,
      description: 'Run browser E2E tests against the backend currently available on host.docker.internal:8081.'
    )
    string(
      name: 'SONAR_HOST_URL',
      defaultValue: '',
      description: 'Optional SonarQube URL. Leave blank to skip analysis.'
    )
  }

  triggers {
    // A local Jenkins controller cannot receive GitHub webhooks without a
    // public tunnel, so poll until a proper deployment server is available.
    pollSCM('H/5 * * * *')
  }

  environment {
    REGISTRY = 'ghcr.io'
    APP_IMAGE = 'ghcr.io/harshithrao07/voyager-app'
    FRONTEND_IMAGE = 'ghcr.io/harshithrao07/voyager-frontend'
    DEPLOY_ENV_FILE = '/run/voyager-deploy/.env'
    COMPOSE_PROJECT_NAME = 'voyager'
  }

  stages {
    stage('Checkout') {
      steps {
        checkout scm
        script {
          env.RELEASE_TAG = "sha-${env.GIT_COMMIT.take(12)}"
          currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.RELEASE_TAG}"
        }
      }
    }

    stage('Backend tests') {
      agent {
        docker {
          image 'maven:3.9.11-eclipse-temurin-17'
          args '-v voyager-jenkins-m2:/root/.m2'
          reuseNode true
        }
      }
      steps {
        sh 'mvn -B test'
      }
    }

    stage('Frontend checks') {
      agent {
        docker {
          image 'node:22-alpine'
          reuseNode true
        }
      }
      steps {
        dir('frontend') {
          sh 'npm install --no-audit --no-fund'
          sh 'npm run lint'
          sh 'npm run test:asl'
          sh 'npm run build'
        }
      }
    }

    stage('Browser E2E') {
      when {
        expression { params.RUN_E2E }
      }
      agent {
        docker {
          image 'mcr.microsoft.com/playwright:v1.61.1-noble'
          args '--add-host=host.docker.internal:host-gateway'
          reuseNode true
        }
      }
      environment {
        E2E_BACKEND_URL = 'http://host.docker.internal:8081'
      }
      steps {
        dir('frontend') {
          sh 'npm install --no-audit --no-fund'
          sh 'npm run test:e2e'
        }
      }
    }

    stage('Sonar analysis') {
      when {
        expression { params.SONAR_HOST_URL?.trim() }
      }
      agent {
        docker {
          image 'maven:3.9.11-eclipse-temurin-17'
          args '-v voyager-jenkins-m2:/root/.m2'
          reuseNode true
        }
      }
      steps {
        withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
          sh '''
            set +x
            mvn -B sonar:sonar \
              -Dsonar.host.url="$SONAR_HOST_URL" \
              -Dsonar.token="$SONAR_TOKEN"
          '''
        }
      }
    }

    stage('Build container images') {
      when {
        anyOf {
          branch 'main'
          buildingTag()
        }
      }
      steps {
        sh '''
          docker build \
            --label "org.opencontainers.image.revision=$GIT_COMMIT" \
            --tag "$APP_IMAGE:$RELEASE_TAG" \
            .
          docker build \
            --file frontend/Dockerfile \
            --label "org.opencontainers.image.revision=$GIT_COMMIT" \
            --tag "$FRONTEND_IMAGE:$RELEASE_TAG" \
            .
        '''
      }
    }

    stage('Push images to GHCR') {
      when {
        allOf {
          expression { params.PUBLISH_IMAGES }
          anyOf {
            branch 'main'
            buildingTag()
          }
        }
      }
      steps {
        withCredentials([
          usernamePassword(
            credentialsId: 'github-container-registry',
            usernameVariable: 'GHCR_USER',
            passwordVariable: 'GHCR_TOKEN'
          )
        ]) {
          sh '''
            set +x
            echo "$GHCR_TOKEN" | docker login "$REGISTRY" --username "$GHCR_USER" --password-stdin

            docker push "$APP_IMAGE:$RELEASE_TAG"
            docker push "$FRONTEND_IMAGE:$RELEASE_TAG"

            if [ "$BRANCH_NAME" = "main" ]; then
              docker tag "$APP_IMAGE:$RELEASE_TAG" "$APP_IMAGE:main"
              docker tag "$FRONTEND_IMAGE:$RELEASE_TAG" "$FRONTEND_IMAGE:main"
              docker push "$APP_IMAGE:main"
              docker push "$FRONTEND_IMAGE:main"
            fi

            if [ -n "${TAG_NAME:-}" ]; then
              safe_tag=$(printf '%s' "$TAG_NAME" | tr '/:@ ' '----')
              docker tag "$APP_IMAGE:$RELEASE_TAG" "$APP_IMAGE:$safe_tag"
              docker tag "$FRONTEND_IMAGE:$RELEASE_TAG" "$FRONTEND_IMAGE:$safe_tag"
              docker push "$APP_IMAGE:$safe_tag"
              docker push "$FRONTEND_IMAGE:$safe_tag"
            fi
          '''
        }
      }
    }

    stage('Deploy local') {
      when {
        allOf {
          branch 'main'
          expression { params.PUBLISH_IMAGES && params.DEPLOY_LOCAL }
        }
      }
      steps {
        sh 'bash scripts/jenkins-local-deploy.sh'
      }
    }
  }

  post {
    always {
      junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
      archiveArtifacts allowEmptyArchive: true, artifacts: 'target/site/jacoco/**/*,frontend/playwright-report/**/*,frontend/test-results/**/*'
      publishHTML target: [
        allowMissing: true,
        alwaysLinkToLastBuild: true,
        keepAll: true,
        reportDir: 'target/site/jacoco',
        reportFiles: 'index.html',
        reportName: 'JaCoCo Coverage'
      ]
      sh 'docker logout ghcr.io >/dev/null 2>&1 || true'
      cleanWs deleteDirs: true, notFailBuild: true
    }
  }
}
