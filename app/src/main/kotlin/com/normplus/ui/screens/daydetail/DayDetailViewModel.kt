package com.normplus.ui.screens.daydetail

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.normplus.data.preferences.WatchPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** What Day detail shows. */
@Immutable
data class DayDetailUiState(
    /** The day on show; null until the route has said which. */
    val date: LocalDate? = null,
    val today: LocalDate,
    /** The step goal Norm+ keeps (#102), which every day is measured against, as on Today and in History. */
    val goal: Int = WatchPreferences.DEFAULT_STEP_GOAL,
    /** Distances in miles: the units setting is "IMPERIAL". */
    val imperial: Boolean = false,
    /** False until the day's first read is in: nothing is drawn rather than a flash of "no data". */
    val loaded: Boolean = false,
    /** The day's records; null before the read and after a failed one. */
    val day: DayRecords? = null,
    /** Some record lies before this day, so there is somewhere to go back to. */
    val hasEarlier: Boolean = false,
    /** Reading the database failed; the screen offers Retry. */
    val failed: Boolean = false,
) {
    val canGoPrevious: Boolean get() = date != null && hasEarlier
    val canGoNext: Boolean get() = date != null && canGoNext(date, today)
    val isToday: Boolean get() = date == today
}

@HiltViewModel
class DayDetailViewModel internal constructor(
    private val source: DayDetailSource,
    stepGoal: Flow<Int>,
    units: Flow<String>,
    private val clock: () -> LocalDate,
) : ViewModel() {

    @Inject
    constructor(source: DayDetailSource, preferences: WatchPreferences) :
        this(source, preferences.stepGoal, preferences.units, LocalDate::now)

    /** The day asked for. The screen moves to it once it has been read, so a day never shows half-drawn. */
    private val requested = MutableStateFlow<LocalDate?>(null)
    private val retries = MutableStateFlow(0)

    private val _state = MutableStateFlow(DayDetailUiState(today = clock()))
    val state: StateFlow<DayDetailUiState> = _state.asStateFlow()

    init {
        // A sync adding records, the goal, the units, a new day asked for, Retry: each re-reads.
        viewModelScope.launch {
            combine(requested.filterNotNull(), source.recordCounts, stepGoal, units, retries) { date, _, goal, u, _ ->
                Triple(date, goal, u == "IMPERIAL")
            }.collectLatest { (date, goal, imperial) -> read(date, goal, imperial) }
        }
    }

    /** The day the route opened. Only the first call counts: the screen keeps its day across a rotation. */
    fun open(date: LocalDate) {
        if (requested.value == null) requested.value = date
    }

    fun previous() {
        val s = _state.value
        if (s.canGoPrevious) requested.value = s.date!!.minusDays(1)
    }

    fun next() {
        val s = _state.value
        if (s.canGoNext) requested.value = s.date!!.plusDays(1)
    }

    fun retry() {
        retries.update { it + 1 }
    }

    private suspend fun read(date: LocalDate, goal: Int, imperial: Boolean) {
        val today = clock()
        try {
            val day = source.day(date)
            val earlier = source.hasEarlier(date)
            _state.value = DayDetailUiState(
                date = date, today = today, goal = goal, imperial = imperial,
                loaded = true, day = day, hasEarlier = earlier,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Logged and shown as a typed state; it never takes the screen down.
            Log.e(TAG, "Day detail: reading $date failed", e)
            _state.update {
                it.copy(date = date, today = today, goal = goal, imperial = imperial, loaded = true, day = null, failed = true)
            }
        }
    }

    private companion object {
        const val TAG = "DayDetail"
    }
}
