package com.quickstart.md3.ui.main

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickstart.md3.data.AppSettings
import com.quickstart.md3.data.GestureAction
import com.quickstart.md3.data.KeyGestureConfig
import com.quickstart.md3.data.T9QuickKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val T9Letters = mapOf(
    1 to "",
    2 to "ABC",
    3 to "DEF",
    4 to "GHI",
    5 to "JKL",
    6 to "MNO",
    7 to "PQRS",
    8 to "TUV",
    9 to "WXYZ",
    0 to ""
)

@Composable
fun T9Keypad(
    settings: AppSettings,
    t9QuickKeys: List<T9QuickKey>,
    pianoMode: Boolean,
    contactsActive: Boolean,
    onDigitTap: (Int) -> Unit,
    onDigitLongPress: (Int) -> Unit,
    onDigitSwipeUp: (Int) -> Unit,
    onGestureAction: (GestureAction, Int) -> Unit,
    onToggleIme: () -> Unit,
    modifier: Modifier = Modifier,
    keyHeight: Dp = 50.dp,
    keySpacing: Dp = 6.dp,
    getAppIcon: (String) -> ImageBitmap? = { null }
) {
    val context = LocalContext.current
    val quickMap = remember(t9QuickKeys) { t9QuickKeys.associateBy { it.digit } }
    val cornerRadius = if (keyHeight < 42.dp) 12.dp else 16.dp
    val iconSize = if (keyHeight < 42.dp) 19.dp else 22.dp

    fun vibrate() {
        if (!settings.hapticEnabled) return
        val duration = when (settings.hapticStrength) {
            1 -> 8L
            2 -> 16L
            3 -> 28L
            else -> 40L
        }
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vib?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(keySpacing)
    ) {
        if (pianoMode) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "演奏模式已开启 · 点击任意键弹奏，长按飞机键退出",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Row 1: 1, 2, 3, Delete
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(keySpacing)
        ) {
            DigitKeyCell(1, settings, quickMap[1], getAppIcon, keyHeight, cornerRadius, ::vibrate, onDigitTap, onDigitLongPress, onDigitSwipeUp, Modifier.weight(1f))
            DigitKeyCell(2, settings, quickMap[2], getAppIcon, keyHeight, cornerRadius, ::vibrate, onDigitTap, onDigitLongPress, onDigitSwipeUp, Modifier.weight(1f))
            DigitKeyCell(3, settings, quickMap[3], getAppIcon, keyHeight, cornerRadius, ::vibrate, onDigitTap, onDigitLongPress, onDigitSwipeUp, Modifier.weight(1f))
            FunctionKeyCell(
                icon = Icons.AutoMirrored.Rounded.Backspace,
                label = "删除",
                config = settings.deleteKey,
                highlighted = false,
                soundIndex = 12,
                keyHeight = keyHeight,
                cornerRadius = cornerRadius,
                iconSize = iconSize,
                onVibrate = ::vibrate,
                onAction = onGestureAction,
                modifier = Modifier.weight(1f)
            )
        }

        // Row 2: 4, 5, 6, Contacts
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(keySpacing)
        ) {
            DigitKeyCell(4, settings, quickMap[4], getAppIcon, keyHeight, cornerRadius, ::vibrate, onDigitTap, onDigitLongPress, onDigitSwipeUp, Modifier.weight(1f))
            DigitKeyCell(5, settings, quickMap[5], getAppIcon, keyHeight, cornerRadius, ::vibrate, onDigitTap, onDigitLongPress, onDigitSwipeUp, Modifier.weight(1f))
            DigitKeyCell(6, settings, quickMap[6], getAppIcon, keyHeight, cornerRadius, ::vibrate, onDigitTap, onDigitLongPress, onDigitSwipeUp, Modifier.weight(1f))
            FunctionKeyCell(
                icon = Icons.Rounded.PersonSearch,
                label = "联系人",
                config = settings.contactKey,
                highlighted = contactsActive,
                soundIndex = 13,
                keyHeight = keyHeight,
                cornerRadius = cornerRadius,
                iconSize = iconSize,
                onVibrate = ::vibrate,
                onAction = onGestureAction,
                modifier = Modifier.weight(1f)
            )
        }

        // Row 3: 7, 8, 9, Settings
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(keySpacing)
        ) {
            DigitKeyCell(7, settings, quickMap[7], getAppIcon, keyHeight, cornerRadius, ::vibrate, onDigitTap, onDigitLongPress, onDigitSwipeUp, Modifier.weight(1f))
            DigitKeyCell(8, settings, quickMap[8], getAppIcon, keyHeight, cornerRadius, ::vibrate, onDigitTap, onDigitLongPress, onDigitSwipeUp, Modifier.weight(1f))
            DigitKeyCell(9, settings, quickMap[9], getAppIcon, keyHeight, cornerRadius, ::vibrate, onDigitTap, onDigitLongPress, onDigitSwipeUp, Modifier.weight(1f))
            FunctionKeyCell(
                icon = Icons.Rounded.Settings,
                label = "设置",
                config = settings.settingKey,
                highlighted = false,
                soundIndex = 10,
                keyHeight = keyHeight,
                cornerRadius = cornerRadius,
                iconSize = iconSize,
                onVibrate = ::vibrate,
                onAction = onGestureAction,
                modifier = Modifier.weight(1f)
            )
        }

        // Row 4: IME Switch, 0, Airplane (Quick Menu)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(keySpacing)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(cornerRadius),
                modifier = Modifier
                    .weight(1f)
                    .height(keyHeight)
                    .clip(RoundedCornerShape(cornerRadius))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                vibrate()
                                onToggleIme()
                            }
                        )
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Keyboard,
                        contentDescription = "切换输入法",
                        modifier = Modifier.size(iconSize)
                    )
                }
            }

            DigitKeyCell(0, settings, quickMap[0], getAppIcon, keyHeight, cornerRadius, ::vibrate, onDigitTap, onDigitLongPress, onDigitSwipeUp, Modifier.weight(1.4f))

            FunctionKeyCell(
                icon = Icons.Rounded.Flight,
                label = "快捷",
                config = settings.airplaneKey,
                highlighted = true,
                soundIndex = 14,
                keyHeight = keyHeight,
                cornerRadius = cornerRadius,
                iconSize = iconSize,
                onVibrate = ::vibrate,
                onAction = onGestureAction,
                modifier = Modifier.weight(1.6f)
            )
        }
    }
}

@Composable
private fun DigitKeyCell(
    digit: Int,
    settings: AppSettings,
    quickKey: T9QuickKey?,
    getAppIcon: (String) -> ImageBitmap?,
    keyHeight: Dp,
    cornerRadius: Dp,
    onVibrate: () -> Unit,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onSwipeUp: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val letters = T9Letters[digit].orEmpty()
    val shape = RoundedCornerShape(cornerRadius)
    val compact = keyHeight < 44.dp
    val showBadge = settings.showT9KeyBadges && quickKey != null
    val iconPkg = quickKey?.packageName.orEmpty()
        .ifBlank { quickKey?.shortcutData?.packageName.orEmpty() }
    val appIcon by produceState<ImageBitmap?>(initialValue = null, key1 = showBadge, key2 = iconPkg) {
        value = if (showBadge && iconPkg.isNotBlank()) {
            withContext(Dispatchers.IO) { getAppIcon(iconPkg) }
        } else {
            null
        }
    }
    val digitFontSize = when {
        keyHeight < 34.dp -> 13.sp
        compact -> 15.sp
        else -> 18.sp
    }
    val letterFontSize = when {
        keyHeight < 34.dp -> 7.sp
        compact -> 8.sp
        else -> 9.sp
    }

    Surface(
        color = if (showBadge) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (showBadge) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
        } else {
            null
        },
        tonalElevation = if (showBadge) 0.dp else 1.dp,
        shape = shape,
        modifier = modifier
            .height(keyHeight)
            .clip(shape)
            .pointerInput(digit) {
                detectTapGestures(
                    onTap = {
                        onVibrate()
                        onTap(digit)
                    },
                    onLongPress = {
                        onVibrate()
                        onLongPress(digit)
                    }
                )
            }
            .pointerInput(digit, settings.swipeUpStartsListApp) {
                var totalY = 0f
                var fired = false
                detectDragGestures(
                    onDragStart = {
                        totalY = 0f
                        fired = false
                    },
                    onDrag = { _, dragAmount ->
                        totalY += dragAmount.y
                        if (!fired && totalY < -44f) {
                            fired = true
                            onVibrate()
                            onSwipeUp(digit)
                        }
                    }
                )
            }
    ) {
        if (showBadge && !compact) {
            val hasTopRow = settings.showKeyDigits || (settings.showKeyLetters && letters.isNotEmpty())
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 5.dp, vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (hasTopRow) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        if (settings.showKeyDigits) {
                            Text(
                                text = digit.toString(),
                                fontSize = 15.sp,
                                lineHeight = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (settings.showKeyLetters && letters.isNotEmpty()) {
                            Text(
                                text = letters,
                                fontSize = 8.5.sp,
                                lineHeight = 10.sp,
                                letterSpacing = 0.2.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)
                ) {
                    appIcon?.let { iconBitmap ->
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = null,
                            modifier = Modifier
                                .size(13.dp)
                                .clip(RoundedCornerShape(3.5.dp))
                        )
                    }
                    Text(
                        text = quickKey!!.label,
                        fontSize = 9.5.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                if (showBadge) {
                    val iconBitmap = appIcon
                    if (keyHeight >= 38.dp && iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = null,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 3.dp, end = 4.dp)
                                .size(11.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 3.dp, end = 4.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }

                if (settings.horizontalKeyLayout) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (settings.showKeyDigits) {
                            Text(
                                text = digit.toString(),
                                fontSize = digitFontSize,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (settings.showKeyLetters && letters.isNotEmpty()) {
                            Text(
                                text = letters,
                                fontSize = letterFontSize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (settings.showKeyDigits) {
                            Text(
                                text = digit.toString(),
                                fontSize = digitFontSize,
                                lineHeight = when {
                                    keyHeight < 34.dp -> 14.sp
                                    compact -> 17.sp
                                    else -> 20.sp
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (settings.showKeyLetters && letters.isNotEmpty() && keyHeight >= 32.dp) {
                            Text(
                                text = letters,
                                fontSize = letterFontSize,
                                lineHeight = if (compact) 9.sp else 11.sp,
                                letterSpacing = 0.4.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FunctionKeyCell(
    icon: ImageVector,
    label: String,
    config: KeyGestureConfig,
    highlighted: Boolean,
    soundIndex: Int,
    keyHeight: Dp,
    cornerRadius: Dp,
    iconSize: Dp,
    onVibrate: () -> Unit,
    onAction: (GestureAction, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val bg: Color = if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val fg: Color = if (highlighted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    val shape = RoundedCornerShape(cornerRadius)
    val latestConfig by rememberUpdatedState(config)

    Surface(
        color = bg,
        contentColor = fg,
        shape = shape,
        modifier = modifier
            .height(keyHeight)
            .clip(shape)
            .pointerInput(soundIndex) {
                detectTapGestures(
                    onTap = {
                        onVibrate()
                        onAction(latestConfig.click, soundIndex)
                    },
                    onLongPress = {
                        onVibrate()
                        onAction(latestConfig.longPress, soundIndex)
                    }
                )
            }
            .pointerInput(soundIndex) {
                var dx = 0f
                var dy = 0f
                var fired = false
                detectDragGestures(
                    onDragStart = {
                        dx = 0f
                        dy = 0f
                        fired = false
                    },
                    onDrag = { _, dragAmount ->
                        dx += dragAmount.x
                        dy += dragAmount.y
                        if (!fired && (abs(dx) > 44f || abs(dy) > 44f)) {
                            fired = true
                            val act = if (abs(dx) > abs(dy)) {
                                if (dx > 0) latestConfig.swipeRight else latestConfig.swipeLeft
                            } else {
                                if (dy > 0) latestConfig.swipeDown else latestConfig.swipeUp
                            }
                            if (act != GestureAction.NONE) {
                                onVibrate()
                                onAction(act, soundIndex)
                            }
                        }
                    }
                )
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
