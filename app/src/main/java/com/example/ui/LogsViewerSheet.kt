package com.example.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.AppLogManager
import com.example.LogEntry
import com.example.LogLevel
import com.example.R
import kotlinx.coroutines.launch

private val TerminalBackground = Color(0xFF0F172A)
private val TerminalCardBg = Color(0xFF1E293B)
private val TerminalBorder = Color(0xFF334155)
private val ColorError = Color(0xFFFF5252)
private val ColorWarn = Color(0xFFFFB74D)
private val ColorSuccess = Color(0xFF69F0AE)
private val ColorInfo = Color(0xFF38BDF8)
private val ColorDebug = Color(0xFFC084FC)

enum class LogFilter {
    ALL,
    ERRORS,
    WARNINGS,
    INFO
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsViewerSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val logs by AppLogManager.logs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var activeFilter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    val listState = rememberLazyListState()

    val errorCount = remember(logs) { logs.count { it.level == LogLevel.ERROR } }
    val warnCount = remember(logs) { logs.count { it.level == LogLevel.WARN } }
    val infoCount = remember(logs) { logs.count { it.level == LogLevel.INFO || it.level == LogLevel.SUCCESS } }

    val filteredLogs = remember(logs, activeFilter) {
        when (activeFilter) {
            LogFilter.ALL -> logs
            LogFilter.ERRORS -> logs.filter { it.level == LogLevel.ERROR }
            LogFilter.WARNINGS -> logs.filter { it.level == LogLevel.WARN }
            LogFilter.INFO -> logs.filter { it.level == LogLevel.INFO || it.level == LogLevel.SUCCESS }
        }
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("logs_modal_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
        ) {
            // Header: Title and Close
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Terminal Icon",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Text(
                            text = stringResource(R.string.logs_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${logs.size} total entries • $errorCount errors",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (errorCount > 0) ColorError else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Logs"
                    )
                }
            }

            // Quick Actions: Copy All & Clear
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Copy Logs Button
                FilledTonalButton(
                    onClick = {
                        val allLogs = AppLogManager.getAllLogsAsText()
                        clipboardManager.setText(AnnotatedString(allLogs))
                        Toast.makeText(context, context.getString(R.string.logs_copied), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("copy_logs_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.copy_logs),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.copy_logs),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Clear Logs Button
                OutlinedButton(
                    onClick = {
                        AppLogManager.clearLogs()
                        Toast.makeText(context, context.getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("clear_logs_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = stringResource(R.string.clear_logs),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.clear_logs),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = activeFilter == LogFilter.ALL,
                    onClick = { activeFilter = LogFilter.ALL },
                    label = { Text("All (${logs.size})") },
                    modifier = Modifier.testTag("filter_chip_all")
                )
                FilterChip(
                    selected = activeFilter == LogFilter.ERRORS,
                    onClick = { activeFilter = LogFilter.ERRORS },
                    label = { Text("Errors ($errorCount)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ColorError.copy(alpha = 0.2f),
                        selectedLabelColor = ColorError
                    ),
                    modifier = Modifier.testTag("filter_chip_errors")
                )
                FilterChip(
                    selected = activeFilter == LogFilter.WARNINGS,
                    onClick = { activeFilter = LogFilter.WARNINGS },
                    label = { Text("Warnings ($warnCount)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ColorWarn.copy(alpha = 0.2f),
                        selectedLabelColor = ColorWarn
                    ),
                    modifier = Modifier.testTag("filter_chip_warnings")
                )
                FilterChip(
                    selected = activeFilter == LogFilter.INFO,
                    onClick = { activeFilter = LogFilter.INFO },
                    label = { Text("Info ($infoCount)") },
                    modifier = Modifier.testTag("filter_chip_info")
                )
            }

            // Terminal Log Viewer Canvas
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 4.dp, bottom = 12.dp),
                shape = RoundedCornerShape(12.dp),
                color = TerminalBackground,
                border = BorderStroke(1.dp, TerminalBorder)
            ) {
                if (filteredLogs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = "Empty logs",
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = stringResource(R.string.no_logs),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF94A3B8),
                                fontFamily = FontFamily.Monospace,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .testTag("logs_list"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredLogs, key = { it.id }) { entry ->
                            LogItemRow(
                                entry = entry,
                                onCopy = { textToCopy ->
                                    clipboardManager.setText(AnnotatedString(textToCopy))
                                    Toast.makeText(context, "Log line copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogItemRow(
    entry: LogEntry,
    onCopy: (String) -> Unit
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }

    val levelBgColor = when (entry.level) {
        LogLevel.ERROR -> ColorError.copy(alpha = 0.2f)
        LogLevel.WARN -> ColorWarn.copy(alpha = 0.2f)
        LogLevel.SUCCESS -> ColorSuccess.copy(alpha = 0.2f)
        LogLevel.INFO -> ColorInfo.copy(alpha = 0.2f)
        LogLevel.DEBUG -> ColorDebug.copy(alpha = 0.2f)
    }

    val levelTextColor = when (entry.level) {
        LogLevel.ERROR -> ColorError
        LogLevel.WARN -> ColorWarn
        LogLevel.SUCCESS -> ColorSuccess
        LogLevel.INFO -> ColorInfo
        LogLevel.DEBUG -> ColorDebug
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = TerminalCardBg,
        border = BorderStroke(
            1.dp,
            if (entry.level == LogLevel.ERROR) ColorError.copy(alpha = 0.4f) else TerminalBorder.copy(alpha = 0.6f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            // Header Row: Level + Time + Tag + Copy
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Level badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = levelBgColor
                    ) {
                        Text(
                            text = entry.level.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = levelTextColor,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }

                    // Timestamp
                    Text(
                        text = entry.formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF94A3B8)
                    )

                    // Tag
                    Text(
                        text = "[${entry.tag}]",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF38BDF8)
                    )
                }

                // Copy single log button
                IconButton(
                    onClick = { onCopy(entry.toFormattedString()) },
                    modifier = Modifier
                        .size(32.dp)
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy log entry",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Message text
            SelectionContainer {
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (entry.level == LogLevel.ERROR) Color(0xFFFF8A80) else Color(0xFFF1F5F9),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Expandable details (stack trace) if available
            if (!entry.details.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { isExpanded = !isExpanded }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse details" else "Expand details",
                        tint = levelTextColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isExpanded) "Hide Exception Details" else "View Exception Details / StackTrace",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = levelTextColor
                    )
                }

                AnimatedVisibility(visible = isExpanded) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF020617),
                        border = BorderStroke(1.dp, ColorError.copy(alpha = 0.3f))
                    ) {
                        SelectionContainer {
                            Text(
                                text = entry.details,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFEF4444),
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
