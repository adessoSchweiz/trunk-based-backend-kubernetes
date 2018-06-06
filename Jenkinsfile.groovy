@Library('adesso') _

podTemplate(label: 'mypod', containers: [
        containerTemplate(name: 'docker', image: 'docker', ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'kubectl', image: 'lachlanevenson/k8s-kubectl:v1.10.3', command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'curl', image: 'khinkali/jenkinstemplate:0.0.3', command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'maven', image: 'maven:3.5.2-jdk-8', command: 'cat', ttyEnabled: true)
],
        volumes: [
                hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
        ]) {

    def version
    def repoUser = 'adessoSchweiz'
    def repoName = 'trunk-based-backend-kubernetes'
    def dockerUser = 'adesso'
    def dockerProject = 'trunk-based'
    def repoNamePerformanceTests = 'performance-testing'
    def dockerProjectPerformanceTests = 'performance-testing'

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

        stage('checkout & unit tests & build') {
            git url: "https://github.com/${repoUser}/${repoName}"
            container('maven') {
                sh 'mvn clean package'
            }
            junit allowEmptyResults: true, testResults: '**/target/surefire-reports/TEST-*.xml'
        }

        stage('build image & git tag & docker push') {
            version = semanticReleasing()
            currentBuild.displayName = version
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
                sh "git push https://${GIT_USERNAME}:${GIT_PASSWORD.replaceAll('\\$', '\\\\\\\$')}@github.com/${repoUser}/${repoName}.git --tags"
            }

            container('docker') {
                sh "docker build -t ${dockerUser}/${dockerProject}:${version} ."
                withCredentials([usernamePassword(credentialsId: 'dockerhub', passwordVariable: 'DOCKER_PASSWORD', usernameVariable: 'DOCKER_USERNAME')]) {
                    sh "docker login --username ${DOCKER_USERNAME} --password ${DOCKER_PASSWORD.replaceAll('\\$', '\\\\\\\$')}"
                }
                sh "docker push ${dockerUser}/${dockerProject}:${version}"
            }
        }

        stage('deploy to test') {
            sh "sed -i -e 's/image: ${dockerUser}\\/${dockerProject}:todo/image: ${dockerUser}\\/${dockerProject}:${version}/' kubeconfig.yml"
            sh "sed -i -e 's/value: \"todo\"/value: \"${version}\"/' kubeconfig.yml"
            sh "sed -i -e 's/namespace: todo/namespace: test/' kubeconfig.yml"
            sh "sed -i -e 's/nodePort: todo/nodePort: 31000/' kubeconfig.yml"
            container('kubectl') {
                sh "kubectl apply -f kubeconfig.yml"
            }
            waitUntilReady('app=trunk-based', 'trunk-based', version, 'test')
        }

        stage('system tests') {
            container('maven') {
                sh "mvn clean integration-test failsafe:integration-test failsafe:verify"
            }
            junit allowEmptyResults: true, testResults: '**/target/failsafe-reports/TEST-*.xml'
        }

        dir('testing') {
            stage('Performance Tests') {
                git url: "https://github.com/${repoUser}/${repoNamePerformanceTests}"
                container('maven') {
                    sh 'mvn clean gatling:integration-test'
                }
                archiveArtifacts artifacts: 'target/gatling/**/*.*', fingerprint: true
                sh 'mkdir site'
                sh 'cp -r target/gatling/healthsimulation*/* site'
            }

            stage('Build Report Image') {
                container('docker') {
                    def image = "${dockerUser}/${dockerProjectPerformanceTests}:${version}"
                    sh "docker build -t ${image} ."
                    withCredentials([usernamePassword(credentialsId: 'dockerhub', passwordVariable: 'DOCKER_PASSWORD', usernameVariable: 'DOCKER_USERNAME')]) {
                        sh "docker login --username ${DOCKER_USERNAME} --password ${DOCKER_PASSWORD.replaceAll('\\$', '\\\\\\\$')}"
                    }
                    sh "docker push ${image}"
                }
            }

            stage('Deploy Testing on Dev') {
                sh "sed -i -e 's/image: ${dockerUser}\\/${dockerProjectPerformanceTests}:todo/image: ${dockerUser}\\/${dockerProjectPerformanceTests}:${version}/' kubeconfig.yml"
                sh "sed -i -e 's/value: \"todo\"/value: \"${version}\"/' kubeconfig.yml"
                sh "sed -i -e 's/namespace: todo/namespace: test/' kubeconfig.yml"
                sh "sed -i -e 's/nodePort: todo/nodePort: 31400/' kubeconfig.yml"
                container('kubectl') {
                    sh "kubectl apply -f kubeconfig.yml"
                }

                stash includes: 'kubeconfig.yml', name: 'kubeconfig'
            }
        }
    }

    stage('deploy to prod') {
        try {
            def userInput = input(message: 'manuel user tests ok?', submitterParameter: 'submitter')
            currentBuild.description = "${currentBuild.description}\nGo for Prod by: ${userInput}"


        } catch (err) {
            def user = err.getCauses()[0].getUser()
            currentBuild.description = "${currentBuild.description}\nNoGo for Prod by: ${user}"
            currentBuild.result = 'ABORTED'
        }
    }

    if (currentBuild.result != 'ABORTED') {
        node('mypod') {
            unstash 'kubeconfig'

            withCredentials([string(credentialsId: 'github-api-token', variable: 'GITHUB_TOKEN')]) {
                container('curl') {
                    gitHubRelease(version, repoUser, repoName, GITHUB_TOKEN)
                }
            }
            def kubeconfig = 'kubeconfig.yml'
            sh "sed -i -e 's/namespace: test/namespace: default/' ${kubeconfig}"
            sh "sed -i -e 's/nodePort: 31000/nodePort: 30000/' ${kubeconfig}"
            container('kubectl') {
                sh "kubectl apply -f ${kubeconfig}"
            }
            waitUntilReady('app=trunk-based', 'trunk-based', version, 'default')
        }
    }
}