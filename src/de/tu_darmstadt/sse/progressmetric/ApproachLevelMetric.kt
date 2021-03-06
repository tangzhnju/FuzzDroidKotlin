package de.tu_darmstadt.sse.progressmetric

import com.google.common.collect.HashBasedTable
import de.tu_darmstadt.sse.appinstrumentation.UtilInstrumenter
import de.tu_darmstadt.sse.decisionmaker.server.history.ClientHistory
import de.tu_darmstadt.sse.sharedclasses.util.Pair
import soot.Unit
import soot.jimple.infoflow.solver.cfg.InfoflowCFG
import java.util.*


class ApproachLevelMetric(private val targetUnits: Collection<Unit>, private val cfg: InfoflowCFG) : IProgressMetric {

    private val targetBasedDistancemap = HashBasedTable.create<Unit, Unit, Int>()


    private var bestSoFar: Int? = Integer.MAX_VALUE


    private var currentTargetLocation: Unit? = null

    private inner class ApproachLevelItem(val currentUnit: Unit, val approachLevel: Int)

    override fun initialize() {
        if (currentTargetLocation == null)
            throw RuntimeException("we have to have a target!")
        //we have to do this for every target unit
        for (singleTarget in targetUnits) {
            //			DefaultHashMap<Unit, Integer> distanceMap = new DefaultHashMap<Unit, Integer>(Integer.MAX_VALUE);

            //check if this method is reachable; otherwise we can not create an inter-procedurarl CFG
            if (!cfg.isReachable(singleTarget))
                continue

            val reachedUnits = HashSet<Unit>()
            val worklist = LinkedList<ApproachLevelItem>()

            worklist.add(ApproachLevelItem(singleTarget, Integer.MAX_VALUE))

            while (!worklist.isEmpty()) {
                // get front element
                val curItem = worklist.removeAt(0)
                val currentUnit = curItem.currentUnit
                var currentMetricValue = curItem.approachLevel

                // Do not in circles
                if (!reachedUnits.add(currentUnit)) {
                    continue
                }

                // If we already have a better approach level from another path, do not
                // continue here
                if (targetBasedDistancemap.contains(singleTarget, currentUnit)) {
                    val oldMetricValue = targetBasedDistancemap.get(singleTarget, currentUnit)
                    if (oldMetricValue > currentMetricValue)
                        continue
                }

                // We decrease the approach level for every statement that we travel farther
                // away from the target location
                currentMetricValue--
                targetBasedDistancemap.put(singleTarget, currentUnit, currentMetricValue)

                //in case we reached the start of the method (vice verse in backward analysis)
                if (cfg.isStartPoint(currentUnit)) {
                    val sm = cfg.getMethodOf(currentUnit)
                    val callers = cfg.getCallersOf(sm)
                    callers
                            .flatMap { cfg.getPredsOf(it) }
                            .mapTo(worklist) { ApproachLevelItem(it, currentMetricValue) }
                }
                if (cfg.isExitStmt(currentUnit)) {
                    val sm = cfg.getMethodOf(currentUnit)
                    //first: get all callers
                    val callers = cfg.getCallersOf(sm)
                    for (caller in callers) {
                        for (retSite in cfg.getReturnSitesOfCallAt(caller)) {
                            //second: add distance info to all callers
                            targetBasedDistancemap.put(singleTarget, retSite, currentMetricValue)
                            //third get the predecessors (aka succs of cfg) of the callers and add them to the worklist
                            worklist.add(ApproachLevelItem(retSite, currentMetricValue))
                        }
                    }
                    //there is no need for further progress
                    continue
                } else if (cfg.isCallStmt(currentUnit)) {
                    for (callee in cfg.getCalleesOfCallAt(currentUnit)) {
                        val clazzOfInvoke = callee.declaringClass

                        if (UtilInstrumenter.isAppDeveloperCode(clazzOfInvoke)) {
                            //get all return statements
                            val returnStmts = cfg.getStartPointsOf(callee)
                            returnStmts
                                    .filter {
                                        //We have to do this, since SPARK has a well known issue with
                                        //iterators for instance where the correct building of a call graph is NOT possible.
                                        //changing to CHA would solve the issue, but would blow up the call graph
                                        cfg.getMethodOf(it) != null
                                    }
                                    .mapTo(worklist) { ApproachLevelItem(it, currentMetricValue) }
                            continue
                        }
                    }
                }//in case of a non-api call

                val nextUnits = cfg.getPredsOf(currentUnit)
                nextUnits.mapTo(worklist) { ApproachLevelItem(it, currentMetricValue) }
            }
        }
    }

    private fun getBestApproachLevel(path: Collection<Unit>): Pair<Unit, Int> {
        val retval = Pair<Unit, Int>(null, Integer.MAX_VALUE)
        for (unit in path) {
            val distance = targetBasedDistancemap.get(currentTargetLocation, unit)
            //in case we are not able to extract the the distance information, we take the old one
            if (distance == null) {
                //				LoggerHelper.logWarning("not able to extract the distance information for: " + unit);
                retval.setFirst(unit)
                retval.setSecond(retval.getSecond()!!)
            } else if ((distance as Int) < (retval.getSecond() as Int)) {
                retval.setFirst(unit)
                retval.setSecond(distance)
            }

        }
        return retval
    }


    override fun update(history: ClientHistory): Int {
        val value = getBestApproachLevel(history.codePostions).getSecond()
        bestSoFar = java.lang.Math.min(bestSoFar!!, value as Int)
        // Set progress value
        history.setProgressValue(getMetricIdentifier(), value)
        return value
    }

    override fun getMetricName(): String {
        return "ApproachLevel"
    }

    override fun getMetricIdentifier(): String {
        return "ApproachLevel"
    }

    override fun setCurrentTargetLocation(currentTargetLocation: Unit) {
        this.currentTargetLocation = currentTargetLocation
    }
}
