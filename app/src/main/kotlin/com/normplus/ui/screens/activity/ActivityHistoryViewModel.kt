@file:Suppress("DEPRECATION") // pre-redesign screen: ui/legacy until its rebuild (#96)

package com.normplus.ui.screens.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.normplus.data.db.dao.HeartRateDao
import com.normplus.data.db.dao.SleepDao
import com.normplus.data.db.dao.SportDao
import com.normplus.data.db.dao.WorkoutDao
import com.normplus.domain.model.WorkoutSummary
import com.normplus.ui.legacy.SleepSegment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

enum class ActivityTab { STEPS, HEART_RATE, SLEEP, CALORIES }
enum class ChartRange(val days: Int) { WEEK(7), MONTH(30), THREE_MONTHS(90) }

data class ActivityUiState(
    val selectedTab: ActivityTab = ActivityTab.STEPS,
    val selectedRange: ChartRange = ChartRange.WEEK,
    val chartPoints: List<Pair<Long, Float>> = emptyList(),
    val sleepSegments: List<SleepSegment> = emptyList(),
    val workouts: List<WorkoutSummary> = emptyList(),
    val isLoading: Boolean = false,
)

@HiltViewModel
class ActivityHistoryViewModel @Inject constructor(
    private val sportDao: SportDao,
    private val heartRateDao: HeartRateDao,
    private val sleepDao: SleepDao,
    private val workoutDao: WorkoutDao,
) : ViewModel() {

    private val _state = MutableStateFlow(ActivityUiState())
    val state: StateFlow<ActivityUiState> = _state.asStateFlow()

    // Track the active data-load job so switching tabs cancels the previous one.
    private var dataJob: Job? = null

    init {
        loadData()
        loadWorkouts()
    }

    fun selectTab(tab: ActivityTab) {
        _state.update { it.copy(selectedTab = tab) }
        loadData()
    }

    fun selectRange(range: ChartRange) {
        _state.update { it.copy(selectedRange = range) }
        loadData()
    }

    private fun loadData() {
        // Cancel any in-flight query from a previous tab/range selection.
        dataJob?.cancel()
        dataJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, chartPoints = emptyList()) }
            val endEpoch = System.currentTimeMillis()
            val startEpoch = LocalDate.now()
                .minusDays(_state.value.selectedRange.days.toLong())
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            // Use first() — charts are a point-in-time snapshot; the ViewModel reloads
            // explicitly when the user changes tab or range. Avoids accumulating collectors.
            when (_state.value.selectedTab) {
                ActivityTab.STEPS -> {
                    val records = sportDao.queryByRange(startEpoch, endEpoch).first()
                    val pts = records.groupBy { dayKey(it.timestampEpoch) }
                        .map { (day, items) -> day to items.sumOf { it.steps }.toFloat() }
                        .sortedBy { it.first }
                    _state.update { it.copy(chartPoints = pts, isLoading = false) }
                }
                ActivityTab.CALORIES -> {
                    val records = sportDao.queryByRange(startEpoch, endEpoch).first()
                    val pts = records.groupBy { dayKey(it.timestampEpoch) }
                        .map { (day, items) -> day to items.sumOf { it.calories.toDouble() }.toFloat() }
                        .sortedBy { it.first }
                    _state.update { it.copy(chartPoints = pts, isLoading = false) }
                }
                ActivityTab.HEART_RATE -> {
                    val records = heartRateDao.queryByRange(startEpoch, endEpoch).first()
                    val pts = records.groupBy { dayKey(it.timestampEpoch) }
                        .map { (day, items) -> day to items.map { it.bpm }.average().toFloat() }
                        .sortedBy { it.first }
                    _state.update { it.copy(chartPoints = pts, isLoading = false) }
                }
                ActivityTab.SLEEP -> {
                    // Sleep sessions shown as segments below the chart — just clear loading.
                    sleepDao.querySessions(startEpoch, endEpoch).first()
                    _state.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    private fun loadWorkouts() {
        // Workouts list stays live — it correctly accumulates database updates.
        viewModelScope.launch {
            workoutDao.queryAll().collect { entities ->
                val summaries = entities.map { e ->
                    WorkoutSummary(
                        id = e.id,
                        startEpochMs = e.startEpoch,
                        durationSeconds = e.durationSeconds,
                        sportType = e.sportType,
                        steps = e.steps,
                        calories = e.calories,
                        distanceMeters = e.distanceMeters,
                        avgHeartRate = e.avgHeartRate,
                        hasGps = e.gpsPointsJson != null,
                    )
                }
                _state.update { it.copy(workouts = summaries) }
            }
        }
    }

    private fun dayKey(epochMs: Long): Long {
        val cal = java.util.Calendar.getInstance().also { it.timeInMillis = epochMs }
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
