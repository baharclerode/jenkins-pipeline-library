def call(Closure closure) {
    // Project Config
    def buildEnvironmentImage = "docker.dragon.zone:10081/dragonzone/maven-build:master"
    def buildableBranchRegex = ".*" // ( PRs are in the form 'PR-\d+' )
    def deployableBranchRegex = "master"

    // Maven Config
    def mavenArgs = "-B -U -Dci=true"
    def mavenValidateProjectGoals = "clean initialize"
    def mavenNonDeployArgs = "-Psign"
    def mavenNonDeployGoals = "verify"
    def mavenDeployArgs = "-Psign,maven-central -DdeployAtEnd=true"
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

            configFileProvider([configFile(fileId: globalMavenSettingsConfig, variable: "MAVEN_SETTINGS")]) {
                def mvn = "mvn -X -s \\\"$MAVEN_SETTINGS\\\" \\\"-Dmaven.repo.local=$WORKSPACE/.m2\\\""

                /*
                 * Clone the repository and make sure that the pom.xml file is structurally valid and has a GAV
                 */
                stage("Checkout & Initialize Project") {
                    checkout scm
                    sh "$mvn ${mavenArgs} ${mavenValidateProjectGoals}"
                }

                // Get Git Information
                def gitSha1 = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                def gitAuthor = "${env.CHANGE_AUTHOR ? env.CHANGE_AUTHOR : sh(returnStdout: true, script: 'git log -1 --format="%aN" HEAD').trim()}"
                def gitAuthorEmail = "${env.CHANGE_AUTHOR_EMAIL ? env.CHANGE_AUTHOR_EMAIL : sh(returnStdout: true, script: 'git log -1 --format="%aE" HEAD').trim()}"
                sh "git config user.name '${gitAuthor}'"
                sh "git config user.email '${gitAuthorEmail}'"

                // Set Build Information
                def pom = readMavenPom(file: "pom.xml")
                def artifactId = pom.artifactId
                def versionWithBuild = pom.version.replace("-SNAPSHOT", ".${env.BUILD_NUMBER}")
                def version = "${versionWithBuild}-${gitSha1.take(6)}"
                def tag = "${artifactId}-${isDeployableBranch ? versionWithBuild : version}"
                currentBuild.displayName = "${artifactId}-${version}"
                currentBuild.description = gitAuthor

                /*
                 * Use the maven-release-plugin to verify that the pom is ready for release (no snapshots) and update the
                 * version. We don't push changes here, because we will push the tag after the build if it succeeds. We
                 * also set the preparationGoals to initialize so that we don't do a build here, just pom updates.
                 */
                stage("Validate Project") {
                    sh "$mvn ${mavenArgs} release:prepare -Dresume=false -DpushChanges=false -DpreparationGoals=initialize -Dtag=${tag} -DreleaseVersion=${version} -DdevelopmentVersion=${pom.version} '-Darguments=${mavenArgs}'"
                }

                // Actually build the project
                stage("Build Project") {
                    try {
                        withCredentials([string(credentialsId: 'gpg-signing-key-id', variable: 'GPG_KEYID'), file(credentialsId: 'gpg-signing-key', variable: 'GPG_SIGNING_KEY')]) {
                            sh 'gpg --allow-secret-key-import --import $GPG_SIGNING_KEY && echo "$GPG_KEYID:6:" | gpg --import-ownertrust'
                            sh "$mvn ${mavenArgs} release:perform -DlocalCheckout=true '-Dgoals=${isDeployableBranch ? mavenDeployGoals : mavenNonDeployGoals}' '-Darguments=${mavenArgs} ${isDeployableBranch ? mavenDeployArgs : mavenNonDeployArgs} -Dgpg.keyname=$GPG_KEYID'"
                        }
                        archiveArtifacts 'target/checkout/**/pom.xml'

                        if (isDeployableBranch) {
                            sshagent([scm.userRemoteConfigs[0].credentialsId]) {
                                sh 'mkdir ~/.ssh && echo StrictHostKeyChecking no > ~/.ssh/config'
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
                            sh "$mvn -f target/checkout/pom.xml ${mavenArgs} -P maven-central nexus-staging:deploy-staged"

                            input message: 'Publish to Central?', ok: 'Publish'

                            sh "$mvn -f target/checkout/pom.xml ${mavenArgs} -P maven-central nexus-staging:release"
                        } catch (err) {
                            sh "$mvn -f target/checkout/pom.xml ${mavenArgs} -P maven-central nexus-staging:drop"
                            throw err
                        }
                    }
                }
            }
        }
    }
}
