package com.evolutionofgames.zgamespsx.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PostDetailsContent(description: String, tags: List<String>, modifier: Modifier = Modifier) {
    if (description.isBlank() && tags.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            tags.take(5).forEach { tag ->
                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
                    Text(tag, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (description.isNotBlank()) {
            var expanded by remember(description) { mutableStateOf(false) }
            var overflow by remember(description) { mutableStateOf(false) }
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f))) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DESCRIPTION", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(description, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, lineHeight = 21.sp, maxLines = if (expanded) Int.MAX_VALUE else 5, onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow })
                    if (overflow || expanded) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (expanded) "Show less" else "Show more", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
