// TransferSection.kt

package com.mochyy.mochymusic.ui.screens.settings.storage

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.mochyy.mochymusic.ui.component.LocalMenuState
import com.mochyy.mochymusic.ui.screens.library.showConfirm
import com.mochyy.mochymusic.viewmodels.TransferStep
import com.mochyy.mochymusic.viewmodels.TransferViewModel
import kotlinx.coroutines.launch

private val ZipTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

/** export everything to one zip for a new phone, or bring such a zip back in */
@Composable
internal fun TransferSection(viewModel: TransferViewModel = hiltViewModel()) {
    val export by viewModel.export.collectAsState()
    val import by viewModel.import.collectAsState()
    val menuState = LocalMenuState.current
    val scope = rememberCoroutineScope()
    var checkError by remember { mutableStateOf<String?>(null) }
    var waitingForPermission by remember { mutableStateOf<Uri?>(null) }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.exportTo(uri)
    }
    // local songs come back through the phone's music library, a no still imports everything else
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        waitingForPermission?.let(viewModel::importFrom)
        waitingForPermission = null
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        checkError = null
        scope.launch {
            viewModel.check(uri)
                .onSuccess { manifest ->
                    if (viewModel.wantsAudioPermission(manifest)) {
                        waitingForPermission = uri
                        audioPermission.launch(TransferViewModel.AudioPermission)
                    } else {
                        viewModel.importFrom(uri)
                    }
                }
                .onFailure { checkError = it.message }
        }
    }

    Text(
        "Đổi điện thoại? Hãy mang theo mọi thứ",
        style = storageText(17f, FontWeight.SemiBold),
        modifier = Modifier.padding(horizontal = 2.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp)) {
        StorageActionButton(
            label = "Xuất toàn bộ",
            detail = when (val step = export) {
                is TransferStep.Working -> step.progress?.let { "Đang xuất… ${(it * 100).toInt()}%" } ?: "Đang xuất…"
                TransferStep.Done -> "Đã xuất! Nhập tệp .zip vào Mochy trên điện thoại khác"
                is TransferStep.Failed -> step.message
                TransferStep.Idle -> "Thư viện, lịch sử, nội dung tải xuống và cài đặt trong một tệp .zip, không kèm thông tin đăng nhập"
            },
            enabled = export !is TransferStep.Working && import !is TransferStep.Working,
            onClick = { exportPicker.launch(viewModel.exportFileName()) },
        )
        StorageActionButton(
            label = "Nhập toàn bộ",
            detail = when (val step = import) {
                is TransferStep.Working -> step.progress?.let { "Đang nhập… ${(it * 100).toInt()}%, Mochy sẽ khởi động lại khi hoàn tất" } ?: "Đang nhập…"
                TransferStep.Done -> "Hoàn tất, đang khởi động lại…"
                is TransferStep.Failed -> step.message
                TransferStep.Idle -> checkError ?: "Khôi phục tệp .zip được tạo bằng chức năng Xuất toàn bộ"
            },
            danger = true,
            enabled = export !is TransferStep.Working && import !is TransferStep.Working && import !is TransferStep.Done,
            onClick = {
                menuState.showConfirm(
                    title = "Thay thế thư viện?",
                    message = "Toàn bộ nội dung hiện có trong Mochy sẽ được thay thế bằng dữ liệu trong tệp xuất: thư viện, lịch sử, " +
                        "nội dung tải xuống và cài đặt. Thông tin đăng nhập vẫn được giữ nguyên. Mochy sẽ khởi động lại một lần để hoàn tất.",
                    action = "Chọn tệp .zip",
                    onConfirm = {
                        viewModel.clearImport()
                        importPicker.launch(ZipTypes)
                    },
                )
            },
        )
    }
}
