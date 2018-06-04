@Library('adesso') _

podTemplate(label: 'mypod', containers: [
        containerTemplate(name: 'docker', image: 'docker', ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'kubectl', image: 'lachlanevenson/k8s-kubectl:v1.8.0', command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'curl', image: 'khinkali/jenkinstemplate:0.0.3', command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'maven', image: 'maven:3.5.2-jdk-8', command: 'cat', ttyEnabled: true)
],
        volumes: [
                hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
        ]) {
    node('mypod') {
        properties([
                buildDiscarder(
                        logRotator(artifactDaysToKeepStr: '',
                                artifactNumToKeepStr: '',
                                daysToKeepStr: '',
                                numToKeepStr: '30'
                        )
                ),
                pipelineTriggers([])
        ])

        def version = 'not set'
        def repoUser = 'adessoSchweiz'
        def repoName = 'trunk-based-backend-kubernetes'
        def dockerUser = 'adesso'
        def dockerProject = 'trunk-based'

        stage('checkout & unit tests & build') {
            git url: "https://github.com/${repoUser}/${repoName}"
            junit allowEmptyResults: true, testResults: '**/target/surefire-reports/TEST-*.xml'
        }

        stage('build image & git tag & docker push') {
            version = semanticReleasing()
            currentBuild.displayName = env.VERSION
            wrap([$class: 'BuildUser']) {
                currentBuild.description = "Started by: ${BUILD_USER} (${BUILD_USER_EMAIL})"
            }

            container('maven') {
                sh "mvn versions:set -DnewVersion=${version}"
            }
            sh "git config user.email \"jenkins@adesso.ch\""
            sh "git config user.name \"Jenkins\""
            sh "git tag -a ${version} -m \"${version}\""
            withCredentials([usernamePassword(credentialsId: 'github', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
                sh "git push https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/${repoUser}/${repoName}.git --tags"
            }

            container('docker') {
                sh "docker build -t ${dockerUser}/${dockerProject}:${version} ."
                withCredentials([usernamePassword(credentialsId: 'dockerhub', passwordVariable: 'DOCKER_PASSWORD', usernameVariable: 'DOCKER_USERNAME')]) {
                    sh "docker login --username ${DOCKER_USERNAME} --password ${DOCKER_PASSWORD}"
                }
                sh "docker push ${dockerUser}/${dockerProject}:${version}"
            }
        }

        stage('deploy to test') {
            sh "sed -i -e 's/image: ${dockerUser}\\/${dockerProject}:todo/image: ${dockerUser}\\/${dockerProject}:${version}/' kubeconfig.yml"
            sh "sed -i -e 's/value: \"todo\"/value: \"${version}\"/' kubeconfig.yml"
            container('kubectl') {
                sh "kubectl apply -f kubeconfig.yml"
            }
            waitUntilReady('app=trunk-based', 'trunk-based')
        }

        stage('system tests') {
            withCredentials([usernamePassword(credentialsId: 'application', passwordVariable: 'APPLICATION_PASSWORD', usernameVariable: 'APPLICATION_USER_NAME')]) {
                container('maven') {
                    sh "mvn -s settings.xml clean integration-test failsafe:integration-test failsafe:verify"
                }
            }
            junit allowEmptyResults: true, testResults: '**/target/failsafe-reports/TEST-*.xml'
        }

        stage('last test') {
            dir('testing') {
                stage('Performance Tests') {
                    git url: 'https://github.com/khinkali/sink-testing'
                    container('maven') {
                        sh 'mvn clean gatling:integration-test'
                    }
                    archiveArtifacts artifacts: 'target/gatling/**/*.*', fingerprint: true
                    sh 'mkdir site'
                    sh 'cp -r target/gatling/healthsimulation*/* site'
                }

                stage('Build Report Image') {
                    container('docker') {
                        sh "docker build -t khinkali/sink-testing:${env.VERSION} ."
                        withCredentials([usernamePassword(credentialsId: 'dockerhub', passwordVariable: 'DOCKER_PASSWORD', usernameVariable: 'DOCKER_USERNAME')]) {
                            sh "docker login --username ${DOCKER_USERNAME} --password ${DOCKER_PASSWORD}"
                        }
                        sh "docker push khinkali/sink-testing:${env.VERSION}"
                    }
                }

                stage('Deploy Testing on Dev') {
                    sh "sed -i -e 's/image: khinkali\\/sink-testing:todo/image: khinkali\\/sink-testing:${env.VERSION}/' kubeconfig.yml"
                    sh "sed -i -e 's/value: \"todo\"/value: \"${env.VERSION}\"/' kubeconfig.yml"
                    container('kubectl') {
                        sh "kubectl apply -f kubeconfig.yml"
                    }
                }
            }
        }

        stage('deploy to prod') {
            try {
                def userInput = input(message: 'manuel user tests ok?', submitterParameter: 'submitter')
                currentBuild.description = "${currentBuild.description}\nGo for Prod by: ${userInput}"

                withCredentials([usernamePassword(credentialsId: 'github-api-token', passwordVariable: 'GITHUB_TOKEN', usernameVariable: 'GIT_USERNAME')]) {
                    container('curl') {
                        gitHubRelease(env.VERSION, 'khinkali', 'sink', GITHUB_TOKEN)
                    }
                }
                sh "sed -i -e 's/namespace: test/namespace: default/' startup.yml"
                sh "sed -i -e 's/nodePort: 31081/nodePort: 30081/' startup.yml"
                sh "sed -i -e 's/value: \"http:\\/\\/5.189.154.24:31190\\/auth\"/value: \"http:\\/\\/5.189.154.24:30190\\/auth\"/' startup.yml"
                container('kubectl') {
                    sh "kubectl apply -f startup.yml"
                }
                container('curl') {
                    checkVersion(env.VERSION, 'http://5.189.154.24:30081/sink/resources/health', 1, 5)
                }
            } catch (err) {
                def user = err.getCauses()[0].getUser()
                currentBuild.description = "${currentBuild.description}\nNoGo for Prod by: ${user}"
                currentBuild.result = 'ABORTED'
            }
        }
    }
}