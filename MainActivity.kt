package com.example.financetracker

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.*

/* ----------------- THEME & COLORS ----------------- */

val AppColors = object {
    val Background = Color(0xFF181825)
    val Surface = Color(0xFF262636)
    val Primary = Color(0xFF6D4AFF)
    val TextPrimary = Color(0xFFFFFFFF)
    val Success = Color(0xFF16A34A)
    val Error = Color(0xFFDC2626)
    val Highlight = Color(0xFF3E3E50)
}

/* ----------------- MODELS ----------------- */

data class Transaction(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val amount: Double,
    val isExpense: Boolean,
    val date: LocalDateTime = LocalDateTime.now()
)

data class GraphPoint(
    val value: Double,
    val label: String,
    val rawDate: LocalDate
)

enum class ChartType { BALANCE, SAVINGS, EXPENSES }
enum class Granularity { DAY, MONTH }

enum class TimeFilter(val label: String, val granularity: Granularity?) {
    DAYS_7("7 Days", Granularity.DAY),
    MONTHS_3("3 Months", Granularity.DAY),
    YEAR_1("1 Year", Granularity.MONTH),
    ALL("All", null)
}

/* ----------------- MAIN ACTIVITY ----------------- */

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = AppColors.Background,
                    surface = AppColors.Surface,
                    primary = AppColors.Primary,
                    onBackground = AppColors.TextPrimary,
                    onSurface = AppColors.TextPrimary
                )
            ) {
                FinanceDashboard()
            }
        }
    }
}

/* ----------------- LOGIC HELPERS ----------------- */

private fun calculateChartData(
    transactions: List<Transaction>,
    filter: TimeFilter,
    valueSelector: (List<Transaction>) -> Double
): List<GraphPoint> {
    val points = mutableListOf<GraphPoint>()
    val today = LocalDate.now()
    val xAxisDates = mutableListOf<LocalDate>()

    // Determine Granularity
    val granularity = if (filter == TimeFilter.ALL) {
        val firstDate = if (transactions.isNotEmpty()) transactions.minOf { it.date.toLocalDate() } else today
        if (ChronoUnit.DAYS.between(firstDate, today) > 90) Granularity.MONTH else Granularity.DAY
    } else {
        filter.granularity!!
    }

    // Build X-Axis
    when (filter) {
        TimeFilter.DAYS_7 -> for (i in 6 downTo 0) xAxisDates.add(today.minusDays(i.toLong()))
        TimeFilter.MONTHS_3 -> for (i in 90 downTo 0) xAxisDates.add(today.minusDays(i.toLong()))
        TimeFilter.YEAR_1 -> for (i in 11 downTo 0) xAxisDates.add(today.minusMonths(i.toLong()).with(TemporalAdjusters.lastDayOfMonth()))
        TimeFilter.ALL -> {
            if (transactions.isEmpty()) xAxisDates.add(today)
            else {
                val start = transactions.minOf { it.date.toLocalDate() }
                if (granularity == Granularity.DAY) {
                    var d = start
                    while (!d.isAfter(today)) { xAxisDates.add(d); d = d.plusDays(1) }
                } else {
                    var d = start.with(TemporalAdjusters.lastDayOfMonth())
                    val end = today.with(TemporalAdjusters.lastDayOfMonth())
                    while (!d.isAfter(end) || (d.month == end.month && d.year == end.year)) {
                        xAxisDates.add(d); d = d.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth())
                    }
                    if (xAxisDates.isNotEmpty() && xAxisDates.last().isBefore(today)) xAxisDates.add(today)
                }
            }
        }
    }

    // Calculate Values
    val startDate = if (granularity == Granularity.DAY) xAxisDates.first().atStartOfDay() else xAxisDates.first().withDayOfMonth(1).atStartOfDay()
    val priorTransactions = transactions.filter { it.date.isBefore(startDate) }
    var runningTotal = valueSelector(priorTransactions)
    val currentPeriodTransactions = transactions.filter { !it.date.isBefore(startDate) }

    val dayFormatter = DateTimeFormatter.ofPattern("dd/MM")
    val dayNameFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    val monthFormatter = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())

    xAxisDates.forEach { date ->
        val bucketTransactions = if (granularity == Granularity.MONTH) {
            currentPeriodTransactions.filter { it.date.year == date.year && it.date.month == date.month && !it.date.toLocalDate().isAfter(date) }
        } else {
            currentPeriodTransactions.filter { it.date.toLocalDate().isEqual(date) }
        }
        
        runningTotal += valueSelector(bucketTransactions)

        val label = if (granularity == Granularity.MONTH) {
            date.format(monthFormatter)
        } else {
            if (xAxisDates.size > 10) {
                if (date.dayOfWeek.value == 1) date.format(dayFormatter) else ""
            } else {
                date.format(dayNameFormatter)
            }
        }
        points.add(GraphPoint(runningTotal, label, date))
    }
    return points
}

/* ----------------- UI COMPOSABLES ----------------- */

@Composable
fun FinanceDashboard() {
    val transactions = remember { mutableStateListOf<Transaction>() }
    var targetString by remember { mutableStateOf("2000") }
    var expandedChart by remember { mutableStateOf<ChartType?>(null) }
    var activeFilter by remember { mutableStateOf(TimeFilter.DAYS_7) }

    // Modal States
    var showTransactionDialog by remember { mutableStateOf(false) }
    var showGoalDialog by remember { mutableStateOf(false) }
    var showOptionsDialog by remember { mutableStateOf(false) }
    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }
    var transactionToEdit by remember { mutableStateOf<Transaction?>(null) }

    val currentTotal = transactions.sumOf { if (it.isExpense) -it.amount else it.amount }

    // Derived State for Charts
    val balancePoints = remember(transactions, activeFilter) {
        calculateChartData(transactions, activeFilter) { list -> list.sumOf { if (it.isExpense) -it.amount else it.amount } }
    }
    val savingsPoints = remember(transactions, activeFilter) {
        calculateChartData(transactions, activeFilter) { list -> list.sumOf { if (!it.isExpense) it.amount else 0.0 } }
    }
    val expensePoints = remember(transactions, activeFilter) {
        calculateChartData(transactions, activeFilter) { list -> list.sumOf { if (it.isExpense) it.amount else 0.0 } }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        DashboardContent(
            transactions = transactions,
            targetString = targetString,
            currentTotal = currentTotal,
            balancePoints = balancePoints,
            savingsPoints = savingsPoints,
            expensePoints = expensePoints,
            activeFilter = activeFilter,
            onFilterChange = { activeFilter = it },
            onExpandChart = { expandedChart = it },
            onAddClick = { transactionToEdit = null; showTransactionDialog = true },
            onSettingsClick = { showGoalDialog = true },
            onTransactionLongPress = { t -> selectedTransaction = t; showOptionsDialog = true }
        )

        // Expanded Chart Overlay
        AnimatedVisibility(
            visible = expandedChart != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.zIndex(10f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { expandedChart = null },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = expandedChart != null,
                    enter = scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
                    exit = scaleOut()
                ) {
                    val (data, color, title) = when (expandedChart) {
                        ChartType.BALANCE -> Triple(balancePoints, AppColors.Primary, "Balance Over Time")
                        ChartType.SAVINGS -> Triple(savingsPoints, AppColors.Success, "Savings Growth")
                        ChartType.EXPENSES -> Triple(expensePoints, AppColors.Error, "Expense History")
                        null -> Triple(emptyList(), Color.White, "")
                    }
                    LineChart(
                        data = data,
                        title = "$title (${activeFilter.label})",
                        lineColor = color,
                        isMaximized = true,
                        modifier = Modifier.fillMaxWidth(0.95f).aspectRatio(1.7f)
                    )
                }
            }
        }
    }

    // Dialogs
    if (showTransactionDialog) {
        TransactionEditorDialog(
            existing = transactionToEdit,
            onDismiss = { showTransactionDialog = false },
            onConfirm = { title, amount, isExpense, date ->
                if (transactionToEdit != null) {
                    val idx = transactions.indexOfFirst { it.id == transactionToEdit!!.id }
                    if (idx != -1) transactions[idx] = Transaction(id = transactionToEdit!!.id, title = title, amount = amount, isExpense = isExpense, date = date)
                } else {
                    transactions.add(0, Transaction(title = title, amount = amount, isExpense = isExpense, date = date))
                }
                showTransactionDialog = false
            }
        )
    }

    if (showOptionsDialog && selectedTransaction != null) {
        OptionsDialog(
            title = selectedTransaction!!.title,
            onDismiss = { showOptionsDialog = false },
            onEdit = {
                transactionToEdit = selectedTransaction
                showOptionsDialog = false
                showTransactionDialog = true
            },
            onDelete = {
                transactions.remove(selectedTransaction)
                showOptionsDialog = false
            }
        )
    }

    if (showGoalDialog) {
        GoalSettingDialog(
            current = targetString,
            onDismiss = { showGoalDialog = false },
            onConfirm = { targetString = it; showGoalDialog = false },
            onClearData = { transactions.clear(); showGoalDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardContent(
    transactions: List<Transaction>,
    targetString: String,
    currentTotal: Double,
    balancePoints: List<GraphPoint>,
    savingsPoints: List<GraphPoint>,
    expensePoints: List<GraphPoint>,
    activeFilter: TimeFilter,
    onFilterChange: (TimeFilter) -> Unit,
    onExpandChart: (ChartType) -> Unit,
    onAddClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onTransactionLongPress: (Transaction) -> Unit
) {
    val targetVal = targetString.toDoubleOrNull() ?: 1.0
    val progress = (currentTotal / targetVal).toFloat().coerceIn(0f, 1f)
    val percentage = ((currentTotal / targetVal) * 100).toInt()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddClick,
                shape = CircleShape,
                containerColor = AppColors.Primary,
                contentColor = Color.White
            ) { Icon(Icons.Default.Add, contentDescription = "Add") }
        },
        containerColor = AppColors.Background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Section
            Box(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = progress,
                            modifier = Modifier.size(160.dp),
                            strokeWidth = 14.dp,
                            color = if (currentTotal >= 0) AppColors.Primary else AppColors.Error,
                            trackColor = Color.DarkGray
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$percentage%", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                            Text("Balance: €$currentTotal", fontSize = 14.sp, color = if (currentTotal >= 0) AppColors.Success else AppColors.Error)
                        }
                    }
                }
                IconButton(onClick = onSettingsClick, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Divider(color = Color.Gray.copy(alpha = 0.3f))

            // Scrollable Content
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                item {
                    Text("Recent Transactions", modifier = Modifier.padding(vertical = 16.dp), fontSize = 14.sp, color = Color.Gray)
                }

                items(transactions.sortedByDescending { it.date }, key = { it.id }) { t ->
                    TransactionRow(item = t, onLongClick = { onTransactionLongPress(t) })
                }

                item {
                    Spacer(modifier = Modifier.height(30.dp))
                    Text("Analytics", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Filter Pills
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TimeFilter.values().forEach { filter ->
                            FilterPill(
                                text = filter.label,
                                isSelected = activeFilter == filter,
                                onClick = { onFilterChange(filter) }
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Charts
                    ChartWrapper(balancePoints, "Net Balance", AppColors.Primary) { onExpandChart(ChartType.BALANCE) }
                    Spacer(modifier = Modifier.height(16.dp))
                    ChartWrapper(savingsPoints, "Income & Savings", AppColors.Success) { onExpandChart(ChartType.SAVINGS) }
                    Spacer(modifier = Modifier.height(16.dp))
                    ChartWrapper(expensePoints, "Expenses", AppColors.Error) { onExpandChart(ChartType.EXPENSES) }
                    
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
fun FilterPill(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (isSelected) AppColors.Primary else AppColors.Highlight)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (isSelected) Color.White else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChartWrapper(data: List<GraphPoint>, title: String, color: Color, onLongPress: () -> Unit) {
    Box(modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)) {
        LineChart(data, title, color, isMaximized = false, modifier = Modifier.fillMaxWidth().height(200.dp))
    }
}

@Composable
fun LineChart(
    data: List<GraphPoint>,
    title: String,
    lineColor: Color,
    isMaximized: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textPaint = remember(density) {
        Paint().apply {
            color = android.graphics.Color.GRAY
            textAlign = Paint.Align.CENTER
            textSize = density.run { 10.sp.toPx() }
        }
    }
    
    val valuePaint = remember(density, lineColor) {
        Paint().apply {
            color = lineColor.toArgb()
            textSize = density.run { 14.sp.toPx() }
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        modifier = modifier,
        elevation = CardDefaults.cardElevation(if (isMaximized) 10.dp else 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = if (isMaximized) 20.sp else 14.sp,
                color = if (isMaximized) AppColors.TextPrimary else Color.Gray,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (data.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No data available", color = Color.Gray, fontSize = 12.sp)
                }
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val values = data.map { it.value }
                    val maxVal = values.maxOrNull() ?: 1.0
                    val minVal = values.minOrNull() ?: 0.0
                    val rangeY = if (maxVal == minVal) 1.0 else (maxVal - minVal) * 1.2
                    val baseLine = minVal - (if (maxVal == minVal) 0.5 else 0.0)

                    val width = size.width
                    val height = size.height
                    val bottomPadding = 30f
                    val graphHeight = height - bottomPadding
                    val stepX = width / (data.size - 1).coerceAtLeast(1)

                    val screenPoints = data.mapIndexed { i, p ->
                        val x = i * stepX
                        val y = graphHeight - ((p.value - baseLine) / rangeY * graphHeight).toFloat()
                        
                        // Grid & Labels
                        val labelInterval = when {
                            data.size > 60 -> 15
                            data.size > 15 -> 5
                            else -> 1
                        }

                        if ((i % labelInterval == 0) || i == data.lastIndex) {
                            val alpha = if(data.size > 15) 0.1f else 0.2f
                            drawLine(Color.Gray.copy(alpha = alpha), Offset(x, 0f), Offset(x, graphHeight), strokeWidth = 1.dp.toPx())
                            if (p.label.isNotEmpty()) {
                                drawContext.canvas.nativeCanvas.drawText(p.label, x, height, textPaint)
                            }
                        }
                        Offset(x, y)
                    }

                    // Curve Path
                    val path = Path()
                    if (screenPoints.isNotEmpty()) {
                        path.moveTo(screenPoints[0].x, screenPoints[0].y)
                        for (i in 0 until screenPoints.size - 1) {
                            val p1 = screenPoints[i]
                            val p2 = screenPoints[i + 1]
                            val control1 = Offset((p1.x + p2.x) / 2, p1.y)
                            val control2 = Offset((p1.x + p2.x) / 2, p2.y)
                            path.cubicTo(control1.x, control1.y, control2.x, control2.y, p2.x, p2.y)
                        }
                    }
                    
                    drawPath(path, lineColor, style = Stroke(if (isMaximized) 5.dp.toPx() else 3.dp.toPx()))
                    
                    // Gradient Fill
                    if (screenPoints.isNotEmpty()) {
                        val fillPath = Path()
                        fillPath.addPath(path)
                        fillPath.lineTo(screenPoints.last().x, graphHeight)
                        fillPath.lineTo(screenPoints.first().x, graphHeight)
                        fillPath.close()
                        drawPath(fillPath, Brush.verticalGradient(listOf(lineColor.copy(if (isMaximized) 0.5f else 0.3f), Color.Transparent), 0f, graphHeight))
                    }

                    // Last Value Line & Indicator
                    if (data.isNotEmpty()) {
                        val lastVal = data.last().value
                        val lastY = screenPoints.last().y
                        val valText = "€${String.format(Locale.US, "%.2f", lastVal)}"
                        
                        val bounds = android.graphics.Rect()
                        valuePaint.getTextBounds(valText, 0, valText.length, bounds)
                        val textW = bounds.width()
                        val textH = bounds.height()
                        val padding = 20f
                        val textStartX = 60f

                        // Left Line segment
                        drawLine(
                            color = lineColor.copy(alpha = 0.5f),
                            start = Offset(0f, lastY),
                            end = Offset(textStartX - padding, lastY),
                            strokeWidth = 2.dp.toPx()
                        )

                        // Value Text
                        drawContext.canvas.nativeCanvas.drawText(
                            valText,
                            textStartX,
                            lastY + (textH / 3),
                            valuePaint
                        )

                        // Right Line segment
                        drawLine(
                            color = lineColor.copy(alpha = 0.5f),
                            start = Offset(textStartX + textW + padding, lastY),
                            end = Offset(width, lastY),
                            strokeWidth = 2.dp.toPx()
                        )
                        
                        drawCircle(lineColor, radius = 5.dp.toPx(), center = Offset(screenPoints.last().x, lastY))
                    }
                }
            }
        }
    }
}

/* ----------------- LIST ITEMS & DIALOGS ----------------- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionRow(item: Transaction, onLongClick: () -> Unit) {
    val formatter = DateTimeFormatter.ofPattern("dd MMM HH:mm")
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).combinedClickable(onClick = {}, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(item.title, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                Row {
                    Text(if (item.isExpense) "Expense" else "Income", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.width(8.dp))
                    Text("• ${item.date.format(formatter)}", fontSize = 12.sp, color = Color.Gray)
                }
            }
            Text(
                text = (if (item.isExpense) "- " else "+ ") + "€${item.amount}",
                color = if (item.isExpense) AppColors.Error else AppColors.Success,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEditorDialog(
    existing: Transaction?,
    onDismiss: () -> Unit,
    onConfirm: (String, Double, Boolean, LocalDateTime) -> Unit
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var amount by remember { mutableStateOf(existing?.amount?.toString() ?: "") }
    var isExpense by remember { mutableStateOf(existing?.isExpense ?: false) }
    var selectedDate by remember { mutableStateOf(existing?.date ?: LocalDateTime.now()) }
    var showCalendar by remember { mutableStateOf(false) }
    val formatter = DateTimeFormatter.ofPattern("dd MMM yyyy")

    if (showCalendar) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = selectedDate.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showCalendar = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val newDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                        selectedDate = LocalDateTime.of(newDate, selectedDate.toLocalTime())
                    }
                    showCalendar = false
                }) { Text("OK", color = AppColors.Primary) }
            },
            dismissButton = { TextButton(onClick = { showCalendar = false }) { Text("Cancel", color = Color.Gray) } },
            colors = DatePickerDefaults.colors(containerColor = AppColors.Surface)
        ) { DatePicker(state = pickerState) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.Surface,
        title = { Text(if (existing == null) "New Transaction" else "Edit Transaction", color = AppColors.TextPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Description") },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = AppColors.TextPrimary, unfocusedTextColor = AppColors.TextPrimary)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount (€)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = AppColors.TextPrimary, unfocusedTextColor = AppColors.TextPrimary)
                )
                Spacer(Modifier.height(16.dp))
                
                OutlinedCard(
                    onClick = { showCalendar = true },
                    colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
                    border = BorderStroke(1.dp, Color.Gray)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Date: ${selectedDate.format(formatter)}", color = AppColors.TextPrimary)
                        Icon(Icons.Default.DateRange, contentDescription = "Select Date", tint = AppColors.Primary)
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                Row {
                    Button(
                        onClick = { isExpense = false },
                        colors = ButtonDefaults.buttonColors(containerColor = if (!isExpense) AppColors.Success else Color.Gray),
                        modifier = Modifier.weight(1f)
                    ) { Text("Income") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { isExpense = true },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isExpense) AppColors.Error else Color.Gray),
                        modifier = Modifier.weight(1f)
                    ) { Text("Expense") }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val v = amount.toDoubleOrNull()
                    if (title.isNotEmpty() && v != null) onConfirm(title, v, isExpense, selectedDate)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary)
            ) { Text("Save", color = Color.White) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) }
        }
    )
}

@Composable
fun OptionsDialog(title: String, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.Surface,
        title = { Text("Manage '$title'", color = AppColors.TextPrimary) },
        confirmButton = {
            Button(onClick = onEdit, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary)) {
                Icon(Icons.Default.Edit, null)
                Text(" Edit")
            }
        },
        dismissButton = {
            Button(onClick = onDelete, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Error)) {
                Icon(Icons.Default.Delete, null)
                Text(" Delete")
            }
        }
    )
}

@Composable
fun GoalSettingDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit, onClearData: () -> Unit) {
    var txt by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.Surface,
        title = { Text("Settings", color = AppColors.TextPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = txt,
                    onValueChange = { txt = it },
                    label = { Text("Saving Goal") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onClearData,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Error.copy(alpha = 0.2f), contentColor = AppColors.Error),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("RESET ALL DATA") }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(txt) }, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary)) {
                Text("Save")
            }
        }
    )
}