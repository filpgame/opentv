/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.sync.SmbClient
import app.opentv.sync.SmbConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The SMB share NAS sync talks to: server, share, folder and login, with a "Test connection" that
 * connects, authenticates and opens the share. The credentials live only on this device, exactly
 * like a provider's login. Edits stay local to the form until Save, so a half-typed server never
 * replaces a working one.
 */
@Composable
fun NasConnectionForm(settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(settings.smbHost.value) }
    var share by remember { mutableStateOf(settings.smbShare.value) }
    var folder by remember { mutableStateOf(settings.smbFolder.value) }
    var user by remember { mutableStateOf(settings.smbUser.value) }
    var password by remember { mutableStateOf(settings.smbPassword.value) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.nas_connection),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Field(stringResource(R.string.nas_field_server), host, "192.168.1.10") { host = it }
        Field(stringResource(R.string.nas_field_share), share, "video") { share = it }
        Field(stringResource(R.string.nas_field_folder), folder, "OpenTV") { folder = it }
        Field(stringResource(R.string.common_username), user, "") { user = it }
        Field(stringResource(R.string.common_password), password, "", isPassword = true) { password = it }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = host.isNotBlank() && share.isNotBlank(),
                onClick = {
                    settings.setSmbConfig(host, share, folder, user, password)
                    status = context.getString(R.string.nas_status_saved)
                },
            ) { Text(stringResource(R.string.common_save)) }

            OutlinedButton(
                enabled = !testing && host.isNotBlank() && share.isNotBlank(),
                onClick = {
                    testing = true
                    status = context.getString(R.string.nas_status_testing)
                    val cfg = SmbConfig(host.trim(), share.trim(), folder.trim(), user, password)
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { SmbClient.test(cfg) } }
                        testing = false
                        status = result.fold(
                            onSuccess = { context.getString(R.string.nas_status_connected) },
                            onFailure = { context.getString(R.string.nas_status_failed, it.message ?: it.javaClass.simpleName) },
                        )
                    }
                },
            ) {
                Text(
                    if (testing) stringResource(R.string.nas_status_testing)
                    else stringResource(R.string.common_test_connection),
                )
            }
        }
        status?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    isPassword: Boolean = false,
    onChange: (String) -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = true,
        // A NAS password is a real credential — don't paint it on a living-room screen. Masked by
        // default, with an eye to reveal it if you need to check what you typed.
        visualTransformation = if (isPassword && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (!isPassword) null else {
            {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(
                            if (revealed) R.string.nas_pw_hide else R.string.nas_pw_show,
                        ),
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
