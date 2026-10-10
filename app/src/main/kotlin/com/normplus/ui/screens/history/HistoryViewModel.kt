package com.normplus.ui.screens.history

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.normplus.data.preferences.WatchPreferences
import com.normplus.status.WatchStatusSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** What History shows. */
@Immutable
data class HistoryUiState(
    val today: LocalDate,
    val metric: HistoryMetric = HistoryMetric.Steps,
    val range: HistoryRange = HistoryRange.Week,
    /** The goal the metric is measured against: the step goal Norm+ keeps, for steps; none otherwise. */
    val goal: Int? = null,
    /** False until the first read is in: nothing is drawn rather than a flash of "empty". */
    val loaded: Boolean = false,
    /** No record of any kind in the database: nothing has been synced yet. */
    val nothingSynced: Boolean = false,
    /** The chart's days, oldest first, one per day of the range; null where no record came. */
    val chart: List<HistoryDay?> = emptyList(),
    /** The rows read so far, newest first: only days with a record of the metric. */
    val rows: List<HistoryDay> = emptyList(),
    /** Every row is read: there is nothing older. */
    val rowsComplete: Boolean = false,
    val loadingMore: Boolean = false,
    /** Reading the database failed; the screen offers Retry. */
    val failed: Boolean = false,
)

@HiltViewModel
class HistoryViewModel internal constructor(
    private val source: HistorySource,
    stepGoal: Flow<Int>,
    private val startSync: () -> Boolean,
    private val clock: () -> LocalDate,
) : ViewModel() {

    @Inject
    constructor(source: HistorySource, preferences: WatchPreferences, status: WatchStatusSource) :
        this(source, preferences.stepGoal, status::sync, LocalDate::now)

    private val metric = MutableStateFlow(HistoryMetric.Steps)
    private val range = MutableStateFlow(HistoryRange.Week)
    private val retries = MutableStateFlow(0)

    private val _state = MutableStateFlow(HistoryUiState(today = clock()))
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private var pageJob: Job? = null
    private var nextBefore: LocalDate? = null

    init {
        // Anything that changes what is on screen re-reads it: a sync adding records, a tab,
        // the goal, Retry. The range only moves the chart (below).
        viewModelScope.launch {
            combine(source.recordCounts, metric, stepGoal, retries) { counts, m, goal, _ -> Triple(counts, m, goal) }
                .collectLatest { (counts, m, goal) -> reload(counts, m, goal) }
        }
    }

    fun selectMetric(m: HistoryMetric) {
        metric.value = m
    }

    fun selectRange(r: HistoryRange) {
        if (range.value == r) return
        range.value = r
        _state.update { it.copy(range = r) }
        viewModelScope.launch { guarded { readChart(_state.value.metric, r, _state.value.today) } }
    }

    fun retry() {
        retries.update { it + 1 }
    }

    /** Starts a sync (the empty state's action); the shell's status shows its progress. */
    fun sync() {
        startSync()
    }

    /** The list has scrolled near its end: read the next page, once at a time. */
    fun loadMore() {
        val s = _state.value
        val before = nextBefore
        if (!s.loaded || s.rowsComplete || s.loadingMore || pageJob?.isActive == true || before == null) return
        _state.update { it.copy(loadingMore = true) }
        pageJob = viewModelScope.launch {
            guarded {
                val page = source.page(s.metric, before)
                nextBefore = page.nextBefore
                _state.update {
                    if (it.metric != s.metric) it
                    else it.copy(rows = it.rows + page.days, rowsComplete = page.nextBefore == null)
                }
            }
            _state.update { it.copy(loadingMore = false) }
        }
    }

    private suspend fun reload(counts: RecordCounts, m: HistoryMetric, stepGoal: Int) {
        pageJob?.cancel()
        val today = clock()
        val goal = if (m == HistoryMetric.Steps) stepGoal else null
        _state.update {
            val switched = it.metric != m
            it.copy(
                today = today, metric = m, goal = goal, nothingSynced = counts.isEmpty, failed = false, loadingMore = false,
                // Another metric's days are never shown under this one's tab, not even for a frame.
                loaded = it.loaded && !switched,
                chart = if (switched) emptyList() else it.chart,
                rows = if (switched) emptyList() else it.rows,
            )
        }
        guarded {
            readChart(m, _state.value.range, today)
            // The first page: the newest days, up to today.
            val first = source.page(m, today.plusDays(1))
            nextBefore = first.nextBefore
            _state.update { it.copy(rows = first.days, rowsComplete = first.nextBefore == null, loaded = true) }
        }
    }

    private suspend fun readChart(m: HistoryMetric, r: HistoryRange, today: LocalDate) {
        val days = source.days(m, r.firstDay(today), today)
        _state.update { if (it.metric == m && it.range == r) it.copy(chart = chartSlots(days, r, today)) else it }
    }

    /** A failed read is logged and shown as a typed state; it never takes the screen down. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "History: reading the records failed", e)
            _state.update { it.copy(failed = true, loaded = true, loadingMore = false) }
        }
    }

    private companion object {
        const val TAG = "History"
    }
}

/** History reads the database through [HistorySource], so its tests can hand it days instead. */
@Module
@InstallIn(ViewModelComponent::class)
internal abstract class HistoryModule {
    @Binds
    abstract fun bindSource(repository: HistoryRepository): HistorySource
}
