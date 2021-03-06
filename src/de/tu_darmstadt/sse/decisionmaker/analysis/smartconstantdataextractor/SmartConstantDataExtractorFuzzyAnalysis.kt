package de.tu_darmstadt.sse.decisionmaker.analysis.smartconstantdataextractor

import com.google.common.collect.HashBasedTable
import com.google.common.collect.Table
import de.tu_darmstadt.sse.FrameworkOptions
import de.tu_darmstadt.sse.apkspecific.CodeModel.CodePositionManager
import de.tu_darmstadt.sse.commandlinelogger.LoggerHelper
import de.tu_darmstadt.sse.commandlinelogger.MyLevel
import de.tu_darmstadt.sse.decisionmaker.analysis.AnalysisDecision
import de.tu_darmstadt.sse.decisionmaker.analysis.FuzzyAnalysis
import de.tu_darmstadt.sse.decisionmaker.analysis.dynamicValues.*
import de.tu_darmstadt.sse.decisionmaker.analysis.symbolicexecution.SMTConverter
import de.tu_darmstadt.sse.decisionmaker.analysis.symbolicexecution.SMTExecutor
import de.tu_darmstadt.sse.decisionmaker.analysis.symbolicexecution.datastructure.SMTAssertStatement
import de.tu_darmstadt.sse.decisionmaker.analysis.symbolicexecution.datastructure.SMTConstantValue
import de.tu_darmstadt.sse.decisionmaker.analysis.symbolicexecution.datastructure.SMTProgram
import de.tu_darmstadt.sse.decisionmaker.analysis.symbolicexecution.datastructure.SMTSimpleAssignment
import de.tu_darmstadt.sse.decisionmaker.server.ThreadTraceManager
import de.tu_darmstadt.sse.decisionmaker.server.TraceManager
import de.tu_darmstadt.sse.decisionmaker.server.history.ClientHistory
import de.tu_darmstadt.sse.sharedclasses.networkconnection.DecisionRequest
import de.tu_darmstadt.sse.sharedclasses.networkconnection.ServerResponse
import de.tu_darmstadt.sse.sharedclasses.util.Pair
import org.apache.commons.codec.binary.Hex
import soot.Scene
import soot.Unit
import soot.jimple.ArrayRef
import soot.jimple.AssignStmt
import soot.jimple.IntConstant
import soot.jimple.Stmt
import soot.jimple.infoflow.Infoflow
import soot.jimple.infoflow.android.data.parsers.PermissionMethodParser
import soot.jimple.infoflow.android.source.AccessPathBasedSourceSinkManager
import soot.jimple.infoflow.data.pathBuilders.DefaultPathBuilderFactory
import soot.jimple.infoflow.data.pathBuilders.DefaultPathBuilderFactory.PathBuilder
import soot.jimple.infoflow.handlers.ResultsAvailableHandler
import soot.jimple.infoflow.results.InfoflowResults
import soot.jimple.infoflow.results.ResultSourceInfo
import soot.jimple.infoflow.solver.cfg.IInfoflowCFG
import soot.jimple.infoflow.source.ISourceSinkManager
import soot.jimple.infoflow.source.data.SourceSinkDefinition
import soot.jimple.infoflow.taintWrappers.EasyTaintWrapper
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.*


class SmartConstantDataExtractorFuzzyAnalysis : FuzzyAnalysis() {
    override fun getAnalysisName(): String {
        return "SmartConstantDataExtractorFuzzyAnalysis"
    }

    internal var codePositionManager: CodePositionManager? = CodePositionManager.codePositionManagerInstance
    internal var constantBasedValuesToFuzz: MutableMap<Int, MutableSet<Any>> = HashMap()
    internal var dynamicValueBasedValuesToFuzz: MutableMap<Int, MutableSet<Any>> = HashMap()


    internal var dataFlowsToSMTPrograms: MutableMap<DataFlowObject, MutableSet<SMTProgram>> = HashMap()

    private val dynamicValueInfos = HashMap<SMTProgram, Set<DynamicValueInformation>>()

    private val dynValuesOfRuns = HashSet<Set<DynamicValue>>()

    private val staticValuesSent = HashSet<Int>()


    private inner class InplaceInfoflow : Infoflow() {

        public override fun runAnalysis(sourcesSinks: ISourceSinkManager) {
            super.runAnalysis(sourcesSinks)
        }

    }

    private inner class FuzzerResultsAvailableHandler(private val sources: Set<SourceSinkDefinition>,
                                                      private val targetUnits: Set<Unit>) : ResultsAvailableHandler {

        override fun onResultsAvailable(cfg: IInfoflowCFG, results: InfoflowResults) {
            println("############################# RESULTS: " + results.results.keySet().size)
            val smtPreparation = SMTPreparationPhase(cfg, results)
            val preparedDataFlowsForSMT = smtPreparation.prepareDataFlowPathsForSMTConverter()

            //pre-run for split methods
            val splitInfos = HashBasedTable.create<Stmt, Int, MutableSet<String>>()
            for (dataFlow in preparedDataFlowsForSMT) {
                //pre-analysis especially for the split api call
                if (dataFlow.path[0].containsInvokeExpr()) {
                    val inv = dataFlow.path[0].invokeExpr
                    //special treatment in case of a dataflow starting with a split method
                    if (inv.method.signature == "<java.lang.String: java.lang.String[] split(java.lang.String)>") {

                        //we remove the split-API method from the source list
                        val iterator = (this.sources as MutableSet).iterator()
                        while (iterator.hasNext()) {
                            val source = iterator.next()
                            if (source.method.signature == "<java.lang.String: java.lang.String[] split(java.lang.String)>")
                                iterator.remove()
                        }

                        splitAPI_DataFlowtoSMTConvertion(dataFlow, cfg, preparedDataFlowsForSMT, splitInfos)
                    }
                }
            }

            //actual run:
            for (dataFlow in preparedDataFlowsForSMT) {
                if (dataFlow.path[0].containsInvokeExpr()) {
                    val inv = dataFlow.path[0].invokeExpr
                    //standard case
                    if (inv.method.signature != "<java.lang.String: java.lang.String[] split(java.lang.String)>") {
                        standardDataFlowToSMTConvertion(dataFlow, cfg, preparedDataFlowsForSMT, splitInfos)
                    }
                }
            }

        }


        private fun standardDataFlowToSMTConvertion(dataFlow: ResultSourceInfo, cfg: IInfoflowCFG, preparedDataFlowsForSMT: Set<ResultSourceInfo>, splitInfos: Table<Stmt, Int, MutableSet<String>>) {
            val converter = SMTConverter(sources)
            for (i in 0..dataFlow.path.size - 1) {
                println("\t" + dataFlow.path[i])
                println("\t\t" + dataFlow.pathAccessPaths[i])
            }

            converter.convertJimpleToSMT(dataFlow.path,
                    dataFlow.pathAccessPaths, targetUnits, cfg, splitInfos)

            dataFlowsToSMTPrograms.put(DataFlowObject(dataFlow.path), converter.smtPrograms!!)

            //dynamic value information
            dynamicValueInfos.putAll(converter.dynamicValueInfos)

            converter.printProgramToCmdLine()

            val z3str2Script = File(FrameworkOptions.Z3SCRIPT_LOCATION)
            if (!z3str2Script.exists())
                throw RuntimeException("There is no z3-script available")
            val smtExecutor = SMTExecutor(converter.smtPrograms!!, z3str2Script)
            val smtFiles = smtExecutor.createSMTFile()

            val values = HashSet<Any>()
            for (smtFile in smtFiles) {
                var loggingPointValue: String? = smtExecutor.executeZ3str2ScriptAndExtractLoggingPointValue(smtFile)
                if (loggingPointValue != null) {
                    loggingPointValue = fixSMTSolverIntegerOutput(loggingPointValue, dataFlow.path[0])

                    //SMT solver only returns hex-based UTF-8 values in some cases; we fixed this with our own hexToUnicode converter
                    if (loggingPointValue != null && loggingPointValue.contains("\\x"))
                        addAdditionalUnicodeValue(loggingPointValue, values)
                    if (loggingPointValue != null)
                        values.add(loggingPointValue)
                    println(String.format("Extracted loggingpoint-value: %s", loggingPointValue))
                }
            }

            println("####################################")

            //add values to fuzzy-seed
            val stmt = dataFlow.source
            val position = codePositionManager?.getCodePositionForUnit(stmt)
            if (constantBasedValuesToFuzz.containsKey(position!!.id))
                constantBasedValuesToFuzz[position.id]?.addAll(values)
            else
                constantBasedValuesToFuzz.put(position.id, values)
        }


        private fun splitAPI_DataFlowtoSMTConvertion(dataFlow: ResultSourceInfo, cfg: IInfoflowCFG, preparedDataFlowsForSMT: Set<ResultSourceInfo>, splitInfos: Table<Stmt, Int, MutableSet<String>>) {
            val converter = SMTConverter(sources)
            for (i in 0..dataFlow.path.size - 1) {
                println("\t" + dataFlow.path[i])
                println("\t\t" + dataFlow.pathAccessPaths[i])
            }

            //we remove the first statement (split-API method)
            val n = dataFlow.path.size - 1
            val reducedDataFlow = arrayOfNulls<Stmt>(n)
            System.arraycopy(dataFlow.path, 1, reducedDataFlow, 0, n)

            //currently only possible if there is a constant index for the array
            if (hasConstantIndexAtArrayForSplitDataFlow(reducedDataFlow as Array<Stmt>)) {
                val valueOfInterest = getValueOfInterestForSplitDataflow(reducedDataFlow)

                converter.convertJimpleToSMT(reducedDataFlow, dataFlow.pathAccessPaths, targetUnits, cfg, null)

                converter.printProgramToCmdLine()

                val z3str2Script = File(FrameworkOptions.Z3SCRIPT_LOCATION)
                if (!z3str2Script.exists())
                    throw RuntimeException("There is no z3-script available")
                val smtExecutor = SMTExecutor(converter.smtPrograms!!, z3str2Script)
                val smtFiles = smtExecutor.createSMTFile()

                for (smtFile in smtFiles) {
                    val loggingPointValue = smtExecutor.executeZ3str2ScriptAndExtractValue(smtFile, valueOfInterest)
                    if (loggingPointValue != null) {
                        val splitStmt = dataFlow.path[0]
                        val index = getConstantArrayIndexForSplitDataFlow(reducedDataFlow)

                        if (splitInfos.contains(splitStmt, index))
                            splitInfos.get(splitStmt, index).add(loggingPointValue)
                        else {
                            val values = HashSet<String>()
                            values.add(loggingPointValue)
                            splitInfos.put(splitStmt, index, values)
                        }
                    }
                    println(loggingPointValue)
                }

                println("####################################")
            }
        }
    }


    private fun getValueOfInterestForSplitDataflow(dataflow: Array<Stmt>): String {
        val firstAssign = dataflow[0]
        if (firstAssign is AssignStmt) {
            return firstAssign.leftOp.toString()
        } else
            throw RuntimeException("this should not happen - wrong assumption")
    }


    private fun hasConstantIndexAtArrayForSplitDataFlow(dataflow: Array<Stmt>): Boolean {
        val firstAssign = dataflow[0]
        if (firstAssign is AssignStmt) {
            val value = firstAssign.rightOp
            if (value is ArrayRef) {
                val index = value.index

                if (index is IntConstant)
                    return true
            }
        } else
            throw RuntimeException("this should not happen - wrong assumption")

        return false
    }

    private fun getConstantArrayIndexForSplitDataFlow(dataflow: Array<Stmt>): Int {
        val firstAssign = dataflow[0]
        if (firstAssign is AssignStmt) {
            val value = firstAssign.rightOp
            if (value is ArrayRef) {
                val index = value.index

                if (index is IntConstant)
                    return index.value
            }
        } else
            throw RuntimeException("this should not happen - wrong assumption")

        return -1
    }

    override fun doPreAnalysis(targetUnits: Set<Unit>, traceManager: TraceManager) {
        //necessary for update information once a new dynamic value is available
        //		traceManager.addThreadTraceCreateHandler(new ThreadTraceManagerCreatedHandler() {
        //
        //			@Override
        //			public void onThreadTraceManagerCreated(
        //					final ThreadTraceManager threadTraceManager) {
        //				threadTraceManager.addOnCreateHandler(new ClientHistoryCreatedHandler() {
        //
        //					@Override
        //					public void onClientHistoryCreated(ClientHistory history) {
        //						DynamicValueWorker worker = new DynamicValueWorker(threadTraceManager);
        //						history.getDynamicValues().addDynamicValueUpdateHandler(worker);
        //					}
        //				});
        //			}
        //		});

        runAnalysis(targetUnits)
    }

    override fun resolveRequest(clientRequest: DecisionRequest, completeHistory: ThreadTraceManager): List<AnalysisDecision> {
        val decisions = ArrayList<AnalysisDecision>()
        var codePosition = clientRequest.codePosition
        //Todo: why is there an 1 offset?
        codePosition += 1

        val history = completeHistory.lastClientHistory
        val dynValueCheckNecessary = areNewDynValuesOfHistory(history)

        //are there dynamic values available? static values are sent for the first request (so decision maker has all static values already)
        if (dynValueCheckNecessary && history != null) {
            dynValuesOfRuns.add(history.dynamicValues.getValues())
            val SMT_ADDITIONAL_COMPUTATION_TIME = 3 * 60
            val dynamicValues = history.dynamicValues
            FrameworkOptions.forceTimeout = FrameworkOptions.forceTimeout + SMT_ADDITIONAL_COMPUTATION_TIME
            //depending on the argPos or the baseObject, we need to create a new value
            val updateInfo = getSMTProgramUpdateInfos(dynamicValues)
            if (!updateInfo.isEmpty) {
                for (smtProgram in updateInfo.rowKeySet()) {
                    val allAssertCombinations = getAllAssertCombinations(updateInfo.row(smtProgram))

                    for (assertCombi in allAssertCombinations) {
                        var sourceOfDataflow: Stmt? = null
                        for (assertInfo in assertCombi) {
                            smtProgram.addAssertStatement(assertInfo.assertionStmt)
                            if (sourceOfDataflow == null)
                                sourceOfDataflow = assertInfo.sourceOfDataflow
                            if (sourceOfDataflow != null) {
                                if (sourceOfDataflow.toString() != assertInfo.sourceOfDataflow.toString())
                                    LoggerHelper.logWarning("sourceOfDataflow have to be the same all the time!")
                            }
                        }

                        val z3str2Script = File(FrameworkOptions.Z3SCRIPT_LOCATION)
                        if (!z3str2Script.exists())
                            throw RuntimeException("There is no z3-script available")
                        val smtExecutor = SMTExecutor(setOf(smtProgram), z3str2Script)
                        val smtFiles = smtExecutor.createSMTFile()

                        //we need to remove it. if there are more dynamic values available, we need to get the clean
                        //old program for the solver
                        for (assertInfo in assertCombi) {
                            smtProgram.removeAssertStatement(assertInfo.assertionStmt)
                        }

                        for (smtFile in smtFiles) {
                            val loggingPointValue = smtExecutor.executeZ3str2ScriptAndExtractLoggingPointValue(smtFile)
                            if (loggingPointValue != null) {
                                if (isSemanticallyCorrect(loggingPointValue, sourceOfDataflow!!)) {
                                    println(loggingPointValue)
                                    assertCombi
                                            .map {
                                                //add values to fuzzy-seed
                                                it.sourceOfDataflow
                                            }
                                            .map { codePositionManager?.getCodePositionForUnit(it) }
                                            .forEach {
                                                if (dynamicValueBasedValuesToFuzz.containsKey(it!!.id))
                                                    dynamicValueBasedValuesToFuzz[it.id]?.add(loggingPointValue)
                                                else {
                                                    val values = HashSet<Any>()
                                                    values.add(loggingPointValue)
                                                    dynamicValueBasedValuesToFuzz.put(it.id, values)
                                                }
                                            }

                                    //										//SMT solver only returns hex-based UTF-8 values in some cases; we fixed this with our own hexToUnicode converter
                                    //										if(loggingPointValue != null && loggingPointValue.contains("\\x"))
                                    //											addAdditionalUnicodeValue(loggingPointValue, values);
                                    //										if(loggingPointValue != null)
                                    //											values.add(loggingPointValue);
                                    //										System.out.println(String.format("Extracted NEW DYNAMIC-BASED loggingpoint-value: %s", loggingPointValue));

                                    assertCombi
                                            .map {
                                                //add values to fuzzy-seed
                                                it.sourceOfDataflow
                                            }
                                            .map { codePositionManager?.getCodePositionForUnit(it) }
                                            .forEach {
                                                if (dynamicValueBasedValuesToFuzz.containsKey(it!!.id))
                                                    dynamicValueBasedValuesToFuzz[it.id]?.add(loggingPointValue)
                                                else {
                                                    val values = HashSet<Any>()
                                                    values.add(loggingPointValue)
                                                    dynamicValueBasedValuesToFuzz.put(it.id, values)
                                                }
                                            }
                                }
                            }

                        }
                    }
                }
            }

            if (dynamicValueBasedValuesToFuzz.containsKey(codePosition)) {
                //we return all extracted values at once!
                val valueIt = dynamicValueBasedValuesToFuzz[codePosition]?.iterator()
                while (valueIt!!.hasNext()) {
                    val valueToFuzz = valueIt.next()
                    LoggerHelper.logEvent(MyLevel.SMT_SOLVER_VALUE, String.format("<---- dyn-values (first run) : " + valueToFuzz))
                    val sResponse = ServerResponse()
                    sResponse.analysisName = getAnalysisName()
                    sResponse.setResponseExist(true)
                    sResponse.returnValue = valueToFuzz
                    val finalDecision = AnalysisDecision()
                    finalDecision.analysisName = getAnalysisName()
                    finalDecision.decisionWeight = 12
                    finalDecision.serverResponse = sResponse
                    decisions.add(finalDecision)
                }
            }

            FrameworkOptions.forceTimeout = FrameworkOptions.forceTimeout - SMT_ADDITIONAL_COMPUTATION_TIME

        } else if (dynamicValueBasedValuesToFuzz.containsKey(codePosition)) {
            //we return all extracted values at once!
            val valueIt = dynamicValueBasedValuesToFuzz[codePosition]?.iterator()
            while (valueIt!!.hasNext()) {
                val valueToFuzz = valueIt.next()
                LoggerHelper.logEvent(MyLevel.SMT_SOLVER_VALUE, String.format("<---- dyn-values: " + valueToFuzz))
                val sResponse = ServerResponse()
                sResponse.setResponseExist(true)
                sResponse.analysisName = getAnalysisName()
                sResponse.returnValue = valueToFuzz
                val finalDecision = AnalysisDecision()
                finalDecision.analysisName = getAnalysisName()
                finalDecision.decisionWeight = 12
                finalDecision.serverResponse = sResponse
                decisions.add(finalDecision)
            }
        } else if (constantBasedValuesToFuzz.containsKey(codePosition) && !staticValuesAlreadySend(codePosition)) {
            staticValuesSent.add(codePosition)
            //we return all extracted values at once!
            val valueIt = constantBasedValuesToFuzz[codePosition]?.iterator()
            while (valueIt!!.hasNext()) {
                val valueToFuzz = valueIt.next()
                LoggerHelper.logEvent(MyLevel.SMT_SOLVER_VALUE, "<---- static-values: " + valueToFuzz)
                val sResponse = ServerResponse()
                sResponse.setResponseExist(true)
                sResponse.analysisName = getAnalysisName()
                sResponse.returnValue = valueToFuzz
                val finalDecision = AnalysisDecision()
                finalDecision.analysisName = getAnalysisName()
                finalDecision.decisionWeight = 8
                finalDecision.serverResponse = sResponse
                decisions.add(finalDecision)
            }
        }//second all constant-based values

        //no decision found
        if (decisions.isEmpty()) {
            val sResponse = ServerResponse()
            sResponse.setResponseExist(false)
            sResponse.analysisName = getAnalysisName()
            val noDecision = AnalysisDecision()
            noDecision.analysisName = getAnalysisName()
            noDecision.decisionWeight = 8
            noDecision.serverResponse = sResponse
            return listOf(noDecision)
        }

        return decisions
    }


    private fun areNewDynValuesOfHistory(history: ClientHistory?): Boolean {
        var dynValueCheckNecessary = true
        if (history != null) {
            val currValues = history.dynamicValues.getValues()

            for (values in dynValuesOfRuns) {
                for (value in values) {
                    if (!currValues.contains(value))
                        break
                }
                dynValueCheckNecessary = false
            }
        }
        return dynValueCheckNecessary
    }


    private fun staticValuesAlreadySend(codePosition: Int): Boolean {
        return staticValuesSent.contains(codePosition)
    }


    private fun addAdditionalUnicodeValue(loggingPointValue: String, values: MutableSet<Any>) {
        val delim = "#################################################################################################################"
        val allStrings = ArrayList<String>()
        val hexValues = ArrayList<String>()

        var currentHexValue = ""
        var currentNormalString : String = ""

        var i = 0
        while (i < loggingPointValue.length) {
            val c = loggingPointValue[i]
            if (c == '\\') {
                if (loggingPointValue[i + 1] == 'x') {
                    if (currentNormalString.isNotEmpty()) {
                        allStrings.add(currentNormalString)
                        currentNormalString = ""
                    }
                    i += 2
                    //look ahead
                    currentHexValue += loggingPointValue[i]
                    ++i
                    currentHexValue += loggingPointValue[i]
                } else {
                    if (currentHexValue.isNotEmpty()) {
                        hexValues.add(currentHexValue)
                        allStrings.add(delim)
                        currentHexValue = ""
                    }
                    currentNormalString += c

                }
            } else {
                if (currentHexValue.isNotEmpty()) {
                    hexValues.add(currentHexValue)
                    allStrings.add(delim)
                    currentHexValue = ""
                }
                currentNormalString += c
            }

            //last values
            if (i + 1 == loggingPointValue.length) {
                if (currentHexValue.isNotEmpty()) {
                    hexValues.add(currentHexValue)
                    allStrings.add(delim)
                    currentHexValue = ""
                }
                if (currentNormalString.isNotEmpty()) {
                    allStrings.add(currentNormalString)
                    currentNormalString = ""
                }
            }
            i++

        }


        for (hexValue in hexValues) {
            val tmp1: ByteArray
            var newValue: String?
            try {
                tmp1 = Hex.decodeHex(hexValue.toCharArray())
                newValue = tmp1.toString(StandardCharsets.UTF_8)
                val replaceIndex = allStrings.indexOf(delim)
                allStrings[replaceIndex] = newValue
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }

        var newValue = ""
        for (string in allStrings)
            newValue += string


        if (newValue != "") {
            values.add(newValue)
            println(String.format("Extracted loggingpoint-value: %s", newValue))
        }
    }


    private fun runAnalysis(targetUnits: Set<Unit>) {
        try {
            Scene.v().orMakeFastHierarchy

            val infoflow = InplaceInfoflow()
            //			InfoflowConfiguration.setAccessPathLength(2);
            infoflow.setPathBuilderFactory(DefaultPathBuilderFactory(
                    PathBuilder.ContextSensitive, true))
            infoflow.taintWrapper = EasyTaintWrapper(TAINT_WRAPPER_PATH)
            infoflow.config.enableExceptionTracking = false
            infoflow.config.enableArraySizeTainting = false
            //			infoflow.getConfig().setCallgraphAlgorithm(CallgraphAlgorithm.CHA);

            println("Running data flow analysis...")
            val pmp = PermissionMethodParser.fromFile(SOURCES_SINKS_FILE)
            val srcSinkManager = AccessPathBasedSourceSinkManager(pmp.sources, pmp.sinks)

            infoflow.addResultsAvailableHandler(FuzzerResultsAvailableHandler(pmp.sources,
                    targetUnits))
            infoflow.runAnalysis(srcSinkManager)
        } catch (ex: IOException) {
            throw RuntimeException("Could not read source/sink file", ex)
        }

    }

    override fun reset() {

    }


    private fun fixSMTSolverIntegerOutput(loggingPoint: String, stmt: Stmt): String {
        if (stmt.containsInvokeExpr()) {
            val inv = stmt.invokeExpr
            val metSig = inv.method.signature
            if (metSig == "<android.telephony.TelephonyManager: java.lang.String getSimOperator()>" || metSig == "<android.telephony.TelephonyManager: java.lang.String getNetworkOperator()>") {
                var newLoggingPoint = ""
                for (c in loggingPoint.toCharArray()) {
                    if (c < '0' || c > '9') {
                        val rand = Random()
                        val num = rand.nextInt(10)
                        newLoggingPoint += num
                    } else
                        newLoggingPoint += c
                }
                return newLoggingPoint
            }
        }
        return loggingPoint
    }


    private fun isSemanticallyCorrect(loggingPoint: String?, stmt: Stmt): Boolean {
        if (loggingPoint == null)
            return false
        if (stmt.containsInvokeExpr()) {
            val inv = stmt.invokeExpr
            val metSig = inv.method.signature
            if (metSig == "<android.telephony.TelephonyManager: java.lang.String getSimOperator()>" || metSig == "<android.telephony.TelephonyManager: java.lang.String getNetworkOperator()>") {
                return !loggingPoint.toCharArray().any { it < '0' || it > '9' }
            }
        }
        return true
    }


    private fun getSMTProgramUpdateInfos(dynamicValues: DynamicValueContainer): Table<SMTProgram, Stmt, MutableList<Pair<DynamicValueInformation, DynamicValue>>> {
        val updateInfoTable = HashBasedTable.create<SMTProgram, Stmt, MutableList<Pair<DynamicValueInformation, DynamicValue>>>()

        for (value in dynamicValues.getValues()) {
            val unit = codePositionManager?.getUnitForCodePosition(value.codePosition + 1)
            val paramIdx = value.paramIdx

            for ((key, value1) in dynamicValueInfos) {
                value1
                        .filter { it.statement == unit }
                        .forEach {
                            //base object
                            if (paramIdx == -1) {
                                if (it.isBaseObject) {
                                    if (!updateInfoTable.contains(key, it.statement))
                                        updateInfoTable.put(key, it.statement, ArrayList<Pair<DynamicValueInformation, DynamicValue>>())
                                    updateInfoTable.get(key, it.statement).add(Pair(it, value))

                                }
                            } else {
                                if (it.argPos == paramIdx) {
                                    if (!updateInfoTable.contains(key, it.statement))
                                        updateInfoTable.put(key, it.statement, ArrayList<Pair<DynamicValueInformation, DynamicValue>>())
                                    updateInfoTable.get(key, it.statement).add(Pair(it, value))
                                }
                            }//method arguments
                        }
            }
        }

        return updateInfoTable
    }


    private fun getAllAssertCombinations(map: Map<Stmt, List<Pair<DynamicValueInformation, DynamicValue>>>): Set<Set<SMTUpdateInfo>> {
        val allAssertions = HashSet<Set<SMTUpdateInfo>>()
        val currentPos = IntArray(map.keys.size)
        val keys = ArrayList(map.keys)
        val maxSize = keys.map { map[it]!!.size }

        val allPermutations = ArrayList<IntArray>()
        generateAllPermutations(maxSize, currentPos, currentPos.size - 1, allPermutations)


        for (combinations in allPermutations) {
            val currentAssertions = HashSet<SMTUpdateInfo>()
            for (i in combinations.indices) {
                val stmt = keys[i]
                val valueInfo = map[stmt]!![combinations[i]]

                var assignment: SMTSimpleAssignment? = null
                val dynValue = valueInfo.getSecond()
                val bindingToUpdate = valueInfo.getFirst()!!.binding
                if (dynValue is DynamicStringValue) {
                    val stringValue = dynValue.stringValue
                    assignment = SMTSimpleAssignment(bindingToUpdate, SMTConstantValue(stringValue))
                } else if (dynValue is DynamicIntValue) {
                    val intValue = dynValue.intValue
                    assignment = SMTSimpleAssignment(bindingToUpdate, SMTConstantValue(intValue))
                }

                val assignAssert = SMTAssertStatement(assignment!!)
                currentAssertions.add(SMTUpdateInfo(assignAssert, stmt, valueInfo.getFirst()!!.sourceOfDataflow!!))
            }
            allAssertions.add(currentAssertions)
        }

        return allAssertions
    }


    fun generateAllPermutations(maxSize: List<Int>, currArray: IntArray, currIndex: Int, allPermutations: MutableList<IntArray>) {
        var currIndex = currIndex
        if (currIndex == -1)
            return
        val startPos = currArray.size - 1
        var currValue = currArray[startPos]
        if (currValue + 1 < maxSize[startPos]) {
            currArray[startPos] = currArray[startPos] + 1
            allPermutations.add(currArray.clone())
        } else {
            currValue = currArray[currIndex]
            //increment index
            if (currValue + 1 < maxSize[currIndex]) {
                currArray[currIndex] = currArray[currIndex] + 1
                for (i in currIndex + 1..currArray.size - 1)
                    currArray[i] = 0
                allPermutations.add(currArray.clone())
            } else {
                //find next index to update
                while (currIndex >= 0) {
                    currValue = currArray[currIndex]
                    if (currValue + 1 < maxSize[currIndex])
                        break
                    currIndex--
                }
                if (currIndex == -1)
                    return
                currArray[currIndex] = currArray[currIndex] + 1
                for (i in currIndex + 1..currArray.size - 1)
                    currArray[i] = 0
                currIndex = currArray.size - 1
                allPermutations.add(currArray.clone())
            }
        }

        generateAllPermutations(maxSize, currArray, currIndex, allPermutations)

    }

    companion object {

        private val TAINT_WRAPPER_PATH = FrameworkOptions.frameworkDir + "/src/de/tu_darmstadt/sse/decisionmaker/analysis/EasyTaintWrapperSource.txt"
        private val SOURCES_SINKS_FILE = FrameworkOptions.frameworkDir + "/src/de/tu_darmstadt/sse/decisionmaker/analysis/smartconstantdataextractor/SourcesAndSinks.txt"
    }


    //	public class DynamicValueWorker implements DynamicValueUpdateHandler {
    //
    //		private final ThreadTraceManager traceManager;
    //
    //		public DynamicValueWorker(ThreadTraceManager manager) {
    //			this.traceManager = manager;
    //		}
    //
    //		@Override
    //		public void onDynamicValueAvailable(DynamicValue dynValue, int lastExecutedStatement) {
    //			int paramIdx = dynValue.getParamIdx();
    //			//depending on the argPos or the baseObject, we need to create a new value
    //			 Map<SMTProgram, Set<DynamicValueInformation>> updateInfo = getSMTProgramUpdateInfos(lastExecutedStatement, paramIdx);
    //			//we need a new SMT run for extracting a more precise value
    //			if(!updateInfo.isEmpty()) {
    //				for(Map.Entry<SMTProgram, Set<DynamicValueInformation>> entry : updateInfo.entrySet()) {
    //					for(DynamicValueInformation valueInfo : entry.getValue()) {
    //						SMTBinding bindingToUpdate = valueInfo.getBinding();
    //						SMTProgram smtProg = entry.getKey();
    //
    //						SMTSimpleAssignment assignment = null;
    //						if(dynValue instanceof DynamicStringValue) {
    //							String stringValue = ((DynamicStringValue)dynValue).getStringValue();
    //							assignment = new SMTSimpleAssignment(bindingToUpdate, new SMTConstantValue<String>(stringValue));
    //							System.out.println("+++++++: " + lastExecutedStatement + ": " + stringValue);
    //						}
    //						else if(dynValue instanceof DynamicIntValue) {
    //							int intValue = ((DynamicIntValue)dynValue).getIntValue();
    //							assignment = new SMTSimpleAssignment(bindingToUpdate, new SMTConstantValue<Integer>(intValue));
    //							System.out.println("+++++++: " + lastExecutedStatement + ": " + intValue);
    //						}
    //
    //						SMTAssertStatement assignAssert = new SMTAssertStatement(assignment);
    //						smtProg.addAssertStatement(assignAssert);
    //
    //
    //						File z3str2Script = new File(FrameworkOptions.Z3SCRIPT_LOCATION);
    //						if(!z3str2Script.exists())
    //							throw new RuntimeException("There is no z3-script available");
    //						SMTExecutor smtExecutor = new SMTExecutor(Collections.singleton(smtProg), z3str2Script);
    //						Set<File> smtFiles = smtExecutor.createSMTFile();
    //
    //						//we need to remove it. if there are more dynamic values available, we need to get the clean
    //						//old program for the solver
    //						smtProg.removeAssertStatement(assignAssert);
    //
    //						Set<Object> values = new HashSet<Object>();
    //						for(File smtFile : smtFiles) {
    //							String loggingPointValue = smtExecutor.executeZ3str2ScriptAndExtractLoggingPointValue(smtFile);
    //
    //							if(isSemanticallyCorrect(loggingPointValue, valueInfo.getSourceOfDataflow())) {
    //								//SMT solver only returns hex-based UTF-8 values in some cases; we fixed this with our own hexToUnicode converter
    //								if(loggingPointValue != null && loggingPointValue.contains("\\x"))
    //									addAdditionalUnicodeValue(loggingPointValue, values);
    //								if(loggingPointValue != null)
    //									values.add(loggingPointValue);
    //								System.out.println(String.format("Extracted NEW DYNAMIC-BASED loggingpoint-value: %s", loggingPointValue));
    //							}
    //						}
    //
    //						//add values to fuzzy-seed
    //						Stmt stmt = valueInfo.getSourceOfDataflow();
    //						CodePosition position = codePositionManager.getCodePositionForUnit(stmt);
    //						if(dynamicValueBasedValuesToFuzz.containsKey(position.getID()))
    //							dynamicValueBasedValuesToFuzz.get(position.getID()).addAll(values);
    //						else
    //							dynamicValueBasedValuesToFuzz.put(position.getID(), values);
    //					}
    //				}
    //			}
    //
    //		}
    //	}
}
