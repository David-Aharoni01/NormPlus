package com.normplus.ui.screens.daydetail

import com.normplus.data.db.dao.HeartRateDao
import com.normplus.data.db.dao.SleepDao
import com.normplus.data.db.dao.SportDao
import com.normplus.ui.screens.history.DayTotals
import com.normplus.ui.screens.history.RecordCounts
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import javax.inject.Inject

/** The records Day detail reads. What the ViewModel needs and nothing else. */
interface DayDetailSource {
    /** [date]'s records, made into its figures. */
    suspend fun day(date: LocalDate): DayRecords

    /** Whether any record (sport, heart rate, or a night's end) lies before [date]. */
    suspend fun hasEarlier(date: LocalDate): Boolean

    /** The number of records of each kind, as it changes: a sync that adds any re-reads the day. */
    val recordCounts: Flow<RecordCounts>
}

/**
 * [DayDetailSource] over the database, through History's queries (#100): the day's sport
 * records and heart-rate readings by their stamps, and the sessions that ended on it.
 */
class DayDetailRepository @Inject constructor(
    private val sportDao: SportDao,
    private val heartRateDao: HeartRateDao,
    private val sleepDao: SleepDao,
) : DayDetailSource {
    private val totals = DayTotals()
    private val builder = DayBuilder()

    override suspend fun day(date: LocalDate): DayRecords {
        val start = totals.startOf(date)
        val end = totals.startOf(date.plusDays(1))
        return builder.build(
            date = date,
            sport = sportDao.listInRange(start, end),
            heartRate = heartRateDao.listInRange(start, end),
            sessions = sleepDao.listSessionsEndingIn(start, end),
            stages = sleepDao.listStagesOfSessionsEndingIn(start, end),
        )
    }

    override suspend fun hasEarlier(date: LocalDate): Boolean {
        val cutoff = totals.startOf(date)
        return sportDao.latestBefore(cutoff) != null ||
            heartRateDao.latestBefore(cutoff) != null ||
            sleepDao.latestEndBefore(cutoff) != null
    }

    override val recordCounts: Flow<RecordCounts> = combine(
        sportDao.observeCount(),
        heartRateDao.observeCount(),
        sleepDao.observeCount(),
    ) { sport, hr, sleep -> RecordCounts(sport, hr, sleep) }.distinctUntilChanged()
}

/** Day detail reads the database through [DayDetailSource], so a test can hand it days instead. */
@Module
@InstallIn(ViewModelComponent::class)
internal abstract class DayDetailModule {
    @Binds
    abstract fun bindSource(repository: DayDetailRepository): DayDetailSource
}
