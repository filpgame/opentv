/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opentv.R
import app.opentv.core.DensityUtils
import app.opentv.data.model.Source
import app.opentv.pairing.PairingServer
import app.opentv.pairing.QrCodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * "Set it up from your phone."
 *
 * Shows a QR code pointing at a web server running on this television, plus a six-digit code
 * the phone must echo back. See [PairingServer] for why it works this way rather than through
 * a website.
 */
@Composable
fun PhonePairingScreen(
    onReceived: (Source) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val context = LocalContext.current.applicationContext
    val server = remember { PairingServer(scope, context) }
    val state by server.state.collectAsState()

    DisposableEffect(Unit) {
        server.start()
        onDispose {
            // Never leave a socket listening on someone's home network after they have
            // navigated away.
            server.stop()
            scope.cancel()
        }
    }

    LaunchedEffect(state) {
        (state as? PairingServer.State.Received)?.let { onReceived(it.draft) }
    }

    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        when (val current = state) {
            is PairingServer.State.Listening -> Listening(current.session, onCancel)

            is PairingServer.State.Failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.pairing_stopped), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(10.dp))
                Text(
                    current.reason,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { server.start() }) { Text(stringResource(R.string.common_try_again)) }
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.pairing_type_here)) }
                }
            }

            is PairingServer.State.Received -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.pairing_got_it))
            }

            PairingServer.State.Idle -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun Listening(session: PairingServer.Session, onCancel: () -> Unit) {
    // Generated once per session rather than on every recomposition — encoding is not free
    // and the content does not change.
    val qr = remember(session.url) { QrCodes.render(session.url, QR_SIZE_PX) }
    val context = LocalContext.current
    val inCar = remember(context) { DensityUtils.isAutomotive(context) }

    // Measured, not read from LocalConfiguration: on a head unit DensityUtils overrides the density,
    // so the configuration's screenWidthDp no longer matches what is actually laid out.
    BoxWithConstraints {
        // The QR beside its text needs ~870 dp. A portrait head unit (1440x1920) or a tablet held
        // upright has less, so the text goes underneath instead of being squeezed or cut off.
        val stacked = maxWidth < SIDE_BY_SIDE_MIN_WIDTH || maxHeight > maxWidth
        if (stacked) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                QrImage(qr)
                PairingDetails(session, inCar, onCancel, centered = true)
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(48.dp),
            ) {
                QrImage(qr)
                PairingDetails(session, inCar, onCancel, centered = false)
            }
        }
    }
}

@Composable
private fun QrImage(qr: android.graphics.Bitmap?) {
    if (qr == null) return
    Image(
        bitmap = qr.asImageBitmap(),
        contentDescription = stringResource(R.string.pairing_qr_desc),
        modifier = Modifier
            .size(300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(12.dp),
    )
}

@Composable
private fun PairingDetails(
    session: PairingServer.Session,
    inCar: Boolean,
    onCancel: () -> Unit,
    centered: Boolean,
) {
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(
        Modifier.widthIn(max = 520.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(
            stringResource(R.string.pairing_setup_title),
            style = MaterialTheme.typography.displaySmall,
            textAlign = align,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.pairing_setup_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
        )

        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(R.string.pairing_enter_code),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            // Spaced out because it is being read across a room and typed into a phone.
            text = session.confirmCode.toCharArray().joinToString("  "),
            fontSize = 52.sp,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.pairing_camera_fail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
        )
        Spacer(Modifier.height(4.dp))
        Text(session.shortUrl, style = MaterialTheme.typography.titleLarge)

        Spacer(Modifier.height(32.dp))
        // "On the TV" would be wrong in a car, so it gets the generic wording the failure screen uses.
        OutlinedButton(onClick = onCancel) {
            Text(stringResource(if (inCar) R.string.pairing_type_here else R.string.pairing_type_on_tv))
        }
    }
}

private val SIDE_BY_SIDE_MIN_WIDTH = 840.dp
private const val QR_SIZE_PX = 600
