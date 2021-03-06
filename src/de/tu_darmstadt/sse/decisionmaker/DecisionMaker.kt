package de.tu_darmstadt.sse.decisionmaker

import de.tu_darmstadt.sse.EnvironmentResult
import de.tu_darmstadt.sse.FrameworkOptions
import de.tu_darmstadt.sse.FrameworkOptions.TraceConstructionMode
import de.tu_darmstadt.sse.apkspecific.CodeModel.CodePositionManager
import de.tu_darmstadt.sse.apkspecific.CodeModel.StaticCodeIndexer
import de.tu_darmstadt.sse.apkspecific.UtilApk
import de.tu_darmstadt.sse.bootstrap.AnalysisTaskManager
import de.tu_darmstadt.sse.bootstrap.DexFileManager
import de.tu_darmstadt.sse.commandlinelogger.LoggerHelper
import de.tu_darmstadt.sse.commandlinelogger.MyLevel
import de.tu_darmstadt.sse.decisionmaker.analysis.AnalysisDecision
import de.tu_darmstadt.sse.decisionmaker.analysis.filefuzzer.FileFuzzer
import de.tu_darmstadt.sse.decisionmaker.server.SocketServer
import de.tu_darmstadt.sse.decisionmaker.server.ThreadTraceManager
import de.tu_darmstadt.sse.decisionmaker.server.TraceManager
import de.tu_darmstadt.sse.decisionmaker.server.history.ClientHistory
import de.tu_darmstadt.sse.decisionmaker.server.history.GeneticCombination
import de.tu_darmstadt.sse.dynamiccfg.DynamicCallgraphBuilder
import de.tu_darmstadt.sse.dynamiccfg.utils.MapUtils
import de.tu_darmstadt.sse.frameworkevents.FrameworkEvent
import de.tu_darmstadt.sse.frameworkevents.manager.FrameworkEventManager
import de.tu_darmstadt.sse.sharedclasses.networkconnection.DecisionRequest
import de.tu_darmstadt.sse.sharedclasses.networkconnection.ServerResponse
import soot.jimple.infoflow.android.manifest.ProcessManifest
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.Map.Entry


class DecisionMaker(val config: DecisionMakerConfig, val dexFileManager: DexFileManager,
                    val analysisTaskManager: AnalysisTaskManager) {

    private var result: EnvironmentResult? = null

    private var manifest: ProcessManifest? = null

    private var socketServer: SocketServer? = null
    private var eventManager: FrameworkEventManager? = null
    var dynamicCallgraph: DynamicCallgraphBuilder? = null
        private set
    var codePositionManager: CodePositionManager? = null
        private set
    private var codeIndexer: StaticCodeIndexer? = null

    private val traceManager = TraceManager()

    private var logFileProgressName: String? = null

    private var geneticOnlyMode = false


    fun runPreAnalysisPhase() {
        logProgressMetricsInit()
        startAllPreAnalysis()
    }


    private fun logProgressMetricsInit() {
        val date = Date()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH-mm-ss")
        val logFileProgress: FileWriter
        try {
            logFileProgressName = "plot" + File.separator + "logProgress-" + dateFormat.format(date) + ".data"
            logFileProgress = FileWriter(logFileProgressName!!, true)
            config.metrics
                    .map { it.javaClass.name }
                    .forEach {
                        logFileProgress.write(it.substring(
                                it.lastIndexOf('.') + 1) + '\t')
                    }
            logFileProgress.write(System.getProperty("line.separator"))
            logFileProgress.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

    }


    private fun startAllPreAnalysis() {
        if (config.analyses.size == 0)
            throw RuntimeException("There should be at least one analysis registered!")
        for (analysis in config.analyses) {
            LoggerHelper.logEvent(MyLevel.PRE_ANALYSIS_START, analysis.getAnalysisName())
            analysis.doPreAnalysis(config.allTargetLocations!!, traceManager)
            LoggerHelper.logEvent(MyLevel.PRE_ANALYSIS_STOP, analysis.getAnalysisName())
        }
    }


    private fun computeResponse(request: DecisionRequest,
                                currentManager: ThreadTraceManager): ServerResponse {
        // If we already have a decision for that request in the current
        // history, we take it
        run {
            val response = currentManager.newestClientHistory?.getResponseForRequest(request)
            if (response != null && response.serverResponse!!.doesResponseExist()) {
                response.setDecisionUsed()
                LoggerHelper.logEvent(MyLevel.ANALYSIS_NAME, response.analysisName!!)
                return response.serverResponse!!
            }
        }

        // Compute the analyses for the current request
        val allDecisions = config.analyses
                .mapNotNull {
                    it.resolveRequest(
                            request, currentManager)
                }
                .flatMap { // We only add decisions that actually value values
                    it
                }
                .filter { it.serverResponse!!.doesResponseExist() }
                .toMutableList()

        // If we are in genetic-only mode and don't have a response in the
        // current trace, we try to get something from an older trace
        if (geneticOnlyMode && allDecisions.isEmpty()) {
            val decision = currentManager.getBestResponse(request)
            if (decision != null && decision.serverResponse!!.doesResponseExist())
                allDecisions.add(decision)
        }

        // If no analysis returned anything, but we asked, we create an empty response
        // so that we can at least keep track of the hook
        if (allDecisions.isEmpty()) {
            val resp = ServerResponse()
            resp.setResponseExist(false)
            resp.analysisName = "EMPTY_ANALYSIS"

            val decision = AnalysisDecision()
            decision.serverResponse = resp
            decision.analysisName = "EMPTY_ANALYSIS"
            allDecisions.add(decision)

            if (geneticOnlyMode)
                System.err.println("We're in genetic-only mode, but don't have a value for the " + "request. **playing sad music**")
        }

        // Apply penalties (if any) to the decisions
        for (decision in allDecisions) {
            val analysis = config.getAnalysisByName(decision.analysisName!!)
            if (analysis != null) {
                val penalty = analysis.penaltyRank
                if (penalty > 0) {
                    val newWeight = (decision.decisionWeight.toFloat() / (0.1 * penalty.toFloat() + 1.0f)).toFloat()
                    decision.decisionWeight = Math.round(newWeight)
                }
            }
        }

        // Get one of the decisions with the highest confidence
        val finalDecision = getFinalDecision(allDecisions)

        // If the analysis gave us lots of choices, we need to feed them into
        // the trace set to make them available to the genetic algorithm in
        // future runs
        val currentHistory = currentManager.newestClientHistory
        if (allDecisions.size > 1) {
            for (nonPickedDecision in allDecisions)
                if (nonPickedDecision !== finalDecision && nonPickedDecision.serverResponse!!.doesResponseExist()) {
                    val shadow = currentHistory!!.clone()
                    shadow.addDecisionRequestAndResponse(request, nonPickedDecision)
                    shadow.isShadowTrace = true
                    currentManager.addShadowHistory(shadow)
                }
        }

        // Check that we have a decision
        if (finalDecision == null)
            return ServerResponse.emptyResponse
        else
            finalDecision.setDecisionUsed()

        // Extract the server response to send back to the app and add it to the
        // current trace
        currentHistory?.addDecisionRequestAndResponse(request, finalDecision)

        // If we have a shadow that is a prefix of the decision we have taken anyway,
        // there is no need to keep the shadow around for further testing.
        var removedCount = 0
        val shadowIt = currentManager.shadowHistories.iterator()
        while (shadowIt.hasNext()) {
            val shadow = shadowIt.next()
            if (shadow.isPrefixOf(currentHistory!!)) {
                shadowIt.remove()
                removedCount++
            }
        }
        if (removedCount > 0)
            LoggerHelper.logInfo("Removed " + removedCount + " shadow histories, because they "
                    + "were prefixes of the decision we are trying now.")

        val serverResponse = finalDecision.serverResponse
        serverResponse!!.analysisName = finalDecision.analysisName
        return serverResponse
    }


    fun resolveRequest(request: DecisionRequest): ServerResponse? {
        println("Incoming decision request: " + request)

        // Get the current trace we're working on
        val currentManager = initializeHistory() ?: return ServerResponse.emptyResponse

        // If we need a decision at a certain statement, we have reached that statement
        currentManager.newestClientHistory!!.addCodePosition(request.codePosition,
                codePositionManager!!)

        // Make sure that we have updated the dynamic callgraph
        if (dynamicCallgraph != null)
            dynamicCallgraph!!.updateCFG()

        // Make sure that our metrics are up to date
        for (metric in config.metrics) {
            metric.update(currentManager.newestClientHistory!!)
        }

        // Compute the decision
        var response: ServerResponse? = computeResponse(request, currentManager)
        if (response == null)
            response = ServerResponse.emptyResponse

        //updating the Analysis Progress Metric
        //logging the new data to file
        val logFileProgress: FileWriter
        try {
            logFileProgress = FileWriter(logFileProgressName!!, true)
            for (metric in config.metrics) {
                val newlyCovered = metric.update(currentManager.newestClientHistory!!)
                println("Metric for " + metric.getMetricName() + ":" + newlyCovered)
                logFileProgress.write(Integer.toString(newlyCovered) + '\t')
            }
            logFileProgress.write(System.getProperty("line.separator"))
            logFileProgress.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        return response
    }


    private fun getFinalDecision(decisions: List<AnalysisDecision>): AnalysisDecision? {
        val finalDecisions = ArrayList<AnalysisDecision>()
        if (decisions.isEmpty())
            return null

        // Pick among those decisions with the highest confidence
        Collections.sort(decisions)
        val highestWeight = decisions[0].decisionWeight
        for (decision in decisions) {
            if (decision.decisionWeight == highestWeight)
                finalDecisions.add(decision)
            else if (DeterministicRandom.theRandom.nextInt(GENETIC_RANDOM_OFFSET) < GENETIC_RANDOM_OFFSET * GENETIC_PICK_BAD_DECISION_PROBABILITY)
                finalDecisions.add(decision)// with a certain (low) probability, we also pick a decision with lower
            // confidence
        }

        //random pick
        val amountOfDecisons = finalDecisions.size
        if (amountOfDecisons > 1) {
            val randomPick = DeterministicRandom.theRandom.nextInt(amountOfDecisons)
            return finalDecisions[randomPick]
        } else
            return finalDecisions[0]
    }

    fun initialize() {
        this.manifest = UtilApk.getManifest()

        // set up event manager
        eventManager = FrameworkEventManager.eventManager

        // Get a code model
        codePositionManager = CodePositionManager.codePositionManagerInstance
        codeIndexer = StaticCodeIndexer()

        //start server...
        socketServer = SocketServer.getInstance(this)
        val r1 = Runnable { socketServer!!.startSocketServerObjectTransfer() }
        val backgroundThreadForObjectTransfer = Thread(r1)
        backgroundThreadForObjectTransfer.start()

        // set up event manager
        eventManager = FrameworkEventManager.eventManager
        eventManager!!.connectToAndroidDevice()

        //monitor the logcat for VM crashes
        if (FrameworkOptions.enableLogcatViewer)
            eventManager!!.startLogcatCrashViewer()
    }


    private fun reset() {
        // Create a new result object
        result = EnvironmentResult()

        // Reset all analyses
        for (analysis in config.analyses)
            analysis.reset()
    }

    fun executeDecisionMaker(event: FrameworkEvent?): EnvironmentResult? {
        reset()
        var startingTime = System.currentTimeMillis()

        //client handling...
        if (!FrameworkOptions.testServer) {
            //pull files onto device
            eventManager!!.pushFiles(FileFuzzer.FUZZY_FILES_DIR)
            eventManager!!.installApp(manifest!!.packageName)

            //add contacts onto device
            eventManager!!.addContacts(manifest!!.packageName)

            tryStartingApp()
            try {
                Thread.sleep(2000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }

            if (event != null)
                eventManager!!.sendEvent(event)

            // Make sure that we don't have any old state lying around
            socketServer!!.resetForNewRun()

            // We only reset the genetic-only mode per app installation
            geneticOnlyMode = false

            var trying = true
            while (trying && !result!!.isTargetReached) {
                // Compute the time since the last client request
                val currentTime = System.currentTimeMillis()
                val timeDiff = currentTime - socketServer!!.lastRequestProcessed

                // we do a complete (clean) re-install of the app
                if (timeDiff > FrameworkOptions.inactivityTimeout * 1000 || currentTime - startingTime > FrameworkOptions.forceTimeout * 1000) {

                    if (result!!.restartCount < FrameworkOptions.maxRestarts || FrameworkOptions.maxRestarts == -1) {
                        LoggerHelper.logEvent(MyLevel.RESTART, String.format("Restarted app due to timeout: %d", result!!.restartCount + 1))
                        LoggerHelper.logEvent(MyLevel.RESTART, String.format("timeDiff: %d\ncurr - starting: %d", timeDiff, currentTime - startingTime))

                        eventManager!!.killAppProcess(manifest!!.packageName)
                        eventManager!!.uninstallAppProcess(manifest!!.packageName)

                        //wait a couple of seconds...
                        try {
                            Thread.sleep(5000)
                        } catch (e: InterruptedException) {
                            e.printStackTrace()
                        }

                        // Reset our internal state
                        dynamicCallgraph = null
                        result!!.restartCount = result!!.restartCount + 1
                        socketServer!!.notifyAppRunDone()

                        // We need to clean up the trace and remove all decision we haven't used
                        cleanUpUnusedDecisions()

                        // Check if one analysis performed poorly
                        penalizePoorAnalyses()

                        eventManager!!.installApp(manifest!!.packageName)
                        startingTime = System.currentTimeMillis()
                        tryStartingApp()

                        try {
                            Thread.sleep(3000)
                        } catch (e: InterruptedException) {
                            e.printStackTrace()
                        }

                        //send events
                        if (event != null)
                            eventManager!!.sendEvent(event)
                    } else {
                        LoggerHelper.logEvent(MyLevel.RUNTIME, "Maximum number of restarts reached -- giving up.")
                        trying = false
                    }
                }
            }

            // Wait for the next task to arrive. We only need this is the app
            // sends really large dex files
            //			try {
            //				Thread.sleep(60000);
            //			} catch (InterruptedException e) {
            //				// TODO Auto-generated catch block
            //				e.printStackTrace();
            //			}

            // Make sure to clean up after ourselves
            eventManager!!.killAppProcess(manifest!!.packageName)
            eventManager!!.uninstallAppProcess(manifest!!.packageName)
        } else {
            System.err.println("TESTING SERVER ONLY")
            while (true) {
            }
        }//test server
        return result
    }


    private fun cleanUpUnusedDecisions() {
        for (ttm in traceManager.allThreadTraceManagers) {
            ttm.newestClientHistory?.removeUnusedDecisions()
        }
    }


    private fun penalizePoorAnalyses() {
        var historyCount = 0
        val analysisToBestScore = HashMap<String, Int>()
        for (tm in traceManager.allThreadTraceManagers) {
            for (hist in tm.histories) {
                historyCount++
                val progressVal = hist.getProgressValue("ApproachLevel")
                for (pair in hist.allDecisionRequestsAndResponses) {
                    val name = pair.getSecond()!!.analysisName
                    val oldVal = analysisToBestScore[name]
                    if (oldVal == null || oldVal < progressVal)
                        analysisToBestScore.put(name!!, progressVal)
                }
            }
        }

        // We only judge analyses if have some data
        if (historyCount < PENALYZE_ANALYSES_MIN_HISTORY_COUNT || analysisToBestScore.size < 2)
            return

        // Check if we have an analysis that is  10 times worse than the next
        // better one
        val sortedMap = MapUtils.sortByValue(analysisToBestScore)
        var lastEntry: Entry<String, Int>? = null
        var penaltyRank = 1
        for (entry in sortedMap.entries) {
            // Skip the first entry
            if (lastEntry == null) {
                lastEntry = entry
                continue
            }

            // Is this entry 10 times worse than the previous one?
            if (entry.value * PENALYZE_ANALYSES_FACTOR < lastEntry.value) {
                val analysis = config.getAnalysisByName(entry.key)
                analysis.penaltyRank = penaltyRank++
            }

            lastEntry = entry
        }
    }

    fun tearDown() {
        socketServer!!.stop()
    }

    //
    //	private String getLaunchableActivity(ProcessManifest manifest) {
    //		Set<AXmlNode> allLaunchableActivities = manifest.getLaunchableActivities();
    //		if(allLaunchableActivities.size() == 0) {
    //			throw new RuntimeException("we do not support apps yet that do not have a launchable activitiy (e.g., just services)");
    //		}
    //		else if(allLaunchableActivities.size() > 1)
    //			LoggerHelper.logWarning("This app contains more than one activity that is launchable! Taking the first one which is defined in the manifest...");
    //		else {
    //			AXmlNode node = allLaunchableActivities.iterator().next();
    //			return (String)node.getAttribute("name").getValue();
    //		}
    //		return null;
    //	}


    fun getManagerForThreadId(threadId: Long): ThreadTraceManager {
        val manager = traceManager.getThreadTraceManager(threadId)

        return manager
    }


    @Synchronized fun initializeHistory(): ThreadTraceManager? {
        val manager = traceManager.getOrCreateThreadTraceManager(-1)

        // Only perform genetic recombination when actually generating new traces
        var forceGenetic : Boolean
        if (manager.histories.size < result!!.restartCount + 1) {
            // Are we in genetic-only mode?
            forceGenetic = geneticOnlyMode

            // If we did not get any new values in the last run, the analyses have
            // run out of values. In that case, we can only rely on genetic
            // recombination.
            if (!forceGenetic) {
                // We can only make this decision if we have already had one complete run
                if (manager.histories.size > 1 && manager.lastClientHistory!!.hasOnlyEmptyDecisions()) {
                    if (manager.historyAndShadowCount >= 2) {
                        forceGenetic = true
                        geneticOnlyMode = true
                        LoggerHelper.logEvent(MyLevel.GENTETIC_ONLY_MODE, "genetic only mode on")
                    } else {
                        System.err.println("It's all empty now, but we don't have enough histories " + "to combine. Looks like we're seriously out of luck.")
                        return null
                    }
                }
            }

            // If we have a couple of histories, we do genetic recombination
            if (!forceGenetic) {
                if (manager.histories.size > GENETIC_MIN_GENE_POOL_SIZE) {
                    if (DeterministicRandom.theRandom.nextInt(GENETIC_RANDOM_OFFSET) < GENETIC_GENE_POOL_EXTENSION_PROBABILITY * GENETIC_RANDOM_OFFSET) {
                        forceGenetic = true
                        LoggerHelper.logEvent(MyLevel.GENTETIC_ONLY_MODE, "genetic only mode on")
                    }
                }
            }

            // When we do genetic recombination, we pre-create a history object
            if (forceGenetic && FrameworkOptions.traceConstructionMode !== TraceConstructionMode.AnalysesOnly) {
                LoggerHelper.logInfo("Using genetic recombination for generating a trace...")

                // We also need to take the shadow histories into account. We take histories
                // from all threads in case we are not on the main thread
                val histories = HashSet<ClientHistory>()
                for (tmanager in traceManager.allThreadTraceManagers) {
                    histories.addAll(tmanager.histories)
                    histories.addAll(tmanager.shadowHistories)
                }

                // Do the genetic combination
                val combination = GeneticCombination()
                val combinedHistory = combination.combineGenetically(histories)
                if (combinedHistory == null) {
                    LoggerHelper.logWarning("Genetic recombination failed.")
                    return null
                }
                combinedHistory.isShadowTrace = false
                manager.ensureHistorySize(result!!.restartCount + 1, combinedHistory)

                // Create the dynamic callgraph
                this.dynamicCallgraph = DynamicCallgraphBuilder(
                        manager.newestClientHistory!!.callgraph!!,
                        codePositionManager!!,
                        codeIndexer!!)
                return manager
            } else if (manager.ensureHistorySize(result!!.restartCount + 1)) {
                // Check it
                if (geneticOnlyMode)
                    System.err.println("In genetic only mode, but didn't recombine anything. Life ain't good, man :(")

                // Create the new trace
                LoggerHelper.logInfo("Creating a new empty trace...")
                this.dynamicCallgraph = DynamicCallgraphBuilder(
                        manager.newestClientHistory!!.callgraph!!,
                        codePositionManager!!,
                        codeIndexer!!)
            }// If we actually created a new trace, we must re-initialize the
            // factories

            // We need a dynamic callgraph
            if (this.dynamicCallgraph == null)
                throw RuntimeException("This should never happen. There is no such exception. " + "It's all just an illusion. Move along.")
        }

        return manager
    }


    fun setTargetReached(targetReached: Boolean) {
        result!!.isTargetReached = targetReached
    }


    private fun tryStartingApp() {
        val hasLaunchableActivity = manifest!!.launchableActivities.size > 0
        val packageName = manifest!!.packageName
        if (hasLaunchableActivity) {
            eventManager!!.startApp(packageName)
        } else if (manifest!!.activities.size > 0) {
            val node = manifest!!.activities.iterator().next()
            val activityName = node.getAttribute("name").value as String

            eventManager!!.startActivity(packageName, activityName)
        } else if (manifest!!.services.size > 0) {
            val node = manifest!!.services.iterator().next()
            val serviceName = node.getAttribute("name").value as String

            eventManager!!.startService(packageName, serviceName)
        } else
            throw RuntimeException("we are not able to start the application")//if there is no launchable activity and no activity at all, we try calling the first service in manifest
        //if there is no launchable activity, we try calling the first activity in manifest
    }


    fun SIMPLE_START_APP_OR_START_APP_AND_INIT_EVENT_EVALUATION_CASE(event: FrameworkEvent?) {
        var startingTime = System.currentTimeMillis()
        // Create a new result object
        result = EnvironmentResult()
        //pull files onto device
        eventManager!!.installApp(manifest!!.packageName)
        tryStartingApp()
        try {
            Thread.sleep(2000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        if (event != null)
            eventManager!!.sendEvent(event)
        var trying = true
        while (trying && !result!!.isTargetReached) {
            val currentTime = System.currentTimeMillis()
            val timeDiff = currentTime - socketServer!!.lastRequestProcessed

            // we do a complete (clean) re-install of the app
            if (timeDiff > FrameworkOptions.inactivityTimeout * 1000 || currentTime - startingTime > FrameworkOptions.forceTimeout * 1000) {

                if (result!!.restartCount < FrameworkOptions.maxRestarts || FrameworkOptions.maxRestarts == -1) {
                    LoggerHelper.logEvent(MyLevel.RESTART, String.format("Restarted app due to timeout: %d", result!!.restartCount + 1))
                    LoggerHelper.logEvent(MyLevel.RESTART, String.format("timeDiff: %d\ncurr - starting: %d", timeDiff, currentTime - startingTime))

                    eventManager!!.killAppProcess(manifest!!.packageName)
                    eventManager!!.uninstallAppProcess(manifest!!.packageName)

                    //wait a couple of seconds...
                    try {
                        Thread.sleep(5000)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }


                    // Reset our internal state
                    dynamicCallgraph = null
                    geneticOnlyMode = false
                    result!!.restartCount = result!!.restartCount + 1
                    socketServer!!.notifyAppRunDone()

                    eventManager!!.installApp(manifest!!.packageName)
                    startingTime = System.currentTimeMillis()
                    tryStartingApp()

                    try {
                        Thread.sleep(3000)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }

                    //send events
                    if (event != null)
                        eventManager!!.sendEvent(event)
                } else {
                    LoggerHelper.logEvent(MyLevel.RUNTIME, "Maximum number of restarts reached -- giving up.")
                    trying = false
                }
            }
        }

        // Make sure to clean up after ourselves
        eventManager!!.killAppProcess(manifest!!.packageName)
        eventManager!!.uninstallAppProcess(manifest!!.packageName)
    }

    companion object {


        private val GENETIC_MIN_GENE_POOL_SIZE = 5

        private val GENETIC_RANDOM_OFFSET = 10000

        private val GENETIC_GENE_POOL_EXTENSION_PROBABILITY = 0.25f

        private val GENETIC_PICK_BAD_DECISION_PROBABILITY = 0.10f

        private val PENALYZE_ANALYSES_MIN_HISTORY_COUNT = 5

        private val PENALYZE_ANALYSES_FACTOR = 2.0f
    }

}
