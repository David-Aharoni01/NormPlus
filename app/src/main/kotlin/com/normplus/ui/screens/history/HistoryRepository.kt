package com.normplus.ui.screens.history

import com.normplus.data.db.dao.HeartRateDao
import com.normplus.data.db.dao.SleepDao
import com.normplus.data.db.dao.SportDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import javax.inject.Inject

/** How many calendar days one page of rows reads. A page always holds at least one day with data. */
const val HISTORY_PAGE_DAYS = 31L

/** A page of rows: the days, newest first, and where the next page starts. */
data class HistoryPage(
    val days: List<HistoryDay>,
    /** The next page reads the days before this one; null when there is nothing older. */
    val nextBefore: LocalDate?,
)

/** The days History reads, for one metric at a time. What the ViewModel needs and nothing else. */
interface HistorySource {
    /** The metric's days from [from] to [to], both included, oldest first. */
    suspend fun days(metric: HistoryMetric, from: LocalDate, to: LocalDate): List<HistoryDay>

    /** The latest day before [before] that has any record of [metric]; null when there is none. */
    suspend fun latestDayBefore(metric: HistoryMetric, before: LocalDate): LocalDate?

    /** The number of records of each kind, as it changes: a sync that adds any re-reads the screen. */
    val recordCounts: Flow<RecordCounts>
}

data class RecordCounts(val sport: Int, val heartRate: Int, val sleep: Int) {
    val isEmpty: Boolean get() = sport == 0 && heartRate == 0 && sleep == 0
}

/**
 * History is years deep, so its rows are read a page at a time: each page jumps straight to
 * the latest day with data before the last page (skipping a gap of any length in one query)
 * and reads the [HISTORY_PAGE_DAYS] days ending there.
 */
suspend fun HistorySource.page(metric: HistoryMetric, before: LocalDate): HistoryPage {
    val latest = latestDayBefore(metric, before) ?: return HistoryPage(emptyList(), null)
    val from = latest.minusDays(HISTORY_PAGE_DAYS - 1)
    return HistoryPage(days(metric, from, latest).sortedByDescending { it.date }, from)
}

/** [HistorySource] over the database. */
class HistoryRepository @Inject constructor(
    private val sportDao: SportDao,
    private val heartRateDao: HeartRateDao,
    private val sleepDao: SleepDao,
) : HistorySource {
    private val totals = DayTotals()

    override suspend fun days(metric: HistoryMetric, from: LocalDate, to: LocalDate): List<HistoryDay> {
        val start = totals.startOf(from)
        val end = totals.startOf(to.plusDays(1))
        return when (metric) {
            HistoryMetric.Steps -> totals.steps(sportDao.listInRange(start, end))
            HistoryMetric.Calories -> totals.calories(sportDao.listInRange(start, end))
            HistoryMetric.HeartRate -> totals.heartRate(heartRateDao.listInRange(start, end))
            HistoryMetric.Sleep -> totals.sleep(
                sleepDao.listSessionsEndingIn(start, end),
                sleepDao.listStagesOfSessionsEndingIn(start, end),
            )
        }
    }

    override suspend fun latestDayBefore(metric: HistoryMetric, before: LocalDate): LocalDate? {
        val cutoff = totals.startOf(before)
        val latest = when (metric) {
            HistoryMetric.Steps, HistoryMetric.Calories -> sportDao.latestBefore(cutoff)
            HistoryMetric.HeartRate -> heartRateDao.latestBefore(cutoff)
            HistoryMetric.Sleep -> sleepDao.latestEndBefore(cutoff)
        }
        return latest?.let { totals.dayOf(it) }
    }

    override val recordCounts: Flow<RecordCounts> = combine(
        sportDao.observeCount(),
        heartRateDao.observeCount(),
        sleepDao.observeCount(),
    ) { sport, hr, sleep -> RecordCounts(sport, hr, sleep) }.distinctUntilChanged()
}
