package io.github.lrq3000.utterlane.transcribe

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R

@Composable
internal fun QueueResumeAction(queue: TranscriptionQueue) {
    Text(stringResource(R.string.queue_service_pending))
    TextButton({ queue.wake() }) { Text(stringResource(R.string.queue_service_resume)) }
}

@Composable
internal fun ImportRecoveryControls(queue: TranscriptionQueue, reopen: (String) -> Unit) {
    val batches by queue.imports.pending.collectAsStateWithLifecycle()
    for (batch in batches) TextButton({ reopen(batch.id) }) {
        Text(stringResource(R.string.queue_batch_open, batch.accepted, batch.total))
    }
    val status by queue.admission.status.collectAsStateWithLifecycle()
    val jobs by queue.rows.collectAsStateWithLifecycle()
    if (jobs.any(status::needsResume)) QueueResumeAction(queue)
}
