// This will only work when run against an Operations Center instance.

// WARNING
// This uses OperationsCenter classes to achieve the results desired, as well as sets default
// that are not standard. Any use of this script requires a full understanding of what it's doing
// and how it will affect your installation.
//
// The underlying class implementations may change in future versions and render this
// obsolete and ineffective.

// Usage: `java -jar jenkins-cli.jar -auth @/path/to/secret/token -s http://jenkins.example.com/cjoc/ groovy = test-master-name tmpl-a 001,002 < create-update-mm.groovy`
// Or, from REST API: https://support.cloudbees.com/hc/en-us/articles/217509228-Execute-Groovy-script-in-Jenkins-with-an-API-call

import com.cloudbees.masterprovisioning.kubernetes.KubernetesMasterProvisioning
import com.cloudbees.opscenter.server.casc.BundleStorage
import com.cloudbees.opscenter.server.model.ManagedMaster
import com.cloudbees.opscenter.server.model.OperationsCenter
import com.cloudbees.opscenter.server.properties.ConnectedMasterLicenseServerProperty
import hudson.ExtensionList
import jenkins.model.Jenkins
import org.apache.commons.io.FileUtils

if (args.length != 3) {
    println "args required: masterName, bundleTemplate, appNumbers(comma-separated, no spaces)"
}
args.each { println it }
String masterName = args[0]
String bundleTemplate = args[1]
String appNumbers = args[2]
String yamlToMerge = """kind: StatefulSet
spec:
  template:
    spec:
      containers:
      - name: jenkins
        volumeMounts:
        - name: mm-custom-groovy
          mountPath: /var/jenkins_config/configure-jenkins.groovy.d/
      volumes:
      - name: mm-custom-groovy
        configMap:
          defaultMode: 420
          name: mm-custom-groovy
"""


Map props = [
//    allowExternalAgents: false, //boolean
//    clusterEndpointId: "default", //String
//    cpus: 1.0, //Double
disk       : 5, //Integer //
//    domain: "test-custom-domain-1", //String
envVars    : "APP_NUMBERS=${appNumbers}", //String
//    fsGroup: "1000", //String
//    image: "custom-image-name", //String -- set this up in Operations Center Docker Image configuration
//    javaOptions: "${KubernetesMasterProvisioning.JAVA_OPTIONS} -Djenkins.install.runSetupWizard=false ", //String
//    jenkinsOptions:"", //String
//    kubernetesInternalDomain: "cluster.local", //String
//    livenessInitialDelaySeconds: 300, //Integer
//    livenessPeriodSeconds: 10, //Integer
//    livenessTimeoutSeconds: 10, //Integer
memory     : 4096, //Integer
//    namespace: null, //String
//    nodeSelectors: null, //String
//    ratio: 0.7, //Double
//    storageClassName: null, //String
//    systemProperties:"", //String
//    terminationGracePeriodSeconds: 1200, //Integer
yaml: yamlToMerge //String
]

//Either update or create the mm with this config
if (OperationsCenter.getInstance().getConnectedMasters().any { it?.getName() == masterName }) {
    updateMM(masterName, props, bundleTemplate)
} else {
    createMM(masterName, props, bundleTemplate)
}

private void createMM(String masterName, LinkedHashMap<String, Serializable> props, String bundleTemplate) {
    def configuration = new KubernetesMasterProvisioning()

    props.each { key, value ->
        configuration."$key" = value
    }

    ManagedMaster master = Jenkins.instance.createProject(ManagedMaster.class, masterName)

    println "Set config..."
    master.setConfiguration(configuration)
    master.properties.replace(new ConnectedMasterLicenseServerProperty(null))

    println "Save..."
    master.save()

    println "Run onModified..."
    master.onModified()

    createOrUpdateCascBundle(masterName, bundleTemplate)

    applyRbacAtMasterRoot(masterName)

    //ok, now we can actually boot this thing up
    println "Provision and start..."
    master.provisionAndStartAction();
}

private void updateMM(String masterName, LinkedHashMap<String, Serializable> props, String bundleTemplate) {
    println "Master with this name already exists. Updating it."
    ManagedMaster managedMaster = OperationsCenter.getInstance().getConnectedMasters().find { it.name == masterName } as ManagedMaster
    def configuration = managedMaster.configuration
    props.each { key, value ->
        configuration."$key" = value
    }
    managedMaster.configuration = configuration
    managedMaster.save()

    applyRbacAtMasterRoot(masterName)

    createOrUpdateCascBundle(masterName, bundleTemplate)
    managedMaster.restartAction(false)
    sleep(400)
}


//TODO: here is where you should apply rbac to the master, if needed, since we are currently within the context of the CJOC.
//      note that the reference arch this script intends that masters do not have admin access applied to them other
//      than what is inherited from cluster admins. All master-specific RBAC should be a part of what is  autoconfigured on
//      folders on the master
//
// this code works, just leave it commented until we're ready to use it
private void applyRbacAtMasterRoot(String masterName) {
//    def master = Jenkins.instance.getAllItems().find { it.name.equals(masterName) }
//
//    String internalGroupName = "j-GroupAlpha"
//    String externalGroupName = "GroupAlpha"
//    String roleName = "develop"
//
//    GroupContainer container = GroupContainerLocator.locate(master)
//    Group group = new Group(container, internalGroupName)
//    group.doAddMember(externalGroupName)
//    group.doGrantRole(roleName, 0, Boolean.TRUE)
//    container.addGroup(group)
}


//instantiate a copy of the bundle template with the master name
private void createOrUpdateCascBundle(String masterName, String bundleTemplate) {
    def destinationDir = new File("/var/jenkins_home/jcasc-bundles-store/${masterName}")
    def sourceDir = new File("/var/jenkins_config/bundle-templates/${bundleTemplate}")
    def bundleYamlFileHandle = new File("/var/jenkins_home/jcasc-bundles-store/${masterName}/bundle.yaml")
    int bundleVersion = 1

    if (destinationDir.exists()) {
        println "Bundle with this masterName already exists. Updating it..."
        bundleVersion = getCurrentBundleVersion(bundleYamlFileHandle) + 1

        createOrUpdateBundleDir(destinationDir, sourceDir)
        writeBundleYamlFile(masterName, bundleTemplate, bundleVersion, bundleYamlFileHandle)

        //ensure our changes on disk are pulled in
        ExtensionList.lookupSingleton(BundleStorage.class).initialize()
    } else {
        println "Bundle with this masterName does not exist. Creating it..."

        createEntryInSecurityFile(masterName)
        createOrUpdateBundleDir(destinationDir, sourceDir)
        writeBundleYamlFile(masterName, bundleTemplate, bundleVersion, bundleYamlFileHandle)

        ExtensionList.lookupSingleton(BundleStorage.class).initialize()
        BundleStorage.AccessControl accessControl = ExtensionList.lookupSingleton(BundleStorage.class).getAccessControl()
        accessControl.updateMasterPath(masterName, masterName)
        accessControl.regenerate(masterName)
    }
}

private void createOrUpdateBundleDir(File destinationDir, File sourceDir) {
    if(destinationDir.exists()) {
        FileUtils.forceDelete(destinationDir)
    }
    FileUtils.copyDirectory(sourceDir, destinationDir)
}

private void createEntryInSecurityFile(String masterName) {
    //create entry in security file; only the first time we create a bundle and never again. Hopefully this goes
    //away in future versions of CB CasC
    String newerEntry = """\n<entry>
      <string>${masterName}</string>
      <com.cloudbees.opscenter.server.casc.BundleStorage_-AccessControlEntry>
        <secret>{aGVyZWJlZHJhZ29ucwo=}</secret>
        <masterPath>${masterName}</masterPath>
      </com.cloudbees.opscenter.server.casc.BundleStorage_-AccessControlEntry>
    </entry>\n"""

    def cascSecFilePath = "/var/jenkins_home/core-casc-security.xml"
    def cascSecFile = new File(cascSecFilePath)
    String cascSecFileContents = cascSecFile.getText('UTF-8')

    if (cascSecFileContents.contains("<entries/>")) {
        cascSecFileContents = cascSecFileContents.replace("<entries/>", "<entries></entries>")
    } else {
        cascSecFileContents = cascSecFileContents.replace("<entries>", "<entries>${newerEntry}")
    }
    cascSecFile.write(cascSecFileContents)
}

private void writeBundleYamlFile(String masterName, String bundleTemplate, int bundleVersion, bundleYamlFileHandle) {
    bundleYamlFileHandle.write(
            """id: '${masterName}'
version: '${bundleVersion}'
apiVersion: '1'
description: 'copy of ${bundleTemplate} for ${masterName}'
plugins:
- 'plugins.yaml'
jcasc:
- 'jenkins.yaml'
catalog:
- 'plugin-catalog.yaml'
"""
    )
}

private int getCurrentBundleVersion(File bundleYamlFileHandle) {
    def versionLine = bundleYamlFileHandle.readLines()[1]
    String version = versionLine.replace("version:", "").replace(" ", "").replace("'","")
    return version as int
}