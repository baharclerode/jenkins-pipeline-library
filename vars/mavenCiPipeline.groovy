def call(Closure closure) {
    // Project Config
    def buildEnvironmentImage = "docker.dragon.zone:10081/dragonzone/maven-build:master"
    def buildableBranchRegex = ".*" // ( PRs are in the form 'PR-\d+' )
    def deployableBranchRegex = "master"

    // Maven Config
    def mavenArgs = "-B -U -Dci=true"
    def mavenValidateProjectGoals = "clean initialize"
    def mavenNonDeployArgs = "-P sign"
    def mavenNonDeployGoals = "verify"
    def mavenDeployArgs = "-P sign,maven-central -DdeployAtEnd=true"
    def mavenDeployGoals = "deploy nexus-staging:deploy"
    def requireTests = false
    def globalMavenSettingsConfig = "maven-dragonZone"

    // Exit if we shouldn't be building
    if (!env.BRANCH_NAME.matches(buildableBranchRegex)) {
        echo "Branch ${env.BRANCH_NAME} is not buildable, aborting."
        return
    }

    // Pipeline Definition
    node("docker") {
        // Prepare the docker image to be used as a build environment
        def buildEnv = docker.image(buildEnvironmentImage)
        def isDeployableBranch = env.BRANCH_NAME.matches(deployableBranchRegex)

        stage("Prepare Build Environment") {
            buildEnv.pull()
        }

        buildEnv.inside {
            withMaven(globalMavenSettingsConfig: globalMavenSettingsConfig, mavenLocalRepo: '.m2') {
                /*
                 * Clone the repository and make sure that the pom.xml file is structurally valid and has a GAV
                 */
                stage("Checkout & Initialize Project") {
                    checkout scm
                    sh "mvn ${mavenArgs} ${mavenValidateProjectGoals} com.outbrain.swinfra:ci-friendly-flatten-maven-plugin:1.0.6:version"
                }

                // Get Git Information
                def gitSha1 = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                def gitAuthor = "${env.CHANGE_AUTHOR ? env.CHANGE_AUTHOR : sh(returnStdout: true, script: 'git log -1 --format="%aN" HEAD').trim()}"
                def gitAuthorEmail = "${env.CHANGE_AUTHOR_EMAIL ? env.CHANGE_AUTHOR_EMAIL : sh(returnStdout: true, script: 'git log -1 --format="%aE" HEAD').trim()}"
                sh "git config user.name '${gitAuthor}'"
                sh "git config user.email '${gitAuthorEmail}'"

                // Set Build Information
                def revision = readFile(file: "revision.txt")
                def pom = readMavenPom(file: "pom.xml")
                def artifactId = pom.artifactId
                def tag = "${artifactId}-${revision}"
                currentBuild.displayName = tag
                currentBuild.description = gitAuthor
                mavenArgs = "${mavenArgs} -Dsha1=${gitSha1} -Drevision=${revision}"

                // Build the project
                stage("Build Project") {
                    try {
                        withCredentials([string(credentialsId: 'gpg-signing-key-id', variable: 'GPG_KEYID'), file(credentialsId: 'gpg-signing-key', variable: 'GPG_SIGNING_KEY')]) {
                            sh 'gpg --allow-secret-key-import --import $GPG_SIGNING_KEY && echo "$GPG_KEYID:6:" | gpg --import-ownertrust'

                            sh "mvn ${mavenArgs} ${isDeployableBranch ? mavenDeployGoals : mavenNonDeployGoals} -Dgpg.keyname=$GPG_KEYID\""
                        }
                        archiveArtifacts 'target/checkout/**/pom.xml'

                        if (isDeployableBranch) {
                            sshagent([scm.userRemoteConfigs[0].credentialsId]) {
                                sh 'mkdir ~/.ssh && echo StrictHostKeyChecking no > ~/.ssh/config'
                                sh "git tag ${tag}"
                                sh "git push origin ${tag}"
                            }
                        }
                    } finally {
                        junit allowEmptyResults: !requireTests, testResults: "target/checkout/**/target/surefire-reports/TEST-*.xml"
                    }
                }
                if (isDeployableBranch) {
                    stage("Stage to Maven Central") {
                        try {
                            sh "mvn ${mavenArgs} -P maven-central nexus-staging:deploy-staged"

                            input message: 'Publish to Central?', ok: 'Publish'

                            sh "mvn ${mavenArgs} -P maven-central nexus-staging:release"
                        } catch (err) {
                            sh "mvn ${mavenArgs} -P maven-central nexus-staging:drop"
                            throw err
                        }
                    }
                }
            }
        }
    }
}