package com.mylife.planner

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class Priority(val label: String) {
    LOW("Low"), MEDIUM("Medium"), HIGH("High")
}

enum class TaskCategory(val label: String) {
    PERSONAL("Personal"), WORK("Work"), ERRANDS("Errands"),
    HEALTH("Health"), STUDY("Study"), OTHER("Other")
}

enum class ExpenseCategory(val label: String) {
    FOOD("Food"), TRANSPORT("Transport"), BILLS("Bills"),
    SHOPPING("Shopping"), HEALTH("Health"), OTHER("Other")
}

enum class PaymentMethod(val label: String) {
    UPI("UPI"), CASH("Cash"), CARD("Card"), BANK_TRANSFER("Bank Transfer")
}

class Converters {
    @TypeConverter fun fromPriority(v: Priority): String = v.name
    @TypeConverter fun toPriority(v: String): Priority =
        runCatching { Priority.valueOf(v) }.getOrDefault(Priority.MEDIUM)

    @TypeConverter fun fromTaskCategory(v: TaskCategory): String = v.name
    @TypeConverter fun toTaskCategory(v: String): TaskCategory =
        runCatching { TaskCategory.valueOf(v) }.getOrDefault(TaskCategory.PERSONAL)

    @TypeConverter fun fromExpenseCategory(v: ExpenseCategory): String = v.name
    @TypeConverter fun toExpenseCategory(v: String): ExpenseCategory =
        runCatching { ExpenseCategory.valueOf(v) }.getOrDefault(ExpenseCategory.OTHER)

    @TypeConverter fun fromPaymentMethod(v: PaymentMethod): String = v.name
    @TypeConverter fun toPaymentMethod(v: String): PaymentMethod =
        runCatching { PaymentMethod.valueOf(v) }.getOrDefault(PaymentMethod.UPI)
}

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val description: String? = null,
    val dueDate: Long? = null,
    val priority: Priority = Priority.MEDIUM,
    val category: TaskCategory = TaskCategory.PERSONAL,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val startTime: Long,
    val endTime: Long,
    val isAllDay: Boolean = false,
    val reminderMinutesBefore: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val amount: Double,
    val category: ExpenseCategory = ExpenseCategory.OTHER,
    val paymentMethod: PaymentMethod = PaymentMethod.UPI,
    val date: Long,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "budgets",
    indices = [Index(value = ["yearMonth", "category"], unique = true)]
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val yearMonth: String,
    val category: String? = null,
    val limitAmount: Double,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY isCompleted ASC, dueDate ASC, createdAt DESC")
    fun observeAllTasks(): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity): Long

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Delete
    suspend fun deleteTask(task: TaskEntity)

    @Query("UPDATE tasks SET isCompleted = :isCompleted, completedAt = :completedAt WHERE id = :taskId")
    suspend fun setTaskCompleted(taskId: Long, isCompleted: Boolean, completedAt: Long?)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY startTime ASC")
    fun observeAllEvents(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE startTime >= :fromTime ORDER BY startTime ASC")
    fun observeUpcomingEvents(fromTime: Long): Flow<List<EventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: EventEntity): Long

    @Update
    suspend fun updateEvent(event: EventEntity)

    @Delete
    suspend fun deleteEvent(event: EventEntity)
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY date DESC, createdAt DESC")
    fun observeAllExpenses(): Flow<List<ExpenseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Update
    suspend fun updateExpense(expense: ExpenseEntity)

    @Delete
    suspend fun deleteExpense(expense: ExpenseEntity)
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets WHERE yearMonth = :yearMonth ORDER BY category ASC")
    fun observeBudgetsForMonth(yearMonth: String): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE yearMonth = :yearMonth AND category IS NULL LIMIT 1")
    fun observeOverallBudget(yearMonth: String): Flow<BudgetEntity?>

    @Query("SELECT * FROM budgets WHERE yearMonth = :yearMonth AND category IS NULL LIMIT 1")
    suspend fun getOverallBudget(yearMonth: String): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE yearMonth = :yearMonth AND category = :category LIMIT 1")
    suspend fun getCategoryBudget(yearMonth: String, category: String): BudgetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBudget(budget: BudgetEntity): Long

    @Delete
    suspend fun deleteBudget(budget: BudgetEntity)
}

@Database(
    entities = [TaskEntity::class, EventEntity::class, ExpenseEntity::class, BudgetEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun eventDao(): EventDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun budgetDao(): BudgetDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mylife_planner.db"
                ).build().also { INSTANCE = it }
            }
        }

        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}

class PlannerRepository(
    private val taskDao: TaskDao,
    private val eventDao: EventDao,
    private val expenseDao: ExpenseDao,
    private val budgetDao: BudgetDao
) {
    fun observeAllTasks(): Flow<List<TaskEntity>> = taskDao.observeAllTasks()
    suspend fun saveTask(task: TaskEntity): Long = taskDao.insertTask(task)
    suspend fun updateTask(task: TaskEntity) = taskDao.updateTask(task)
    suspend fun deleteTask(task: TaskEntity) = taskDao.deleteTask(task)
    suspend fun toggleTaskCompleted(taskId: Long, isCompleted: Boolean) {
        taskDao.setTaskCompleted(taskId, isCompleted, if (isCompleted) System.currentTimeMillis() else null)
    }

    fun observeAllEvents(): Flow<List<EventEntity>> = eventDao.observeAllEvents()
    fun observeUpcomingEvents(fromTime: Long = System.currentTimeMillis()): Flow<List<EventEntity>> =
        eventDao.observeUpcomingEvents(fromTime)
    suspend fun saveEvent(event: EventEntity): Long = eventDao.insertEvent(event)
    suspend fun updateEvent(event: EventEntity) = eventDao.updateEvent(event)
    suspend fun deleteEvent(event: EventEntity) = eventDao.deleteEvent(event)

    fun observeAllExpenses(): Flow<List<ExpenseEntity>> = expenseDao.observeAllExpenses()
    suspend fun saveExpense(expense: ExpenseEntity): Long = expenseDao.insertExpense(expense)
    suspend fun updateExpense(expense: ExpenseEntity) = expenseDao.updateExpense(expense)
    suspend fun deleteExpense(expense: ExpenseEntity) = expenseDao.deleteExpense(expense)

    fun observeBudgetsForMonth(yearMonth: String): Flow<List<BudgetEntity>> =
        budgetDao.observeBudgetsForMonth(yearMonth)
    fun observeOverallBudget(yearMonth: String): Flow<BudgetEntity?> =
        budgetDao.observeOverallBudget(yearMonth)

    suspend fun setMonthlyBudget(yearMonth: String, limitAmount: Double, category: ExpenseCategory? = null) {
        val existing = if (category == null) {
            budgetDao.getOverallBudget(yearMonth)
        } else {
            budgetDao.getCategoryBudget(yearMonth, category.name)
        }
        budgetDao.upsertBudget(
            BudgetEntity(
                id = existing?.id ?: 0L,
                yearMonth = yearMonth,
                category = category?.name,
                limitAmount = limitAmount,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteBudget(budget: BudgetEntity) = budgetDao.deleteBudget(budget)
}

object DataPortabilityManager {
    private const val DB_NAME = "mylife_planner.db"

    suspend fun exportExpensesToCsv(context: Context, uri: Uri, expenses: List<ExpenseEntity>): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val zone = ZoneId.systemDefault()
                val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.appendLine("Date,Category,Payment Method,Amount (INR),Notes")
                    expenses.sortedByDescending { it.date }.forEach { e ->
                        val dateStr = Instant.ofEpochMilli(e.date).atZone(zone).format(fmt)
                        val notes = escapeCsv(e.notes.orEmpty())
                        writer.appendLine("$dateStr,${e.category.label},${e.paymentMethod.label},${e.amount},$notes")
                    }
                } ?: error("Unable to open output stream")
                expenses.size
            }
        }

    suspend fun backupDatabase(context: Context, destinationUri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val db = AppDatabase.getInstance(context)
                db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
                AppDatabase.closeInstance()
                val dbFile = context.getDatabasePath(DB_NAME)
                require(dbFile.exists()) { "Database file not found" }
                context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                    dbFile.inputStream().use { input -> input.copyTo(out) }
                    Unit
                } ?: error("Unable to open backup destination")
            }
        }

    suspend fun restoreDatabase(context: Context, sourceUri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                AppDatabase.closeInstance()
                val dbFile = context.getDatabasePath(DB_NAME)
                File("${dbFile.path}-wal").delete()
                File("${dbFile.path}-shm").delete()
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    dbFile.outputStream().use { out -> input.copyTo(out) }
                    Unit
                } ?: error("Unable to open backup file")
            }
        }

    private fun escapeCsv(value: String): String {
        val quote = value.contains(',') || value.contains('"') || value.contains('\n')
        return if (quote) "\"${value.replace("\"", "\"\"")}\"" else value
    }
}

class MyLifeApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val repository: PlannerRepository by lazy {
        PlannerRepository(
            database.taskDao(), database.eventDao(),
            database.expenseDao(), database.budgetDao()
        )
    }
}
