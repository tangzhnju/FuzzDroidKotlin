package de.tu_darmstadt.sse.decisionmaker.analysis.stringtotypeextractor

import java.util.ArrayList
import java.util.HashMap

import soot.Scene
import soot.Unit
import soot.jimple.infoflow.Infoflow
import soot.jimple.infoflow.android.data.parsers.PermissionMethodParser
import soot.jimple.infoflow.android.source.AccessPathBasedSourceSinkManager
import soot.jimple.infoflow.data.pathBuilders.DefaultPathBuilderFactory
import soot.jimple.infoflow.data.pathBuilders.DefaultPathBuilderFactory.PathBuilder
import soot.jimple.infoflow.source.ISourceSinkManager
import soot.jimple.infoflow.taintWrappers.EasyTaintWrapper
import de.tu_darmstadt.sse.FrameworkOptions
import de.tu_darmstadt.sse.decisionmaker.analysis.AnalysisDecision
import de.tu_darmstadt.sse.decisionmaker.analysis.FuzzyAnalysis
import de.tu_darmstadt.sse.decisionmaker.server.ThreadTraceManager
import de.tu_darmstadt.sse.decisionmaker.server.TraceManager
import de.tu_darmstadt.sse.sharedclasses.networkconnection.DecisionRequest
import de.tu_darmstadt.sse.sharedclasses.networkconnection.ServerResponse


class StringToPrimitiveTypeFuzzer : FuzzyAnalysis() {

    private val valuesToFuzz = HashMap<Int, Set<Any>>()

    override fun doPreAnalysis(targetUnits: Set<Unit>, traceManager: TraceManager) {
        runDataflowAnalysis()
    }

    override fun resolveRequest(clientRequest: DecisionRequest, completeHistory: ThreadTraceManager): List<AnalysisDecision> {
        val decisions = ArrayList<AnalysisDecision>()
        var codePosID = clientRequest.codePosition
        //we have to add one to it
        codePosID += 1

        if (valuesToFuzz.keys.contains(codePosID)) {
            val values = valuesToFuzz[codePosID]
            if (values != null) {
                for (value in values) {
                    val response = ServerResponse()
                    response.analysisName = getAnalysisName()
                    response.setResponseExist(true)
                    response.returnValue = value
                    val finalDecision = AnalysisDecision()
                    finalDecision.analysisName = getAnalysisName()
                    finalDecision.decisionWeight = 10
                    finalDecision.serverResponse = response
                    decisions.add(finalDecision)
                }
            }
        }

        return decisions
    }

    override fun reset() {
        // TODO Auto-generated method stub

    }

    override fun getAnalysisName(): String {
        return "StringToPrimitiveInference"
    }

    private fun runDataflowAnalysis() {
        try {
            Scene.v().orMakeFastHierarchy

            val infoflow = InplaceInfoflow()
            infoflow.setPathBuilderFactory(DefaultPathBuilderFactory(
                    PathBuilder.ContextSensitive, true))
            infoflow.taintWrapper = EasyTaintWrapper(TAINT_WRAPPER_PATH)
            infoflow.config.enableExceptionTracking = false
            infoflow.config.enableArraySizeTainting = false
            //			infoflow.getConfig().setCallgraphAlgorithm(CallgraphAlgorithm.CHA);

            println("Running data flow analysis...")
            val pmp = PermissionMethodParser.fromFile(SOURCES_SINKS_FILE)
            val srcSinkManager = AccessPathBasedSourceSinkManager(pmp.sources, pmp.sinks)

            infoflow.addResultsAvailableHandler(StringToPrimitiveTypeExtractorDataflowHandler(valuesToFuzz))
            infoflow.runAnalysis(srcSinkManager)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }

    }


    private inner class InplaceInfoflow : Infoflow() {

        public override fun runAnalysis(sourcesSinks: ISourceSinkManager) {
            super.runAnalysis(sourcesSinks)
        }
    }

    companion object {
        private val TAINT_WRAPPER_PATH = FrameworkOptions.frameworkDir + "/src/de/tu_darmstadt/sse/decisionmaker/analysis/EasyTaintWrapperSource.txt"
        private val SOURCES_SINKS_FILE = FrameworkOptions.frameworkDir + "/src/de/tu_darmstadt/sse/decisionmaker/analysis/stringtotypeextractor/SourcesAndSinks.txt"
    }

}
