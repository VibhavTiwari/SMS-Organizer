package com.vibhavtiwari.smsorganizer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vibhavtiwari.smsorganizer.core.Category

internal fun Category.displayLabel(): String = when (this) {
    Category.PERSONAL -> "Personal"
    Category.TRANSACTIONS -> "Transactions"
    Category.OTP -> "OTPs"
    Category.PROMOTIONS -> "Promotions"
    Category.TRAVEL -> "Travel"
    Category.BILLS -> "Bills"
    Category.OTHER -> "Other"
}

@Composable
internal fun CategoryPicker(current: Category, onSelect: (Category?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change category") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                for (category in Category.entries) {
                    val label = category.displayLabel()
                    DropdownMenuItem(
                        text = { Text(if (category == current) "$label (current)" else label) },
                        onClick = { onSelect(category) },
                    )
                }
                DropdownMenuItem(text = { Text("Classify automatically") }, onClick = { onSelect(null) })
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
