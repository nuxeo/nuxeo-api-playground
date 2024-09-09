/*
* (C) Copyright 2023 Nuxeo (http://nuxeo.com/) and others.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
* Contributors:
*     Kevin Leturc <kevin.leturc@hyland.com>
*     Antoine Taillefer <antoine.taillefer@hyland.com>
*/
library identifier: "platform-ci-shared-library@v0.0.40"

String getCLIDSecret() {
  container('maven') {
    def nuxeoParentVersion = readMavenPom().getParent().getVersion()
    // target connect preprod if nuxeo-parent is a snapshot version or a build version
    return nuxeoParentVersion.matches("^\\d+\\.\\d+(-SNAPSHOT|\\.\\d+)\$") ? 'instance-clid-preprod' : 'instance-clid'
  }
}

pipeline {
  agent {
    label 'jenkins-nuxeo-jsf-lts-2023'
  }
  options {
    buildDiscarder(logRotator(daysToKeepStr: '60', numToKeepStr: '60', artifactNumToKeepStr: '5'))
    disableConcurrentBuilds(abortPrevious: true)
    githubProjectProperty(projectUrlStr: 'https://github.com/nuxeo/nuxeo-api-playground')
  }
  environment {
    CONNECT_CLID_SECRET = getCLIDSecret()
    CURRENT_NAMESPACE = nxK8s.getCurrentNamespace()
    MAVEN_OPTS = "$MAVEN_OPTS -Xms512m -Xmx3072m"
    VERSION = nxUtils.getVersion()
    NUXEO_API_PLAYGROUND_PACKAGE_PATH = "nuxeo-api-playground-package/target/nuxeo-api-playground-package-${VERSION}.zip"
  }
  stages {
    stage('Set labels') {
      steps {
        container('maven') {
          script {
            nxK8s.setPodLabels()
          }
        }
      }
    }
    stage('Update version') {
      steps {
        container('maven') {
          script {
            nxMvn.updateVersion()
          }
        }
      }
    }
    stage('Compile') {
      steps {
        container('maven') {
          nxWithGitHubStatus(context: 'compile') {
            echo """
            ----------------------------------------
            Compile
            ----------------------------------------"""
            echo "MAVEN_OPTS=$MAVEN_OPTS"
            sh 'mvn -B -nsu -T4C install -DskipTests'
          }
        }
      }
      post {
        always {
          archiveArtifacts artifacts: '**/target/*.jar, **/target/nuxeo-*-package-*.zip'
        }
      }
    }
    stage('Build Docker image') {
      steps {
        container('maven') {
          nxWithGitHubStatus(context: 'docker/build') {
            script {
              sh "mkdir -p ci/docker/target && cp ${NUXEO_API_PLAYGROUND_PACKAGE_PATH} ci/docker/target"
              def nuxeoVersion = sh(returnStdout: true,
                  script: 'mvn org.apache.maven.plugins:maven-help-plugin:3.3.0:evaluate -Dexpression=nuxeo.platform.version -q -DforceStdout')
              nxDocker.build(skaffoldFile: 'ci/docker/skaffold.yaml', envVars: ["NUXEO_VERSION=${nuxeoVersion}"])
            }
          }
        }
      }
    }
    stage('Git commit, tag and push') {
      when {
        expression { !nxUtils.isPullRequest() }
      }
      steps {
        container('maven') {
          script {
            echo """
            ----------------------------------------
            Git commit, tag and push
            ----------------------------------------
            """
            nxGit.commitTagPush()
          }
        }
      }
    }
    stage('Deploy Maven artifacts') {
      when {
        expression { !nxUtils.isPullRequest() }
      }
      steps {
        container('maven') {
          nxWithGitHubStatus(context: 'maven/deploy', message: 'Deploy Maven artifacts') {
            script {
              echo """
              ----------------------------------------
              Deploy Maven artifacts
              ----------------------------------------"""
              nxMvn.deploy()
            }
          }
        }
      }
    }
    stage('Deploy Nuxeo package') {
      when {
        expression { !nxUtils.isPullRequest() }
      }
      steps {
        container('maven') {
          nxWithGitHubStatus(context: 'package/deploy', message: 'Deploy Nuxeo packages') {
            script {
              echo """
              ----------------------------------------
              Upload Nuxeo Package to ${CONNECT_PREPROD_SITE_URL}
              ----------------------------------------"""
              nxUtils.postForm(credentialsId: 'connect-preprod', url: "${CONNECT_PREPROD_SITE_URL}marketplace/upload?batch=true",
                  form: ["package=@${NUXEO_API_PLAYGROUND_PACKAGE_PATH}"])
            }
          }
        }
      }
    }
    stage('Deploy Preview') {
      when {
        expression { nxUtils.isPullRequest() && pullRequest.labels.contains('preview') }
      }
      steps {
        container('maven') {
          nxWithGitHubStatus(context: 'preview', message: 'Deploy preview') {
            script {
              echo """
              ----------------------------------------
              Deploy preview environment
              ----------------------------------------"""
              // Kubernetes namespace, requires lower case alphanumeric characters
              def previewNamespace = "${CURRENT_NAMESPACE}-playground-${BRANCH_NAME}-preview".replaceAll('\\.', '-').toLowerCase()
              nxHelmfile.template(namespace: previewNamespace, environment: 'preview', outputDir: 'target')
              nxHelmfile.deploy(namespace: previewNamespace, environment: "preview",
                  secrets: [[name: CONNECT_CLID_SECRET, namespace: 'platform'], [name: 'platform-cluster-tls', namespace: 'platform']])
              def host = sh(returnStdout: true, script: """
                kubectl get ingress nuxeo \
                  --namespace=${previewNamespace} \
                  -ojsonpath='{.spec.rules[*].host}'
              """)
              def previewURL = "https://${host}"
              echo """
              -----------------------------------------------
              Preview available at: ${previewURL}
              -----------------------------------------------"""
              nxGitHub.commentPullRequest(
                  branch: CHANGE_BRANCH,
                  body: ":star: PR built and available in a preview environment **${previewNamespace}** [here](${previewURL})"
              )
            }
          }
        }
      }
      post {
        always {
          archiveArtifacts allowEmptyArchive: true, artifacts: '**/target/**/*.yaml'
        }
      }
    }
  }
  post {
    always {
      script {
        currentBuild.description = "Build ${VERSION}"
        nxJira.updateIssues()
      }
    }
  }
}
