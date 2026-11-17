package com.mylife.planner

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

fun formatInr(amount: Double): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    formatter.maximumFractionDigits = if (amount % 1.0 == 0.0) 0 else 2
    return formatter.format(amount)
}

enum class TaskFilter(val label: String) {
    ALL("All"), TODAY("Today"), UPCOMING("Upcoming"), COMPLETED("Completed")
}

data class TasksUiState(
    val tasks: List<TaskEntity> = emptyList(),
    val selectedFilter: TaskFilter = TaskFilter.ALL,
    val selectedCategory: TaskCategory? = null
)

class TasksViewModel(private val repository: PlannerRepository) : ViewModel() {
    private val selectedFilter = MutableStateFlow(TaskFilter.ALL)
    private val selectedCategory = MutableStateFlow<TaskCategory?>(null)

    val uiState: StateFlow<TasksUiState> = combine(
        repository.observeAllTasks(), selectedFilter, selectedCategory
    ) { allTasks, filter, category ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val startOfTomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val filtered = allTasks.filter { task ->
            val matchesFilter = when (filter) {
                TaskFilter.ALL -> true
                TaskFilter.TODAY -> !task.isCompleted && task.dueDate != null &&
                    task.dueDate >= startOfToday && task.dueDate < startOfTomorrow
                TaskFilter.UPCOMING -> !task.isCompleted && task.dueDate != null &&
                    task.dueDate >= startOfTomorrow
                TaskFilter.COMPLETED -> task.isCompleted
            }
            matchesFilter && (category == null || task.category == category)
        }
        TasksUiState(filtered, filter, category)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState())

    fun setFilter(filter: TaskFilter) { selectedFilter.value = filter }
    fun setCategoryFilter(category: TaskCategory?) {
        selectedCategory.value = if (selectedCategory.value == category) null else category
    }

    fun saveTask(
        existingTask: TaskEntity?, title: String, description: String?,
        dueDate: Long?, priority: Priority, category: TaskCategory
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return
        val cleanDesc = description?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            if (existingTask == null) {
                repository.saveTask(TaskEntity(title = cleanTitle, description = cleanDesc, dueDate = dueDate, priority = priority, category = category))
            } else {
                repository.updateTask(existingTask.copy(title = cleanTitle, description = cleanDesc, dueDate = dueDate, priority = priority, category = category))
            }
        }
    }

    fun toggleTaskCompleted(task: TaskEntity) {
        viewModelScope.launch { repository.toggleTaskCompleted(task.id, !task.isCompleted) }
    }

    fun deleteTask(task: TaskEntity) { viewModelScope.launch { repository.deleteTask(task) } }
    fun restoreTask(task: TaskEntity) { viewModelScope.launch { repository.saveTask(task) } }

    companion object {
        fun provideFactory(repository: PlannerRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TasksViewModel(repository) as T
        }
    }
}

data class CalendarUiState(
    val currentMonth: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val daysWithTasks: Set<LocalDate> = emptySet(),
    val daysWithEvents: Set<LocalDate> = emptySet(),
    val selectedDayTasks: List<TaskEntity> = emptyList(),
    val selectedDayEvents: List<EventEntity> = emptyList()
)

class CalendarViewModel(private val repository: PlannerRepository) : ViewModel() {
    private val currentMonth = MutableStateFlow(YearMonth.now())
    private val selectedDate = MutableStateFlow(LocalDate.now())

    val uiState: StateFlow<CalendarUiState> = combine(
        repository.observeAllTasks(), repository.observeAllEvents(), currentMonth, selectedDate
    ) { tasks, events, month, date ->
        val zone = ZoneId.systemDefault()
        CalendarUiState(
            currentMonth = month,
            selectedDate = date,
            daysWithTasks = tasks.mapNotNull { it.dueDate?.let { ms -> Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() } }.toSet(),
            daysWithEvents = events.map { Instant.ofEpochMilli(it.startTime).atZone(zone).toLocalDate() }.toSet(),
            selectedDayTasks = tasks.filter { it.dueDate?.let { ms -> Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() == date } ?: false },
            selectedDayEvents = events.filter { Instant.ofEpochMilli(it.startTime).atZone(zone).toLocalDate() == date }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    fun selectDate(date: LocalDate) { selectedDate.value = date; currentMonth.value = YearMonth.from(date) }
    fun previousMonth() { currentMonth.value = currentMonth.value.minusMonths(1) }
    fun nextMonth() { currentMonth.value = currentMonth.value.plusMonths(1) }
    fun toggleTaskCompleted(task: TaskEntity) {
        viewModelScope.launch { repository.toggleTaskCompleted(task.id, !task.isCompleted) }
    }

    companion object {
        fun provideFactory(repository: PlannerRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CalendarViewModel(repository) as T
        }
    }
}

class EventsViewModel(
    private val repository: PlannerRepository,
    private val reminderScheduler: ReminderScheduler
) : ViewModel() {
    val events: StateFlow<List<EventEntity>> = repository.observeAllEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveEvent(
        existingEvent: EventEntity?, title: String, location: String?,
        description: String?, startTime: Long, endTime: Long, reminderMinutesBefore: Int?
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return
        val cleanLoc = location?.trim()?.takeIf { it.isNotEmpty() }
        val cleanDesc = description?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            if (existingEvent == null) {
                val e = EventEntity(title = cleanTitle, location = cleanLoc, description = cleanDesc, startTime = startTime, endTime = endTime, reminderMinutesBefore = reminderMinutesBefore)
                val id = repository.saveEvent(e)
                reminderScheduler.scheduleReminder(e.copy(id = id))
            } else {
                val updated = existingEvent.copy(title = cleanTitle, location = cleanLoc, description = cleanDesc, startTime = startTime, endTime = endTime, reminderMinutesBefore = reminderMinutesBefore)
                repository.updateEvent(updated)
                reminderScheduler.scheduleReminder(updated)
            }
        }
    }

    fun deleteEvent(event: EventEntity) {
        viewModelScope.launch {
            reminderScheduler.cancelReminder(event.id)
            repository.deleteEvent(event)
        }
    }

    companion object {
        fun provideFactory(repository: PlannerRepository, scheduler: ReminderScheduler) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = EventsViewModel(repository, scheduler) as T
            }
    }
}

data class DailyExpenseGroup(val date: LocalDate, val dayTotal: Double, val expenses: List<ExpenseEntity>)
data class ExpensesUiState(
    val todayTotal: Double = 0.0,
    val weekTotal: Double = 0.0,
    val monthTotal: Double = 0.0,
    val groupedExpenses: List<DailyExpenseGroup> = emptyList()
)

class ExpensesViewModel(private val repository: PlannerRepository) : ViewModel() {
    val uiState: StateFlow<ExpensesUiState> = repository.observeAllExpenses().map { expenses ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val currentMonth = YearMonth.from(today)

        var todaySum = 0.0; var weekSum = 0.0; var monthSum = 0.0
        val grouped = expenses
            .groupBy { Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() }
            .toSortedMap(compareByDescending { it })
            .map { (date, list) ->
                val sum = list.sumOf { it.amount }
                if (date == today) todaySum += sum
                if (!date.isBefore(weekStart) && !date.isAfter(today)) weekSum += sum
                if (YearMonth.from(date) == currentMonth) monthSum += sum
                DailyExpenseGroup(date, sum, list)
            }
        ExpensesUiState(todaySum, weekSum, monthSum, grouped)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpensesUiState())

    fun saveExpense(
        existingExpense: ExpenseEntity?, amount: Double, category: ExpenseCategory,
        paymentMethod: PaymentMethod, dateMillis: Long, notes: String?
    ) {
        if (amount <= 0.0) return
        val cleanNotes = notes?.trim()?.takeIf { it.isNotEmpty() }
        val title = cleanNotes ?: category.label
        viewModelScope.launch {
            if (existingExpense == null) {
                repository.saveExpense(ExpenseEntity(title = title, amount = amount, category = category, paymentMethod = paymentMethod, date = dateMillis, notes = cleanNotes))
            } else {
                repository.updateExpense(existingExpense.copy(title = title, amount = amount, category = category, paymentMethod = paymentMethod, date = dateMillis, notes = cleanNotes))
            }
        }
    }

    fun deleteExpense(expense: ExpenseEntity) { viewModelScope.launch { repository.deleteExpense(expense) } }
    fun restoreExpense(expense: ExpenseEntity) { viewModelScope.launch { repository.saveExpense(expense) } }

    companion object {
        fun provideFactory(repository: PlannerRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ExpensesViewModel(repository) as T
        }
    }
}

data class BudgetProgressItem(
    val category: ExpenseCategory?, val label: String, val spent: Double,
    val limit: Double, val budgetEntity: BudgetEntity
) {
    val progress: Float get() = if (limit > 0.0) (spent / limit).toFloat() else 0f
    val isWarning: Boolean get() = progress > 0.80f
    val isExceeded: Boolean get() = spent > limit
}

data class CategorySlice(val category: ExpenseCategory, val amount: Double, val percentage: Float)

data class BudgetUiState(
    val selectedMonth: YearMonth = YearMonth.now(),
    val totalSpent: Double = 0.0,
    val overallBudget: BudgetProgressItem? = null,
    val categoryBudgets: List<BudgetProgressItem> = emptyList(),
    val spendingSlices: List<CategorySlice> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModel(private val repository: PlannerRepository, appContext: Context) : ViewModel() {
    private val context = appContext.applicationContext
    private val prefs = context.getSharedPreferences("budget_alert_prefs", Context.MODE_PRIVATE)
    private val selectedMonth = MutableStateFlow(YearMonth.now())

    val uiState: StateFlow<BudgetUiState> = selectedMonth.flatMapLatest { month ->
        val key = month.toString()
        combine(repository.observeBudgetsForMonth(key), repository.observeAllExpenses()) { budgets, allExpenses ->
            val zone = ZoneId.systemDefault()
            val monthExpenses = allExpenses.filter {
                YearMonth.from(Instant.ofEpochMilli(it.date).atZone(zone)) == month
            }
            val totalSpent = monthExpenses.sumOf { it.amount }
            val byCat = monthExpenses.groupBy { it.category }.mapValues { (_, l) -> l.sumOf { it.amount } }

            val overall = budgets.firstOrNull { it.category == null }?.let {
                BudgetProgressItem(null, "Overall Monthly Budget", totalSpent, it.limitAmount, it)
            }
            val catItems = budgets.filter { it.category != null }.mapNotNull { entity ->
                val cat = runCatching { ExpenseCategory.valueOf(entity.category!!) }.getOrNull() ?: return@mapNotNull null
                BudgetProgressItem(cat, cat.label, byCat[cat] ?: 0.0, entity.limitAmount, entity)
            }
            val slices = byCat.entries.filter { it.value > 0.0 }.sortedByDescending { it.value }.map { (c, a) ->
                CategorySlice(c, a, if (totalSpent > 0.0) (a / totalSpent).toFloat() else 0f)
            }
            if (month == YearMonth.now()) checkAndNotify(key, overall, catItems)
            BudgetUiState(month, totalSpent, overall, catItems, slices)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetUiState())

    private fun checkAndNotify(key: String, overall: BudgetProgressItem?, cats: List<BudgetProgressItem>) {
        (listOfNotNull(overall) + cats).forEach { item ->
            val prefKey = "alert_${key}_${item.category?.name ?: "OVERALL"}"
            if (item.isExceeded) {
                if (!prefs.getBoolean(prefKey, false)) {
                    NotificationHelper.showBudgetExceeded(
                        context, prefKey.hashCode(), "${item.label} Exceeded",
                        "Spent ${formatInr(item.spent)} of ${formatInr(item.limit)} limit."
                    )
                    prefs.edit().putBoolean(prefKey, true).apply()
                }
            } else if (prefs.getBoolean(prefKey, false)) {
                prefs.edit().putBoolean(prefKey, false).apply()
            }
        }
    }

    fun previousMonth() { selectedMonth.value = selectedMonth.value.minusMonths(1) }
    fun nextMonth() { selectedMonth.value = selectedMonth.value.plusMonths(1) }
    fun saveBudget(category: ExpenseCategory?, limit: Double) {
        if (limit <= 0.0) return
        viewModelScope.launch { repository.setMonthlyBudget(selectedMonth.value.toString(), limit, category) }
    }
    fun deleteBudget(budget: BudgetEntity) { viewModelScope.launch { repository.deleteBudget(budget) } }

    companion object {
        fun provideFactory(repository: PlannerRepository, context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = BudgetViewModel(repository, context) as T
        }
    }
}

data class HomeUiState(
    val todayTasks: List<TaskEntity> = emptyList(),
    val nextEvent: EventEntity? = null,
    val todaySpending: Double = 0.0,
    val monthSpending: Double = 0.0,
    val monthlyBudgetLimit: Double? = null,
    val allExpenses: List<ExpenseEntity> = emptyList()
) {
    val budgetLeft: Double? get() = monthlyBudgetLimit?.let { it - monthSpending }
}

class HomeViewModel(private val repository: PlannerRepository) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeAllTasks(),
        repository.observeUpcomingEvents(System.currentTimeMillis()),
        repository.observeAllExpenses(),
        repository.observeOverallBudget(YearMonth.now().toString())
    ) { tasks, events, expenses, budget ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val month = YearMonth.from(today)
        HomeUiState(
            todayTasks = tasks.filter { !it.isCompleted && it.dueDate?.let { ms -> Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() == today } == true },
            nextEvent = events.firstOrNull(),
            todaySpending = expenses.filter { Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() == today }.sumOf { it.amount },
            monthSpending = expenses.filter { YearMonth.from(Instant.ofEpochMilli(it.date).atZone(zone)) == month }.sumOf { it.amount },
            monthlyBudgetLimit = budget?.limitAmount,
            allExpenses = expenses
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun toggleTaskCompleted(task: TaskEntity) {
        viewModelScope.launch { repository.toggleTaskCompleted(task.id, !task.isCompleted) }
    }

    companion object {
        fun provideFactory(repository: PlannerRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(repository) as T
        }
    }
}
