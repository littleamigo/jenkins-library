import com.sap.piper.ConfigurationHelper
import com.sap.piper.DownloadCacheUtils
import com.sap.piper.GenerateStageDocumentation
import com.sap.piper.ReportAggregator
import com.sap.piper.Utils
import groovy.transform.Field

import static groovy.json.JsonOutput.toJson

import static com.sap.piper.Prerequisites.checkScript

@Field String STEP_NAME = getClass().getName()

@Field Set GENERAL_CONFIG_KEYS = []
@Field Set STAGE_STEP_KEYS = [
    /** Parameters for deployment to a Nexus Repository Manager. */
    'nexus',
        /**
         * Version of Nexus. Can be nexus2 or nexus3.
         * @parentConfigKey nexus
         * @default `nexus3`
         * @possibleValues `nexus2`, `nexus3`
         */
        'version',
        /**
         * URL of the Nexus. The scheme part of the URL will not be considered,
         * because only http is supported.
         * @parentConfigKey nexus
         */
        'url',
        /**
         * Name of the Nexus repository.
         * @parentConfigKey nexus
         */
        'repository',
        /**
         * List of additional classifiers that should be deployed to Nexus.
         * Each item is a map of a type and a classifier name.
         * @parentConfigKey nexus
         */
        'additionalClassifiers',
        /**
         * ID to the credentials which is used to connect to Nexus.
         * Anonymous deployments do not require a credentialsId.
         * @parentConfigKey nexus
         * @possibleValues Jenkins credential id
         */
        'credentialsId',
]
@Field Set STEP_CONFIG_KEYS = GENERAL_CONFIG_KEYS.plus(STAGE_STEP_KEYS)
@Field Set PARAMETER_KEYS = STEP_CONFIG_KEYS.plus([
    /** Artifact ID of the main build artifact. */
    'artifactId',
])

/**
 * This stage is responsible for releasing/deploying artifacts to a Nexus Repository Manager.<br />
 */
@GenerateStageDocumentation(defaultStageName = 'artifactDeployment')
void call(Map parameters = [:]) {
    String stageName = 'artifactDeployment'
    final script = checkScript(this, parameters) ?: this
    def utils = parameters.juStabUtils ?: new Utils()

    Map config = ConfigurationHelper.newInstance(this)
        .loadStepDefaults()
        .mixinGeneralConfig(script.commonPipelineEnvironment, GENERAL_CONFIG_KEYS)
        .mixinStageConfig(script.commonPipelineEnvironment, stageName, STEP_CONFIG_KEYS)
        .mixin(parameters, PARAMETER_KEYS)
        .withMandatoryProperty('nexus')
        .use()

    piperStageWrapper(stageName: stageName, script: script) {

        def commonPipelineEnvironment = script.commonPipelineEnvironment
        List unstableSteps = commonPipelineEnvironment?.getValue('unstableSteps') ?: []
        if (unstableSteps) {
            piperPipelineStageConfirm script: script
            unstableSteps = []
            commonPipelineEnvironment.setValue('unstableSteps', unstableSteps)
        }

        // telemetry reporting
        utils.pushToSWA([step: STEP_NAME], config)

        Map nexusConfig = config.nexus as Map

        // Add all mandatory parameters
        Map nexusUploadParams = [
            script: script,
            version: nexusConfig.version,
            repository: nexusConfig.repository,
            url: nexusConfig.url,
        ]

        nexusUploadParams = DownloadCacheUtils.injectDownloadCacheInMavenParameters(script as Script, nexusUploadParams)

        // Set artifactId if provided via parameters, fall-back to artifactId from CPE if set
        if (nexusConfig.artifactId) {
            nexusUploadParams.artifactId = nexusConfig.artifactId
        } else if (script.commonPipelineEnvironment.configuration.artifactId) {
            nexusUploadParams.artifactId = script.commonPipelineEnvironment.configuration.artifactId
        }
        // Replace 'additionalClassifiers' List with JSON encoded String
        if (nexusConfig.additionalClassifiers) {
            nexusUploadParams.additionalClassifiers = "${toJson(nexusConfig.additionalClassifiers as List)}"
        }

        nexusUploadParams.verbose = true

        // The withEnv wrapper can be removed before merging to master.
        withEnv(['REPOSITORY_UNDER_TEST=SAP/jenkins-library','LIBRARY_VERSION_UNDER_TEST=stage-artifact-deployment']) {
            nexusUpload(nexusUploadParams)
        }

        ReportAggregator.instance.reportDeploymentToNexus()
    }
}