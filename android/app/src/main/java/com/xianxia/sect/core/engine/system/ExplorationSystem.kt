package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.model.ExplorationTeam
import com.xianxia.sect.core.model.ExplorationStatus
import com.xianxia.sect.core.util.StateFlowListUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExplorationSystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "ExplorationSystem"
        const val SYSTEM_NAME = "ExplorationSystem"
    }
    
    private val _teams = MutableStateFlow<List<ExplorationTeam>>(emptyList())
    val teams: StateFlow<List<ExplorationTeam>> = _teams.asStateFlow()
    internal val mutableTeams: MutableStateFlow<List<ExplorationTeam>> get() = _teams
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "ExplorationSystem initialized")
    }
    
    override fun release() {
        Log.d(TAG, "ExplorationSystem released")
    }
    
    override fun clear() = StateFlowListUtils.clearList(_teams)
    
    fun loadTeams(teams: List<ExplorationTeam>) = StateFlowListUtils.setList(_teams, teams)
    
    fun getTeams(): List<ExplorationTeam> = _teams.value
    
    fun getTeamById(id: String): ExplorationTeam? = StateFlowListUtils.findItemById(_teams, id) { it.id }
    
    fun updateTeams(transform: (List<ExplorationTeam>) -> List<ExplorationTeam>) {
        _teams.value = transform(_teams.value)
    }
    
    fun updateTeam(teamId: String, transform: (ExplorationTeam) -> ExplorationTeam): Boolean =
        StateFlowListUtils.updateItemById(_teams, teamId, { it.id }, transform)
    
    fun addTeam(team: ExplorationTeam) = StateFlowListUtils.addItem(_teams, team)
    
    fun removeTeam(teamId: String): Boolean = 
        StateFlowListUtils.removeItemById(_teams, teamId, getId = { it.id })
    
    fun getActiveTeams(): List<ExplorationTeam> = 
        _teams.value.filter { it.status != ExplorationStatus.COMPLETED }
    
    fun getIdleTeams(): List<ExplorationTeam> = 
        _teams.value.filter { it.status == ExplorationStatus.COMPLETED }
    
    fun isDiscipleInTeam(discipleId: String): Boolean =
        _teams.value.any { team -> team.memberIds.contains(discipleId) }
}
