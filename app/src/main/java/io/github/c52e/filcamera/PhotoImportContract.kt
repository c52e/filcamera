package io.github.c52e.filcamera

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

internal class PhotoImportContract: ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context,input: Array<String>): Intent =
        super.createIntent(context,input).apply {
            // Some vendor pickers hide DNGs when EXTRA_MIME_TYPES is present.
            // Restrict browsing to images; PhotoRepository validates their contents.
            type="image/*"
            removeExtra(Intent.EXTRA_MIME_TYPES)
        }
}
