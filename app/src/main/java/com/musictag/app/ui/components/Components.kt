package com.musictag.app.ui.components

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musictag.app.R
import com.musictag.app.data.NetworkImageLoader
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixCapsuleShape

/** Rounded artwork thumbnail with a graceful placeholder. */
@Composable
fun ArtworkCard(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    corner: Dp = 14.dp,
    @DrawableRes placeholderIcon: Int = R.drawable.ic_music_note,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MiuixTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(placeholderIcon),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

/** Network cover image with an in-memory cache and placeholder. */
@Composable
fun NetworkCover(url: String?, modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = url) {
        value = url?.let { NetworkImageLoader.load(it, 320) }
    }
    ArtworkCard(
        bitmap = bitmap,
        modifier = modifier,
        corner = corner,
        placeholderIcon = R.drawable.ic_album,
    )
}

/** Small capsule label, e.g. the container format of a track. */
@Composable
fun FormatBadge(text: String?) {
    val label = text ?: return
    Text(
        text = label.uppercase(),
        style = MiuixTheme.textStyles.footnote2,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Standard grouped card used by the editor and settings screens. */
@Composable
fun MiuixSectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            if (title != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        modifier = Modifier.weight(1f),
                    )
                    trailing?.invoke()
                }
            }
            Column(modifier = Modifier.padding(horizontal = contentPadding)) {
                content()
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    BasicComponent(
        modifier = modifier.fillMaxWidth(),
        title = label,
        summary = value,
        insideMargin = PaddingValues(horizontal = 0.dp, vertical = 10.dp),
    )
}

/** Primary button used for the main action of a screen. */
@Composable
fun MiuixPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        cornerRadius = 14.dp,
        minHeight = 42.dp,
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) {
        Text(text, style = MiuixTheme.textStyles.button, fontWeight = FontWeight.Medium)
    }
}

/** MIUI capsule extended floating action button (icon + label). */
@Composable
fun MiuixExtendedFloatingActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val container = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.disabledPrimaryButton
    val content = if (enabled) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.disabledOnPrimaryButton
    Surface(
        modifier = modifier.clip(miuixCapsuleShape()).clickable(enabled = enabled, onClick = onClick),
        shape = miuixCapsuleShape(),
        color = container,
        contentColor = content,
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(9.dp))
            }
            Text(
                text = text,
                style = MiuixTheme.textStyles.button,
                fontWeight = FontWeight.Medium,
                color = content,
            )
        }
    }
}

/**
 * Fully rounded MIUI segmented control. The selected segment is filled with the
 * primary color so it stands out clearly from the light gray track.
 */
@Composable
fun MiuixSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MiuixTheme.colorScheme.secondaryContainer,
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) MiuixTheme.colorScheme.primary else Color.Transparent)
                        .clickable { onSelected(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.button,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) {
                            MiuixTheme.colorScheme.onPrimary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MiuixTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp),
            )
        }
        Spacer(Modifier.height(22.dp))
        Text(
            text = title,
            style = MiuixTheme.textStyles.title3,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = description,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onAction,
            cornerRadius = 14.dp,
            minHeight = 44.dp,
            colors = ButtonDefaults.buttonColorsPrimary(),
        ) {
            Text(actionLabel, style = MiuixTheme.textStyles.button)
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            Spacer(Modifier.height(6.dp))
            top.yukonga.miuix.kmp.basic.TextButton(
                text = secondaryActionLabel,
                onClick = onSecondaryAction,
                minHeight = 38.dp,
            )
        }
    }
}

@Composable
fun LoadingState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "出错了",
            style = MiuixTheme.textStyles.title4,
            color = MiuixTheme.colorScheme.error,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = message,
            style = MiuixTheme.textStyles.body2,
            textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            MiuixPrimaryButton(text = "重试", onClick = onRetry)
        }
    }
}

/** MIUI style input field with a floating label. */
@Composable
fun MusicTagTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    enabled: Boolean = true,
    labelAsPlaceholder: Boolean = false,
    hideLabel: Boolean = false,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = if (hideLabel) "" else label,
        useLabelAsPlaceholder = labelAsPlaceholder,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        enabled = enabled,
        cornerRadius = 12.dp,
        backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
        textStyle = MiuixTheme.textStyles.body1,
    )
}

/**
 * Capsule search field with explicit spacing: icon has a 20 dp inset from the
 * capsule edge and a 14 dp gap before the placeholder / input text. The MiuiX
 * TextField leadingIcon slot hugs the left edge, so the row is composed
 * manually here instead.
 */
@Composable
fun MiuixSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(miuixCapsuleShape())
            .background(MiuixTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiuixIcons.Search,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MiuixTheme.textStyles.body2.copy(
                    color = MiuixTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().height(46.dp),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.CenterStart,
                    ) { innerTextField() }
                },
            )
        }
        if (onClear != null && value.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = "清除搜索内容",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * MIUI style dropdown menu anchored to its trigger. It is hosted in a window
 * popup so the card keeps a proper inset from the screen edge (matching the
 * HyperOS/KernelSU context menu), instead of being clamped to the window edge.
 */
@Composable
fun MiuixOverflowMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<Pair<String, () -> Unit>>,
) {
    var visible by remember { mutableStateOf(expanded) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        if (expanded) {
            visible = true
            progress.animateTo(1f, tween(durationMillis = 140))
        } else {
            progress.animateTo(0f, tween(durationMillis = 120))
            visible = false
        }
    }
    if (!visible) return
    val marginPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    val positionProvider = remember(marginPx) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ): androidx.compose.ui.unit.IntOffset {
                val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
                val x = (anchorBounds.right - popupContentSize.width).coerceIn(marginPx, maxX)
                val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
                val y = (anchorBounds.bottom + marginPx).coerceIn(marginPx, maxY)
                return androidx.compose.ui.unit.IntOffset(x, y)
            }
        }
    }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier
                .width(200.dp)
                .graphicsLayer {
                    val p = progress.value
                    alpha = p
                    scaleX = 0.88f + 0.12f * p
                    scaleY = 0.88f + 0.12f * p
                    transformOrigin = TransformOrigin(1f, 0f)
                },
            shape = RoundedCornerShape(16.dp),
            color = MiuixTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column {
                items.forEach { (label, action) ->
                    BasicComponent(
                        title = label,
                        onClick = {
                            onDismissRequest()
                            action()
                        },
                        insideMargin = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}
