package com.mylife.planner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

class ThemePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.SYSTEM.name)!!) }
            .getOrDefault(ThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode
    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
        _themeMode.value = mode
    }
}

@Composable
fun MyLifePlannerTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) darkColorScheme(primary = Color(0xFF53DBC9))
        else lightColorScheme(primary = Color(0xFF006A60))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

enum class Screen(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Today", Icons.Outlined.Home),
    Tasks("tasks", "Tasks", Icons.Outlined.CheckCircle),
    Calendar("calendar", "Calendar", Icons.Outlined.CalendarMonth),
    Events("events", "Events", Icons.Outlined.Event),
    Expenses("expenses", "Expenses", Icons.Outlined.ReceiptLong),
    Budget("budget", "Budget", Icons.Outlined.AccountBalanceWallet)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val themePrefs = ThemePreferences(applicationContext)
        setContent {
            val themeMode by themePrefs.themeMode.collectAsStateWithLifecycle()
            MyLifePlannerTheme(themeMode = themeMode) {
                MyLifePlannerApp(themeMode = themeMode, onThemeModeChange = themePrefs::setThemeMode)
            }
        }
    }
}

@Composable
fun MyLifePlannerApp(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as MyLifeApplication
    val scheduler = remember { ReminderScheduler(context.applicationContext) }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        selected = currentRoute == screen.route,
                        alwaysShowLabel = false,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(navController = navController, startDestination = Screen.Home.route, modifier = Modifier.padding(innerPadding)) {
            composable(Screen.Home.route) {
                HomeScreen(viewModel(factory = HomeViewModel.provideFactory(app.repository)), themeMode, onThemeModeChange)
            }
            composable(Screen.Tasks.route) {
                TasksScreen(viewModel(factory = TasksViewModel.provideFactory(app.repository)))
            }
            composable(Screen.Calendar.route) {
                CalendarScreen(viewModel(factory = CalendarViewModel.provideFactory(app.repository)))
            }
            composable(Screen.Events.route) {
                EventsScreen(viewModel(factory = EventsViewModel.provideFactory(app.repository, scheduler)))
            }
            composable(Screen.Expenses.route) {
                ExpensesScreen(viewModel(factory = ExpensesViewModel.provideFactory(app.repository)))
            }
            composable(Screen.Budget.route) {
                BudgetScreen(viewModel(factory = BudgetViewModel.provideFactory(app.repository, context)))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, currentThemeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val res = DataPortabilityManager.exportExpensesToCsv(context, uri, uiState.allExpenses)
            snackbar.showSnackbar(res.fold({ "Exported $it expenses to CSV" }, { "Export failed" }))
        }
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            val res = DataPortabilityManager.backupDatabase(context, uri)
            snackbar.showSnackbar(res.fold({ "Database backup saved" }, { "Backup failed" }))
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val res = DataPortabilityManager.restoreDatabase(context, uri)
            if (res.isSuccess) {
                val act = context as? Activity
                act?.packageManager?.getLaunchIntentForPackage(act.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                }?.let { act.startActivity(it); act.finish() }
            } else snackbar.showSnackbar("Restore failed")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Today") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Today's Spending", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(formatInr(uiState.todaySpending), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Budget Left", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            val left = uiState.budgetLeft
                            Text(
                                text = when {
                                    left == null -> "Not set"
                                    left >= 0 -> formatInr(left)
                                    else -> "-${formatInr(-left)}"
                                },
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                color = if (left != null && left < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Next Event", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        val e = uiState.nextEvent
                        if (e == null) {
                            Text("No upcoming events", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            val t = Instant.ofEpochMilli(e.startTime).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, MMM d • hh:mm a"))
                            Text(e.title, style = MaterialTheme.typography.titleMedium)
                            Text(if (e.location.isNullOrBlank()) t else "$t • ${e.location}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { Text("Today's Tasks (${uiState.todayTasks.size})", style = MaterialTheme.typography.titleMedium) }
            if (uiState.todayTasks.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text("All caught up for today!", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(uiState.todayTasks, key = { it.id }) { task ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = task.isCompleted, onCheckedChange = { viewModel.toggleTaskCompleted(task) })
                            Column(Modifier.weight(1f)) {
                                Text(task.title, style = MaterialTheme.typography.titleSmall)
                                Text("${task.category.label} • ${task.priority.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(selected = currentThemeMode == mode, onClick = { onThemeModeChange(mode) }, label = { Text(mode.label) })
                    }
                }
                HorizontalDivider()
                Text("Data & Backup", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { showSettings = false; csvLauncher.launch("mylife_expenses.csv") }, modifier = Modifier.fillMaxWidth()) { Text("Export Expenses to CSV") }
                OutlinedButton(onClick = { showSettings = false; backupLauncher.launch("mylife_planner_backup.db") }, modifier = Modifier.fillMaxWidth()) { Text("Backup Database (.db)") }
                OutlinedButton(onClick = { showSettings = false; restoreLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Restore Database (.db)") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(viewModel: TasksViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSheet by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TaskEntity?>(null) }

    val handleDelete: (TaskEntity) -> Unit = { task ->
        viewModel.deleteTask(task)
        scope.launch {
            val res = snackbar.showSnackbar("Deleted \"${task.title}\"", "Undo", duration = SnackbarDuration.Short)
            if (res == SnackbarResult.ActionPerformed) viewModel.restoreTask(task)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Tasks") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showSheet = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "Add task")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskFilter.entries.forEach { f -> FilterChip(selected = uiState.selectedFilter == f, onClick = { viewModel.setFilter(f) }, label = { Text(f.label) }) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskCategory.entries.forEach { c -> FilterChip(selected = uiState.selectedCategory == c, onClick = { viewModel.setCategoryFilter(c) }, label = { Text(c.label) }) }
            }
            if (uiState.tasks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No tasks found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(uiState.tasks, key = { it.id }) { task ->
                        val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = {
                            if (it == SwipeToDismissBoxValue.EndToStart) { handleDelete(task); true } else false
                        })
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            backgroundContent = {
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer, CardDefaults.shape).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        ) {
                            Card(Modifier.fillMaxWidth().clickable { editing = task; showSheet = true }) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
                                    Checkbox(checked = task.isCompleted, onCheckedChange = { viewModel.toggleTaskCompleted(task) })
                                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                                        Text(task.title, style = MaterialTheme.typography.titleMedium, textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        task.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                                        Spacer(Modifier.height(6.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            AssistChip(onClick = {}, label = { Text(task.priority.label) })
                                            AssistChip(onClick = {}, label = { Text(task.category.label) })
                                            task.dueDate?.let { ms ->
                                                val dt = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, hh:mm a"))
                                                Text(dt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSheet) {
        AddEditTaskSheet(
            existingTask = editing,
            onDismiss = { showSheet = false },
            onSave = { t, d, due, p, c -> viewModel.saveTask(editing, t, d, due, p, c); showSheet = false },
            onDelete = editing?.let { t -> { showSheet = false; handleDelete(t) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditTaskSheet(
    existingTask: TaskEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String?, Long?, Priority, TaskCategory) -> Unit,
    onDelete: (() -> Unit)?
) {
    val zone = remember { ZoneId.systemDefault() }
    val initDt = remember(existingTask) { existingTask?.dueDate?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() } }
    var title by remember { mutableStateOf(existingTask?.title.orEmpty()) }
    var desc by remember { mutableStateOf(existingTask?.description.orEmpty()) }
    var date by remember { mutableStateOf<LocalDate?>(initDt?.toLocalDate()) }
    var time by remember { mutableStateOf<LocalTime?>(initDt?.toLocalTime()) }
    var priority by remember { mutableStateOf(existingTask?.priority ?: Priority.MEDIUM) }
    var category by remember { mutableStateOf(existingTask?.category ?: TaskCategory.PERSONAL) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (existingTask == null) "New Task" else "Edit Task", style = MaterialTheme.typography.titleLarge)
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
            }
            OutlinedTextField(title, { title = it }, label = { Text("Task title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(desc, { desc = it }, label = { Text("Notes (optional)") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) {
                    Text(date?.format(DateTimeFormatter.ofPattern("MMM d, yyyy")) ?: "Due date")
                }
                OutlinedButton(onClick = { if (date == null) date = LocalDate.now(zone); showTime = true }, modifier = Modifier.weight(1f)) {
                    Text(time?.format(DateTimeFormatter.ofPattern("hh:mm a")) ?: "Time")
                }
                if (date != null) IconButton(onClick = { date = null; time = null }) { Icon(Icons.Outlined.Close, contentDescription = "Clear") }
            }
            Text("Priority", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.forEach { p -> FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p.label) }) }
            }
            Text("Category", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskCategory.entries.forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.label) }) }
            }
            Button(
                onClick = {
                    val ms = date?.let { d -> d.atTime(time ?: LocalTime.of(23, 59)).atZone(zone).toInstant().toEpochMilli() }
                    onSave(title, desc, ms, priority, category)
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (existingTask == null) "Add Task" else "Save Changes") }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now(zone)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
    if (showTime) {
        val init = time ?: LocalTime.of(9, 0)
        val state = rememberTimePickerState(init.hour, init.minute)
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("Select Time") },
            text = { TimePicker(state = state) },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(state.hour, state.minute); showTime = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(viewModel: CalendarViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }

    Scaffold(topBar = { TopAppBar(title = { Text("Calendar") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::previousMonth) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Prev") }
                Text(uiState.currentMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = viewModel::nextMonth) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Next") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { d ->
                    Text(d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val firstDay = uiState.currentMonth.atDay(1)
            val offset = firstDay.dayOfWeek.value % 7
            val daysInMonth = uiState.currentMonth.lengthOfMonth()
            val rows = (offset + daysInMonth + 6) / 7
            Column(Modifier.padding(horizontal = 8.dp)) {
                for (r in 0 until rows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (c in 0 until 7) {
                            val dayNum = r * 7 + c - offset + 1
                            if (dayNum in 1..daysInMonth) {
                                val date = uiState.currentMonth.atDay(dayNum)
                                val selected = date == uiState.selectedDate
                                val isToday = date == today
                                val hasTask = date in uiState.daysWithTasks
                                val hasEvent = date in uiState.daysWithEvents
                                Column(
                                    modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(CircleShape)
                                        .then(
                                            when {
                                                selected -> Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                                                isToday -> Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                                else -> Modifier
                                            }
                                        ).clickable { viewModel.selectDate(date) },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(dayNum.toString(), color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Normal)
                                    if (hasTask || hasEvent) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                            if (hasEvent) Box(Modifier.size(5.dp).background(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.tertiary, CircleShape))
                                            if (hasTask) Box(Modifier.size(5.dp).background(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary, CircleShape))
                                        }
                                    }
                                }
                            } else Spacer(Modifier.weight(1f).aspectRatio(1f))
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(uiState.selectedDate.format(DateTimeFormatter.ofPattern("EEEE, MMM d")), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (uiState.selectedDayEvents.isEmpty() && uiState.selectedDayTasks.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("Nothing scheduled for this day", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.selectedDayEvents, key = { "e_${it.id}" }) { event ->
                        val t = Instant.ofEpochMilli(event.startTime).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("hh:mm a"))
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(event.title, style = MaterialTheme.typography.titleSmall)
                                Text("Event • $t" + (event.location?.let { " • $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(uiState.selectedDayTasks, key = { "t_${it.id}" }) { task ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = task.isCompleted, onCheckedChange = { viewModel.toggleTaskCompleted(task) })
                                Column {
                                    Text(task.title, style = MaterialTheme.typography.titleSmall, textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null)
                                    Text("Task • ${task.category.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(viewModel: EventsViewModel) {
    val context = LocalContext.current
    val events by viewModel.events.collectAsStateWithLifecycle()
    var hasPerm by remember {
        mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPerm = it }
    var showSheet by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EventEntity?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Events") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showSheet = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "Add event")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!hasPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Allow notifications for event reminders.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
                    }
                }
            }
            if (events.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No events scheduled", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(events, key = { it.id }) { event ->
                        val zone = ZoneId.systemDefault()
                        val s = Instant.ofEpochMilli(event.startTime).atZone(zone).format(DateTimeFormatter.ofPattern("EEE, MMM d • hh:mm a"))
                        val e = Instant.ofEpochMilli(event.endTime).atZone(zone).format(DateTimeFormatter.ofPattern("hh:mm a"))
                        Card(Modifier.fillMaxWidth().clickable { editing = event; showSheet = true }) {
                            Column(Modifier.padding(14.dp)) {
                                Text(event.title, style = MaterialTheme.typography.titleMedium)
                                Text("$s – $e", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!event.location.isNullOrBlank()) Text(event.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                event.reminderMinutesBefore?.let { mins ->
                                    Text(if (mins == 0) "Reminder at event time" else "Reminder ${mins}m before", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSheet) {
        AddEditEventSheet(
            existingEvent = editing,
            onDismiss = { showSheet = false },
            onSave = { t, loc, desc, s, e, rem ->
                if (rem != null && !hasPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                viewModel.saveEvent(editing, t, loc, desc, s, e, rem)
                showSheet = false
            },
            onDelete = editing?.let { ev -> { viewModel.deleteEvent(ev); showSheet = false } }
        )
    }
}

private enum class TimeTarget { START, END }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditEventSheet(
    existingEvent: EventEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String?, String?, Long, Long, Int?) -> Unit,
    onDelete: (() -> Unit)?
) {
    val zone = remember { ZoneId.systemDefault() }
    val initStart = remember(existingEvent) { existingEvent?.startTime?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() } }
    val initEnd = remember(existingEvent) { existingEvent?.endTime?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() } }

    var title by remember { mutableStateOf(existingEvent?.title.orEmpty()) }
    var location by remember { mutableStateOf(existingEvent?.location.orEmpty()) }
    var desc by remember { mutableStateOf(existingEvent?.description.orEmpty()) }
    var date by remember { mutableStateOf(initStart?.toLocalDate() ?: LocalDate.now(zone)) }
    var startTime by remember { mutableStateOf(initStart?.toLocalTime() ?: LocalTime.of(10, 0)) }
    var endTime by remember { mutableStateOf(initEnd?.toLocalTime() ?: LocalTime.of(11, 0)) }
    var reminder by remember { mutableStateOf(existingEvent?.reminderMinutesBefore) }
    var showDate by remember { mutableStateOf(false) }
    var timeTarget by remember { mutableStateOf<TimeTarget?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (existingEvent == null) "New Event" else "Edit Event", style = MaterialTheme.typography.titleLarge)
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
            }
            OutlinedTextField(title, { title = it }, label = { Text("Event title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(location, { location = it }, label = { Text("Location (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(desc, { desc = it }, label = { Text("Notes (optional)") }, maxLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
                Text(date.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { timeTarget = TimeTarget.START }, modifier = Modifier.weight(1f)) { Text("Start: ${startTime.format(DateTimeFormatter.ofPattern("hh:mm a"))}") }
                OutlinedButton(onClick = { timeTarget = TimeTarget.END }, modifier = Modifier.weight(1f)) { Text("End: ${endTime.format(DateTimeFormatter.ofPattern("hh:mm a"))}") }
            }
            Text("Reminder", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "None", 0 to "At time", 10 to "10m", 30 to "30m", 60 to "1h").forEach { (mins, label) ->
                    FilterChip(selected = reminder == mins, onClick = { reminder = mins }, label = { Text(label) })
                }
            }
            Button(
                onClick = {
                    val s = date.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
                    val safeEnd = if (endTime.isAfter(startTime)) endTime else startTime.plusHours(1)
                    val e = date.atTime(safeEnd).atZone(zone).toInstant().toEpochMilli()
                    onSave(title, location, desc, s, e, reminder)
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (existingEvent == null) "Save Event" else "Save Changes") }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
    timeTarget?.let { target ->
        val init = if (target == TimeTarget.START) startTime else endTime
        val state = rememberTimePickerState(init.hour, init.minute)
        AlertDialog(
            onDismissRequest = { timeTarget = null },
            title = { Text(if (target == TimeTarget.START) "Start Time" else "End Time") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val picked = LocalTime.of(state.hour, state.minute)
                    if (target == TimeTarget.START) startTime = picked else endTime = picked
                    timeTarget = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { timeTarget = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(viewModel: ExpensesViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSheet by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ExpenseEntity?>(null) }

    val handleDelete: (ExpenseEntity) -> Unit = { exp ->
        viewModel.deleteExpense(exp)
        scope.launch {
            val res = snackbar.showSnackbar("Deleted ${formatInr(exp.amount)}", "Undo", duration = SnackbarDuration.Short)
            if (res == SnackbarResult.ActionPerformed) viewModel.restoreExpense(exp)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Expenses") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showSheet = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "Add expense")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Today" to uiState.todayTotal, "Week" to uiState.weekTotal, "Month" to uiState.monthTotal).forEach { (lbl, amt) ->
                    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(lbl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatInr(amt), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if (uiState.groupedExpenses.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No expenses logged yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    uiState.groupedExpenses.forEach { group ->
                        item(key = "h_${group.date}") {
                            val today = LocalDate.now()
                            val label = when (group.date) {
                                today -> "Today"
                                today.minusDays(1) -> "Yesterday"
                                else -> group.date.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy"))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text(formatInr(group.dayTotal), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        items(group.expenses, key = { it.id }) { exp ->
                            Card(Modifier.fillMaxWidth().clickable { editing = exp; showSheet = true }) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(exp.notes?.takeIf { it.isNotBlank() } ?: exp.category.label, style = MaterialTheme.typography.titleMedium)
                                        Text("${exp.category.label} • ${exp.paymentMethod.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(formatInr(exp.amount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSheet) {
        AddEditExpenseSheet(
            existingExpense = editing,
            onDismiss = { showSheet = false },
            onSave = { amt, cat, pm, ms, notes -> viewModel.saveExpense(editing, amt, cat, pm, ms, notes); showSheet = false },
            onDelete = editing?.let { exp -> { showSheet = false; handleDelete(exp) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditExpenseSheet(
    existingExpense: ExpenseEntity?,
    onDismiss: () -> Unit,
    onSave: (Double, ExpenseCategory, PaymentMethod, Long, String?) -> Unit,
    onDelete: (() -> Unit)?
) {
    val zone = remember { ZoneId.systemDefault() }
    var amountText by remember { mutableStateOf(existingExpense?.amount?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()) }
    var notes by remember { mutableStateOf(existingExpense?.notes.orEmpty()) }
    var category by remember { mutableStateOf(existingExpense?.category ?: ExpenseCategory.FOOD) }
    var method by remember { mutableStateOf(existingExpense?.paymentMethod ?: PaymentMethod.UPI) }
    var date by remember { mutableStateOf(existingExpense?.date?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: LocalDate.now(zone)) }
    var showDate by remember { mutableStateOf(false) }
    val parsed = amountText.toDoubleOrNull()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (existingExpense == null) "Add Expense" else "Edit Expense", style = MaterialTheme.typography.titleLarge)
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
            }
            OutlinedTextField(
                value = amountText,
                onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d*\\.?\\d{0,2}$"))) amountText = it },
                label = { Text("Amount") }, prefix = { Text("₹ ") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(notes, { notes = it }, label = { Text("Note (e.g., Lunch, Metro)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) { Text(date.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy"))) }
            Text("Category", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExpenseCategory.entries.forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.label) }) }
            }
            Text("Payment Method", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaymentMethod.entries.forEach { m -> FilterChip(selected = method == m, onClick = { method = m }, label = { Text(m.label) }) }
            }
            Button(
                onClick = { parsed?.let { onSave(it, category, method, date.atStartOfDay(zone).toInstant().toEpochMilli(), notes) } },
                enabled = parsed != null && parsed > 0.0,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (existingExpense == null) "Save Expense" else "Update Expense") }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(viewModel: BudgetViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BudgetProgressItem?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Budget") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showDialog = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "Set budget")
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = viewModel::previousMonth) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Prev") }
                    Text(uiState.selectedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = viewModel::nextMonth) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Next") }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Spending by Category", style = MaterialTheme.typography.titleMedium)
                        Text("Total: ${formatInr(uiState.totalSpent)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        if (uiState.spendingSlices.isEmpty()) {
                            Text("No expenses recorded for this month.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Canvas(Modifier.size(120.dp)) {
                                    var start = -90f
                                    uiState.spendingSlices.forEach { s ->
                                        val sweep = s.percentage * 360f
                                        drawArc(color = categoryColor(s.category), startAngle = start, sweepAngle = sweep, useCenter = false, style = Stroke(32f))
                                        start += sweep
                                    }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    uiState.spendingSlices.forEach { s ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(Modifier.size(10.dp).background(categoryColor(s.category), CircleShape))
                                                Spacer(Modifier.width(6.dp))
                                                Text(s.category.label, style = MaterialTheme.typography.bodySmall)
                                            }
                                            Text("${formatInr(s.amount)} (${(s.percentage * 100).roundToInt()}%)", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            uiState.overallBudget?.let { item { BudgetCard(it) { editing = it; showDialog = true } } }
            items(uiState.categoryBudgets, key = { it.category?.name ?: "overall" }) { item ->
                BudgetCard(item) { editing = item; showDialog = true }
            }
        }
    }

    if (showDialog) {
        var cat by remember { mutableStateOf(editing?.category) }
        var limitText by remember { mutableStateOf(editing?.limit?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()) }
        val parsed = limitText.toDoubleOrNull()
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Set Monthly Budget") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = cat == null, onClick = { cat = null }, label = { Text("Overall") })
                        ExpenseCategory.entries.forEach { c -> FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c.label) }) }
                    }
                    OutlinedTextField(
                        value = limitText,
                        onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d*\\.?\\d{0,2}$"))) limitText = it },
                        label = { Text("Limit") }, prefix = { Text("₹ ") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { parsed?.let { viewModel.saveBudget(cat, it) }; showDialog = false }, enabled = parsed != null && parsed > 0.0) { Text("Save") }
            },
            dismissButton = {
                Row {
                    editing?.let { b -> TextButton(onClick = { viewModel.deleteBudget(b.budgetEntity); showDialog = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) } }
                    TextButton(onClick = { showDialog = false }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
private fun BudgetCard(item: BudgetProgressItem, onClick: () -> Unit) {
    val barColor = if (item.isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.label, style = MaterialTheme.typography.titleMedium)
                Text("${(item.progress * 100).roundToInt()}%", color = barColor, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { item.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(10.dp),
                color = barColor, trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeCap = StrokeCap.Round
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${formatInr(item.spent)} / ${formatInr(item.limit)}", style = MaterialTheme.typography.bodySmall)
                val diff = item.limit - item.spent
                Text(if (diff >= 0) "${formatInr(diff)} left" else "${formatInr(-diff)} over!", style = MaterialTheme.typography.bodySmall, color = if (item.isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun categoryColor(category: ExpenseCategory): Color = when (category) {
    ExpenseCategory.FOOD -> Color(0xFF4CAF50)
    ExpenseCategory.TRANSPORT -> Color(0xFF2196F3)
    ExpenseCategory.BILLS -> Color(0xFFFF9800)
    ExpenseCategory.SHOPPING -> Color(0xFF9C27B0)
    ExpenseCategory.HEALTH -> Color(0xFFF44336)
    ExpenseCategory.OTHER -> Color(0xFF607D8B)
}
