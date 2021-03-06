package de.tu_darmstadt.sse.apkspecific

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

import soot.jimple.infoflow.android.axml.AXmlAttribute
import soot.jimple.infoflow.android.axml.AXmlNode
import soot.jimple.infoflow.android.manifest.ProcessManifest
import de.tu_darmstadt.sse.FrameworkOptions
import de.tu_darmstadt.sse.appinstrumentation.UtilInstrumenter
import de.tu_darmstadt.sse.commandlinelogger.LoggerHelper
import de.tu_darmstadt.sse.commandlinelogger.MyLevel

object UtilApk {

    private val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    private var manifest: ProcessManifest? = null

    fun jarsigner(appName: String) {
        LoggerHelper.logInfo("Started jarsigner...")
        val command = arrayOfNulls<String>(14)

        command[0] = "jarsigner"
        command[1] = "-verbose"
        command[2] = "-sigalg"
        command[3] = "SHA1withRSA"
        command[4] = "-digestalg"
        command[5] = "SHA1"
        command[6] = "-keystore"
        command[7] = FrameworkOptions.KEYSTORE_PATH
        command[8] = UtilInstrumenter.SOOT_OUTPUT_APK
        command[9] = FrameworkOptions.KEYSTORE_NAME
        command[10] = "-storepass"
        command[11] = FrameworkOptions.KEYSTORE_PASSWORD
        command[12] = "-keypass"
        command[13] = FrameworkOptions.KEYSTORE_PASSWORD

        try {
            val p = Runtime.getRuntime().exec(command)

            val input = BufferedReader(InputStreamReader(p.inputStream))
            val line = input.readLine()
            while (line != null) {
                println(line)
            }

            input.close()

            p.waitFor()
        } catch (ex: Exception) {
            LoggerHelper.logEvent(MyLevel.EXCEPTION_ANALYSIS, ex.message!!)
            ex.printStackTrace()
            System.exit(1)
        }

        LoggerHelper.logInfo("Finished jarsigner...")
    }

    fun zipalign(appName: String) {
        LoggerHelper.logInfo("Started zipalign...")
        val command = arrayOfNulls<String>(5)

        var toolsPath = FrameworkOptions.BUILD_TOOLS
        if (!toolsPath.endsWith(File.separator))
            toolsPath += File.separator

        command[0] = toolsPath + "zipalign"
        command[1] = "-v"
        command[2] = "4"
        command[3] = UtilInstrumenter.SOOT_OUTPUT_APK
        command[4] = UtilInstrumenter.SOOT_OUTPUT_DEPLOYED_APK


        val p: Process
        try {
            p = Runtime.getRuntime().exec(command)

            val input = BufferedReader(InputStreamReader(p.inputStream))
            var line = input.readLine()
            while (line != null) {
                println(line)
                line = input.readLine()
            }

            input.close()

            p.waitFor()
        } catch (ex: Exception) {
            LoggerHelper.logEvent(MyLevel.EXCEPTION_ANALYSIS, ex.message!!)
            ex.printStackTrace()
            System.exit(1)
        }

        LoggerHelper.logInfo("Finished zipalign...")
    }


    fun manipulateAndroidManifest(androidManifest: ProcessManifest) {
        // process old manifest
        addHookinHelperAsApplicationIfNecessary(androidManifest)
        addInternetPermissionIfNecessary(androidManifest)
        addTracingService(androidManifest)
        addComponentCallerService(androidManifest)
        addMaxPrioForSMSReceiver(androidManifest)
        addPermissionIfNecessary("android.permission.READ_EXTERNAL_STORAGE", androidManifest)
        addPermissionIfNecessary("android.permission.WRITE_EXTERNAL_STORAGE", androidManifest)
        addPermissionIfNecessary("android.permission.WRITE_CONTACT", androidManifest)
    }


    private fun addPermissionIfNecessary(permission: String, androidManifest: ProcessManifest) {
        val allPermissions = androidManifest.permissions
        allPermissions
                .filter {
                    it == permission
                    //do nothing in case the sdcard-permission already exists
                }
                .forEach { return }

        androidManifest.addPermission(permission)
    }


    private fun addMaxPrioForSMSReceiver(manifest: ProcessManifest) {
        for (receiver in manifest.receivers) {
            receiver.children.filter { it.tag == "intent-filter" }.forEach { receiverChild ->
                //search for SMS receiver
                receiverChild.children
                        .filter { it.tag == "action" && it.hasAttribute("name") && (it.getAttribute("name").value as String).equals("android.provider.Telephony.SMS_RECEIVED", ignoreCase = true) }
                        .forEach {
                            //prepare the priority filter
                            if (receiverChild.hasAttribute("priority"))
                                (receiverChild.getAttribute("priority") as AXmlAttribute<Int>).setValue(Integer.MAX_VALUE)
                            else {
                                val attr = AXmlAttribute("priority", Integer.MAX_VALUE, ANDROID_NAMESPACE)
                                receiverChild.addAttribute(attr)
                            }
                        }
            }
        }
    }


    private fun addComponentCallerService(androidManifest: ProcessManifest) {
        val componentCallerService = AXmlNode("service", null, androidManifest.application)
        val nameAttribute = AXmlAttribute("name", UtilInstrumenter.COMPONENT_CALLER_SERVICE_HELPER, ANDROID_NAMESPACE)
        val exportedAttribute = AXmlAttribute("exported", "false", ANDROID_NAMESPACE)
        componentCallerService.addAttribute(nameAttribute)
        componentCallerService.addAttribute(exportedAttribute)

        androidManifest.addService(componentCallerService)
    }


    private fun addHookinHelperAsApplicationIfNecessary(androidManifest: ProcessManifest) {
        val application = androidManifest.application
        if (!application.hasAttribute("name")) {
            val nameAttribute = AXmlAttribute("name", UtilInstrumenter.HELPER_APPLICATION_FOR_HOOKING, ANDROID_NAMESPACE)
            application.addAttribute(nameAttribute)
        }
    }


    private fun addInternetPermissionIfNecessary(androidManifest: ProcessManifest) {
        val internetPerm = "android.permission.INTERNET"
        val allPermissions = androidManifest.permissions
        for (perm in allPermissions) {
            if (perm == internetPerm)
            //do nothing in case the internet-permission already exists
                return
        }

        androidManifest.addPermission(internetPerm)
    }


    private fun addTracingService(androidManifest: ProcessManifest) {
        val tracingService = AXmlNode("service", null, androidManifest.application)
        val nameAttribute = AXmlAttribute("name", UtilInstrumenter.HELPER_SERVICE_FOR_PATH_TRACKING, ANDROID_NAMESPACE)
        val exportedAttribute = AXmlAttribute("exported", "false", ANDROID_NAMESPACE)
        tracingService.addAttribute(nameAttribute)
        tracingService.addAttribute(exportedAttribute)

        androidManifest.addService(tracingService)
    }

    fun removeOldAPKs(appName: String) {
        val apkFile = File(UtilInstrumenter.SOOT_OUTPUT_APK)
        if (apkFile.exists()) apkFile.delete()
        val apkDeployedFile = File(UtilInstrumenter.SOOT_OUTPUT_DEPLOYED_APK)
        if (apkDeployedFile.exists()) apkDeployedFile.delete()

    }


    fun getManifest(): ProcessManifest? {
        if (manifest == null) {
            try {
                manifest = ProcessManifest(FrameworkOptions.apkPath)
            } catch (e: Exception) {
                LoggerHelper.logEvent(MyLevel.EXCEPTION_ANALYSIS, "There is a problem with the manifest: " + e.message)
                e.printStackTrace()
                System.exit(-1)
            }

        }
        return manifest
    }
}
